package com.halo.core;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;

public class TcpSender {
    private final String serverIp;
    private final int port;
    private volatile Socket activeSocket;

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

    public void cancel() {
        if (activeSocket != null && !activeSocket.isClosed()) {
            try {
                new DataOutputStream(activeSocket.getOutputStream()).writeUTF("CANCEL");
                activeSocket.getOutputStream().flush();
            } catch (Exception ignored) {}
            try {
                activeSocket.close();
            } catch (Exception ignored) {}
        }
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

    private void checkCancellation(DataInputStream dataIn) throws IOException {
        try {
            if (dataIn.available() > 0) {
                String msg = dataIn.readUTF();
                if ("CANCEL".equals(msg)) {
                    throw new IOException("Transfer cancelled by receiver.");
                }
            }
        } catch (IOException e) {
            if ("Transfer cancelled by receiver.".equals(e.getMessage())) throw e;
        } catch (Exception ignored) {}
    }

    public void sendStream(InputStream is, long fileSize, String fileName, String mimeType) throws IOException {
        System.out.println("Connecting to " + serverIp + ":" + port + "...");
        
        try (Socket socket = new Socket()) {
            activeSocket = socket;
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
                    dataOut.flush();
                } catch (IOException e) {
                    checkCancellation(dataIn);
                    throw new IOException("Connection lost: " + e.getMessage(), e);
                }

                if (totalSent != fileSize) {
                     throw new IOException("Unable to read complete file! Expected " + fileSize + " bytes, but sent " + totalSent);
                }

                System.out.println("Waiting for receiver confirmation...");
                try {
                    String finalResponse = dataIn.readUTF();
                    if ("CANCEL".equals(finalResponse)) {
                        throw new IOException("Transfer cancelled by receiver.");
                    } else if (!"TRANSFER_COMPLETE".equals(finalResponse)) {
                        throw new IOException("Unexpected confirmation response: " + finalResponse);
                    }
                } catch (IOException e) {
                    checkCancellation(dataIn);
                    throw new IOException("Connection lost waiting for confirmation: " + e.getMessage(), e);
                }

                System.out.println("Receiver confirmed completion.");
            }
        }
    }
}
