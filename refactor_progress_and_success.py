import os

# Update TransferViewModel.kt
with open("app/src/main/java/com/halo/ui/TransferViewModel.kt", "r", encoding="utf-8") as f:
    vm_code = f.read()

# 1. Add friendly error mapping
friendly_err = """
    private fun getUserFriendlyErrorMessage(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("ECONNRESET") || msg.contains("Connection reset") -> "Connection lost."
            msg.contains("Connection refused") -> "Receiver refused connection."
            msg.contains("Transfer declined") -> "Transfer declined by receiver."
            msg.contains("EHOSTUNREACH") -> "Device unreachable."
            msg.contains("Socket closed") -> "Transfer cancelled."
            else -> "An unexpected network error occurred."
        }
    }
"""
vm_code = vm_code.replace("class TransferViewModel : ViewModel() {", "class TransferViewModel : ViewModel() {\n" + friendly_err)

# 2. Add progress tracking variables
tracking_vars = """
    private var lastRecvTime = 0L
    private var lastRecvBytes = 0L
    private var lastSendTime = 0L
    private var lastSendBytes = 0L
"""
vm_code = vm_code.replace("private var currentReceiver: TcpReceiver? = null", "private var currentReceiver: TcpReceiver? = null\n" + tracking_vars)

# 3. Modify Receiver onProgress
recv_prog_old = """
                    override fun onProgress(bytesReceived: Long, totalBytes: Long) {
                        val progress = if (totalBytes > 0) bytesReceived.toFloat() / totalBytes else 0f
                        _transferState.value = TransferState.Transferring(progress, bytesReceived, totalBytes)
                    }
"""
recv_prog_new = """
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
"""
vm_code = vm_code.replace(recv_prog_old.strip(), recv_prog_new.strip())

# 4. Modify Receiver onError to use friendly message
vm_code = vm_code.replace('_transferState.value = TransferState.Error(e.message ?: "Unknown error")',
                          '_transferState.value = TransferState.Error(getUserFriendlyErrorMessage(e))')

# 5. Modify Sender onProgress and Reset in sendFile
sender_prog_old = """
                val sender = TcpSender(ip, port)
                sender.setProgressListener { bytesSent, totalBytes ->
                    val progress = if (totalBytes > 0) bytesSent.toFloat() / totalBytes else 0f
                    _transferState.value = TransferState.Transferring(progress, bytesSent, totalBytes)
                }
"""
sender_prog_new = """
                lastSendTime = System.currentTimeMillis()
                lastSendBytes = 0L
                val sender = TcpSender(ip, port)
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
"""
vm_code = vm_code.replace(sender_prog_old.strip(), sender_prog_new.strip())

# 6. Update Success emissions to include metadata if appropriate.
# For sender:
vm_code = vm_code.replace('_transferState.value = TransferState.Success("Transfer Complete")',
                          '_transferState.value = TransferState.Success("File sent successfully", fileName, fileSize)')
# For receiver save:
vm_code = vm_code.replace('_transferState.value = TransferState.Success("File saved successfully!")',
                          '_transferState.value = TransferState.Success("File received successfully", pendingState.originalName, pendingState.fileSize)')


with open("app/src/main/java/com/halo/ui/TransferViewModel.kt", "w", encoding="utf-8") as f:
    f.write(vm_code)


# Update MainActivity.kt

with open("app/src/main/java/com/halo/MainActivity.kt", "r", encoding="utf-8") as f:
    main_code = f.read()

# 1. Remove LaunchedEffect delay(3000) goHome() for Success/Error (so user can see Success screens)
main_code = main_code.replace("""
    LaunchedEffect(state) {
        if (state is TransferState.Success || state is TransferState.Error) {
            delay(3000)
            goHome()
        }
    }""", "")

# 2. Add speed formatter inside TransferApp
speed_fmt = """
    fun formatSpeed(speedMbps: Double): String {
        return String.format("%.1f MB/s", speedMbps)
    }
"""
main_code = main_code.replace("    fun goHome() {", speed_fmt + "\n    fun goHome() {")

# 3. Replace Transferring UI for SENDER
send_transfer_old = """
                                    if (state is TransferState.Transferring) {
                                        val s = state as TransferState.Transferring
                                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(6.dp)), color = OlivePrimary)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = OliveText)
                                        Text("${s.bytesSent / 1024 / 1024} MB / ${s.totalBytes / 1024 / 1024} MB", color = OliveText.copy(alpha=0.6f))
                                    }
"""
send_transfer_new = """
                                    if (state is TransferState.Transferring) {
                                        val s = state as TransferState.Transferring
                                        Text(selectedFileName, style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.8f))
                                        Spacer(modifier = Modifier.height(16.dp))
                                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(6.dp)), color = OlivePrimary)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = OliveText)
                                        Text("${s.bytesSent / 1024 / 1024} MB / ${s.totalBytes / 1024 / 1024} MB", color = OliveText.copy(alpha=0.6f))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Speed: ${formatSpeed(s.speedMbps)}", color = OliveText.copy(alpha=0.6f))
                                    }
"""
main_code = main_code.replace(send_transfer_old.strip(), send_transfer_new.strip())

