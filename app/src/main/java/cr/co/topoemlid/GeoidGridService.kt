package cr.co.topoemlid

import android.content.Context
import android.net.Uri
import kotlin.math.abs
import kotlin.math.floor
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GeoidSample(
    val undulationM: Double,
    val sourceName: String
)

object GeoidGridService {
    private data class Node(val lat: Double, val lon: Double, val n: Double)

    /**
     * Intenta leer una grilla geoidal de texto con filas latitud,longitud,ondulación.
     * Se aceptan espacios, coma, punto y coma o tabulador como separadores.
     *
     * Si el archivo es un formato binario propietario (por ejemplo algunas grillas
     * de fabricante), se devuelve un error explícito en vez de inventar una altura.
     */
    fun undulation(
        context: Context,
        uriText: String?,
        fileName: String?,
        latitude: Double,
        longitude: Double
    ): Result<GeoidSample> = runCatching {
        require(!uriText.isNullOrBlank()) { "El proyecto no tiene archivo geoidal." }
        val uri = Uri.parse(uriText)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: error("No se pudo abrir el archivo geoidal.")

        require(bytes.isNotEmpty()) { "El archivo geoidal está vacío." }

        if (isSouthGridFile(bytes)) {
            val value = readSouthGridUndulation(bytes, latitude, longitude)
            return@runCatching GeoidSample(value, fileName ?: "Geoide SGF")
        }

        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
            ?: error("La grilla geoidal no es texto UTF-8 compatible.")

        val printable = text.count { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 }
        require(printable.toDouble() / text.length.coerceAtLeast(1) > 0.80) {
            "El archivo geoidal parece binario y todavía necesita un lector específico para este formato."
        }

        val number = Regex("[-+]?\\d+(?:[.,]\\d+)?(?:[eE][-+]?\\d+)?")
        val nodes = ArrayList<Node>()
        text.lineSequence().forEach { line ->
            val values = number.findAll(line)
                .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
                .toList()
            if (values.size >= 3) {
                for (i in 0..values.size - 3) {
                    val lat = values[i]
                    var lon = values[i + 1]
                    val n = values[i + 2]
                    if (lon > 180.0 && lon <= 360.0) lon -= 360.0
                    if (lat in -90.0..90.0 && lon in -180.0..180.0 && n in -250.0..250.0) {
                        nodes += Node(lat, lon, n)
                        break
                    }
                }
            }
        }

        require(nodes.size >= 4) {
            "No se reconoció una grilla latitud/longitud/ondulación en el archivo."
        }

        val lat0 = nodes.filter { it.lat <= latitude }.maxOfOrNull { it.lat }
        val lat1 = nodes.filter { it.lat >= latitude }.minOfOrNull { it.lat }
        val lon0 = nodes.filter { it.lon <= longitude }.maxOfOrNull { it.lon }
        val lon1 = nodes.filter { it.lon >= longitude }.minOfOrNull { it.lon }

        val value = if (lat0 != null && lat1 != null && lon0 != null && lon1 != null) {
            val q11 = nearestExact(nodes, lat0, lon0)
            val q12 = nearestExact(nodes, lat0, lon1)
            val q21 = nearestExact(nodes, lat1, lon0)
            val q22 = nearestExact(nodes, lat1, lon1)
            if (q11 != null && q12 != null && q21 != null && q22 != null) {
                bilinear(latitude, longitude, lat0, lat1, lon0, lon1, q11, q12, q21, q22)
            } else {
                nearest(nodes, latitude, longitude).n
            }
        } else {
            nearest(nodes, latitude, longitude).n
        }

        GeoidSample(value, fileName ?: "Geoide local")
    }

    private fun isSouthGridFile(bytes: ByteArray): Boolean {
        if (bytes.size < 256) return false
        val magic = bytes.copyOfRange(0, 15).toString(Charsets.US_ASCII)
        return magic == "SOUTH GRID FILE"
    }

