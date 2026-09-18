package cr.co.topoemlid

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

data class NtripMountPoint(
    val name: String,
    val identifier: String = "",
    val format: String = "",
    val carrier: String = "",
    val country: String = ""
)

data class NtripCasterCheck(
    val reachable: Boolean,
    val statusLine: String,
    val mountPoints: List<NtripMountPoint>
)

object NtripSourceTableClient {

    fun check(
        address: String,
        port: Int,
        username: String,
        password: String,
        timeoutMs: Int = 7000
    ): Result<NtripCasterCheck> = runCatching {
        val parsed = parseAddress(address)
        val attempts = listOf(RequestMode.NTRIP2, RequestMode.NTRIP1)
        var lastFailure: Throwable? = null

        for (mode in attempts) {
            try {
                val response = requestSourceTable(
                    host = parsed.host,
                    path = parsed.path,
                    secure = parsed.secure,
                    port = port,
                    username = username,
                    password = password,
                    timeoutMs = timeoutMs,
                    mode = mode
                )

                val points = parseMountPoints(response.lines)

                if (response.statusLine.contains("401") || response.statusLine.contains("403")) {
                    error("El caster respondió, pero rechazó el usuario o la contraseña.")
                }

                if (
                    response.statusLine.contains("200") ||
                    response.lines.any { it.contains("SOURCETABLE", ignoreCase = true) } ||
                    points.isNotEmpty()
                ) {
                    return@runCatching NtripCasterCheck(
                        reachable = true,
                        statusLine = response.statusLine,
                        mountPoints = points
                    )
                }

                lastFailure = IllegalStateException(
                    "El servidor respondió, pero no devolvió una sourcetable NTRIP. Respuesta: ${response.statusLine.ifBlank { "sin cabecera" }}"
                )
            } catch (e: Throwable) {
                lastFailure = e
            }
        }

        throw (lastFailure ?: IllegalStateException("No se pudo consultar el caster NTRIP."))
    }

    fun load(
        host: String,
        port: Int,
        username: String,
        password: String,
        timeoutMs: Int = 7000
    ): Result<List<NtripMountPoint>> {
        return check(host, port, username, password, timeoutMs).mapCatching { check ->
            if (check.mountPoints.isEmpty()) {
                error("El caster respondió correctamente, pero no publicó puntos de montaje.")
            }
            check.mountPoints
        }
    }

    private data class ParsedAddress(
        val host: String,
        val path: String,
        val secure: Boolean
    )

    private data class SourceTableResponse(
        val statusLine: String,
        val lines: List<String>
    )

    private enum class RequestMode { NTRIP1, NTRIP2 }

    private fun parseAddress(address: String): ParsedAddress {
        val trimmed = address.trim()
        require(trimmed.isNotBlank()) { "La dirección del caster está vacía." }

        val secure = trimmed.startsWith("https://", ignoreCase = true)
        val withoutScheme = trimmed
            .removePrefix("http://")
            .removePrefix("https://")

        val hostPart = withoutScheme.substringBefore('/')
        val pathPart = withoutScheme.substringAfter('/', "")

        val hostOnly = hostPart.substringBefore(':').trim()
        require(hostOnly.isNotBlank()) { "La dirección del caster no es válida." }

        val path = if (pathPart.isBlank()) "/" else "/$pathPart"

        return ParsedAddress(
            host = hostOnly,
            path = path,
            secure = secure
        )
    }

    private fun requestSourceTable(
        host: String,
        path: String,
        secure: Boolean,
        port: Int,
        username: String,
        password: String,
        timeoutMs: Int,
        mode: RequestMode
    ): SourceTableResponse {
        val socket: Socket = if (secure) {
            SSLSocketFactory.getDefault().createSocket() as Socket
        } else {
            Socket()
        }

        socket.use {
            it.connect(InetSocketAddress(host, port), timeoutMs)
            it.soTimeout = timeoutMs

            val auth = if (username.isNotBlank()) {
                val token = Base64.encodeToString(
                    "$username:$password".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                "Authorization: Basic $token\r\n"
            } else ""

            val request = when (mode) {
                RequestMode.NTRIP2 -> buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: $host:$port\r\n")
                    append("User-Agent: NTRIP TopoEmlid/0.3\r\n")
                    append("Ntrip-Version: Ntrip/2.0\r\n")
                    append("Accept: */*\r\n")
                    append(auth)
                    append("Connection: close\r\n\r\n")
                }
                RequestMode.NTRIP1 -> buildString {
                    append("GET $path HTTP/1.0\r\n")
                    append("User-Agent: NTRIP TopoEmlid/0.3\r\n")
                    append(auth)
                    append("\r\n")
                }
            }

            it.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            it.getOutputStream().flush()

            val reader = BufferedReader(
                InputStreamReader(it.getInputStream(), Charsets.ISO_8859_1)
            )
            val lines = generateSequence { reader.readLine() }.toList()

            return SourceTableResponse(
                statusLine = lines.firstOrNull().orEmpty(),
                lines = lines
            )
        }
    }

    private fun parseMountPoints(lines: List<String>): List<NtripMountPoint> {
        return lines
            .asSequence()
            .filter { it.startsWith("STR;") }
            .mapNotNull { line ->
                val f = line.split(';')
                val name = f.getOrNull(1)?.trim().orEmpty()
                if (name.isBlank()) null else NtripMountPoint(
                    name = name,
                    identifier = f.getOrNull(2)?.trim().orEmpty(),
                    format = f.getOrNull(3)?.trim().orEmpty(),
                    carrier = f.getOrNull(5)?.trim().orEmpty(),
                    country = f.getOrNull(8)?.trim().orEmpty()
                )
            }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
            .toList()
    }
}
