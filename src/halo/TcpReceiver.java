package halo;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class TcpReceiver {
    private final int port;
    private final File outputDirectory;

    public TcpReceiver(int port, String outputDir) {
        this.port = port;
        this.outputDirectory = new File(outputDir);
        if (!this.outputDirectory.exists()) {
            this.outputDirectory.mkdirs();
        }
    }

    public void startListening() {
        System.out.println("Receiver started. Listening on port " + port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                try (Socket clientSocket = serverSocket.accept()) {
                    System.out.println("Incoming connection from: " + clientSocket.getInetAddress());
                    handleClient(clientSocket);
                    break; // Break after one transfer for prototype testing
                } catch (IOException e) {
                    System.err.println("Error handling client connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Could not start server on port " + port + ": " + e.getMessage());
        }
    }

    private void handleClient(Socket socket) {
        // try-with-resources safely closes the DataInputStream, which recursively closes the Socket
        try (DataInputStream dataIn = new DataInputStream(socket.getInputStream())) {
            
            // 1. Read Metadata
            FileMetadata metadata = FileMetadata.readFrom(dataIn);
            System.out.println("Receiving file: " + metadata.fileName + " (" + metadata.fileSize + " bytes)");

            // 2. Sanitize filename (Prevent Path Traversal & illegal characters)
            String safeFileName = new File(metadata.fileName).getName();
            safeFileName = safeFileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            
            // 3. Prepare output file
            File outputFile = new File(outputDirectory, "received_" + safeFileName);
            long totalBytesRead = 0;
            byte[] buffer = new byte[8192];

            // 4. Receive file content
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                int bytesRead;
                long bytesRemaining = metadata.fileSize;
                
                while (bytesRemaining > 0 && (bytesRead = dataIn.read(buffer, 0, (int) Math.min(buffer.length, bytesRemaining))) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    bytesRemaining -= bytesRead;
                }
            } // fos is safely closed here

            // 5. Verification
            if (totalBytesRead == metadata.fileSize) {
                System.out.println("Transfer successful. Saved to: " + outputFile.getAbsolutePath());
            } else {
                System.err.println("Transfer incomplete! Expected " + metadata.fileSize + " bytes, but got " + totalBytesRead);
                outputFile.delete(); // Delete corrupted/incomplete file
            }
        } catch (Exception e) {
            System.err.println("Transfer failed: " + e.getMessage());
            e.printStackTrace();
        } // socket is safely closed here via DataInputStream closure
    }

    public static void main(String[] args) {
        TcpReceiver receiver = new TcpReceiver(8080, "downloads");
        receiver.startListening();
    }
}
