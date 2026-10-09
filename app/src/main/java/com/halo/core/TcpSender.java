package com.halo.core;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;

public class TcpSender {
    private final String serverIp;
    private final int port;

    public interface ProgressListener {
        void onProgress(long bytesSent, long totalBytes);
    }
    
    private ProgressListener listener;

    public TcpSender(String serverIp, int port) {
        this.serverIp = serverIp;
        this.port = port;
    }

    public void setProgressListener(ProgressListener listener) {
        this.listener = listener;
    }

    public void sendFile(File fileToTransfer, String optionalSpoofedName) {
        if (!fileToTransfer.exists() || !fileToTransfer.isFile()) {
            System.err.println("Invalid file: " + fileToTransfer.getAbsolutePath());
            return;
        }
        
        String metadataName = (optionalSpoofedName != null) ? optionalSpoofedName : fileToTransfer.getName();
        try (FileInputStream fis = new FileInputStream(fileToTransfer)) {
            sendStream(fis, fileToTransfer.length(), metadataName, "application/octet-stream");
        } catch (IOException e) {
            System.err.println("File read error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendStream(InputStream is, long fileSize, String fileName, String mimeType) throws IOException {
        System.out.println("Connecting to " + serverIp + ":" + port + "...");
        
        try (Socket socket = new Socket()) {
            // Explicit connection timeout: 10 seconds.
            // If device disappeared, this fails fast instead of hanging.
            socket.connect(new InetSocketAddress(serverIp, port), 10000);
            
            try (DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
                 DataInputStream dataIn = new DataInputStream(socket.getInputStream())) {

                FileMetadata metadata = new FileMetadata(fileName, fileSize, mimeType);
                metadata.writeTo(dataOut);
                dataOut.flush();
                System.out.println("Sent metadata for: " + fileName + ". Waiting for receiver to accept...");

                String response = dataIn.readUTF();
                if ("DECLINE".equals(response)) {
                    throw new IOException("Transfer declined by receiver.");
                } else if (!"ACCEPT".equals(response)) {
                    throw new IOException("Unknown response from receiver: " + response);
                }

                System.out.println("Receiver accepted. Sending file bytes...");

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalSent = 0;

                try {
                    while (totalSent < fileSize && (bytesRead = is.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalSent))) != -1) {
                        dataOut.write(buffer, 0, bytesRead);
                        totalSent += bytesRead;
                        
                        if (listener != null) {
                            listener.onProgress(totalSent, fileSize);
                        }
                    }
                } catch (IOException e) {
                    throw new IOException("Unable to read file: " + e.getMessage(), e);
                }

                if (totalSent != fileSize) {
                     throw new IOException("Unable to read complete file! Expected " + fileSize + " bytes, but sent " + totalSent);
                }

                dataOut.flush();
                System.out.println("Transfer complete. Sent " + totalSent + " bytes.");
            }
        }
    }
}
