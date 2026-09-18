package cr.co.topoemlid

import kotlin.math.floor

data class GgaFix(
    val latitude: Double,
    val longitude: Double,
    val fixQuality: Int,
    val satellites: Int,
    val hdop: Double?,
    val altitudeM: Double?
)

object NmeaParser {
    fun parseGga(sentence: String): GgaFix? {
        if (!sentence.startsWith("\$GPGGA") && !sentence.startsWith("\$GNGGA")) return null
        val p = sentence.substringBefore('*').split(',')
        if (p.size < 10) return null
        val lat = nmeaCoord(p[2], p[3]) ?: return null
        val lon = nmeaCoord(p[4], p[5]) ?: return null
        return GgaFix(
            latitude = lat,
            longitude = lon,
            fixQuality = p[6].toIntOrNull() ?: 0,
            satellites = p[7].toIntOrNull() ?: 0,
            hdop = p[8].toDoubleOrNull(),
            altitudeM = p[9].toDoubleOrNull()
        )
    }

    private fun nmeaCoord(value: String, hemisphere: String): Double? {
        val raw = value.toDoubleOrNull() ?: return null
        val degrees = floor(raw / 100.0)
        val minutes = raw - degrees * 100.0
        val sign = if (hemisphere == "S" || hemisphere == "W") -1 else 1
        return sign * (degrees + minutes / 60.0)
    }
}
