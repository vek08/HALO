import os
import re

# ==========================================
# 1. Update TcpSender.java
# ==========================================
tcpsender_path = "app/src/main/java/com/halo/core/TcpSender.java"
with open(tcpsender_path, "r", encoding="utf-8") as f:
    tcpsender_code = f.read()

tcpsender_new_code = """package com.halo.core;

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
"""
with open(tcpsender_path, "w", encoding="utf-8") as f:
    f.write(tcpsender_new_code)


# ==========================================
# 2. Update TcpReceiver.java
# ==========================================
tcpreceiver_path = "app/src/main/java/com/halo/core/TcpReceiver.java"
with open(tcpreceiver_path, "r", encoding="utf-8") as f:
    tcpreceiver_code = f.read()

tcpreceiver_new_code = """package com.halo.core;

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
        try (ServerSocket serverSocket = new ServerSocket(port)) {
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
            safeFileName = safeFileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            
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
"""
with open(tcpreceiver_path, "w", encoding="utf-8") as f:
    f.write(tcpreceiver_new_code)


# ==========================================
# 3. Update TransferViewModel.kt
# ==========================================
vm_path = "app/src/main/java/com/halo/ui/TransferViewModel.kt"
with open(vm_path, "r", encoding="utf-8") as f:
    vm_code = f.read()

# Add currentSender
if "private var currentSender: TcpSender? = null" not in vm_code:
    vm_code = vm_code.replace("private var currentReceiver: TcpReceiver? = null", 
                              "private var currentReceiver: TcpReceiver? = null\n    private var currentSender: TcpSender? = null")

# Cancel logic
cancel_old = """    fun cancelTransfer() {
        Log.d("HALO_VM", "Cancelling transfer...")
        currentReceiver?.stopListening()
        currentReceiver = null"""
cancel_new = """    fun cancelTransfer() {
        Log.d("HALO_VM", "Cancelling transfer...")
        currentSender?.cancel()
        currentSender = null
        currentReceiver?.cancelActiveTransfer()
        currentReceiver?.stopListening()
        currentReceiver = null"""
vm_code = vm_code.replace(cancel_old, cancel_new)

# Sender creation logic
sender_old = "val sender = TcpSender(ip, port)"
sender_new = "val sender = TcpSender(ip, port)\n                currentSender = sender"
vm_code = vm_code.replace(sender_old, sender_new)

# Update Error Mapping
err_old = """            combined.contains("Unable to read file") -> "Unable to read file."
            combined.contains("Failed to save") || combined.contains("Unable to save file") -> "Unable to save file."
            combined.contains("Connection lost") || combined.contains("incomplete") -> "Connection lost."
            else -> "An unexpected network error occurred."
"""
err_new = """            combined.contains("cancelled by receiver") -> "The receiver cancelled the transfer."
            combined.contains("cancelled by sender") -> "The sender cancelled the transfer."
            combined.contains("Unable to read file") -> "Unable to read file."
            combined.contains("Failed to save") || combined.contains("Unable to save file") -> "Unable to save file."
            combined.contains("Connection lost") || combined.contains("incomplete") -> "Connection lost."
            else -> "An unexpected network error occurred."
"""
vm_code = vm_code.replace(err_old, err_new)

with open(vm_path, "w", encoding="utf-8") as f:
    f.write(vm_code)


# ==========================================
# 4. Update MainActivity.kt
# ==========================================
main_path = "app/src/main/java/com/halo/MainActivity.kt"
with open(main_path, "r", encoding="utf-8") as f:
    main_code = f.read()

# Replace "Transfer failed" title dynamically based on cancellation message
# Note: we have two places (Sender and Receiver) that have Error UI

error_ui_old = """                            } else if (state is TransferState.Error) {
                                val s = state as TransferState.Error
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = OliveError, modifier = Modifier.size(56.dp))
                                    }
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Text("Transfer failed", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OliveText)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(s.userFriendlyMessage, style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.6f), textAlign = TextAlign.Center)
                                }"""
                                
error_ui_new = """                            } else if (state is TransferState.Error) {
                                val s = state as TransferState.Error
                                val title = if (s.userFriendlyMessage.contains("cancelled", ignoreCase = true)) "Transfer cancelled" else "Transfer failed"
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = OliveError, modifier = Modifier.size(56.dp))
                                    }
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OliveText)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(s.userFriendlyMessage, style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.6f), textAlign = TextAlign.Center)
                                }"""

main_code = main_code.replace(error_ui_old, error_ui_new)

with open(main_path, "w", encoding="utf-8") as f:
    f.write(main_code)
