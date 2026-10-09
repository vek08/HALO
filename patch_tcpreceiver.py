import re

path = 'app/src/main/java/com/halo/core/TcpReceiver.java'
with open(path, 'r', encoding='utf-8') as f:
    code = f.read()

old_listen = """        try (ServerSocket serverSocket = new ServerSocket(port)) {"""
new_listen = """        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(port));"""

code = code.replace(old_listen, new_listen)

with open(path, 'w', encoding='utf-8') as f:
    f.write(code)
