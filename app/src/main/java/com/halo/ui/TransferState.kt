package com.halo.ui

import java.io.File

sealed class TransferState {
    object Idle : TransferState()
    object Connecting : TransferState()
    data class Transferring(val progress: Float, val bytesSent: Long, val totalBytes: Long) : TransferState()
    
    // New state when transfer is complete, waiting for user to pick destination
    data class ReceivedPendingSave(
        val tempFile: File,
        val originalName: String,
        val mimeType: String,
        val fileSize: Long
    ) : TransferState()
    
    object Saving : TransferState()
    
    data class Success(val message: String) : TransferState()
    data class Error(val exception: String) : TransferState()
}
