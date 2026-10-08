import re

with open("app/src/main/java/com/halo/MainActivity.kt", "r", encoding="utf-8") as f:
    content = f.read()

replacement = """        if (state is TransferState.Idle || state is TransferState.Success || state is TransferState.Error) {
            
            if (selectedDevice != null) {
                // SELECTED DEVICE UI
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Connected to", style = MaterialTheme.typography.labelMedium)
                        Text(selectedDevice!!.name, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                                        Text(device.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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

            // RECEIVER CONTROLS"""

# The regex should capture from "if (state is TransferState.Idle" down to "// RECEIVER CONTROLS"
pattern = re.compile(r'        if \(state is TransferState\.Idle.*?// RECEIVER CONTROLS', re.DOTALL)
new_content = pattern.sub(replacement, content, count=1)

with open("app/src/main/java/com/halo/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(new_content)

print("Replacement done.")
