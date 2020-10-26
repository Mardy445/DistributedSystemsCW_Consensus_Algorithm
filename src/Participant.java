import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Participant {

    private int cport;
    private int lport;
    private int pport;
    private int timeout;

    private ParticipantLogger logger;

    //The server socket for this participant
    private ServerSocket thisClientSocket;

    //The print writer used by the participant to send data to the server
    private PrintWriter serverOut;

    //A list of all the sockets to other participants
    private List<Socket> otherClients;

    //A list of all the ports of other participants
    private List<Integer> otherClientsPorts;

    //Each other participants print writer, used to send messages to them
    private HashMap<Socket, PrintWriter> outputs;

    //A list of all the readers used to receive inputs from other clients
    private List<BufferedReader> inputs;

    //A list of all unique votes overall
    private List<Vote> votes;

    //A list of all votes received this round
    private List<Vote> newVotes;

    private int round = 1;
    //USED FOR LOGGING
    private HashMap<BufferedReader, Socket> inputsSockets;
    private HashMap<Integer,Integer> portToID;

    public static void main(String[] args) {
        try {
            new Participant(args[0], args[1], args[2], args[3]);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /*
    Constructor
     */
    private Participant(String cport, String lport, String pport, String timeout) throws IOException {
        this.cport = Integer.parseInt(cport);
        this.lport = Integer.parseInt(lport);
        this.pport = Integer.parseInt(pport);
        this.timeout = Integer.parseInt(timeout);

        try {
            ParticipantLogger.initLogger(this.lport, this.pport, this.timeout);
            logger = ParticipantLogger.getLogger();
        }
        catch (IOException e){
            System.out.println("Failed to initialise participant logger");
        }

        otherClients = new ArrayList<>();
        otherClientsPorts = new ArrayList<>();
        outputs = new HashMap<>();
        inputs = new ArrayList<>();
        votes = new ArrayList<>();
        newVotes = new ArrayList<>();

        thisClientSocket = new ServerSocket(this.pport);
        thisClientSocket.setSoTimeout(this.timeout);

        inputsSockets = new HashMap<>();
        portToID = new HashMap<>();

        serverInitRequest();
    }

    /*
    Begins the initial request to JOIN the voting
    Receives the needed information from the server when ready and chooses a vote
     */
    private void serverInitRequest() throws IOException {
        Socket server = new Socket(InetAddress.getLocalHost(), cport);
        serverOut = new PrintWriter(server.getOutputStream());
        serverOut.println("JOIN " + pport);
        logger.joinSent(cport);
        serverOut.flush();


        BufferedReader in = new BufferedReader(new InputStreamReader(server.getInputStream()));
        String details = in.readLine();
        String voteOptions = in.readLine();
        System.out.println(details);
        System.out.println(voteOptions);

        setOtherParticipants(details);
        logger.detailsReceived(otherClientsPorts);
        logger.voteOptionsReceived(Arrays.asList(voteOptions.split(" ")));
        Vote chosenOption = new Vote(pport, getRandomOption(Coordinator.getData(voteOptions)));
        newVotes.add(chosenOption);

        beginVotingCycle();

    }

    /*
    This is the main cycle that will run when looking for new votes
    Firstly the participant will attempt to open a connection to all other know participants
    Then the participant will multicast any need votes
    Then it will listen for votes from all known other participants
     */
    private void beginVotingCycle() {
        try {
            connectToParticipants();
            for (; round <= otherClients.size(); round++) {
                logger.beginRound(round);

                removeDuplicateNewVotes();
                sendVotes(convertVotesToString());
                newVotes.clear();

                listenToVotes();

                printVotes();

                logger.endRound(round);
            }
            logger.outcomeDecided(decideMajorityVote(),getPortsUsed());
            serverOut.println("OUTCOME " + decideMajorityVote() + convertPortListToString(getPortsUsed()));
            logger.outcomeNotified(decideMajorityVote(),getPortsUsed());
            serverOut.flush();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /*
    Awaits socket requests from all known participants
    After a timeout, it will stop waiting
     */
    private void connectToParticipants() {
        int i = 0;
        try {
            logger.startedListening();
            while (true) {
                Socket client = thisClientSocket.accept();
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                inputs.add(in);

                inputsSockets.put(in,client);
                logger.connectionAccepted(client.getPort());
                i++;
            }
        } catch (SocketTimeoutException e) {
            System.out.println("Connection Listener Timeout: " + i);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
        The participant will send all new votes to every other participant
     */
    private void sendVotes(String voteString) {
        PrintWriter out;
        for (Socket client : otherClients) {
            out = outputs.get(client);
            out.println("VOTE" + voteString);
            logger.messageSent(client.getPort(),"VOTE" + voteString);
            out.flush();
            logger.votesSent(client.getPort(), newVotes);
        }
    }

    private void removeDuplicateNewVotes(){
        List<Vote> holdVotes = new ArrayList<>();
        for(Vote vote : newVotes){
            if(isVoteNew(vote)){
                holdVotes.add(vote);
                votes.add(vote);
            }
        }
        newVotes = holdVotes;
    }

    /*
    Will check all recently received votes
    Any new votes will be added to the list of all votes and be appended to a vote string to be multicasted
     */
    private String convertVotesToString() {
        StringBuilder strVotes = new StringBuilder();
        for (Vote vote : newVotes) {
            //if (isVoteNew(vote)) {
                //votes.add(vote);
                strVotes.append(" ").append(vote);
            //}
        }
        //newVotes.clear();
        return strVotes.toString();
    }

    /*
    Returns true if a given vote is a new unique vote
     */
    private boolean isVoteNew(Vote newVote) {
        for (Vote vote : votes) {
            if (vote.getParticipantPort() == newVote.getParticipantPort() && vote.getVote().equals(newVote.getVote())) {
                return false;
            }
        }
        return true;
    }


    /*
    Listens to votes from all connected participants
    This method will only proceed once every connected participant has sent its votes
     */
    private void listenToVotes() throws InterruptedException {
        ExecutorService es = Executors.newCachedThreadPool();
        for (BufferedReader in : inputs) {
            es.execute(new ClientListener(in));
        }
        es.shutdown();
        es.awaitTermination(timeout, TimeUnit.MILLISECONDS);
    }

    /*
    Parses the received data and adds any new votes parsed to the newVotes list
     */
    private void addVotes(String[] data, BufferedReader in) {
        int port = 0;
        String choice;

        List<Vote> holdVotes = new ArrayList<>();

        for (int i = 0; i < data.length; i++) {
            port = Integer.parseInt(data[i]);
            i++;
            choice = data[i];
            newVotes.add(new Vote(port, choice));
            holdVotes.add(new Vote(port,choice));
        }
        if(round == 1){
            logger.votesReceived(port,holdVotes);
            portToID.put(inputsSockets.get(in).getPort(),port);
        }
        else{
            logger.votesReceived(portToID.get(inputsSockets.get(in).getPort()),holdVotes);
        }

    }

    /*
    Calculates the majority vote from all its votes
     */
    private String decideMajorityVote() {
        HashMap<String, Integer> voteCount = new HashMap<>();
        String voteString;
        Integer currentCount;
        for (Vote vote : votes) {
            voteString = vote.getVote();
            currentCount = voteCount.get(voteString);
            if (currentCount != null) {
                voteCount.put(voteString, currentCount + 1);
            } else {
                voteCount.put(voteString, 1);
            }
        }

        String maxVote = null;
        for (String vote : voteCount.keySet()) {
            if (maxVote == null || voteCount.get(vote) > voteCount.get(maxVote)) {
                maxVote = vote;
            }
        }

        return maxVote;
    }

    /*
    Returns a string of all participants ports that contributed to the list of votes
     */
    private List<Integer> getPortsUsed() {
        List<Integer> ports = new ArrayList<>();
        for (Vote vote : votes) {
            if (!ports.contains(vote.getParticipantPort())) {
                ports.add(vote.getParticipantPort());
            }
        }

        return ports;
    }

    private String convertPortListToString(List<Integer> ports){
        StringBuilder portString = new StringBuilder();
        for (int port : ports) {
            portString.append(" ").append(port);
        }
        return portString.toString();
    }

    /*
    Prints the votes received for a given round
    primarily used for debugging
     */
    private void printVotes() {
        StringBuilder votesString = new StringBuilder();
        for (Vote vote : votes) {
            votesString.append(vote).append(" ");
        }
        System.out.println("Votes: " + votesString.toString());
        votesString = new StringBuilder();
        for (Vote vote : newVotes) {
            votesString.append(vote).append(" ");
        }
        System.out.println("New Votes: " + votesString.toString());
        System.out.println();
    }

    /*
    Given the details from the server, opens connections with all potential other participants
     */
    private void setOtherParticipants(String details) {
        String[] ports = Coordinator.getData(details);
        Socket socket;
        PrintWriter out;
            for (String port : ports) {
                try {

                socket = new Socket(InetAddress.getLocalHost(), Integer.parseInt(port));

                socket.setSoTimeout(timeout);

                logger.connectionEstablished(Integer.parseInt(port));
                otherClients.add(socket);
                otherClientsPorts.add(Integer.parseInt(port));
                out = new PrintWriter(socket.getOutputStream());
                outputs.put(socket, out);

                } catch (Exception e) {
                    System.out.println("Error connecting to participant " + port);
                    logger.participantCrashed(Integer.parseInt(port));
                }
            }
    }

    /*
    Randomly chooses a vote
     */
    private String getRandomOption(String[] options) {
        Random random = new Random();
        return options[random.nextInt(options.length)];
    }


    /*
    A client listener thread will be ran for each open connection
    They will run simultaneously and the code will not proceed until all ClientListener threads halt
     */
    public class ClientListener extends Thread {

        BufferedReader in;

        ClientListener(BufferedReader in) {
            this.in = in;
        }

        /*
        Attempts to read a line sent from a specified client
         */
        public void run() {
            String line;
            try {
                line = in.readLine();
                logger.messageReceived(inputsSockets.get(in).getPort(),line);
                if(Coordinator.getProtocol(line).equals("VOTE")) {
                    addVotes(Coordinator.getData(line), in);
                }
            } catch (SocketTimeoutException e) {
                System.out.println("Timeout with participant");
                inputs.remove(in);
                attemptToIdentifyCrashID();
            } catch (IOException e) {
                System.out.println("Connection error with participant");
                inputs.remove(in);
                attemptToIdentifyCrashID();
            }
        }

        private void attemptToIdentifyCrashID(){
            try{
                logger.participantCrashed(portToID.get(inputsSockets.get(in).getPort()));
            }
            catch (NullPointerException e){
                System.out.println("Unable to identify crashed participants ID");
            }
        }
    }

}
