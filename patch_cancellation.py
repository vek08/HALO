import re

path = 'app/src/main/java/com/halo/ui/TransferViewModel.kt'
with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

# Replace all `catch (e: Exception)` with `catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception)`
code = code.replace("catch (e: Exception) {", "catch (e: kotlinx.coroutines.CancellationException) {\n                throw e\n            } catch (e: Exception) {")

with open(path, 'w', encoding='utf-8') as f:
    f.write(code)
