package halo;

import java.io.*;
import java.net.Socket;

public class TcpSender {
    private final String serverIp;
    private final int port;

    public TcpSender(String serverIp, int port) {
        this.serverIp = serverIp;
        this.port = port;
    }

    public void sendFile(File fileToTransfer, String optionalSpoofedName) {
        if (!fileToTransfer.exists() || !fileToTransfer.isFile()) {
            System.err.println("Invalid file: " + fileToTransfer.getAbsolutePath());
            return;
        }

        System.out.println("Connecting to " + serverIp + ":" + port + "...");
        
        // try-with-resources safely closes socket and streams upon completion or error
        try (Socket socket = new Socket(serverIp, port);
             DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
             FileInputStream fis = new FileInputStream(fileToTransfer)) {

            // 1. Send Metadata
            String mimeType = "application/octet-stream";
            String metadataName = (optionalSpoofedName != null) ? optionalSpoofedName : fileToTransfer.getName();
            FileMetadata metadata = new FileMetadata(metadataName, fileToTransfer.length(), mimeType);
            
            metadata.writeTo(dataOut);
            System.out.println("Sent metadata for: " + metadataName);

            // 2. Send File Content
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalSent = 0;

            System.out.println("Sending file bytes...");
            while ((bytesRead = fis.read(buffer)) != -1) {
                dataOut.write(buffer, 0, bytesRead);
                totalSent += bytesRead;
            }

            dataOut.flush();
            System.out.println("Transfer complete. Sent " + totalSent + " bytes.");

        } catch (IOException e) {
            System.err.println("Connection or transfer error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java halo.TcpSender <file_to_send> [spoofed_name]");
            return;
        }
        
        File fileToSend = new File(args[0]);
        String spoofedName = args.length > 1 ? args[1] : null;
        
        TcpSender sender = new TcpSender("127.0.0.1", 8080);
        sender.sendFile(fileToSend, spoofedName);
    }
}
