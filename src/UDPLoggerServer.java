import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UDPLoggerServer {
    private int port;
    //private List<ClientListener> listeners = new ArrayList<>();

    public static void main(String[] args){
        new UDPLoggerServer(args[0]);
    }

    public UDPLoggerServer(String port){
        this.port = Integer.parseInt(port);
        try {
            startListening();
        }
        catch (IOException e){e.printStackTrace();}

    }

    private void startListening() throws IOException {
        PrintStream ps = new PrintStream("logger_server_" + System.currentTimeMillis() + ".log");
        String[] data;

        DatagramSocket socket = new DatagramSocket(port);
        byte[] buffer = new byte[256];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            String received = new String(packet.getData(), 0, packet.getLength());
            System.out.println(received);
            data = received.split(" ", 2);
            ps.println(data[0] + " " + System.currentTimeMillis() + " " + data[1]);

            InetAddress address = packet.getAddress();
            int port = packet.getPort();
            packet = new DatagramPacket("ACK".getBytes(), "ACK".getBytes().length, address, port);
            socket.send(packet);
        }
    }

}
