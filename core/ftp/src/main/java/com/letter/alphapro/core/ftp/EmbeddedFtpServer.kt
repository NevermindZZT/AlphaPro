package com.letter.alphapro.core.ftp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

data class TetherIncomingPhoto(
    val file: File,
    val filename: String,
    val fileSize: Long,
    val timestamp: Long = System.currentTimeMillis()
)

class EmbeddedFtpServer(
    private val saveDirectory: File,
    val port: Int = 2121,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    private val _incomingPhotos = MutableSharedFlow<TetherIncomingPhoto>(extraBufferCapacity = 64)
    val incomingPhotos: SharedFlow<TetherIncomingPhoto> = _incomingPhotos.asSharedFlow()

    fun start() {
        if (isRunning) return
        isRunning = true
        saveDirectory.mkdirs()

        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch { handleFtpSession(clientSocket) }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private suspend fun handleFtpSession(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))

            fun send(msg: String) {
                writer.write(msg + "\r\n")
                writer.flush()
            }

            send("220 AlphaPro Sony Camera FTP Service Ready")

            var pasvServer: ServerSocket? = null
            var binaryMode = false

            while (isRunning) {
                val line = reader.readLine() ?: break
                val parts = line.trim().split(" ", limit = 2)
                val cmd = parts[0].uppercase()
                val arg = if (parts.size > 1) parts[1] else ""

                when (cmd) {
                    "USER" -> send("331 User name okay, need password")
                    "PASS" -> send("230 User logged in, proceed")
                    "SYST" -> send("215 UNIX Type: L8")
                    "PWD" -> send("257 \"/\" is current directory")
                    "TYPE" -> {
                        binaryMode = arg.uppercase() == "I"
                        send("200 Type set to " + (if (binaryMode) "I" else "A"))
                    }
                    "PASV" -> {
                        pasvServer?.close()
                        val pasv = ServerSocket(0) // Random free port
                        pasvServer = pasv
                        val localAddr = client.localAddress
                        val ipBytes = localAddr.address
                        val portNum = pasv.localPort
                        val p1 = portNum / 256
                        val p2 = portNum % 256
                        val ipStr = "${ipBytes[0].toInt() and 0xFF},${ipBytes[1].toInt() and 0xFF},${ipBytes[2].toInt() and 0xFF},${ipBytes[3].toInt() and 0xFF}"
                        send("227 Entering Passive Mode ($ipStr,$p1,$p2)")
                    }
                    "STOR" -> {
                        val fileName = arg.substringAfterLast("/").substringAfterLast("\\")
                        val targetFile = File(saveDirectory, fileName)
                        send("150 Opening BINARY mode data connection for $fileName")

                        val dataSocket = pasvServer?.accept()
                        pasvServer?.close()
                        pasvServer = null

                        if (dataSocket != null) {
                            dataSocket.use { ds ->
                                val input = ds.getInputStream()
                                FileOutputStream(targetFile).use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        out.write(buffer, 0, count)
                                    }
                                    out.flush()
                                }
                            }
                            send("226 Transfer complete")
                            _incomingPhotos.tryEmit(
                                TetherIncomingPhoto(
                                    file = targetFile,
                                    filename = fileName,
                                    fileSize = targetFile.length()
                                )
                            )
                        } else {
                            send("425 Can't open data connection")
                        }
                    }
                    "NOOP" -> send("200 OK")
                    "QUIT" -> {
                        send("221 Goodbye")
                        break
                    }
                    else -> send("502 Command not implemented")
                }
            }
            pasvServer?.close()
        }
    }
}
