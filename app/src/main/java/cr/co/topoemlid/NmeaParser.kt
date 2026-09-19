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
    val geoidSeparationM: Double? = null,
    val correctionAgeS: Double? = null
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
    val pdop: Double?,
    val usedSatelliteIds: Set<String>
)

data class GsvSatellite(
    val id: String,
    val constellation: String,
    val snrDbHz: Double?,
    val elevationDeg: Int?,
    val azimuthDeg: Int?
)

data class GsvStatus(
    val satellitesInView: Int?,
    val totalMessages: Int?,
    val messageNumber: Int?,
    val talker: String,
    val satellites: List<GsvSatellite>
) {
    val snrValues: List<Double>
        get() = satellites.mapNotNull { it.snrDbHz }
}

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
            geoidSeparationM = p.getOrNull(11)?.toDoubleOrNull(),
            correctionAgeS = p.getOrNull(13)?.toDoubleOrNull()
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
        val talker = sentence.removePrefix("$").take(2)
        val used = (3..14)
            .mapNotNull { p.getOrNull(it)?.toIntOrNull() }
            .map { satelliteLabel(talker, it) }
            .toSet()
        return GsaStatus(
            mode = mode,
            pdop = p.getOrNull(15)?.toDoubleOrNull(),
            usedSatelliteIds = used
        )
    }

    fun parseGsv(sentence: String): GsvStatus? {
        if (!sentence.contains("GSV")) return null
        val p = sentence.substringBefore('*').split(',')
        if (p.size < 4) return null

        val talker = sentence.removePrefix("$").take(2)
        val totalMessages = p.getOrNull(1)?.toIntOrNull()
        val messageNumber = p.getOrNull(2)?.toIntOrNull()
        val inView = p.getOrNull(3)?.toIntOrNull()
        val satellites = mutableListOf<GsvSatellite>()

        var i = 4
        while (i + 3 < p.size) {
            val prn = p.getOrNull(i)?.toIntOrNull()
            val elevation = p.getOrNull(i + 1)?.toIntOrNull()
            val azimuth = p.getOrNull(i + 2)?.toIntOrNull()
            val snr = p.getOrNull(i + 3)?.toDoubleOrNull()
            if (prn != null) {
                val label = satelliteLabel(talker, prn)
                satellites += GsvSatellite(
                    id = label,
                    constellation = constellationName(talker, prn),
                    snrDbHz = snr,
                    elevationDeg = elevation,
                    azimuthDeg = azimuth
                )
            }
            i += 4
        }

        return GsvStatus(
            satellitesInView = inView,
            totalMessages = totalMessages,
            messageNumber = messageNumber,
            talker = talker,
            satellites = satellites
        )
    }

    private fun satelliteLabel(talker: String, prn: Int): String {
        val prefix = when (talker.uppercase()) {
            "GP" -> "G"
            "GL" -> "R"
            "GA" -> "E"
            "GB", "BD" -> "C"
            "GQ", "QZ" -> "J"
            "GI" -> "I"
            else -> when (prn) {
                in 1..32 -> "G"
                in 65..96 -> "R"
                in 193..200 -> "J"
                in 201..237 -> "C"
                in 301..336 -> "E"
                else -> "S"
            }
        }
        val displayPrn = when (prefix) {
            "R" -> if (prn >= 65) prn - 64 else prn
            "C" -> if (prn >= 201) prn - 200 else prn
            "E" -> if (prn >= 301) prn - 300 else prn
            else -> prn
        }
        return prefix + displayPrn.toString().padStart(2, '0')
    }

    private fun constellationName(talker: String, prn: Int): String {
        return when (satelliteLabel(talker, prn).firstOrNull()) {
            'G' -> "GPS"
            'R' -> "GLONASS"
            'E' -> "Galileo"
            'C' -> "BeiDou"
            'J' -> "QZSS"
            'I' -> "NavIC"
            else -> "SBAS/Otro"
        }
    }

    private fun nmeaCoord(value: String, hemisphere: String): Double? {
        val raw = value.toDoubleOrNull() ?: return null
        val degrees = floor(raw / 100.0)
        val minutes = raw - degrees * 100.0
        val sign = if (hemisphere == "S" || hemisphere == "W") -1 else 1
        return sign * (degrees + minutes / 60.0)
    }
}
