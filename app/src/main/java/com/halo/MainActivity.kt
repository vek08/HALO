package com.halo

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.halo.ui.TransferState
import com.halo.ui.TransferViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("HALO_LIFECYCLE", "onCreate")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TransferApp()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("HALO_LIFECYCLE", "onDestroy")
    }
}

@Composable
fun TransferApp(viewModel: TransferViewModel = viewModel()) {
    val context = LocalContext.current
    val appContext = context.applicationContext 
    
    val state by viewModel.transferState.collectAsState()
    val localIp by viewModel.localIp.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()

    var receiverIp by remember { mutableStateOf("") }
    var receiverPort by remember { mutableStateOf(8080) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> 
            Log.d("HALO_SAF", "OpenDocument result: $uri")
            selectedUri = uri 
        }
    )

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        Log.d("HALO_SAF", "CreateDocument result: $uri")
        val currentState = viewModel.transferState.value
        
        if (uri != null) {
            if (currentState is TransferState.ReceivedPendingSave) {
                Log.d("HALO_SAF", "Initiating save to destination...")
                viewModel.saveFileToDestination(appContext, uri, currentState)
            } else {
                Log.e("HALO_SAF", "Error: Expected ReceivedPendingSave state, but got ${currentState.javaClass.simpleName}")
            }
        } else {
            Log.d("HALO_SAF", "User cancelled SAF picker. Retaining current state.")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshLocalIp()
    }

    Column(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("HALO Direct Transfer", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // State indicator
        when (val s = state) {
            is TransferState.Idle -> Text("Idle", color = MaterialTheme.colorScheme.onSurfaceVariant)
            is TransferState.Connecting -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Connecting...", color = MaterialTheme.colorScheme.primary)
                    viewModel.receiverQrBitmap.collectAsState().value?.let { bmp ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Receiver QR Code",
                            modifier = Modifier.size(200.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Scan this code from another HALO device",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            is TransferState.Transferring -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Transferring: ${(s.progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("${s.bytesSent} / ${s.totalBytes} bytes", style = MaterialTheme.typography.bodySmall)
                }
            }
            is TransferState.Saving -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Saving to device...", color = MaterialTheme.colorScheme.primary)
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
            }
            is TransferState.ReceivedPendingSave -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("File Received!", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Name: ${s.originalName}", style = MaterialTheme.typography.bodyMedium)
                        Text("Size: ${s.fileSize} bytes", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row {
                            Button(onClick = { 
                                Log.d("HALO_UI", "Launching SAF picker with name: ${s.originalName}")
                                saveFileLauncher.launch(s.originalName)
                            }) {
                                Text("Save to device")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(onClick = { viewModel.discardReceivedFile(s) }) {
                                Text("Discard")
                            }
                        }
                    }
                }
            }
            is TransferState.Success -> {
                Text(s.message, color = MaterialTheme.colorScheme.primary)
            }
            is TransferState.Error -> {
                Text("Error: ${s.exception}", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (state is TransferState.Idle || state is TransferState.Success || state is TransferState.Error) {
            
            // NEARBY DEVICES (DISCOVERY)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Nearby Devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Button(onClick = { 
                            if (isDiscovering) {
                                viewModel.stopDiscovery()
                            } else {
                                viewModel.startDiscovery(appContext)
                                receiverIp = ""
                                receiverPort = 8080
                            }
                        }) {
                            Text(
                                if (isDiscovering) "Stop"
                                else if (selectedDevice != null) "Discover Again"
                                else "Discover"
                            )
                        }
                    }
                    if (isDiscovering) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        Text("Scanning...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (selectedDevice != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Selected: ${selectedDevice!!.name}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("${selectedDevice!!.host.hostAddress}:${selectedDevice!!.port}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else if (discoveredDevices.isEmpty() && !isDiscovering) {
                        Text("No devices found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        discoveredDevices.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        receiverIp = device.host.hostAddress ?: ""
                                        receiverPort = device.port
                                        viewModel.selectDevice(device)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(device.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text("${device.host.hostAddress}:${device.port}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // QR PAIRING SENDER
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Pair with QR", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                            .build()
                        val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
                        scanner.startScan()
                            .addOnSuccessListener { barcode ->
                                barcode.rawValue?.let { rawValue ->
                                    val data = com.halo.network.QrPairingData.fromJsonString(rawValue)
                                    if (data != null) {
                                        receiverIp = data.ip
                                        receiverPort = data.port
                                        android.widget.Toast.makeText(context, "Scanned: ${data.deviceName}", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "Invalid HALO QR format", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                } ?: android.widget.Toast.makeText(context, "Empty QR code", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener { e ->
                                android.widget.Toast.makeText(context, "Scan failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                    }) {
                        Text("Scan QR")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // SENDER CONTROLS
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Send File", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                        Text(if (selectedUri != null) "File Selected" else "Pick File")
                    }
                    OutlinedTextField(
                        value = receiverIp,
                        onValueChange = { receiverIp = it },
                        label = { Text("Receiver IP") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = receiverPort.toString(),
                        onValueChange = { receiverPort = it.toIntOrNull() ?: 8080 },
                        label = { Text("Receiver Port") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Button(
                        onClick = {
                            selectedUri?.let { viewModel.sendFile(appContext, it, receiverIp, receiverPort) }
                        },
                        enabled = selectedUri != null && receiverIp.isNotBlank(),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Send")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // RECEIVER CONTROLS
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Receive File", style = MaterialTheme.typography.titleMedium)
                    Text("My IP: $localIp", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { viewModel.startListening(appContext, 8080) }) {
                        Text("Start Listening")
                    }
                }
            }
        } else if (state !is TransferState.ReceivedPendingSave && state !is TransferState.Saving) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.cancelTransfer() }) {
                Text("Cancel Transfer")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        if (state !is TransferState.Idle && state !is TransferState.ReceivedPendingSave && state !is TransferState.Saving) {
            TextButton(onClick = { viewModel.resetState() }) {
                Text("Reset")
            }
        }
    }
}
