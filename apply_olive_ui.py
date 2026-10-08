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
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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

// OLIVE COLOR PALETTE
val OlivePrimary = Color(0xFF606C38)
val OliveOnPrimary = Color(0xFFFFFFFF)
val OliveSecondary = Color(0xFFA3B18A)
val OliveOnSecondary = Color(0xFFFFFFFF)
val OliveBackground = Color(0xFFFEFAE0)
val OliveOnBackground = Color(0xFF283618)
val OliveSurface = Color(0xFFF2F4EC)
val OliveOnSurface = Color(0xFF283618)
val OliveError = Color(0xFFBC4749)

private val OliveColorScheme = lightColorScheme(
    primary = OlivePrimary,
    onPrimary = OliveOnPrimary,
    primaryContainer = OlivePrimary,
    onPrimaryContainer = OliveOnPrimary,
    secondary = OliveSecondary,
    onSecondary = OliveOnSecondary,
    secondaryContainer = OliveSecondary,
    onSecondaryContainer = OliveOnSecondary,
    background = OliveBackground,
    onBackground = OliveOnBackground,
    surface = OliveSurface,
    onSurface = OliveOnSurface,
    error = OliveError,
    onError = OliveOnPrimary
)

@Composable
fun OliveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OliveColorScheme,
        content = content
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OliveTheme {
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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // HEADER
        Spacer(modifier = Modifier.height(32.dp))
        Icon(
            imageVector = Icons.Default.Share, 
            contentDescription = "HALO Logo", 
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "HALO", 
            style = MaterialTheme.typography.displayMedium, 
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Share files directly nearby", 
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )
        Spacer(modifier = Modifier.height(48.dp))

        // STATUS MESSAGES (Friendly UI states)
        AnimatedVisibility(visible = state is TransferState.Success) {
            val msg = (state as? TransferState.Success)?.message ?: "File transferred successfully"
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Success", tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(msg, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Medium)
                }
            }
        }
        AnimatedVisibility(visible = state is TransferState.Error) {
            val msg = (state as? TransferState.Error)?.exception ?: "Transfer failed"
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.onError)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(msg, color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Medium)
                }
            }
        }

        val qrBitmap = viewModel.receiverQrBitmap.collectAsState().value
        val isReceiver = qrBitmap != null || (state is TransferState.Connecting && selectedDevice == null)

        val isActiveTransfer = state is TransferState.Connecting || state is TransferState.Transferring || state is TransferState.Saving || state is TransferState.ReceivedPendingSave

        if (isActiveTransfer) {
            // ACTIVE TRANSFER VIEW
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state is TransferState.ReceivedPendingSave) {
                        val s = state as TransferState.ReceivedPendingSave
                        Icon(Icons.Default.CheckCircle, contentDescription = "Received", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("File Received", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(s.originalName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text("${s.fileSize / 1024} KB", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.height(32.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedButton(onClick = { viewModel.discardReceivedFile(s) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                                Text("Discard")
                            }
                            Button(onClick = { saveFileLauncher.launch(s.originalName) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                                Text("Save File")
                            }
                        }
                    } else if (isReceiver) {
                        Text("Ready to receive", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(24.dp))
                        qrBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Receiver QR Code",
                                modifier = Modifier.size(220.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text("Scan this code from another HALO device", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f))
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        OutlinedButton(onClick = { viewModel.cancelTransfer() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Text("Stop Listening")
                        }
                    } else {
                        // SENDER ACTIVE
                        val title = if (state is TransferState.Connecting) "Connecting..." else "Sending File"
                        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        if (state is TransferState.Transferring) {
                            val s = state as TransferState.Transferring
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth().height(12.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${s.bytesSent / 1024 / 1024} MB / ${s.totalBytes / 1024 / 1024} MB", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f))
                        } else {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        OutlinedButton(onClick = { viewModel.cancelTransfer() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Text("Cancel Transfer")
                        }
                    }
                }
            }
        } else {
            // NORMAL HOME SCREEN
            
            // 1. NEARBY DEVICES CARD
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Nearby Devices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { 
                            if (isDiscovering) viewModel.stopDiscovery() else viewModel.startDiscovery(appContext)
                        }) {
                            Text(if (isDiscovering) "Stop" else "Discover")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (selectedDevice != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { viewModel.resetState() }) {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(selectedDevice!!.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    } else if (isDiscovering && discoveredDevices.isEmpty()) {
                        Text("Searching for nearby devices...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), color = MaterialTheme.colorScheme.primary)
                    } else if (discoveredDevices.isEmpty()) {
                        Text("No device selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.6f))
                    } else {
                        discoveredDevices.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectDevice(device) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Ready to connect", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f))
                                }
                            }
                        }
                    }
                }
            }

            // 2. PAIR WITH QR CARD
            if (selectedDevice == null) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Pair with QR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                                val scanner = GmsBarcodeScanning.getClient(context, options)
                                scanner.startScan().addOnSuccessListener { barcode ->
                                    barcode.rawValue?.let { rawValue ->
                                        val data = QrPairingData.fromJsonString(rawValue)
                                        if (data != null) viewModel.selectDeviceFromQr(data)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Scan QR", modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }

            // 3. SEND FILE CARD
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Send File", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    
                    if (selectedUri != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp)).padding(16.dp)) {
                            Text(selectedFileName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("${selectedFileSize / 1024} KB", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.7f))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(if (selectedUri != null) "Change" else "Pick File")
                        }
                        Button(
                            onClick = { selectedUri?.let { viewModel.sendFile(appContext, it) } },
                            enabled = selectedUri != null && selectedDevice != null,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Send File")
                        }
                    }
                }
            }

            // 4. RECEIVE FILE CARD
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Receive File", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.startListening(appContext, 8080) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Start Listening", modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}
"""

with open("app/src/main/java/com/halo/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(new_code)