    private fun readSouthGridUndulation(
        bytes: ByteArray,
        latitude: Double,
        longitude: Double
    ): Double {
        require(bytes.size >= 256) { "Archivo SGF incompleto." }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val minLat = bb.getDouble(96)
        val maxLat = bb.getDouble(104)
        val minLonRaw = bb.getDouble(112)
        val maxLonRaw = bb.getDouble(120)
        val latStep = bb.getDouble(128)
        val lonStep = bb.getDouble(136)
        val latCount = bb.getInt(144)
        val lonCount = bb.getInt(148)

        require(minLat.isFinite() && maxLat.isFinite() && latStep > 0.0) {
            "Encabezado SGF inválido: latitud."
        }
        require(minLonRaw.isFinite() && maxLonRaw.isFinite() && lonStep > 0.0) {
            "Encabezado SGF inválido: longitud."
        }
        require(latCount >= 2 && lonCount >= 2) {
            "Encabezado SGF inválido: dimensiones."
        }

        val expected = 256L + latCount.toLong() * lonCount.toLong() * 4L
        require(bytes.size.toLong() >= expected) {
            "Archivo SGF incompleto: faltan datos de la grilla."
        }

        val lon360 = if (longitude < 0.0) longitude + 360.0 else longitude
        require(latitude in minLat..maxLat && lon360 in minLonRaw..maxLonRaw) {
            "La posición está fuera de la cobertura del geoide SGF."
        }

        val fyRaw = (latitude - minLat) / latStep
        val fxRaw = (lon360 - minLonRaw) / lonStep
        val row0 = floor(fyRaw).toInt().coerceIn(0, latCount - 2)
        val col0 = floor(fxRaw).toInt().coerceIn(0, lonCount - 2)
        val ty = (fyRaw - row0).coerceIn(0.0, 1.0)
        val tx = (fxRaw - col0).coerceIn(0.0, 1.0)

        fun sample(row: Int, col: Int): Double {
            val index = row.toLong() * lonCount.toLong() + col.toLong()
            val offset = 256L + index * 4L
            return bb.getFloat(offset.toInt()).toDouble()
        }

        val q11 = sample(row0, col0)
        val q12 = sample(row0, col0 + 1)
        val q21 = sample(row0 + 1, col0)
        val q22 = sample(row0 + 1, col0 + 1)

        require(listOf(q11, q12, q21, q22).all { it.isFinite() && it in -250.0..250.0 }) {
            "La grilla SGF contiene valores geoidales inválidos."
        }

        val south = q11 * (1.0 - tx) + q12 * tx
        val north = q21 * (1.0 - tx) + q22 * tx
        return south * (1.0 - ty) + north * ty
    }

    private fun nearestExact(nodes: List<Node>, lat: Double, lon: Double): Double? =
        nodes.firstOrNull { abs(it.lat - lat) < 1e-10 && abs(it.lon - lon) < 1e-10 }?.n

    private fun nearest(nodes: List<Node>, lat: Double, lon: Double): Node =
        nodes.minBy { (it.lat - lat) * (it.lat - lat) + (it.lon - lon) * (it.lon - lon) }

    private fun bilinear(
        lat: Double,
        lon: Double,
        lat0: Double,
        lat1: Double,
        lon0: Double,
        lon1: Double,
        q11: Double,
        q12: Double,
        q21: Double,
        q22: Double
    ): Double {
        if (abs(lat1 - lat0) < 1e-12 && abs(lon1 - lon0) < 1e-12) return q11
        if (abs(lat1 - lat0) < 1e-12) {
            val x = ((lon - lon0) / (lon1 - lon0)).coerceIn(0.0, 1.0)
            return q11 * (1 - x) + q12 * x
        }
        if (abs(lon1 - lon0) < 1e-12) {
            val y = ((lat - lat0) / (lat1 - lat0)).coerceIn(0.0, 1.0)
            return q11 * (1 - y) + q21 * y
        }
        val x = ((lon - lon0) / (lon1 - lon0)).coerceIn(0.0, 1.0)
        val y = ((lat - lat0) / (lat1 - lat0)).coerceIn(0.0, 1.0)
        val a = q11 * (1 - x) + q12 * x
        val b = q21 * (1 - x) + q22 * x
        return a * (1 - y) + b * y
    }
}
