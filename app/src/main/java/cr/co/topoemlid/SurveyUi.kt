package cr.co.topoemlid

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyScreen(
    project: TopoProject?,
    gnss: GnssStatus
) {
    val context = LocalContext.current
    val pointStore = remember(project?.id) { SurveyPointStore(context) }
    val layerStore = remember(project?.id) { LayerStore(context) }
    val basemapStore = remember(project?.id) { BasemapStore(context) }
    val selectedBasemap = basemapStore.selected(project?.id)
    val mapboxToken = basemapStore.mapboxToken()
    val projectLayers = project?.let { layerStore.load(it.id) } ?: emptyList()
    var savedPoints by remember(project?.id) {
        mutableStateOf(project?.let { pointStore.load(it.id) } ?: emptyList())
    }

    var pointNumber by remember(project?.id) {
        mutableStateOf(nextPointNumber(savedPoints))
    }
    var code by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var antennaHeight by remember(project?.id, project?.antennaHeightM) {
        mutableStateOf((project?.antennaHeightM ?: 2.0).toString())
    }
    var seconds by remember { mutableStateOf("5") }
    var showConfigPanel by remember { mutableStateOf(false) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var pointPhoto by remember { mutableStateOf<Uri?>(null) }
    var followReceiver by remember { mutableStateOf(false) }

    var measuring by remember { mutableStateOf(false) }
    var secondsRemaining by remember { mutableIntStateOf(0) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    var dragOffset by remember { mutableStateOf(Offset(40f, 300f)) }
    var parentSize by remember { mutableStateOf(IntSize.Zero) }
    var buttonSize by remember { mutableStateOf(IntSize.Zero) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pointPhoto = uri
    }

    fun startMeasurement() {
        if (measuring) return
        if (project == null) {
            lastMessage = "Abra o cree un proyecto primero."
            return
        }
        if (!gnss.connected) {
            lastMessage = "No hay receptor GNSS conectado."
            return
        }
        val duration = seconds.toIntOrNull()?.coerceIn(1, 600) ?: 5
        seconds = duration.toString()
        secondsRemaining = duration
        lastMessage = null
        measuring = true
    }

    LaunchedEffect(gnss.latitude, gnss.longitude, followReceiver) {
        if (followReceiver) {
            val lat = gnss.latitude
            val lon = gnss.longitude
            if (lat != null && lon != null) {
                mapRef?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 18.0)
                )
            }
        }
    }

    LaunchedEffect(measuring) {
        if (!measuring) return@LaunchedEffect
        val p = project ?: run {
            measuring = false
            return@LaunchedEffect
        }

        while (secondsRemaining > 0 && measuring) {
            delay(1000)
            secondsRemaining -= 1
        }

        if (measuring && secondsRemaining == 0) {
            val saved = SurveyPoint(
                id = UUID.randomUUID().toString(),
                projectId = p.id,
                pointNumber = pointNumber.ifBlank { nextPointNumber(savedPoints) },
                description = description.trim(),
                code = code.trim(),
                antennaHeightM = antennaHeight.toDoubleOrNull() ?: p.antennaHeightM,
                occupationSeconds = seconds.toIntOrNull()?.coerceIn(1, 600) ?: 5,
                horizontalAccuracyM = gnss.horizontalAccuracyM,
                verticalAccuracyM = gnss.verticalAccuracyM,
                solution = gnss.solution,
                satellites = gnss.satellites
            )

            val updated = savedPoints + saved
            savedPoints = updated
            pointStore.save(p.id, updated)
            pointNumber = incrementPointNumber(saved.pointNumber)
            description = ""
            pointPhoto = null
            measuring = false
            lastMessage = "Punto ${saved.pointNumber} guardado"
            delay(1800)
            if (!measuring) lastMessage = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { parentSize = it.size }
    ) {
        key(
            project?.id,
            selectedBasemap,
            mapboxToken,
            projectLayers.hashCode()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapContext ->
                    MapView(mapContext).apply {
                        onCreate(null)
                        onStart()
                        onResume()
                        getMapAsync { map ->
                            mapRef = map

                            val baseStyle = when (selectedBasemap) {
                                BasemapType.BASIC -> Style.Builder()
                                    .fromUri("https://demotiles.maplibre.org/style.json")

                                BasemapType.MAPBOX_STREETS,
                                BasemapType.MAPBOX_SATELLITE -> Style.Builder()
                                    .fromJson(
                                        """
                                        {
                                          "version": 8,
                                          "sources": {},
                                          "layers": [
                                            {
                                              "id": "background",
                                              "type": "background",
                                              "paint": {"background-color": "#d9dde1"}
                                            }
                                          ]
                                        }
                                        """.trimIndent()
                                    )
                            }

                            map.setStyle(baseStyle) { style ->
                                addSelectedBasemap(style, selectedBasemap, mapboxToken)
                                addProjectRasterLayers(style, projectLayers)
                            }
                        }
                    }
                }
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(8.dp),
            tonalElevation = 4.dp
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(project?.name ?: "Sin proyecto activo", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (gnss.connected) {
                        "${gnss.solution} • Sat: ${gnss.satellites ?: "—"} • H: ${gnss.horizontalAccuracyM?.let { "%.3f m".format(it) } ?: "—"} • V: ${gnss.verticalAccuracyM?.let { "%.3f m".format(it) } ?: "—"}"
                    } else {
                        "NO HAY NINGÚN RECEPTOR CONECTADO"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { mapRef?.animateCamera(CameraUpdateFactory.zoomIn()) }
            ) { Text("+") }

            SmallFloatingActionButton(
                onClick = { mapRef?.animateCamera(CameraUpdateFactory.zoomOut()) }
            ) { Text("−") }

            SmallFloatingActionButton(
                onClick = {
                    val lat = gnss.latitude
                    val lon = gnss.longitude
                    if (lat != null && lon != null) {
                        mapRef?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 18.0)
                        )
                    } else {
                        lastMessage = "Aún no hay posición GNSS para centrar."
                    }
                }
            ) { Text("◎") }

            SmallFloatingActionButton(
                onClick = {
                    val pts = savedPoints.filter { it.latitude != null && it.longitude != null }
                    if (pts.isNotEmpty()) {
                        val p = pts.last()
                        mapRef?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(p.latitude!!, p.longitude!!),
                                17.0
                            )
                        )
                    } else {
                        mapRef?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(9.7489, -83.7534), 7.0)
                        )
                    }
                }
            ) { Text("▣") }

            SmallFloatingActionButton(
                onClick = { followReceiver = !followReceiver }
            ) { Text(if (followReceiver) "F✓" else "F") }

            SmallFloatingActionButton(
                onClick = { lastMessage = "Capas y estilo del mapa se administran desde Capas." }
            ) { Text("▱") }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .size(86.dp)
                .onGloballyPositioned { buttonSize = it.size }
                .pointerInput(parentSize, buttonSize, measuring) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, amount ->
                            change.consume()
                            val maxX = (parentSize.width - buttonSize.width).coerceAtLeast(0).toFloat()
                            val maxY = (parentSize.height - buttonSize.height).coerceAtLeast(0).toFloat()
                            dragOffset = Offset(
                                x = (dragOffset.x + amount.x).coerceIn(0f, maxX),
                                y = (dragOffset.y + amount.y).coerceIn(0f, maxY)
                            )
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            FloatingActionButton(
                onClick = { startMeasurement() },
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                containerColor = if (measuring) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            ) {
                Text(
                    when {
                        measuring -> secondsRemaining.toString()
                        else -> "MEDIR"
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lastMessage?.let { message ->
                Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Text(message, modifier = Modifier.padding(10.dp))
                }
            }

            Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("P: $pointNumber")
                    Text("H: ${gnss.horizontalAccuracyM?.let { "%.3f".format(it) } ?: "—"}")
                    Text("V: ${gnss.verticalAccuracyM?.let { "%.3f".format(it) } ?: "—"}")
                    Text(if (gnss.connected) gnss.solution else "SIN RECEPTOR")
                }
            }

            Button(
                onClick = { showConfigPanel = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Configurar punto") }
        }
    }

    if (showConfigPanel) {
        ModalBottomSheet(onDismissRequest = { showConfigPanel = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Configuración de medición", style = MaterialTheme.typography.headlineSmall)

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pointNumber,
                    onValueChange = { pointNumber = it },
                    label = { Text("Nombre / número del punto") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Código") },
                    supportingText = { Text("Luego aquí se conectará la biblioteca editable de códigos.") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = antennaHeight,
                        onValueChange = { antennaHeight = it },
                        label = { Text("Altura antena (m)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { seconds = it },
                        label = { Text("Tiempo (s)") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(10.dp))
                Button(onClick = { photoPicker.launch("image/*") }) {
                    Text(if (pointPhoto == null) "Agregar foto del punto" else "Cambiar foto")
                }

                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Siguiente observación", style = MaterialTheme.typography.titleMedium)
                        Text("Punto: $pointNumber")
                        Text("Descripción: ${description.ifBlank { "—" }}")
                        Text("Código: ${code.ifBlank { "—" }}")
                        Text("Altura antena: $antennaHeight m")
                        Text("Tiempo: $seconds s")
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showConfigPanel = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Usar esta configuración") }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun nextPointNumber(points: List<SurveyPoint>): String {
    val max = points.mapNotNull { it.pointNumber.toIntOrNull() }.maxOrNull() ?: 0
    return (max + 1).toString()
}

private fun incrementPointNumber(current: String): String {
    return current.toIntOrNull()?.plus(1)?.toString() ?: current
}


private fun addSelectedBasemap(
    style: Style,
    basemap: BasemapType,
    mapboxToken: String
) {
    if (basemap == BasemapType.BASIC) return
    if (mapboxToken.isBlank()) return

    val tileUrl = when (basemap) {
        BasemapType.MAPBOX_STREETS ->
            "https://api.mapbox.com/styles/v1/mapbox/streets-v12/tiles/256/{z}/{x}/{y}?access_token=$mapboxToken"

        BasemapType.MAPBOX_SATELLITE ->
            "https://api.mapbox.com/v4/mapbox.satellite/{z}/{x}/{y}.jpg90?access_token=$mapboxToken"

        BasemapType.BASIC -> return
    }

    runCatching {
        val sourceId = "basemap-mapbox-source"
        val layerId = "basemap-mapbox-layer"
        val tileSet = TileSet("2.2.0", tileUrl)
        style.addSource(RasterSource(sourceId, tileSet, 256))
        style.addLayer(
            RasterLayer(layerId, sourceId).withProperties(
                PropertyFactory.rasterOpacity(1f)
            )
        )
    }
}

private fun addProjectRasterLayers(
    style: Style,
    layers: List<LayerItem>
) {
    layers
        .filter { it.visible }
        .sortedBy { it.order }
        .forEach { layer ->
            val tileUrl = when (layer.type) {
                LayerType.WMS -> buildWmsTileUrl(layer)
                LayerType.XYZ, LayerType.WMTS -> layer.url
                else -> null
            } ?: return@forEach

            val sourceId = "project-source-${layer.id}"
            val layerId = "project-layer-${layer.id}"

            runCatching {
                val tileSet = TileSet("2.2.0", tileUrl)
                style.addSource(RasterSource(sourceId, tileSet, 256))
                style.addLayer(
                    RasterLayer(layerId, sourceId).withProperties(
                        PropertyFactory.rasterOpacity(layer.opacity)
                    )
                )
            }
        }
}

private fun buildWmsTileUrl(layer: LayerItem): String? {
    val base = layer.url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val layerName = layer.layerName?.trim()?.takeIf { it.isNotBlank() } ?: return null

    if (base.contains("{bbox-epsg-3857}", ignoreCase = true)) {
        return base
    }

    val separator = if (base.contains("?")) {
        if (base.endsWith("?") || base.endsWith("&")) "" else "&"
    } else {
        "?"
    }

    val encodedLayer = java.net.URLEncoder.encode(layerName, "UTF-8")
    val encodedStyle = java.net.URLEncoder.encode(layer.styleName.orEmpty(), "UTF-8")
    val encodedFormat = java.net.URLEncoder.encode(layer.imageFormat, "UTF-8")

    return buildString {
        append(base)
        append(separator)
        append("service=WMS")
        append("&request=GetMap")
        append("&version=1.1.1")
        append("&layers=")
        append(encodedLayer)
        append("&styles=")
        append(encodedStyle)
        append("&format=")
        append(encodedFormat)
        append("&transparent=")
        append(layer.transparent)
        append("&srs=EPSG:3857")
        append("&bbox={bbox-epsg-3857}")
        append("&width=256")
        append("&height=256")
    }
}
