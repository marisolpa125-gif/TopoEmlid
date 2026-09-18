package cr.co.topoemlid

data class GnssStatus(
    val receiverName: String = "Emlid Reach RS2+",
    val connected: Boolean = false,
    val solution: String = "SIN SEÑAL",
    val horizontalAccuracyM: Double? = null,
    val verticalAccuracyM: Double? = null,
    val satellites: Int? = null,
    val antennaHeightM: Double = 2.0
)

data class NtripProfile(
    val name: String,
    val host: String,
    val port: Int,
    val mountPoint: String,
    val username: String,
    val password: String
)

data class CrsProfile(
    val name: String,
    val epsg: String?,
    val description: String
)

enum class GeoidModel(val label: String) {
    LOCAL_FILE("Archivo geoidal local"),
    NONE("Altura elipsoidal")
}

data class GeoidFileConfig(
    val uri: String,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null
)

enum class DrawTool(val label: String) {
    POINT("Punto"), LINE("Línea"), POLYGON("Polígono"), RECTANGLE("Rectángulo"), TRIANGLE("Triángulo"), CIRCLE("Círculo")
}

data class LayerItem(val name: String, val visible: Boolean = true)
