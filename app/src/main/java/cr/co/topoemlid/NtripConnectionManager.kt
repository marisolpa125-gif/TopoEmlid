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

                s.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                s.getOutputStream().flush()

                val input = s.getInputStream()
                val header = readHeader(input)
                val statusLine = header.lineSequence().firstOrNull().orEmpty()
                val accepted = statusLine.contains("200") || statusLine.startsWith("ICY 200", ignoreCase = true)
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
                            "Se reciben correcciones RTCM, pero todavía no se están enviando al receptor por el enlace activo."
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

    fun disconnect() {
        worker?.interrupt()
        worker = null
        runCatching { socket?.close() }
        socket = null
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
