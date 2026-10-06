package com.halo.core;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class TcpReceiver {
    private final int port;
    private final File outputDirectory;
    private volatile ServerSocket currentServerSocket;

    public interface ReceiverListener {
        void onProgress(long bytesReceived, long totalBytes);
        void onComplete(File savedFile, FileMetadata metadata);
        void onError(Exception e);
    }

    private ReceiverListener listener;

    public TcpReceiver(int port, File outputDirectory) {
        this.port = port;
        this.outputDirectory = outputDirectory;
        if (!this.outputDirectory.exists()) {
            this.outputDirectory.mkdirs();
        }
    }
    
    // For backwards compatibility
    public TcpReceiver(int port, String outputDir) {
        this(port, new File(outputDir));
    }

    public void setListener(ReceiverListener listener) {
        this.listener = listener;
    }

    public void stopListening() {
        if (currentServerSocket != null && !currentServerSocket.isClosed()) {
            try {
                currentServerSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void startListening() {
        System.out.println("Receiver started. Listening on port " + port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            this.currentServerSocket = serverSocket;
            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Incoming connection from: " + clientSocket.getInetAddress());
                    handleClient(clientSocket);
                    break; // Break after one transfer for now
                } catch (IOException e) {
                    if (serverSocket.isClosed()) {
                        System.out.println("Server socket closed intentionally.");
                    } else {
                        System.err.println("Error handling client connection: " + e.getMessage());
                        if (listener != null) listener.onError(e);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Could not start server on port " + port + ": " + e.getMessage());
            if (listener != null) listener.onError(e);
        }
    }

    private void handleClient(Socket socket) {
        try (DataInputStream dataIn = new DataInputStream(socket.getInputStream())) {
            
            FileMetadata metadata = FileMetadata.readFrom(dataIn);
            String safeFileName = new File(metadata.fileName).getName();
            safeFileName = safeFileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            
            File outputFile = new File(outputDirectory, "received_" + safeFileName);
            long totalBytesRead = 0;
            byte[] buffer = new byte[8192];

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                int bytesRead;
                long bytesRemaining = metadata.fileSize;
                
                while (bytesRemaining > 0 && (bytesRead = dataIn.read(buffer, 0, (int) Math.min(buffer.length, bytesRemaining))) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    bytesRemaining -= bytesRead;
                    if (listener != null) {
                        listener.onProgress(totalBytesRead, metadata.fileSize);
                    }
                }
            }

            if (totalBytesRead == metadata.fileSize) {
                if (listener != null) listener.onComplete(outputFile, metadata);
            } else {
                outputFile.delete();
                throw new IOException("Transfer incomplete! Expected " + metadata.fileSize + " bytes, got " + totalBytesRead);
            }
        } catch (Exception e) {
            if (listener != null) listener.onError(e);
        }
    }
}
