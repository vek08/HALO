import os

new_code = """package com.halo

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.halo.network.QrPairingData
import com.halo.ui.TransferState
import com.halo.ui.TransferViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TransferApp()
                }
            }
        }
    }
}

@Composable
fun TransferApp(viewModel: TransferViewModel = viewModel()) {
    val context = LocalContext.current
    val appContext = context.applicationContext 
    
    val state by viewModel.transferState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var selectedFileSize by remember { mutableStateOf(0L) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> 
            selectedUri = uri
            uri?.let {
                val (name, size) = com.halo.network.UriUtils.getFileNameAndSize(appContext, it)
                selectedFileName = name
                selectedFileSize = size
            }
        }
    )

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
        onResult = { uri ->
            if (uri != null) {
                val currentState = viewModel.transferState.value
                if (currentState is TransferState.ReceivedPendingSave) {
                    viewModel.saveFileToDestination(appContext, uri, currentState)
                }
            } else {
                Log.d("HALO_UI", "Save file cancelled.")
            }
        }
    )

    LaunchedEffect(state) {
        if (state is TransferState.Success || state is TransferState.Error) {
            delay(3000)
            viewModel.resetState()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // HEADER
        Spacer(modifier = Modifier.height(16.dp))
        Icon(
            imageVector = Icons.Default.Share, 
            contentDescription = "HALO Logo", 
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "HALO", 
            style = MaterialTheme.typography.displaySmall, 
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Share files directly nearby", 
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))

        // STATUS MESSAGES (Friendly UI states)
        AnimatedVisibility(visible = state is TransferState.Success) {
            val msg = (state as? TransferState.Success)?.message ?: "Transfer Complete"
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Success", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(msg, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
                }
            }
        }
        AnimatedVisibility(visible = state is TransferState.Error) {
            val msg = (state as? TransferState.Error)?.exception ?: "Transfer failed"
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Couldn't connect / $msg", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Medium)
                }
            }
        }

        // We determine if we are currently acting as a receiver
        val qrBitmap = viewModel.receiverQrBitmap.collectAsState().value
        val isReceiver = qrBitmap != null || (state is TransferState.Connecting && selectedDevice == null)

        val hideSenderControls = isReceiver || state is TransferState.ReceivedPendingSave || state is TransferState.Saving
        val hideReceiverControls = (!isReceiver && state !is TransferState.Idle && state !is TransferState.Success && state !is TransferState.Error)

        // SENDER / DISCOVERY SECTIONS
        if (!hideSenderControls) {
            if (state is TransferState.Connecting || state is TransferState.Transferring) {
                // ACTIVE SENDING STATE
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (state is TransferState.Connecting) "Connecting..." else "Sending...", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (state is TransferState.Transferring) {
                            val s = state as TransferState.Transferring
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("${(s.progress * 100).toInt()}% • ${s.bytesSent / 1024 / 1024} MB / ${s.totalBytes / 1024 / 1024} MB", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            CircularProgressIndicator()
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        OutlinedButton(onClick = { viewModel.cancelTransfer() }) {
                            Text("Cancel Transfer")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            } else {
                // SENDER SETUP (Pick device / QR / Pick File)
                
                // Device Selection
                if (selectedDevice != null) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("Selected Device", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Phone, contentDescription = "Device", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(selectedDevice!!.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("Ready", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(onClick = { viewModel.resetState() }) {
                                Text("Change Device")
                            }
                        }
                    }
                } else {
                    // Nearby Devices
                    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Nearby Devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                TextButton(onClick = { 
                                    if (isDiscovering) viewModel.stopDiscovery() else viewModel.startDiscovery(appContext)
                                }) {
                                    Text(if (isDiscovering) "Stop" else "Discover")
                                }
                            }
                            
                            if (isDiscovering) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Searching for nearby devices...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            if (discoveredDevices.isEmpty() && !isDiscovering) {
                                Text("No device selected", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            } else {
                                discoveredDevices.forEach { device ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.selectDevice(device) },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Phone, contentDescription = "Phone", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(device.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                                Text("Ready to connect", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // QR PAIRING
                    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("Pair with QR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                onClick = {
                                    val options = GmsBarcodeScannerOptions.Builder()
                                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                        .build()
                                    val scanner = GmsBarcodeScanning.getClient(context, options)
                                    scanner.startScan()
                                        .addOnSuccessListener { barcode ->
                                            barcode.rawValue?.let { rawValue ->
                                                val data = QrPairingData.fromJsonString(rawValue)
                                                if (data != null) {
                                                    viewModel.selectDeviceFromQr(data)
                                                }
                                            }
                                        }
                                }
                            ) {
                                Text("Scan QR")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // SEND FILE
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send File", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (selectedUri != null) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Selected File", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(selectedFileName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text("${selectedFileSize / 1024} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(if (selectedUri != null) "Change" else "Pick File")
                            }
                            Button(
                                onClick = { selectedUri?.let { viewModel.sendFile(appContext, it) } },
                                enabled = selectedUri != null && selectedDevice != null,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Send File")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        } // End Sender Controls

        // RECEIVER SECTION
        if (!hideReceiverControls) {
            if (state is TransferState.Connecting || state is TransferState.ReceivedPendingSave || state is TransferState.Saving) {
                // ACTIVE RECEIVER STATE
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        
                        if (state is TransferState.ReceivedPendingSave) {
                            val s = state as TransferState.ReceivedPendingSave
                            Icon(Icons.Default.CheckCircle, contentDescription = "Received", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("File Received!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(12.dp)) {
                                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                                    Text(s.originalName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                    Text("${s.fileSize / 1024} KB", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.discardReceivedFile(s) }, modifier = Modifier.weight(1f)) {
                                    Text("Discard")
                                }
                                Button(onClick = { saveFileLauncher.launch(s.originalName) }, modifier = Modifier.weight(1f)) {
                                    Text("Save File")
                                }
                            }
                        } else if (state is TransferState.Saving) {
                            Text("Saving...", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator()
                        } else {
                            // Connecting / Ready to receive
                            Text("Ready to receive", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            qrBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Receiver QR Code",
                                    modifier = Modifier.size(200.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Scan this code from another HALO device", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            OutlinedButton(onClick = { viewModel.cancelTransfer() }) {
                                Text("Stop Listening")
                            }
                        }
                    }
                }
            } else {
                // RECEIVER SETUP
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Share, contentDescription = "Receive", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Receive File", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.startListening(appContext, 8080) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Start Listening")
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}
"""

with open("app/src/main/java/com/halo/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(new_code)

print("MainActivity perfectly rewritten with proper imports.")
