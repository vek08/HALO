package com.halo.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.halo.core.FileMetadata
import com.halo.core.TcpReceiver
import com.halo.core.TcpSender
import com.halo.network.NetworkUtils
import com.halo.network.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class TransferViewModel : ViewModel() {

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState
    
    private val _localIp = MutableStateFlow("127.0.0.1")
    val localIp: StateFlow<String> = _localIp

    private var currentReceiver: TcpReceiver? = null
    private var transferJob: Job? = null

    fun refreshLocalIp() {
        _localIp.value = NetworkUtils.getLocalIpAddress()
    }

    fun startListening(context: Context, port: Int) {
        if (_transferState.value is TransferState.Connecting || _transferState.value is TransferState.Transferring) return
        
        _transferState.value = TransferState.Connecting
        transferJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Save to app's cache directory temporarily
                val outputDir = context.cacheDir
                currentReceiver = TcpReceiver(port, outputDir)
                currentReceiver?.setListener(object : TcpReceiver.ReceiverListener {
                    override fun onProgress(bytesReceived: Long, totalBytes: Long) {
                        val progress = if (totalBytes > 0) bytesReceived.toFloat() / totalBytes else 0f
                        _transferState.value = TransferState.Transferring(progress, bytesReceived, totalBytes)
                    }

                    override fun onComplete(savedFile: File, metadata: FileMetadata) {
                        _transferState.value = TransferState.ReceivedPendingSave(
                            tempFile = savedFile,
                            originalName = metadata.fileName,
                            mimeType = metadata.mimeType,
                            fileSize = metadata.fileSize
                        )
                        currentReceiver = null
                    }

                    override fun onError(e: Exception) {
                        if (e.message != "Socket closed") {
                            _transferState.value = TransferState.Error(e.message ?: "Unknown error")
                        }
                    }
                })
                currentReceiver?.startListening()
            } catch (e: Exception) {
                _transferState.value = TransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun sendFile(context: Context, uri: Uri, ip: String, port: Int) {
        if (_transferState.value is TransferState.Connecting || _transferState.value is TransferState.Transferring) return
        
        _transferState.value = TransferState.Connecting
        transferJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val (fileName, fileSize) = UriUtils.getFileNameAndSize(context, uri)
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val inputStream = context.contentResolver.openInputStream(uri) 
                    ?: throw Exception("Could not open input stream")

                val sender = TcpSender(ip, port)
                sender.setProgressListener { bytesSent, totalBytes ->
                    val progress = if (totalBytes > 0) bytesSent.toFloat() / totalBytes else 0f
                    _transferState.value = TransferState.Transferring(progress, bytesSent, totalBytes)
                }

                inputStream.use { stream ->
                    sender.sendStream(stream, fileSize, fileName, mimeType)
                }
                
                _transferState.value = TransferState.Success("Transfer Complete")
            } catch (e: Exception) {
                _transferState.value = TransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun saveFileToDestination(context: Context, destinationUri: Uri, pendingState: TransferState.ReceivedPendingSave) {
        _transferState.value = TransferState.Saving
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outputStream = context.contentResolver.openOutputStream(destinationUri)
                    ?: throw Exception("Could not open output stream for saving.")
                
                FileInputStream(pendingState.tempFile).use { inputStream ->
                    outputStream.use { outStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outStream.write(buffer, 0, bytesRead)
                        }
                    }
                }
                
                // Cleanup temp file after successful copy
                pendingState.tempFile.delete()
                _transferState.value = TransferState.Success("File saved successfully!")
            } catch (e: Exception) {
                _transferState.value = TransferState.Error("Failed to save: ${e.message}")
            }
        }
    }

    fun discardReceivedFile(pendingState: TransferState.ReceivedPendingSave) {
        pendingState.tempFile.delete()
        resetState()
    }

    fun resetState() {
        val currentState = _transferState.value
        if (currentState is TransferState.ReceivedPendingSave) {
            currentState.tempFile.delete()
        }
        _transferState.value = TransferState.Idle
    }

    fun cancelTransfer() {
        currentReceiver?.stopListening()
        currentReceiver = null
        transferJob?.cancel()
        val currentState = _transferState.value
        if (currentState is TransferState.ReceivedPendingSave) {
            currentState.tempFile.delete()
        }
        _transferState.value = TransferState.Idle
    }
}
