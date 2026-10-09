package com.halo.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.halo.core.FileMetadata
import com.halo.core.TcpReceiver
import com.halo.core.TcpSender
import com.halo.network.NetworkUtils
import com.halo.network.NsdDiscoveryManager
import com.halo.network.DiscoveredDevice
import com.halo.network.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import android.graphics.Bitmap
import com.halo.network.QrPairingData
import com.halo.network.QrGenerator

class TransferViewModel : ViewModel() {

    private fun getUserFriendlyErrorMessage(e: Exception): String {
        val msg = e.message ?: ""
        val cause = e.cause?.message ?: ""
        val combined = msg + cause
        return when {
            combined.contains("ECONNRESET") || combined.contains("Connection reset") || combined.contains("Broken pipe") || combined.contains("Software caused connection abort") || combined.contains("unexpected end of stream") -> "Connection lost."
            combined.contains("Connection refused") -> "Receiver refused connection."
            combined.contains("Transfer declined") -> "Transfer declined by receiver."
            combined.contains("EHOSTUNREACH") || combined.contains("No route to host") -> "Device unreachable."
            combined.contains("Socket closed") -> "Transfer cancelled."
            combined.contains("connect timed out") || combined.contains("Timeout") -> "Connection unavailable."
            combined.contains("cancelled by receiver") -> "The receiver cancelled the transfer."
            combined.contains("cancelled by sender") -> "The sender cancelled the transfer."
            combined.contains("Unable to read file") -> "Unable to read file."
            combined.contains("Failed to save") || combined.contains("Unable to save file") -> "Unable to save file."
            combined.contains("Connection lost") || combined.contains("incomplete") -> "Connection lost."
            else -> "An unexpected network error occurred."
        }
    }


    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState
    
    private val _localIp = MutableStateFlow("127.0.0.1")
    val localIp: StateFlow<String> = _localIp

    private var currentReceiver: TcpReceiver? = null
    private var currentSender: TcpSender? = null

    private var lastRecvTime = 0L
    private var lastRecvBytes = 0L
    private var lastSendTime = 0L
    private var lastSendBytes = 0L

    private var transferJob: Job? = null

    private var nsdManager: NsdDiscoveryManager? = null
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private val _selectedDevice = MutableStateFlow<DiscoveredDevice?>(null)
    val selectedDevice: StateFlow<DiscoveredDevice?> = _selectedDevice

    private val _receiverQrBitmap = MutableStateFlow<Bitmap?>(null)
    val receiverQrBitmap: StateFlow<Bitmap?> = _receiverQrBitmap

    init {
        Log.d("HALO_VM", "TransferViewModel initialized")
    }

    private fun getNsdManager(context: Context): NsdDiscoveryManager {
        if (nsdManager == null) {
            val appCtx = context.applicationContext
            nsdManager = NsdDiscoveryManager(appCtx)
            viewModelScope.launch {
                nsdManager?.discoveredDevices?.collect { devices ->
                    _discoveredDevices.value = devices
                }
            }
        }
        return nsdManager!!
    }

    fun refreshLocalIp() {
        _localIp.value = NetworkUtils.getLocalIpAddress()
    }

    fun startDiscovery(context: Context) {
        _selectedDevice.value = null
        getNsdManager(context).startDiscovery()
        _isDiscovering.value = true
    }

    fun stopDiscovery() {
        nsdManager?.stopDiscovery()
        _isDiscovering.value = false
    }

    fun selectDevice(device: DiscoveredDevice) {
        _selectedDevice.value = device
        stopDiscovery()
    }

