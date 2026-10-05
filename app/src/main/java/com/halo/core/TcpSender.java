package com.halo.core;

import java.io.*;
import java.net.Socket;

public class TcpSender {
    private final String serverIp;
    private final int port;

    // Optional callback for progress reporting
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

    // Original method preserved
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

    // New adapted method supporting Android SAF InputStreams
    public void sendStream(InputStream is, long fileSize, String fileName, String mimeType) throws IOException {
        System.out.println("Connecting to " + serverIp + ":" + port + "...");
        
        try (Socket socket = new Socket(serverIp, port);
             DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream())) {

            // 1. Send Metadata
            FileMetadata metadata = new FileMetadata(fileName, fileSize, mimeType);
            metadata.writeTo(dataOut);
            System.out.println("Sent metadata for: " + fileName);

            // 2. Send File Content
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalSent = 0;

            System.out.println("Sending file bytes...");
            while ((bytesRead = is.read(buffer)) != -1) {
                dataOut.write(buffer, 0, bytesRead);
                totalSent += bytesRead;
                
                if (listener != null) {
                    listener.onProgress(totalSent, fileSize);
                }
            }

            dataOut.flush();
            System.out.println("Transfer complete. Sent " + totalSent + " bytes.");

        }
    }
}
