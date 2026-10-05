package com.halo.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class FileMetadata {
    public final String fileName;
    public final long fileSize;
    public final String mimeType;

    public FileMetadata(String fileName, long fileSize, String mimeType) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
    }

    // Write metadata securely sequentially over the stream
    public void writeTo(DataOutputStream out) throws IOException {
        out.writeUTF(fileName);
        out.writeLong(fileSize);
        out.writeUTF(mimeType);
    }

    // Read metadata sequentially
    public static FileMetadata readFrom(DataInputStream in) throws IOException {
        String fileName = in.readUTF();
        long fileSize = in.readLong();
        String mimeType = in.readUTF();
        return new FileMetadata(fileName, fileSize, mimeType);
    }
}