    fun startListening(context: Context, port: Int) {
        if (_transferState.value is TransferState.Connecting || _transferState.value is TransferState.Transferring) return
        
        Log.d("HALO_VM", "startListening called on port $port")
        _transferState.value = TransferState.Connecting
        transferJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Save to app's cache directory temporarily
                val outputDir = context.cacheDir
                // Use the requested port (typically 8080) to prevent NSD cache staleness issues.
                currentReceiver = TcpReceiver(port, outputDir)
                currentReceiver?.setListener(object : TcpReceiver.ReceiverListener {
                    override fun onServerStarted(actualPort: Int) {
                        Log.d("HALO_VM", "Server started on ephemeral port: $actualPort")
                        getNsdManager(context).registerService(actualPort)
                        
                        val qrData = QrPairingData(
                            version = 1,
                            deviceName = "HALO_${android.os.Build.MODEL}",
                            ip = _localIp.value,
                            port = actualPort
                        )
                        _receiverQrBitmap.value = QrGenerator.generateQrBitmap(qrData.toJsonString())
                    }

                    override fun onTransferRequested(metadata: FileMetadata, decision: com.halo.core.TcpReceiver.TransferDecision) {
                        Log.d("HALO_VM", "Transfer requested: ${metadata.fileName}")
                        // We must expose this to the UI
                        _transferState.value = TransferState.RequestPending(metadata, decision)
                    }

                    override fun onProgress(bytesReceived: Long, totalBytes: Long) {
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastRecvTime
                        if (timeDiff >= 200 || bytesReceived == totalBytes) {
                            val bytesDiff = bytesReceived - lastRecvBytes
                            val speedMbps = if (timeDiff > 0) (bytesDiff * 1000.0) / timeDiff / (1024.0 * 1024.0) else 0.0
                            val progress = if (totalBytes > 0) bytesReceived.toFloat() / totalBytes else 0f
                            _transferState.value = TransferState.Transferring(progress, bytesReceived, totalBytes, speedMbps)
                            lastRecvTime = currentTime
                            lastRecvBytes = bytesReceived
                        }
                    }

                    override fun onComplete(savedFile: File, metadata: FileMetadata) {
                        Log.d("HALO_VM", "Transfer complete. Pending save for: ${metadata.fileName}")
                        getNsdManager(context).unregisterService() // Stop advertising once we get a file
                        _transferState.value = TransferState.ReceivedPendingSave(
                            tempFile = savedFile,
                            originalName = metadata.fileName,
                            mimeType = metadata.mimeType,
                            fileSize = metadata.fileSize
                        )
                        currentReceiver = null
                    }

                    override fun onError(e: Exception) {
                        Log.e("HALO_VM", "Receiver Error", e)
                        getNsdManager(context).unregisterService()
                        if (e.message != "Socket closed") {
                            _transferState.value = TransferState.Error(getUserFriendlyErrorMessage(e))
                        }
                    }
                })
                currentReceiver?.startListening()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HALO_VM", "startListening Exception", e)
                getNsdManager(context).unregisterService()
                _transferState.value = TransferState.Error(getUserFriendlyErrorMessage(e))
            }
        }
    }

    fun selectDeviceFromQr(qrData: QrPairingData) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inetAddress = java.net.InetAddress.getByName(qrData.ip)
                val device = DiscoveredDevice(
                    name = qrData.deviceName,
                    host = inetAddress,
                    port = qrData.port
                )
                _selectedDevice.value = device
                stopDiscovery()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HALO_VM", "Invalid IP in QR: ${qrData.ip}", e)
            }
        }
    }

    fun sendFile(context: Context, uri: Uri) {
        if (_transferState.value is TransferState.Connecting || _transferState.value is TransferState.Transferring) return
        
        val target = _selectedDevice.value
        if (target == null) {
            _transferState.value = TransferState.Error("No device selected")
            return
        }

        val ip = target.host.hostAddress ?: return
        val port = target.port

        Log.d("HALO_VM", "sendFile called to $ip:$port")
        _transferState.value = TransferState.Connecting
        transferJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val (fileName, fileSize) = UriUtils.getFileNameAndSize(context, uri)
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val inputStream = context.contentResolver.openInputStream(uri) 
                    ?: throw Exception("Could not open input stream")

                lastSendTime = System.currentTimeMillis()
                lastSendBytes = 0L
                val sender = TcpSender(ip, port)
                currentSender = sender
                sender.setProgressListener { bytesSent, totalBytes ->
                    val currentTime = System.currentTimeMillis()
                    val timeDiff = currentTime - lastSendTime
                    if (timeDiff >= 200 || bytesSent == totalBytes) {
                        val bytesDiff = bytesSent - lastSendBytes
                        val speedMbps = if (timeDiff > 0) (bytesDiff * 1000.0) / timeDiff / (1024.0 * 1024.0) else 0.0
                        val progress = if (totalBytes > 0) bytesSent.toFloat() / totalBytes else 0f
                        _transferState.value = TransferState.Transferring(progress, bytesSent, totalBytes, speedMbps)
                        lastSendTime = currentTime
                        lastSendBytes = bytesSent
                    }
                }

                inputStream.use { stream ->
                    sender.sendStream(stream, fileSize, fileName, mimeType)
                }
                
                Log.d("HALO_VM", "Send complete")
                _transferState.value = TransferState.Success("File sent successfully", fileName, fileSize)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HALO_VM", "Send Error", e)
                _transferState.value = TransferState.Error("Could not connect to device: ${e.message}")
            }
        }
    }

    fun saveFileToDestination(context: Context, destinationUri: Uri, pendingState: TransferState.ReceivedPendingSave) {
        Log.d("HALO_VM", "saveFileToDestination called for URI: $destinationUri")
        _transferState.value = TransferState.Saving
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("HALO_VM", "Opening output stream...")
                val outputStream = context.contentResolver.openOutputStream(destinationUri)
                    ?: throw Exception("Could not open output stream for saving.")
                
                Log.d("HALO_VM", "Copying bytes from ${pendingState.tempFile.absolutePath} to destination...")
                FileInputStream(pendingState.tempFile).use { inputStream ->
                    outputStream.use { outStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                        }
                        Log.d("HALO_VM", "Copied $totalRead bytes successfully.")
                    }
                }
                
                Log.d("HALO_VM", "Deleting temp file...")
                val deleted = pendingState.tempFile.delete()
                Log.d("HALO_VM", "Temp file deleted: $deleted")
                
                _transferState.value = TransferState.Success("File received successfully", pendingState.originalName, pendingState.fileSize)
                Log.d("HALO_VM", "State updated to Success.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HALO_VM", "Save Failed", e)
                _transferState.value = TransferState.Error("Failed to save: ${e.message}")
            }
        }
    }

    fun discardReceivedFile(pendingState: TransferState.ReceivedPendingSave) {
        Log.d("HALO_VM", "Discarding received file...")
        pendingState.tempFile.delete()
        resetState()
    }

    fun resetState() {
        val currentState = _transferState.value
        if (currentState is TransferState.ReceivedPendingSave) {
            currentState.tempFile.delete()
        }
        _receiverQrBitmap.value = null
        _selectedDevice.value = null
        _transferState.value = TransferState.Idle
    }

    fun cancelTransfer() {
        Log.d("HALO_VM", "Cancelling transfer...")
        _selectedDevice.value = null
        currentSender?.cancel()
        currentSender = null
        currentReceiver?.cancelActiveTransfer()
        currentReceiver?.stopListening()
        currentReceiver = null
        nsdManager?.unregisterService()
        transferJob?.cancel()
        val currentState = _transferState.value
        if (currentState is TransferState.ReceivedPendingSave) {
            currentState.tempFile.delete()
        }
        _receiverQrBitmap.value = null
        _selectedDevice.value = null
        _transferState.value = TransferState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        nsdManager?.unregisterService()
        nsdManager?.stopDiscovery()
    }
}
