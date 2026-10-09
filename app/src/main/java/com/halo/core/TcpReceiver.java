package com.halo.core;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class TcpReceiver {
    private final int port;
    private final File outputDirectory;
    private volatile ServerSocket currentServerSocket;
    private volatile Socket activeClientSocket;

    public interface TransferDecision {
        void accept();
        void decline();
    }

    public interface ReceiverListener {
        void onServerStarted(int port);
        void onTransferRequested(FileMetadata metadata, TransferDecision decision);
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

    public void cancelActiveTransfer() {
        if (activeClientSocket != null && !activeClientSocket.isClosed()) {
            try {
                new DataOutputStream(activeClientSocket.getOutputStream()).writeUTF("CANCEL");
                activeClientSocket.getOutputStream().flush();
            } catch (Exception ignored) {}
            try {
                activeClientSocket.close();
            } catch (Exception ignored) {}
        }
    }

    public void startListening() {
        System.out.println("Receiver started. Listening on port " + port);
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(port));
            this.currentServerSocket = serverSocket;
            if (listener != null) {
                listener.onServerStarted(serverSocket.getLocalPort());
            }
            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Incoming connection from: " + clientSocket.getInetAddress());
                    handleClient(clientSocket);
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

    private void checkSenderCancellation(DataInputStream dataIn) throws IOException {
        try {
            if (dataIn.available() > 0) {
                String msg = dataIn.readUTF();
                if ("CANCEL".equals(msg)) {
                    throw new IOException("Transfer cancelled by sender.");
                }
            }
        } catch (IOException e) {
            if ("Transfer cancelled by sender.".equals(e.getMessage())) throw e;
        } catch (Exception ignored) {}
    }

    private void handleClient(Socket socket) {
        activeClientSocket = socket;
        File outputFile = null;
        FileMetadata metadata = null;
        boolean isComplete = false;

        try (DataInputStream dataIn = new DataInputStream(socket.getInputStream());
             DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream())) {
            
            metadata = FileMetadata.readFrom(dataIn);

            final Object lock = new Object();
            final boolean[] decisionMade = new boolean[1];
            final boolean[] accepted = new boolean[1];

            if (listener != null) {
                TransferDecision decision = new TransferDecision() {
                    @Override
                    public void accept() {
                        synchronized (lock) {
                            accepted[0] = true;
                            decisionMade[0] = true;
                            lock.notify();
                        }
                    }

                    @Override
                    public void decline() {
                        synchronized (lock) {
                            accepted[0] = false;
                            decisionMade[0] = true;
                            lock.notify();
                        }
                    }
                };

                listener.onTransferRequested(metadata, decision);

                synchronized (lock) {
                    while (!decisionMade[0]) {
                        lock.wait();
                    }
                }

                if (!accepted[0]) {
                    dataOut.writeUTF("DECLINE");
                    dataOut.flush();
                    throw new IOException("Transfer declined by receiver.");
                } else {
                    dataOut.writeUTF("ACCEPT");
                    dataOut.flush();
                }
            } else {
                dataOut.writeUTF("ACCEPT");
                dataOut.flush();
            }

            String safeFileName = new File(metadata.fileName).getName();
            safeFileName = safeFileName.replaceAll("[\\/:*?\"<>|]", "_");
            
            outputFile = new File(outputDirectory, "received_" + safeFileName);
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
            } catch (Exception e) {
                checkSenderCancellation(dataIn);
                throw new IOException("Unable to save file: " + e.getMessage(), e);
            }

            if (totalBytesRead == metadata.fileSize) {
                isComplete = true;
                dataOut.writeUTF("TRANSFER_COMPLETE");
                dataOut.flush();
                if (listener != null) listener.onComplete(outputFile, metadata);
            } else {
                checkSenderCancellation(dataIn);
                throw new IOException("Connection lost. Expected " + metadata.fileSize + " bytes, got " + totalBytesRead);
            }
        } catch (Exception e) {
            System.err.println("Receiver error: " + e.getMessage());
            if (listener != null) listener.onError(e);
        } finally {
            if (!isComplete && outputFile != null && outputFile.exists()) {
                outputFile.delete(); // Ensure partial files are deleted!
            }
            try {
                socket.close();
            } catch (IOException ignored) {}
            activeClientSocket = null;
        }
    }
}
