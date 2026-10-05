package com.halo.ui

sealed class TransferState {
    object Idle : TransferState()
    object Connecting : TransferState()
    data class Transferring(val progress: Float, val bytesSent: Long, val totalBytes: Long) : TransferState()
    data class Success(val message: String) : TransferState()
    data class Error(val exception: String) : TransferState()
}
