package cr.co.topoemlid

import kotlin.math.floor
import kotlin.math.hypot

data class GgaFix(
    val latitude: Double,
    val longitude: Double,
    val fixQuality: Int,
    val satellites: Int,
    val hdop: Double?,
    val altitudeM: Double?,
    val geoidSeparationM: Double? = null
) {
    val ellipsoidalHeightM: Double?
        get() = if (altitudeM != null && geoidSeparationM != null) altitudeM + geoidSeparationM else null
}

data class GstAccuracy(
    val horizontalAccuracyM: Double?,
    val verticalAccuracyM: Double?
)

data class GsaStatus(
    val mode: String?,
    val pdop: Double?
)

data class GsvStatus(
    val satellitesInView: Int?,
    val snrValues: List<Double>
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
            altitudeM = p[9].toDoubleOrNull(),
            geoidSeparationM = p.getOrNull(11)?.toDoubleOrNull()
        )
    }

    fun parseGst(sentence: String): GstAccuracy? {
        if (!sentence.startsWith("\$GPGST") && !sentence.startsWith("\$GNGST")) return null
        val p = sentence.substringBefore('*').split(',')
        if (p.size < 9) return null
        val latSigma = p[6].toDoubleOrNull()
        val lonSigma = p[7].toDoubleOrNull()
        val altSigma = p[8].toDoubleOrNull()
        val h = if (latSigma != null && lonSigma != null) hypot(latSigma, lonSigma) else null
        return GstAccuracy(horizontalAccuracyM = h, verticalAccuracyM = altSigma)
    }

    fun parseGsa(sentence: String): GsaStatus? {
        if (!sentence.contains("GSA")) return null
        val p = sentence.substringBefore('*').split(',')
        if (p.size < 17) return null
        val fixType = p.getOrNull(2)?.toIntOrNull()
        val mode = when (fixType) {
            3 -> "3D"
            2 -> "2D"
            1 -> "Sin solución"
            else -> null
        }
        return GsaStatus(mode = mode, pdop = p.getOrNull(15)?.toDoubleOrNull())
    }

    fun parseGsv(sentence: String): GsvStatus? {
        if (!sentence.contains("GSV")) return null
        val p = sentence.substringBefore('*').split(',')
        if (p.size < 4) return null
        val inView = p.getOrNull(3)?.toIntOrNull()
        val snr = mutableListOf<Double>()
        var i = 7
        while (i < p.size) {
            p.getOrNull(i)?.toDoubleOrNull()?.let { snr += it }
            i += 4
        }
        return GsvStatus(inView, snr)
    }

    private fun nmeaCoord(value: String, hemisphere: String): Double? {
        val raw = value.toDoubleOrNull() ?: return null
        val degrees = floor(raw / 100.0)
        val minutes = raw - degrees * 100.0
        val sign = if (hemisphere == "S" || hemisphere == "W") -1 else 1
        return sign * (degrees + minutes / 60.0)
    }
}
