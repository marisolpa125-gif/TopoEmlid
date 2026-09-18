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
    val id: String,
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


data class TopoProject(
    val id: String,
    val name: String,
    val location: String = "",
    val crsName: String = "CRTM05",
    val geoidModel: GeoidModel = GeoidModel.LOCAL_FILE,
    val geoidFileUri: String? = null,
    val geoidFileName: String? = null,
    val antennaHeightM: Double = 2.0,
    val ntripProfileName: String? = null,
    val ntripProfileId: String? = null,
    val receiverProfileId: String? = null,
    val receiverProfileName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class ReceiverProfile(
    val id: String,
    val name: String,
    val address: String,
    val transport: String = "Bluetooth",
    val lastConnectedAt: Long? = null
)

data class ReceiverTelemetry(
    val connected: Boolean = false,
    val receiverName: String? = null,
    val transport: String? = null,
    val solution: String = "SIN SEÑAL",
    val satellites: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val ellipsoidalHeightM: Double? = null,
    val horizontalAccuracyM: Double? = null,
    val verticalAccuracyM: Double? = null,
    val correctionAgeS: Double? = null,
    val nmeaReceiving: Boolean = false,
    val internetSource: String? = null
)
