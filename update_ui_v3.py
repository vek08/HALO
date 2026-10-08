import re

with open("app/src/main/java/com/halo/MainActivity.kt", "r", encoding="utf-8") as f:
    content = f.read()

# Add missing import if needed
if "import androidx.compose.foundation.rememberScrollState" not in content:
    content = content.replace("import androidx.compose.foundation.clickable", "import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.verticalScroll")

replacement = """    LaunchedEffect(state) {
        if (state is TransferState.Success || state is TransferState.Error) {
            kotlinx.coroutines.delay(3000)
            viewModel.resetState()
        }
    }

    Column(
        modifier = Modifier.padding(16.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("HALO Direct Transfer", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // State indicator
        when (val s = state) {
            is TransferState.Connecting -> {
                if (viewModel.receiverQrBitmap.collectAsState().value == null) {
                    Text("Connecting...", color = MaterialTheme.colorScheme.primary)
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
            else -> {}
        }

        Spacer(modifier = Modifier.height(16.dp))

        val hideControls = state is TransferState.Transferring || state is TransferState.Saving || state is TransferState.ReceivedPendingSave
        val qrBitmap = viewModel.receiverQrBitmap.collectAsState().value
        val isReceiver = qrBitmap != null || (state is TransferState.Connecting && selectedDevice == null)

        if (!hideControls) {
            if (!isReceiver) {
                if (selectedDevice != null) {
                    // SELECTED DEVICE UI
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Connected to", style = MaterialTheme.typography.labelMedium)
                            Text("📱 ${selectedDevice!!.name}", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("Ready to receive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.resetState() }) {
                                Text("Change Device")
                            }
                        }
                    }
                } else {
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
                                    }
                                }) {
                                    Text(if (isDiscovering) "Stop" else "Discover")
                                }
                            }
                            if (isDiscovering) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                Text("Discovering nearby devices...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            if (discoveredDevices.isEmpty() && !isDiscovering) {
                                Text("No device selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                if (discoveredDevices.isNotEmpty()) {
                                    Text("${discoveredDevices.size} device(s) found", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                discoveredDevices.forEach { device ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.selectDevice(device) }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("📱 ${device.name}", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                            Text("Ready to connect", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Button(onClick = { viewModel.selectDevice(device) }) {
                                            Text("Select")
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
                                                viewModel.selectDeviceFromQr(data)
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
                        Button(
                            onClick = {
                                selectedUri?.let { viewModel.sendFile(appContext, it) }
                            },
                            enabled = selectedUri != null && selectedDevice != null,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("Send File")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // RECEIVER CONTROLS
            if (state is TransferState.Connecting && isReceiver) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Receive File", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Ready to receive", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        qrBitmap?.let { bmp ->
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
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.cancelTransfer() }) {
                            Text("Stop Listening")
                        }
                    }
                }
            } else if (state is TransferState.Idle || state is TransferState.Success || state is TransferState.Error) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Receive File", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.startListening(appContext, 8080) }) {
                            Text("Start Listening")
                        }
                    }
                }
            }
        }

        if ((state is TransferState.Connecting && !isReceiver) || state is TransferState.Transferring || state is TransferState.Saving) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.cancelTransfer() }) {
                Text("Cancel Transfer")
            }
        }
    }"""

pattern = re.compile(r'    Column\(\n        modifier = Modifier\.padding\(16\.dp\)\.fillMaxWidth\(\),\n        horizontalAlignment = Alignment\.CenterHorizontally\n    \) \{.*?\n    \}', re.DOTALL)
new_content = pattern.sub(replacement, content, count=1)

with open("app/src/main/java/com/halo/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(new_content)

print("UI rewrite script completed.")
