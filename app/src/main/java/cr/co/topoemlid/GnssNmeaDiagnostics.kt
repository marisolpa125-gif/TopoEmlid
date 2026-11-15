package cr.co.topoemlid

/**
 * Short, user-initiated NMEA capture for diagnosing incomplete GSV output.
 * Records raw NMEA for 30 seconds; no location data is transmitted externally.
 * Export only when the user chooses a document destination.
 */
object GnssNmeaDiagnostics {
    private const val DURATION_MS = 30_000L
    private const val MAX_LINES = 5000
    private val lines = ArrayList<String>()
    private var startedAt = 0L
    private var active = false

    @Synchronized fun start() {
        lines.clear()
        startedAt = System.currentTimeMillis()
        active = true
        lines.add("# TOPO EMLID NMEA raw capture; duration=30s; local time epoch ms")
        lines.add("# WARNING: GGA/RMC may contain precise coordinates. Share only if appropriate.")
    }

    @Synchronized fun record(raw: String) {
        if (!active) return
        val now = System.currentTimeMillis()
        if (now - startedAt >= DURATION_MS || lines.size >= MAX_LINES) {
            active = false
            return
        }
        val type = raw.substringBefore(',').substringBefore('*')
        if (type.endsWith("GGA") || type.endsWith("GSA") ||
            type.endsWith("GSV") || type.endsWith("GST") ||
            type.endsWith("RMC")) {
            lines.add("$now | " + raw.take(512))
        }
    }

    @Synchronized fun isRecording(): Boolean {
        if (active && System.currentTimeMillis() - startedAt >= DURATION_MS) active = false
        return active
    }

    @Synchronized fun snapshot(): String =
        lines.joinToString("\n", postfix = "\n")
}
