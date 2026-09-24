package cr.co.topoemlid

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor

data class GeoidSample(
    val undulationM: Double,
    val sourceName: String
)

object GeoidGridService {
    private data class Node(val lat: Double, val lon: Double, val n: Double)

    fun undulation(
        context: Context,
        uriText: String?,
        fileName: String?,
        latitude: Double,
        longitude: Double
    ): Result<GeoidSample> = runCatching {
        require(!uriText.isNullOrBlank()) { "El proyecto no tiene archivo geoidal." }
        val uri = Uri.parse(uriText)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("No se pudo abrir el archivo geoidal.")

        require(bytes.isNotEmpty()) { "El archivo geoidal está vacío." }

        if (isTiff(bytes)) {
            val value = readGeoTiffUndulation(bytes, latitude, longitude)
            return@runCatching GeoidSample(value, fileName ?: "EGM2008 GeoTIFF")
        }

        if (isSouthGridFile(bytes)) {
            val value = readSouthGridUndulation(bytes, latitude, longitude)
            return@runCatching GeoidSample(value, fileName ?: "Geoide SGF")
        }

        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
            ?: error("La grilla geoidal no es texto UTF-8 compatible.")

        val printable = text.count { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 }
        require(printable.toDouble() / text.length.coerceAtLeast(1) > 0.80) {
            "Formato geoidal binario no reconocido."
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

    private fun isTiff(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val ii = bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte()
        val mm = bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte()
        if (!ii && !mm) return false
        val order = if (ii) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        return ByteBuffer.wrap(bytes, 2, 2).order(order).short.toInt() and 0xffff == 42
    }

    /**
     * Lector GeoTIFF mínimo y deliberadamente estricto para grillas geoidales.
     *
     * La copia EGM2008_CostaRica_2p5min_TOPOEMLID.tif se genera como:
     *  - TIFF clásico
     *  - Float32 / IEEEFP
     *  - 1 banda
     *  - sin compresión
     *  - organización por strips
     *  - ModelPixelScale + ModelTiepoint
     *
     * Mantener estas restricciones evita depender de una librería GDAL pesada en Android.
     */
    private fun readGeoTiffUndulation(
        bytes: ByteArray,
        latitude: Double,
        longitude: Double
    ): Double {
        val little = bytes[0] == 'I'.code.toByte()
        val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val bb = ByteBuffer.wrap(bytes).order(order)

        fun u16(offset: Int): Int {
            require(offset >= 0 && offset + 2 <= bytes.size) { "GeoTIFF truncado." }
            return bb.getShort(offset).toInt() and 0xffff
        }
        fun u32(offset: Int): Long {
            require(offset >= 0 && offset + 4 <= bytes.size) { "GeoTIFF truncado." }
            return bb.getInt(offset).toLong() and 0xffffffffL
        }

        require(u16(2) == 42) { "TIFF no compatible." }
        val ifdOffset = u32(4).toInt()
        require(ifdOffset in 8 until bytes.size - 2) { "IFD GeoTIFF inválido." }

        data class Entry(val type: Int, val count: Int, val valueOffset: Int, val entryOffset: Int)
        val entries = HashMap<Int, Entry>()
        val count = u16(ifdOffset)
        var p = ifdOffset + 2
        repeat(count) {
            require(p + 12 <= bytes.size) { "IFD GeoTIFF incompleto." }
            val tag = u16(p)
            val type = u16(p + 2)
            val n = u32(p + 4).toInt()
            val vo = u32(p + 8).toInt()
            entries[tag] = Entry(type, n, vo, p)
            p += 12
        }

        fun typeSize(type: Int): Int = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11 -> 4
            5, 10, 12 -> 8
            else -> error("Tipo TIFF no soportado: $type")
        }

        fun valueBase(e: Entry): Int {
            val total = e.count.toLong() * typeSize(e.type).toLong()
            return if (total <= 4L) e.entryOffset + 8 else e.valueOffset
        }

        fun longs(tag: Int): LongArray {
            val e = entries[tag] ?: error("GeoTIFF sin tag requerido $tag.")
            val base = valueBase(e)
            return LongArray(e.count) { i ->
                val o = base + i * typeSize(e.type)
                when (e.type) {
                    3 -> u16(o).toLong()
                    4 -> u32(o)
                    else -> error("Tag TIFF $tag con tipo entero no soportado.")
                }
            }
        }

        fun doubles(tag: Int): DoubleArray {
            val e = entries[tag] ?: error("GeoTIFF sin tag requerido $tag.")
            val base = valueBase(e)
            return DoubleArray(e.count) { i ->
                val o = base + i * typeSize(e.type)
                when (e.type) {
                    11 -> bb.getFloat(o).toDouble()
                    12 -> bb.getDouble(o)
                    3 -> u16(o).toDouble()
                    4 -> u32(o).toDouble()
                    else -> error("Tag TIFF $tag con tipo numérico no soportado.")
                }
            }
        }

        val width = longs(256).first().toInt()
        val height = longs(257).first().toInt()
        val bitsPerSample = entries[258]?.let { longs(258).first().toInt() } ?: 32
        val compression = entries[259]?.let { longs(259).first().toInt() } ?: 1
        val samplesPerPixel = entries[277]?.let { longs(277).first().toInt() } ?: 1
        val rowsPerStrip = entries[278]?.let { longs(278).first().toInt() } ?: height
        val sampleFormat = entries[339]?.let { longs(339).first().toInt() } ?: 1
        val predictor = entries[317]?.let { longs(317).first().toInt() } ?: 1
        val planarConfiguration = entries[284]?.let { longs(284).first().toInt() } ?: 1

        require(width >= 2 && height >= 2) { "Grilla GeoTIFF demasiado pequeña." }
        require(bitsPerSample == 32 && sampleFormat == 3) {
            "El GeoTIFF debe ser Float32 IEEE."
        }
        require(compression == 1 && predictor == 1) {
            "Este GeoTIFF está comprimido. Use EGM2008_CostaRica_2p5min_TOPOEMLID.tif."
        }
        require(samplesPerPixel == 1) { "El GeoTIFF geoidal debe tener una sola banda." }
        require(planarConfiguration == 1 || planarConfiguration == 2) {
            "PlanarConfiguration GeoTIFF no soportado."
        }

        val scale = doubles(33550)
        val tie = doubles(33922)
        require(scale.size >= 2 && scale[0] > 0.0 && scale[1] > 0.0) {
            "GeoTIFF sin resolución geográfica válida."
        }
        require(tie.size >= 6) { "GeoTIFF sin punto de amarre geográfico." }

        var rasterType = 2 // PixelIsPoint por defecto para grillas geodésicas PROJ.
        entries[34735]?.let {
            val keys = longs(34735).map { v -> v.toInt() }
            if (keys.size >= 4) {
                val keyCount = keys[3]
                for (i in 0 until keyCount) {
                    val k = 4 + i * 4
                    if (k + 3 < keys.size && keys[k] == 1025 && keys[k + 1] == 0 && keys[k + 2] == 1) {
                        rasterType = keys[k + 3]
                        break
                    }
                }
            }
        }

        val tiePixelX = tie[0]
        val tiePixelY = tie[1]
        var originLon = tie[3] - tiePixelX * scale[0]
        var originLat = tie[4] + tiePixelY * scale[1]

        // PixelIsArea expresa el origen en la esquina del pixel; para interpolar nodos
        // geodésicos usamos el centro.
        if (rasterType == 1) {
            originLon += scale[0] * 0.5
            originLat -= scale[1] * 0.5
        }

        val maxLon = originLon + (width - 1) * scale[0]
        val minLat = originLat - (height - 1) * scale[1]
        require(longitude >= originLon - 1e-10 && longitude <= maxLon + 1e-10 &&
            latitude >= minLat - 1e-10 && latitude <= originLat + 1e-10) {
            "La posición está fuera de la cobertura del EGM2008 cargado."
        }

        val x = ((longitude - originLon) / scale[0]).coerceIn(0.0, (width - 1).toDouble())
        val y = ((originLat - latitude) / scale[1]).coerceIn(0.0, (height - 1).toDouble())
        val col0 = floor(x).toInt().coerceIn(0, width - 2)
        val row0 = floor(y).toInt().coerceIn(0, height - 2)
        val tx = (x - col0).coerceIn(0.0, 1.0)
        val ty = (y - row0).coerceIn(0.0, 1.0)

        val stripOffsets = longs(273)
        val stripByteCounts = longs(279)
        require(stripOffsets.isNotEmpty() && stripOffsets.size == stripByteCounts.size) {
            "GeoTIFF con strips inválidos."
        }

        fun sample(row: Int, col: Int): Double {
            val strip = row / rowsPerStrip
            require(strip in stripOffsets.indices) { "Strip GeoTIFF fuera de rango." }
            val localRow = row % rowsPerStrip
            val byteOffsetInStrip = (localRow.toLong() * width.toLong() + col.toLong()) * 4L
            require(byteOffsetInStrip + 4L <= stripByteCounts[strip]) {
                "Datos GeoTIFF incompletos."
            }
            val absolute = stripOffsets[strip] + byteOffsetInStrip
            require(absolute >= 0 && absolute + 4L <= bytes.size.toLong()) {
                "Offset GeoTIFF inválido."
            }
            val v = bb.getFloat(absolute.toInt()).toDouble()
            require(v.isFinite() && v in -250.0..250.0) {
                "Valor geoidal EGM2008 inválido."
            }
            return v
        }

        val q11 = sample(row0, col0)
        val q12 = sample(row0, col0 + 1)
        val q21 = sample(row0 + 1, col0)
        val q22 = sample(row0 + 1, col0 + 1)

        val top = q11 * (1.0 - tx) + q12 * tx
        val bottom = q21 * (1.0 - tx) + q22 * tx
        return top * (1.0 - ty) + bottom * ty
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