# 4. Replace Transferring UI for RECEIVER
recv_transfer_old = """
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
"""
recv_transfer_new = """
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
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Speed: ${formatSpeed(s.speedMbps)}", color = OliveText.copy(alpha=0.6f))
                                }
"""
main_code = main_code.replace(recv_transfer_old.strip(), recv_transfer_new.strip())

# 5. Inject Success & Error UI into SendFlow and ReceiveFlow.
# We replace the outer Box for SendFlow state rendering:
sendflow_content_old = """
                            if (state is TransferState.Connecting || state is TransferState.Transferring) {
"""
sendflow_content_new = """
                            if (state is TransferState.Success) {
                                val s = state as TransferState.Success
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = OlivePrimary, modifier = Modifier.size(56.dp))
                                    }
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Text(s.message, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OliveText, textAlign = TextAlign.Center)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    if (s.fileName.isNotEmpty()) {
                                        Text(s.fileName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = OliveText, textAlign = TextAlign.Center)
                                        Text("${s.fileSize / 1024 / 1024} MB", style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.6f))
                                    }
                                }
                            } else if (state is TransferState.Error) {
                                val s = state as TransferState.Error
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = OliveError, modifier = Modifier.size(56.dp))
                                    }
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Text("Transfer failed", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OliveText)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(s.userFriendlyMessage, style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.6f), textAlign = TextAlign.Center)
                                }
                            } else if (state is TransferState.Connecting || state is TransferState.Transferring) {
"""
main_code = main_code.replace(sendflow_content_old.strip(), sendflow_content_new.strip())

# Now for ReceiveFlow:
recvflow_content_old = """
                            if (state is TransferState.ReceivedPendingSave) {
"""
recvflow_content_new = """
                            if (state is TransferState.Success) {
                                val s = state as TransferState.Success
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = OlivePrimary, modifier = Modifier.size(56.dp))
                                    }
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Text(s.message, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OliveText, textAlign = TextAlign.Center)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    if (s.fileName.isNotEmpty()) {
                                        Text(s.fileName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = OliveText, textAlign = TextAlign.Center)
                                        Text("${s.fileSize / 1024 / 1024} MB", style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.6f))
                                    }
                                }
                            } else if (state is TransferState.Error) {
                                val s = state as TransferState.Error
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.size(100.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = OliveError, modifier = Modifier.size(56.dp))
                                    }
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Text("Transfer failed", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OliveText)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(s.userFriendlyMessage, style = MaterialTheme.typography.titleMedium, color = OliveText.copy(alpha=0.6f), textAlign = TextAlign.Center)
                                }
                            } else if (state is TransferState.ReceivedPendingSave) {
"""
main_code = main_code.replace(recvflow_content_old.strip(), recvflow_content_new.strip())

# 6. Adjust bottom actions in SendFlow for Success/Error
send_bottom_old = """
                        if (state is TransferState.Connecting || state is TransferState.Transferring) {
"""
send_bottom_new = """
                        if (state is TransferState.Success || state is TransferState.Error) {
                            Button(
                                onClick = { goHome() },
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OlivePrimary),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("Back to Home", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        } else if (state is TransferState.Connecting || state is TransferState.Transferring) {
"""
main_code = main_code.replace(send_bottom_old.strip(), send_bottom_new.strip())

# 7. Adjust bottom actions in ReceiveFlow for Success/Error
recv_bottom_old = """
                        if (state is TransferState.RequestPending) {
"""
recv_bottom_new = """
                        if (state is TransferState.Success || state is TransferState.Error) {
                            Button(
                                onClick = { goHome() },
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OlivePrimary),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("Back to Home", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        } else if (state is TransferState.RequestPending) {
"""
main_code = main_code.replace(recv_bottom_old.strip(), recv_bottom_new.strip())

# 8. Remove the Global Overlays completely
overlays_block = """
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
"""
# Some lines might not match perfectly due to indentation, so let's do a re.sub if needed, or simple slice if we find Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter)
import re
overlay_pattern = r'Box\(modifier = Modifier\.fillMaxSize\(\)\.padding\(24\.dp\), contentAlignment = Alignment\.BottomCenter\) \{.*?\}(?=\s*\n\s*\}\s*\n\})'
main_code = re.sub(overlay_pattern, "", main_code, flags=re.DOTALL)

with open("app/src/main/java/com/halo/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(main_code)
