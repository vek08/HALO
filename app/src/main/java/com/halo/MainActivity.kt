package com.halo

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.halo.ui.TransferState
import com.halo.ui.TransferViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TransferApp()
                }
            }
        }
    }
}

@Composable
fun TransferApp(viewModel: TransferViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.transferState.collectAsState()
    val localIp by viewModel.localIp.collectAsState()

    var receiverIp by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    
    val port = 8080

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> selectedUri = uri }
    )

    LaunchedEffect(Unit) {
        viewModel.refreshLocalIp()
    }

    Column(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("HALO Direct Transfer", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        // State indicator
        when (state) {
            is TransferState.Idle -> Text("Idle", color = MaterialTheme.colorScheme.onSurfaceVariant)
            is TransferState.Connecting -> Text("Connecting...", color = MaterialTheme.colorScheme.primary)
            is TransferState.Transferring -> {
                val t = state as TransferState.Transferring
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Transferring: ${(t.progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(progress = t.progress, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("${t.bytesSent} / ${t.totalBytes} bytes", style = MaterialTheme.typography.bodySmall)
                }
            }
            is TransferState.Success -> {
                val s = state as TransferState.Success
                Text(s.message, color = MaterialTheme.colorScheme.primary)
            }
            is TransferState.Error -> {
                val e = state as TransferState.Error
                Text("Error: ${e.exception}", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (state is TransferState.Idle || state is TransferState.Success || state is TransferState.Error) {
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
                    Button(
                        onClick = {
                            selectedUri?.let { viewModel.sendFile(context, it, receiverIp, port) }
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
                    Button(onClick = { viewModel.startListening(context, port) }) {
                        Text("Start Listening")
                    }
                }
            }
        } else {
            // Cancel button during transfer
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.cancelTransfer() }) {
                Text("Cancel Transfer")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        if (state !is TransferState.Idle) {
            TextButton(onClick = { viewModel.resetState() }) {
                Text("Reset")
            }
        }
    }
}
