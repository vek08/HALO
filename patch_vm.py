import re

path = 'app/src/main/java/com/halo/ui/TransferViewModel.kt'
with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

old_code = """                // Using port 0 allows the OS to pick an ephemeral port, preventing binding errors.
                currentReceiver = TcpReceiver(0, outputDir)"""
new_code = """                // Use the requested port (typically 8080) to prevent NSD cache staleness issues.
                currentReceiver = TcpReceiver(port, outputDir)"""

code = code.replace(old_code, new_code)

# Let's also ensure `_selectedDevice` is cleared in cancelTransfer just to be perfectly clean on the sender side
cancel_old = """    fun cancelTransfer() {
        Log.d("HALO_VM", "Cancelling transfer...")"""
cancel_new = """    fun cancelTransfer() {
        Log.d("HALO_VM", "Cancelling transfer...")
        _selectedDevice.value = null"""
code = code.replace(cancel_old, cancel_new)

with open(path, 'w', encoding='utf-8') as f:
    f.write(code)
