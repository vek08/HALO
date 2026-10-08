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

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState
    
    private val _localIp = MutableStateFlow("127.0.0.1")
    val localIp: StateFlow<String> = _localIp

    private var currentReceiver: TcpReceiver? = null
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
                // Using port 0 allows the OS to pick an ephemeral port, preventing binding errors.
                currentReceiver = TcpReceiver(0, outputDir)
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

                    override fun onProgress(bytesReceived: Long, totalBytes: Long) {
                        val progress = if (totalBytes > 0) bytesReceived.toFloat() / totalBytes else 0f
                        _transferState.value = TransferState.Transferring(progress, bytesReceived, totalBytes)
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
                            _transferState.value = TransferState.Error(e.message ?: "Unknown error")
                        }
                    }
                })
                currentReceiver?.startListening()
            } catch (e: Exception) {
                Log.e("HALO_VM", "startListening Exception", e)
                getNsdManager(context).unregisterService()
                _transferState.value = TransferState.Error(e.message ?: "Unknown error")
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

                val sender = TcpSender(ip, port)
                sender.setProgressListener { bytesSent, totalBytes ->
                    val progress = if (totalBytes > 0) bytesSent.toFloat() / totalBytes else 0f
                    _transferState.value = TransferState.Transferring(progress, bytesSent, totalBytes)
                }

                inputStream.use { stream ->
                    sender.sendStream(stream, fileSize, fileName, mimeType)
                }
                
                Log.d("HALO_VM", "Send complete")
                _transferState.value = TransferState.Success("Transfer Complete")
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
                
                _transferState.value = TransferState.Success("File saved successfully!")
                Log.d("HALO_VM", "State updated to Success.")
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
