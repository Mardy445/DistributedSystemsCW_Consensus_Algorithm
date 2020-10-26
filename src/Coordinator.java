import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class Coordinator {

    private int port;
    private int lport;
    private int parts;
    private int timeout;
    private String[] options;

    private CoordinatorLogger logger;

    //The list of all participants
    private List<Socket> clients;


    private HashMap<Socket, Integer> clientsConnSockets;
    //private HashMap<Socket, PrintWriter> outputs;
    private List<BufferedReader> inputs;
    private HashMap<BufferedReader,Integer> inputsPorts;

    public static void main(String[] args) {
        String[] options = Arrays.copyOfRange(args, 4, args.length);
        new Coordinator(args[0], args[1], args[2], args[3], options);
    }

    private Coordinator(String port, String lport, String parts, String timeout, String[] options) {
        this.port = Integer.parseInt(port);
        this.lport = Integer.parseInt(lport);
        this.parts = Integer.parseInt(parts);
        this.timeout = Integer.parseInt(timeout);
        this.options = options;

        try {
            CoordinatorLogger.initLogger(this.lport, this.port, this.timeout);
            logger = CoordinatorLogger.getLogger();
        }
        catch (IOException e){
            System.out.println("Failed to initialise coordinator logger");
        }

        clients = new ArrayList<>();
        //outputs = new HashMap<>();
        inputs = new ArrayList<>();
        inputsPorts = new HashMap<>();

        clientsConnSockets = new HashMap<>();
        waitForMessages();
    }

    /*
    The main method that runs the entire life of the server process
     */
    private void waitForMessages() {
        try {

            //Opens the server and waits for enough clients to JOIN
            ServerSocket ss = new ServerSocket(port);
            logger.startedListening(port);
            while (clients.size() < parts) {

                Socket client = ss.accept();
                client.setSoTimeout(timeout);
                logger.connectionAccepted(client.getPort());
                //client.setSoTimeout(timeout);
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));

                inputs.add(in);

                String line = in.readLine();
                if (getProtocol(line).equals("JOIN")) {
                    int port = Integer.parseInt(getData(line)[0]);
                    inputsPorts.put(in,port);
                    addOrReplaceClient(client, port);
                    System.out.println(port + " Has joined!");
                    logger.joinReceived(port);
                    logger.messageReceived(client.getPort(), line);
                }

            }


            //Sends DETAILS and VOTE_OPTIONS to every client
            String optionsString = getOptionsForClient();
            String detailsForClient;
            for (Socket client : clients) {
                PrintWriter out = new PrintWriter(client.getOutputStream());
                //outputs.put(client, out);

                detailsForClient = getDetailsForClient(client);
                out.println("DETAILS" + detailsForClient);
                out.flush();
                logger.detailsSent(clientsConnSockets.get(client), new ArrayList<>(clientsConnSockets.values()));
                logger.messageSent(client.getPort(),"DETAILS" + detailsForClient);

                out.println("VOTE_OPTIONS" + optionsString);
                out.flush();
                logger.voteOptionsSent(clientsConnSockets.get(client), Arrays.asList(options));
                logger.messageSent(client.getPort(),"VOTE_OPTIONS" + optionsString);
            }

            //Starts threads to attempt to listen for the OUTCOME from every client
            for (BufferedReader in : inputs) {
                new ClientListener(in, inputsPorts.get(in)).start();
            }
        } catch (Exception e) {
            System.out.println("error " + e);
        }
    }

    /*
        Extracts the protocol from relevant data
     */
    static String getProtocol(String line) {
        return line.split(" ", 2)[0];
    }

    /*
    Extracts the data from any protocol command
     */
    static String[] getData(String line) {
        line = line.replace("<","");
        line = line.replace(">","");
        line = line.replace(",","");
        String[] lineArray = line.split(" ");
        String[] data = Arrays.copyOfRange(lineArray, 1, lineArray.length);
        return data;
    }

    /*
        Creates DETAILS for a specific client
     */
    private String getDetailsForClient(Socket client) {
        StringBuilder details = new StringBuilder();
        for (Socket socket : clients) {
            if (socket != client) {
                details.append(" ").append(clientsConnSockets.get(socket));
            }
        }
        return details.toString();
    }

    /*
        gets OPTIONS to send to every client
     */
    private String getOptionsForClient() {
        StringBuilder options = new StringBuilder();
        for (String option : this.options) {
            options.append(" ").append(option);
        }
        return options.toString();
    }

    /*
        Given a new participant, either adds it to the list of participants or replaces an existing participant with the same port
     */
    private void addOrReplaceClient(Socket client, int ssPort) {
        for (Socket socket : clients) {
            if (client.getPort() == socket.getPort()) {
                clients.remove(socket);
                clientsConnSockets.remove(socket);
                clients.add(client);
                clientsConnSockets.put(client, ssPort);
                return;
            }
        }
        clients.add(client);
        clientsConnSockets.put(client, ssPort);
    }

    /*
    A client listener thread will be ran for each open connection
    They will run simultaneously and the code will not proceed until all ClientListener threads halt
     */
    public class ClientListener extends Thread {

        BufferedReader in;
        int port;

        ClientListener(BufferedReader in, int port) {
            this.in = in;
            this.port = port;
        }

        /*
        Attempts to read a time send from a specified participant
         */
        public void run() {
            try {
                String line = in.readLine();
                System.out.println(line);
                String[] data = getData(line);
                logger.outcomeReceived(Integer.parseInt(data[1]),data[0]);
                logger.messageReceived(port,line);

            } catch (SocketTimeoutException e) {
                System.out.println("Timeout with participant " + port);
                logger.participantCrashed(port);
            }
            catch (IOException e) {
                System.out.println("Connection error with participant " + port);
                logger.participantCrashed(port);
            }
        }
    }
}
