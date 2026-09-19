package cr.co.topoemlid

data class SatelliteSignal(
    val id: String,
    val constellation: String,
    val snrDbHz: Double?,
    val elevationDeg: Int? = null,
    val azimuthDeg: Int? = null,
    val usedInFix: Boolean = false
)

data class GnssStatus(
    val receiverName: String = "Receptor GNSS",
    val connected: Boolean = false,
    val connectionTransport: String? = null,
    val bleServicesDiscovered: Int? = null,
    val solution: String = "SIN SEÑAL",
    val horizontalAccuracyM: Double? = null,
    val verticalAccuracyM: Double? = null,
    val satellites: Int? = null,
    val satellitesInView: Int? = null,
    val pdop: Double? = null,
    val signalNoiseAvgDbHz: Double? = null,
    val satelliteSnrValues: List<Double> = emptyList(),
    val satelliteSignals: List<SatelliteSignal> = emptyList(),
    val positioningMode: String? = null,
    val nmeaReceiving: Boolean = false,
    val lastNmeaSentence: String? = null,
    val lastNmeaAt: Long? = null,
    val correctionAgeS: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val ellipsoidalHeightM: Double? = null,
    val antennaHeightM: Double = 2.0
)

data class NtripLiveStatus(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val profileName: String? = null,
    val caster: String? = null,
    val mountPoint: String? = null,
    val bytesReceived: Long = 0L,
    val bytesForwarded: Long = 0L,
    val lastDataAt: Long? = null,
    val startedAt: Long? = null,
    val lastError: String? = null
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

enum class LayerType(val label: String) {
    WMS("WMS"),
    WMTS("WMTS"),
    XYZ("XYZ / TMS"),
    LOCAL_FILE("Archivo local"),
    DRAWING("Dibujo / CAD")
}

data class LayerItem(
    val id: String,
    val name: String,
    val type: LayerType,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val url: String? = null,
    val layerName: String? = null,
    val styleName: String? = null,
    val imageFormat: String = "image/png",
    val transparent: Boolean = true,
    val crs: String = "EPSG:3857",
    val localUri: String? = null,
    val order: Int = 0
)


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

enum class ReceiverConnectionMode(val label: String) {
    AUTO("Automático"),
    BLE("BLE"),
    BLUETOOTH_NMEA("Bluetooth / NMEA")
}

data class ReceiverProfile(
    val id: String,
    val name: String,
    val address: String,
    val transport: String = "Bluetooth",
    val preferredMode: ReceiverConnectionMode = ReceiverConnectionMode.AUTO,
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

data class SurveyPoint(
    val id: String,
    val projectId: String,
    val pointNumber: String,
    val description: String,
    val code: String,
    val antennaHeightM: Double,
    val occupationSeconds: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val ellipsoidalHeightM: Double? = null,
    val horizontalAccuracyM: Double? = null,
    val verticalAccuracyM: Double? = null,
    val solution: String = "SIN SOLUCIÓN",
    val satellites: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)
