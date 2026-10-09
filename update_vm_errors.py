import os
import re

file_path = "app/src/main/java/com/halo/ui/TransferViewModel.kt"
with open(file_path, "r", encoding="utf-8") as f:
    vm_code = f.read()

friendly_err_old = """
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

friendly_err_new = """
    private fun getUserFriendlyErrorMessage(e: Exception): String {
        val msg = e.message ?: ""
        val cause = e.cause?.message ?: ""
        val combined = msg + cause
        return when {
            combined.contains("ECONNRESET") || combined.contains("Connection reset") || combined.contains("Broken pipe") || combined.contains("Software caused connection abort") || combined.contains("unexpected end of stream") -> "Connection lost."
            combined.contains("Connection refused") -> "Receiver refused connection."
            combined.contains("Transfer declined") -> "Transfer declined by receiver."
            combined.contains("EHOSTUNREACH") || combined.contains("No route to host") -> "Device unreachable."
            combined.contains("Socket closed") -> "Transfer cancelled."
            combined.contains("connect timed out") || combined.contains("Timeout") -> "Connection unavailable."
            combined.contains("Unable to read file") -> "Unable to read file."
            combined.contains("Failed to save") || combined.contains("Unable to save file") -> "Unable to save file."
            combined.contains("Connection lost") || combined.contains("incomplete") -> "Connection lost."
            else -> "An unexpected network error occurred."
        }
    }
"""

if friendly_err_old.strip() in vm_code:
    vm_code = vm_code.replace(friendly_err_old.strip(), friendly_err_new.strip())
else:
    print("Warning: old error block not perfectly matched, using regex replacement.")
    # More robust replacement just in case
    pattern = re.compile(r'private fun getUserFriendlyErrorMessage\(e: Exception\): String \{.*?\n    \}', re.DOTALL)
    vm_code = pattern.sub(friendly_err_new.strip(), vm_code)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(vm_code)
