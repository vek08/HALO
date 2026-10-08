package com.halo.ui

import java.io.File
import com.halo.core.FileMetadata
import com.halo.core.TcpReceiver.TransferDecision

sealed class TransferState {
    object Idle : TransferState()
    object Connecting : TransferState()
    
    data class RequestPending(
        val metadata: FileMetadata,
        val decision: TransferDecision
    ) : TransferState()
    
    data class Transferring(
        val progress: Float, 
        val bytesSent: Long, 
        val totalBytes: Long, 
        val speedMbps: Double
    ) : TransferState()
    
    data class ReceivedPendingSave(
        val tempFile: File,
        val originalName: String,
        val mimeType: String,
        val fileSize: Long
    ) : TransferState()
    
    object Saving : TransferState()
    
    data class Success(
        val message: String,
        val fileName: String = "",
        val fileSize: Long = 0L
    ) : TransferState()
    
    data class Error(val userFriendlyMessage: String) : TransferState()
}
