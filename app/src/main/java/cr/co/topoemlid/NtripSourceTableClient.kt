package cr.co.topoemlid

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

data class NtripMountPoint(
    val name: String,
    val identifier: String = "",
    val format: String = "",
    val carrier: String = "",
    val country: String = ""
)

object NtripSourceTableClient {
    fun load(
        host: String,
        port: Int,
        username: String,
        password: String,
        timeoutMs: Int = 7000
    ): Result<List<NtripMountPoint>> = runCatching {
        val cleanHost = host
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')

        Socket().use { socket ->
            socket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val auth = if (username.isNotBlank()) {
                val token = Base64.encodeToString(
                    "$username:$password".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                "Authorization: Basic $token\r\n"
            } else ""

            val request = buildString {
                append("GET / HTTP/1.0\r\n")
                append("Host: $cleanHost:$port\r\n")
                append("User-Agent: NTRIP TopoEmlid/0.3\r\n")
                append("Ntrip-Version: Ntrip/2.0\r\n")
                append("Accept: */*\r\n")
                append(auth)
                append("Connection: close\r\n\r\n")
            }

            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val lines = generateSequence { reader.readLine() }.toList()

            val first = lines.firstOrNull().orEmpty()
            if (
                first.contains("401") ||
                first.contains("403")
            ) {
                error("El caster rechazó el usuario o la contraseña.")
            }
            if (
                !first.contains("200") &&
                !first.contains("SOURCETABLE", ignoreCase = true)
            ) {
                error("El caster no devolvió una sourcetable válida.")
            }

            val points = lines
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

            if (points.isEmpty()) {
                error("El caster respondió, pero no publicó puntos de montaje.")
            }

            points
        }
    }
}
