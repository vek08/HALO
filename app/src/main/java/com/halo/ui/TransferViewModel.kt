package com.halo.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
                // Save to app's cache directory temporarily to bypass raw File restrictions
                val outputDir = context.cacheDir
                currentReceiver = TcpReceiver(port, outputDir)
                currentReceiver?.setListener(object : TcpReceiver.ReceiverListener {
                    override fun onProgress(bytesReceived: Long, totalBytes: Long) {
                        val progress = if (totalBytes > 0) bytesReceived.toFloat() / totalBytes else 0f
                        _transferState.value = TransferState.Transferring(progress, bytesReceived, totalBytes)
                    }

                    override fun onComplete(savedFile: File) {
                        _transferState.value = TransferState.Success("File saved to ${savedFile.absolutePath}")
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

    fun resetState() {
        _transferState.value = TransferState.Idle
    }

    fun cancelTransfer() {
        currentReceiver?.stopListening()
        currentReceiver = null
        transferJob?.cancel()
        _transferState.value = TransferState.Idle
    }
}
