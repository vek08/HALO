import re

path = 'app/src/main/java/com/halo/core/TcpReceiver.java'
with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

# Replace the broken line
code = re.sub(r'safeFileName\.replaceAll\(.*?\);', 'safeFileName.replaceAll("[\\\\\\\\/:*?\\"<>|]", "_");', code)

with open(path, 'w', encoding='utf-8') as f:
    f.write(code)
