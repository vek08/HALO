package com.halo

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
val OlivePrimary = Color(0xFF556B2F) // Rich olive green
val OliveSecondary = Color(0xFF8F9779) // Muted sage
val OliveBackground = Color(0xFFF9F6F0) // Warm off-white / cream
val OliveSurface = Color(0xFFFFFFFF) // Pure white for cards to pop
val OliveText = Color(0xFF283618) // Dark charcoal/olive for text
val OliveError = Color(0xFFBC4749)

enum class AppScreen { Home, SendFlow, ReceiveFlow }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    background = OliveBackground,
                    surface = OliveSurface,
                    primary = OlivePrimary,
                    secondary = OliveSecondary,
                    onBackground = OliveText,
                    onSurface = OliveText
                )
            ) {
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

    var currentScreen by remember { mutableStateOf(AppScreen.Home) }

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

    fun goHome() {
        viewModel.cancelTransfer()
        if (isDiscovering) viewModel.stopDiscovery()
        viewModel.resetState()
        selectedUri = null
        currentScreen = AppScreen.Home
    }

    BackHandler(currentScreen != AppScreen.Home) { goHome() }

    // Auto-reset on success/error
    LaunchedEffect(state) {
        if (state is TransferState.Success || state is TransferState.Error) {
            delay(3000)
            goHome()
        }
    }

    fun launchScanner() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        val scanner = GmsBarcodeScanning.getClient(context, options)
        scanner.startScan().addOnSuccessListener { barcode ->
            barcode.rawValue?.let { rawValue ->
                val data = QrPairingData.fromJsonString(rawValue)
                if (data != null) {
                    viewModel.selectDeviceFromQr(data)
                    currentScreen = AppScreen.SendFlow
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
            when (screen) {
                AppScreen.Home -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(48.dp))
                        
                        // HERO SECTION
                        Box(
                            modifier = Modifier.size(120.dp).background(Color.White, RoundedCornerShape(40.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "HALO", tint = OlivePrimary, modifier = Modifier.size(64.dp))
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("HALO", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.ExtraBold, color = OliveText)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Share files directly nearby", style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.6f))
                        
                        Spacer(modifier = Modifier.height(64.dp))

                        // PRIMARY ACTIONS
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Send Card
                            Card(
                                modifier = Modifier.weight(1f).aspectRatio(0.9f).clickable { 
                                    currentScreen = AppScreen.SendFlow
                                    viewModel.startDiscovery(appContext)
                                },
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(32.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.size(72.dp).background(OlivePrimary, CircleShape), contentAlignment = Alignment.Center) {
                                        @Suppress("DEPRECATION")
                                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(36.dp))
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Send", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = OliveText)
                                }
                            }

                            // Receive Card
                            Card(
                                modifier = Modifier.weight(1f).aspectRatio(0.9f).clickable { 
                                    currentScreen = AppScreen.ReceiveFlow
                                    viewModel.startListening(appContext, 8080)
                                },
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(32.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.size(72.dp).background(OliveSecondary, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Receive", tint = Color.White, modifier = Modifier.size(48.dp))
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Receive", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = OliveText)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(48.dp))

                        // CONNECTION METHODS
                        Text("Connection methods", style = MaterialTheme.typography.labelLarge, color = OliveText.copy(alpha=0.5f))
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ElevatedButton(
                                onClick = { 
                                    currentScreen = AppScreen.SendFlow
                                    viewModel.startDiscovery(appContext)
                                },
                                modifier = Modifier.weight(1f).height(64.dp),
                                colors = ButtonDefaults.elevatedButtonColors(containerColor = Color.White, contentColor = OliveText),
                                shape = RoundedCornerShape(20.dp),
                                elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 2.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = OlivePrimary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Nearby", style = MaterialTheme.typography.titleMedium)
                            }
                            ElevatedButton(
                                onClick = { launchScanner() },
                                modifier = Modifier.weight(1f).height(64.dp),
                                colors = ButtonDefaults.elevatedButtonColors(containerColor = Color.White, contentColor = OliveText),
                                shape = RoundedCornerShape(20.dp),
                                elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 2.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = OlivePrimary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Scan QR", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }

                AppScreen.SendFlow -> {
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
                            IconButton(onClick = { goHome() }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = OliveText) }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send File", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = OliveText)
                        }

                        if (state is TransferState.Connecting || state is TransferState.Transferring) {
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                    @Suppress("DEPRECATION")
                                    Icon(Icons.Default.Send, contentDescription = null, tint = OlivePrimary, modifier = Modifier.size(48.dp))
                                }
                                Spacer(modifier = Modifier.height(32.dp))
                                Text(if (state is TransferState.Connecting) "Connecting..." else "Sending File", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = OliveText)
                                
                                Spacer(modifier = Modifier.height(24.dp))
                                if (state is TransferState.Transferring) {
                                    val s = state as TransferState.Transferring
                                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)), color = OlivePrimary)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = OliveText)
                                    Text("${s.bytesSent / 1024 / 1024} MB / ${s.totalBytes / 1024 / 1024} MB", color = OliveText.copy(alpha=0.6f))
                                }
                            }
                        } else if (selectedDevice == null) {
                            // Radar/Discovery View
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(modifier = Modifier.height(32.dp))
                                Box(modifier = Modifier.size(150.dp).background(OliveSecondary.copy(alpha=0.2f), CircleShape), contentAlignment = Alignment.Center) {
                                    Box(modifier = Modifier.size(100.dp).background(OliveSecondary.copy(alpha=0.4f), CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Search, contentDescription = null, tint = OlivePrimary, modifier = Modifier.size(48.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(32.dp))
                                Text("Searching for receivers nearby...", style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.8f))
                                Spacer(modifier = Modifier.height(24.dp))
                                
                                discoveredDevices.forEach { device ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { viewModel.selectDevice(device) },
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(20.dp),
                                        elevation = CardDefaults.cardElevation(2.dp)
                                    ) {
                                        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(modifier = Modifier.size(48.dp).background(OlivePrimary.copy(alpha=0.1f), CircleShape), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Phone, contentDescription = null, tint = OlivePrimary)
                                            }
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column {
                                                Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = OliveText)
                                                Text("Tap to connect", style = MaterialTheme.typography.bodyMedium, color = OliveText.copy(alpha=0.6f))
                                            }
                                        }
                                    }
                                }
                            }
                            Button(
                                onClick = { launchScanner() },
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OlivePrimary),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Scan QR code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            // Device Selected -> File Picker -> Send
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(modifier = Modifier.height(32.dp))
                                Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Phone, contentDescription = null, tint = OlivePrimary, modifier = Modifier.size(48.dp))
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Connected to", style = MaterialTheme.typography.bodyMedium, color = OliveText.copy(alpha=0.6f))
                                Text(selectedDevice!!.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = OliveText)
                                
                                Spacer(modifier = Modifier.height(48.dp))
                                
                                if (selectedUri == null) {
                                    OutlinedButton(
                                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                        modifier = Modifier.size(150.dp),
                                        shape = CircleShape
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(40.dp), tint = OlivePrimary)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text("Pick File", style = MaterialTheme.typography.titleMedium, color = OliveText)
                                        }
                                    }
                                } else {
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable { filePickerLauncher.launch(arrayOf("*/*")) },
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(20.dp),
                                        elevation = CardDefaults.cardElevation(2.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = OlivePrimary, modifier = Modifier.size(48.dp))
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(selectedFileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = OliveText, textAlign = TextAlign.Center)
                                            Text("${selectedFileSize / 1024} KB", style = MaterialTheme.typography.bodyMedium, color = OliveText.copy(alpha=0.6f))
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text("Tap to change file", style = MaterialTheme.typography.labelMedium, color = OlivePrimary)
                                        }
                                    }
                                }
                            }
                            
                            Button(
                                onClick = { selectedUri?.let { viewModel.sendFile(appContext, it) } },
                                enabled = selectedUri != null,
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OlivePrimary),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                @Suppress("DEPRECATION")
                                Icon(Icons.Default.Send, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Send File", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                AppScreen.ReceiveFlow -> {
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
                            IconButton(onClick = { goHome() }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = OliveText) }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Receive File", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = OliveText)
                        }

                        if (state is TransferState.ReceivedPendingSave) {
                            val s = state as TransferState.ReceivedPendingSave
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = OlivePrimary, modifier = Modifier.size(56.dp))
                                }
                                Spacer(modifier = Modifier.height(32.dp))
                                Text("File Received", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OliveText)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(s.originalName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = OliveText, textAlign = TextAlign.Center)
                                Text("${s.fileSize / 1024} KB", style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.6f))
                            }
                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedButton(onClick = { viewModel.discardReceivedFile(s); goHome() }, modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(20.dp)) {
                                    Text("Discard", style = MaterialTheme.typography.titleMedium, color = OliveText)
                                }
                                Button(onClick = { saveFileLauncher.launch(s.originalName) }, modifier = Modifier.weight(1f).height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = OlivePrimary), shape = RoundedCornerShape(20.dp)) {
                                    Text("Save File", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        } else {
                            // Waiting / Connecting
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                val qrBitmap = viewModel.receiverQrBitmap.collectAsState().value
                                if (qrBitmap != null) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(32.dp),
                                        elevation = CardDefaults.cardElevation(4.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(220.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Text("Ready to receive", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = OliveText)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Scan this code from another device\\nor select from Nearby Devices", style = MaterialTheme.typography.bodyLarge, color = OliveText.copy(alpha=0.6f), textAlign = TextAlign.Center)
                                } else {
                                    CircularProgressIndicator(color = OlivePrimary)
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Text("Starting receiver...", style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.6f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Global Overlays for Success/Error
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(visible = state is TransferState.Success) {
                val msg = (state as? TransferState.Success)?.message ?: "File transferred successfully"
                Card(
                    colors = CardDefaults.cardColors(containerColor = OlivePrimary),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(msg, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            AnimatedVisibility(visible = state is TransferState.Error) {
                val msg = (state as? TransferState.Error)?.exception ?: "Transfer failed"
                Card(
                    colors = CardDefaults.cardColors(containerColor = OliveError),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(msg, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
