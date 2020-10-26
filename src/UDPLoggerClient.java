import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.*;

public class UDPLoggerClient {

    private final int loggerServerPort;
    private final int processId;
    private final int timeout;

    /**
     * @param loggerServerPort the UDP port where the Logger process is listening o
     * @param processId the ID of the Participant/Coordinator, i.e. the TCP port where the Participant/Coordinator is listening on
     * @param timeout the timeout in milliseconds for this process
     */
    public UDPLoggerClient(int loggerServerPort, int processId, int timeout) {
        this.loggerServerPort = loggerServerPort;
        this.processId = processId;
        this.timeout = timeout;
    }

    public int getLoggerServerPort() {
        return loggerServerPort;
    }

    public int getProcessId() {
        return processId;
    }

    public int getTimeout() {
        return timeout;
    }

    /**
     * Sends a log message to the Logger process
     *
     * @param message the log message
     * @throws IOException
     */
	public void logToServer(String message) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(timeout);

        int count = 0;
        while(true) {
            try {
                byte[] buffer = (processId + " " + message).getBytes();
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getLocalHost(), loggerServerPort);
                socket.send(packet);
                String received = "";
                while(!received.equals("ACK")){
                    packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    received = new String(packet.getData(), 0, packet.getLength());
                }
                return;
            } catch (SocketTimeoutException e) {
                count++;
                if (count == 4) throw new IOException();
            }
        }
	}
}
