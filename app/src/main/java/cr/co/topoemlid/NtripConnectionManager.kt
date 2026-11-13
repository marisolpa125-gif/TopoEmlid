package cr.co.topoemlid

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

class NtripConnectionManager(
    private val forwardCorrections: (ByteArray, Int) -> Boolean
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var worker: Thread? = null
    private val outputLock = Any()
    @Volatile private var lastGgaSentAt: Long = 0L

    var status by mutableStateOf(NtripLiveStatus())
        private set

    fun connect(profile: NtripProfile) {
        disconnect()
        status = NtripLiveStatus(
            connecting = true,
            profileName = profile.name,
            caster = "${profile.host}:${profile.port}",
            mountPoint = profile.mountPoint
        )

        worker = thread(name = "ntrip-rtcm") {
            try {
                val parsed = parseHost(profile.host)
                val s: Socket = if (parsed.secure) {
                    SSLSocketFactory.getDefault().createSocket() as Socket
                } else {
                    Socket()
                }
                socket = s
                s.connect(InetSocketAddress(parsed.host, profile.port), 8000)
                s.soTimeout = 15000

                val auth = if (profile.username.isNotBlank()) {
                    val token = Base64.encodeToString(
                        "${profile.username}:${profile.password}".toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    )
                    "Authorization: Basic $token\r\n"
                } else ""

                val mount = profile.mountPoint.trim().removePrefix("/")
                val request = buildString {
                    append("GET /$mount HTTP/1.1\r\n")
                    append("Host: ${parsed.host}:${profile.port}\r\n")
                    append("User-Agent: NTRIP TopoEmlid/0.4\r\n")
                    append("Ntrip-Version: Ntrip/2.0\r\n")
                    append("Accept: */*\r\n")
                    append(auth)
                    append("Connection: close\r\n\r\n")
                }

synchronized(outputLock) {
                    s.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                    s.getOutputStream().flush()
                }

                val input = s.getInputStream()
                val header = readHeader(input)
                val statusLine = header.lineSequence().firstOrNull().orEmpty()
                val accepted = Regex("^(HTTP/\\d(?:\\.\\d)? 200\\b|ICY 200\\b)", RegexOption.IGNORE_CASE).containsMatchIn(statusLine)
                if (!accepted) {
                    error("El caster rechazó la conexión: ${statusLine.ifBlank { "respuesta vacía" }}")
                }

                var received = 0L
                var forwarded = 0L
                status = status.copy(
                    connecting = false,
                    connected = true,
                    startedAt = System.currentTimeMillis(),
                    lastError = null
                )

                // Bytes forwarded mean written to the transport, NOT RTK corrections applied.
                val buffer = ByteArray(4096)
                while (!Thread.currentThread().isInterrupted) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue

                    received += n
                    val sent = forwardCorrections(buffer, n)
                    if (sent) forwarded += n

                    status = status.copy(
                        connected = true,
                        connecting = false,
                        bytesReceived = received,
                        bytesForwarded = forwarded,
                        lastDataAt = System.currentTimeMillis(),
                        lastError = if (!sent)
                            "Se reciben RTCM, pero no se pueden escribir al enlace del receptor."
                        else null
                    )
                }

                if (!Thread.currentThread().isInterrupted) {
                    status = status.copy(
                        connected = false,
                        connecting = false,
                        lastError = "El caster cerró la conexión NTRIP."
                    )
                }
            } catch (t: Throwable) {
                if (!Thread.currentThread().isInterrupted) {
                    status = status.copy(
                        connected = false,
                        connecting = false,
                        lastError = t.message ?: "No se pudo conectar al caster NTRIP."
                    )
                }
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
        }
    }

    fun sendGga(sentence: String): Boolean {
        val now = System.currentTimeMillis()
        if (!status.connected || now - lastGgaSentAt < 5_000L) return false

        val line = sentence.trim()
        if (!line.startsWith("\$GPGGA") && !line.startsWith("\$GNGGA")) return false

        val s = socket ?: return false
        if (!s.isConnected) return false

        val sent = runCatching {
            synchronized(outputLock) {
                s.getOutputStream().write((line + "\r\n").toByteArray(Charsets.US_ASCII))
                s.getOutputStream().flush()
            }
            true
        }.getOrDefault(false)

        if (sent) {
            lastGgaSentAt = now
            status = status.copy(
                ggaSentCount = status.ggaSentCount + 1L,
                lastGgaSentAt = now
            )
        }
        return sent
    }

    fun disconnect() {
        worker?.interrupt()
        worker = null
        runCatching { socket?.close() }
        socket = null
        lastGgaSentAt = 0L
        status = NtripLiveStatus()
    }

    private data class ParsedHost(val host: String, val secure: Boolean)

    private fun parseHost(raw: String): ParsedHost {
        val text = raw.trim()
        val secure = text.startsWith("https://", ignoreCase = true)
        val host = text
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .trim()
        require(host.isNotBlank()) { "Dirección del caster no válida." }
        return ParsedHost(host, secure)
    }

    private fun readHeader(input: java.io.InputStream): String {
        val bytes = ArrayList<Byte>()
        var a = -1
        var b = -1
        var c = -1
        var d: Int

        while (bytes.size < 16384) {
            d = input.read()
            if (d < 0) break
            bytes += d.toByte()
            if (a == 13 && b == 10 && c == 13 && d == 10) break
            a = b
            b = c
            c = d
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }
}
