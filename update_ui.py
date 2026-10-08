import os
import re

with open("app/src/main/java/com/halo/MainActivity.kt", "r", encoding="utf-8") as f:
    content = f.read()

# Update SendFlow Connecting Text
content = content.replace('Text(if (state is TransferState.Connecting) "Connecting..." else "Sending File"',
                          'Text(if (state is TransferState.Connecting) "Waiting for response..." else "Sending File"')


# Update ReceiveFlow states handling
receive_flow_pattern = r'AppScreen\.ReceiveFlow -> \{.*?\}(?=\s*\}\s*// Global Overlays for Success/Error)'

new_receive_flow = """AppScreen.ReceiveFlow -> {
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                            IconButton(onClick = { goHome() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OliveText) }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Receive File", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = OliveText)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state is TransferState.ReceivedPendingSave) {
                                val s = state as TransferState.ReceivedPendingSave
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = OlivePrimary, modifier = Modifier.size(56.dp))
                                    }
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Text("File Received", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OliveText)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(s.originalName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = OliveText, textAlign = TextAlign.Center)
                                    Text("${s.fileSize / 1024 / 1024} MB", style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.6f))
                                }
                            } else if (state is TransferState.RequestPending) {
                                val s = state as TransferState.RequestPending
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = OlivePrimary, modifier = Modifier.size(56.dp))
                                    }
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Text("Incoming File", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OliveText)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("A device wants to send you a file.", style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.6f), textAlign = TextAlign.Center)
                                    Spacer(modifier = Modifier.height(24.dp))
                                    
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(16.dp),
                                        elevation = CardDefaults.cardElevation(2.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(s.metadata.fileName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = OliveText, textAlign = TextAlign.Center)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text("${s.metadata.fileSize / (1024 * 1024)} MB", style = MaterialTheme.typography.bodyMedium, color = OliveText.copy(alpha=0.6f))
                                        }
                                    }
                                }
                            } else if (state is TransferState.Transferring) {
                                val s = state as TransferState.Transferring
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = OlivePrimary, modifier = Modifier.size(48.dp))
                                    }
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Text("Receiving File", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = OliveText)
                                    Spacer(modifier = Modifier.height(24.dp))
                                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(6.dp)), color = OlivePrimary)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = OliveText)
                                    Text("${s.bytesSent / 1024 / 1024} MB / ${s.totalBytes / 1024 / 1024} MB", color = OliveText.copy(alpha=0.6f))
                                }
                            } else {
                                // Waiting / Connecting
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

                        // Bottom Actions for Receive Flow
                        if (state is TransferState.RequestPending) {
                            val s = state as TransferState.RequestPending
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedButton(onClick = { 
                                    Thread { s.decision.decline() }.start() 
                                }, modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(20.dp)) {
                                    Text("Decline", style = MaterialTheme.typography.titleMedium, color = OliveText)
                                }
                                Button(onClick = { 
                                    Thread { s.decision.accept() }.start()
                                }, modifier = Modifier.weight(1f).height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = OlivePrimary), shape = RoundedCornerShape(20.dp)) {
                                    Text("Accept", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        } else if (state is TransferState.ReceivedPendingSave) {
                            val s = state as TransferState.ReceivedPendingSave
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedButton(onClick = { viewModel.discardReceivedFile(s); goHome() }, modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(20.dp)) {
                                    Text("Discard", style = MaterialTheme.typography.titleMedium, color = OliveText)
                                }
                                Button(onClick = { saveFileLauncher.launch(s.originalName) }, modifier = Modifier.weight(1f).height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = OlivePrimary), shape = RoundedCornerShape(20.dp)) {
                                    Text("Save File", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }"""

content = re.sub(receive_flow_pattern, new_receive_flow.replace("\\", "\\\\"), content, flags=re.DOTALL)

with open("app/src/main/java/com/halo/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(content)
