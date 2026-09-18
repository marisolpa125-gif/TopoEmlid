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
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.annotations.PolygonOptions
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
import kotlin.math.*

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
    var projectLayers by remember(project?.id) {
        mutableStateOf(project?.let { layerStore.load(it.id) } ?: emptyList())
    }
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
    var showLayersPanel by remember { mutableStateOf(false) }
    var showToolsPanel by remember { mutableStateOf(false) }
    var activeMapTool by remember { mutableStateOf(MapFieldTool.NONE) }
    var toolPoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var toolResult by remember { mutableStateOf<String?>(null) }
    var parallelOffsetText by remember { mutableStateOf("1.00") }
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

                            map.addOnMapClickListener { latLng ->
                                if (activeMapTool == MapFieldTool.NONE) {
                                    false
                                } else {
                                    val updated = when (activeMapTool) {
                                        MapFieldTool.POINT -> listOf(latLng)
                                        MapFieldTool.RECTANGLE, MapFieldTool.CIRCLE ->
                                            if (toolPoints.size >= 2) listOf(latLng) else toolPoints + latLng
                                        else -> toolPoints + latLng
                                    }

                                    toolPoints = updated
                                    toolResult = renderFieldTool(map, activeMapTool, updated, parallelOffsetText.toDoubleOrNull() ?: 1.0)

                                    if (
                                        activeMapTool == MapFieldTool.POINT ||
                                        ((activeMapTool == MapFieldTool.RECTANGLE ||
                                          activeMapTool == MapFieldTool.CIRCLE ||
                                          activeMapTool == MapFieldTool.PARALLEL) && updated.size >= 2)
                                    ) {
                                        activeMapTool = MapFieldTool.NONE
                                    }
                                    true
                                }
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
                onClick = {
                    val current = mapRef?.cameraPosition
                    if (current != null) {
                        mapRef?.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                org.maplibre.android.camera.CameraPosition.Builder(current)
                                    .bearing(0.0)
                                    .build()
                            )
                        )
                    }
                }
            ) { Text("N") }

            SmallFloatingActionButton(
                onClick = { showToolsPanel = true }
            ) { Text("✣") }

            SmallFloatingActionButton(
                onClick = {
                    projectLayers = project?.let { layerStore.load(it.id) } ?: emptyList()
                    showLayersPanel = true
                }
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
            toolResult?.let { result ->
                Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Text(result, modifier = Modifier.padding(10.dp))
                }
            }

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

    if (showToolsPanel) {
        ModalBottomSheet(onDismissRequest = { showToolsPanel = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Herramientas del mapa", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Seleccione una herramienta y luego toque el mapa para marcar los puntos.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(12.dp))

                val tools = listOf(
                    MapFieldTool.POINT,
                    MapFieldTool.LINE,
                    MapFieldTool.DISTANCE,
                    MapFieldTool.AREA,
                    MapFieldTool.PERIMETER,
                    MapFieldTool.POLYGON,
                    MapFieldTool.RECTANGLE,
                    MapFieldTool.CIRCLE,
                    MapFieldTool.PARALLEL
                )

                OutlinedTextField(
                    value = parallelOffsetText,
                    onValueChange = { parallelOffsetText = it },
                    label = { Text("Separación paralela (m)") },
                    supportingText = { Text("Se usa al elegir Línea paralela.") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                tools.forEach { tool ->
                    Button(
                        onClick = {
                            activeMapTool = tool
                            toolPoints = emptyList()
                            toolResult = tool.instructions
                            mapRef?.clear()
                            showToolsPanel = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(tool.label)
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (activeMapTool != MapFieldTool.NONE) {
                    if (activeMapTool == MapFieldTool.LINE ||
                        activeMapTool == MapFieldTool.DISTANCE ||
                        activeMapTool == MapFieldTool.AREA ||
                        activeMapTool == MapFieldTool.PERIMETER ||
                        activeMapTool == MapFieldTool.POLYGON
                    ) {
                        Button(
                            onClick = {
                                activeMapTool = MapFieldTool.NONE
                                showToolsPanel = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Terminar herramienta")
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    OutlinedButton(
                        onClick = {
                            activeMapTool = MapFieldTool.NONE
                            toolPoints = emptyList()
                            toolResult = null
                            mapRef?.clear()
                            showToolsPanel = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancelar herramienta")
                    }
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showLayersPanel) {
        ModalBottomSheet(onDismissRequest = { showLayersPanel = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Capas visibles", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Active o desactive las capas cargadas del proyecto sin salir del Levantamiento.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(12.dp))

                if (project == null) {
                    Text("No hay un proyecto activo.")
                } else if (projectLayers.isEmpty()) {
                    Text("Este proyecto no tiene capas cargadas.")
                } else {
                    projectLayers
                        .sortedBy { it.order }
                        .forEach { layer ->
                            Card(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp)
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(layer.name, style = MaterialTheme.typography.titleMedium)
                                        Text(layer.type.label, style = MaterialTheme.typography.bodySmall)
                                    }

                                    Switch(
                                        checked = layer.visible,
                                        onCheckedChange = { checked ->
                                            val updated = projectLayers.map {
                                                if (it.id == layer.id) it.copy(visible = checked) else it
                                            }
                                            projectLayers = updated
                                            project?.let { layerStore.save(it.id, updated) }
                                        }
                                    )
                                }
                            }
                        }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showLayersPanel = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cerrar")
                }

                Spacer(Modifier.height(20.dp))
            }
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
                    onValueChange = { code = it.uppercase() },
                    label = { Text("Código") },
                    supportingText = { Text("Puede escoger uno de la lista o escribir cualquier código manualmente.") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Text("Códigos frecuentes", style = MaterialTheme.typography.titleSmall)

                val defaultCodes = listOf(
                    "CALLE", "CORDÓN", "CUNETA", "CAÑO", "ASFALTO", "LASTRE",
                    "POSTE", "LOTE", "LINDERO", "CERCA", "ACERA", "MURO",
                    "EDIFICIO", "ESQUINA", "EJE", "ALCANTARILLA", "ÁRBOL",
                    "HIDRANTE", "CAJA", "TAPA", "TALUD", "PIE TALUD",
                    "CORONA", "QUEBRADA", "RÍO", "PUENTE", "PORTÓN",
                    "PUNTO CONTROL"
                )

                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    defaultCodes.forEach { item ->
                        AssistChip(
                            onClick = { code = item },
                            label = { Text(item) }
                        )
                    }
                }

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
    val raw = layer.url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val layerName = layer.layerName?.trim()?.takeIf { it.isNotBlank() } ?: return null

    if (raw.contains("{bbox-epsg-3857}", ignoreCase = true)) {
        return raw
    }

    val base = sanitizeWmsBaseUrl(raw)
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


private fun sanitizeWmsBaseUrl(raw: String): String {
    val qIndex = raw.indexOf('?')
    if (qIndex < 0) return raw

    val base = raw.substring(0, qIndex)
    val kept = raw.substring(qIndex + 1)
        .split('&')
        .filter { it.isNotBlank() }
        .filterNot { part ->
            val key = part.substringBefore('=').trim().lowercase()
            key == "request" ||
            key == "service" ||
            key == "version" ||
            key == "layers" ||
            key == "styles" ||
            key == "format" ||
            key == "transparent" ||
            key == "srs" ||
            key == "crs" ||
            key == "bbox" ||
            key == "width" ||
            key == "height"
        }

    return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
}


private enum class MapFieldTool(
    val label: String,
    val instructions: String
) {
    NONE("", ""),
    POINT("Crear punto", "Toque el mapa para crear un punto."),
    LINE("Crear línea", "Toque varios puntos para crear la línea."),
    DISTANCE("Medir distancia", "Toque dos o más puntos. Se mostrará la distancia acumulada."),
    AREA("Medir área", "Toque tres o más puntos para formar el área."),
    PERIMETER("Medir perímetro", "Toque tres o más vértices del polígono."),
    POLYGON("Crear polígono", "Toque tres o más vértices para dibujar el polígono."),
    RECTANGLE("Rectángulo / cuadrado", "Toque dos esquinas opuestas."),
    CIRCLE("Círculo", "Toque el centro y luego un punto del borde."),
    PARALLEL("Línea paralela", "Toque dos puntos de la línea base. Se creará una paralela con la separación indicada.")
}

private fun renderFieldTool(
    map: MapLibreMap,
    tool: MapFieldTool,
    points: List<LatLng>,
    parallelOffsetM: Double
): String? {
    map.clear()
    if (points.isEmpty()) return tool.instructions

    when (tool) {
        MapFieldTool.POINT -> {
            val p = points.last()
            map.addMarker(MarkerOptions().position(p))
            return "Punto: %.7f, %.7f".format(p.latitude, p.longitude)
        }

        MapFieldTool.LINE -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points).width(4f))
                return "Línea: %.2f m".format(polylineDistanceMeters(points))
            }
            return "Marque otro punto para continuar la línea."
        }

        MapFieldTool.DISTANCE -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points).width(4f))
                return "Distancia: %.2f m".format(polylineDistanceMeters(points))
            }
            return "Marque el siguiente punto."
        }

        MapFieldTool.AREA -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points).width(4f))
            }
            if (points.size >= 3) {
                map.addPolygon(PolygonOptions().addAll(points))
                val area = polygonAreaMeters2(points)
                return if (area >= 10000.0) {
                    "Área: %.2f m² (%.4f ha)".format(area, area / 10000.0)
                } else {
                    "Área: %.2f m²".format(area)
                }
            }
            return "Marque al menos 3 puntos."
        }

        MapFieldTool.PERIMETER -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                val closed = if (points.size >= 3) points + points.first() else points
                map.addPolyline(PolylineOptions().addAll(closed).width(4f))
            }
            return if (points.size >= 3) {
                val perimeter = polylineDistanceMeters(points + points.first())
                "Perímetro: %.2f m".format(perimeter)
            } else {
                "Marque al menos 3 vértices."
            }
        }

        MapFieldTool.POLYGON -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 3) {
                map.addPolygon(PolygonOptions().addAll(points))
                val perimeter = polylineDistanceMeters(points + points.first())
                val area = polygonAreaMeters2(points)
                return "Polígono: área %.2f m² • perímetro %.2f m".format(area, perimeter)
            }
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points).width(4f))
            }
            return "Marque al menos 3 vértices."
        }

        MapFieldTool.RECTANGLE -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                val a = points[0]
                val b = points[1]
                val rect = listOf(
                    LatLng(a.latitude, a.longitude),
                    LatLng(a.latitude, b.longitude),
                    LatLng(b.latitude, b.longitude),
                    LatLng(b.latitude, a.longitude)
                )
                map.addPolygon(PolygonOptions().addAll(rect))
                return "Rectángulo: %.2f m²".format(polygonAreaMeters2(rect))
            }
            return "Marque la esquina opuesta."
        }

        MapFieldTool.CIRCLE -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                val center = points[0]
                val radius = haversineMeters(center, points[1])
                val circle = circlePolygon(center, radius, 64)
                map.addPolygon(PolygonOptions().addAll(circle))
                return "Círculo: radio %.2f m • área %.2f m²".format(
                    radius,
                    PI * radius * radius
                )
            }
            return "Marque un punto sobre el borde."
        }

        MapFieldTool.PARALLEL -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                val base = points.take(2)
                map.addPolyline(PolylineOptions().addAll(base).width(4f))
                val parallel = parallelLine(base[0], base[1], parallelOffsetM)
                map.addPolyline(PolylineOptions().addAll(parallel).width(4f))
                return "Paralela: separación %.2f m • longitud %.2f m".format(
                    parallelOffsetM,
                    haversineMeters(parallel[0], parallel[1])
                )
            }
            return "Marque el segundo punto de la línea base."
        }

        MapFieldTool.NONE -> return null
    }
}

private fun polylineDistanceMeters(points: List<LatLng>): Double {
    if (points.size < 2) return 0.0
    return points.zipWithNext().sumOf { (a, b) -> haversineMeters(a, b) }
}

private fun haversineMeters(a: LatLng, b: LatLng): Double {
    val r = 6371008.8
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

private fun polygonAreaMeters2(points: List<LatLng>): Double {
    if (points.size < 3) return 0.0
    val meanLat = Math.toRadians(points.map { it.latitude }.average())
    val r = 6371008.8
    val xy = points.map {
        val x = r * Math.toRadians(it.longitude) * cos(meanLat)
        val y = r * Math.toRadians(it.latitude)
        x to y
    }
    var sum = 0.0
    for (i in xy.indices) {
        val j = (i + 1) % xy.size
        sum += xy[i].first * xy[j].second - xy[j].first * xy[i].second
    }
    return abs(sum) / 2.0
}

private fun parallelLine(a: LatLng, b: LatLng, offsetM: Double): List<LatLng> {
    val meanLat = Math.toRadians((a.latitude + b.latitude) / 2.0)
    val metersPerDegLat = 111132.92
    val metersPerDegLon = 111412.84 * cos(meanLat)

    val dx = (b.longitude - a.longitude) * metersPerDegLon
    val dy = (b.latitude - a.latitude) * metersPerDegLat
    val length = hypot(dx, dy).takeIf { it > 0.0001 } ?: return listOf(a, b)

    val nx = -dy / length
    val ny = dx / length

    val dLon = (nx * offsetM) / metersPerDegLon
    val dLat = (ny * offsetM) / metersPerDegLat

    return listOf(
        LatLng(a.latitude + dLat, a.longitude + dLon),
        LatLng(b.latitude + dLat, b.longitude + dLon)
    )
}

private fun circlePolygon(center: LatLng, radiusM: Double, steps: Int): List<LatLng> {
    val r = 6371008.8
    val lat1 = Math.toRadians(center.latitude)
    val lon1 = Math.toRadians(center.longitude)
    val angular = radiusM / r

    return (0 until steps).map { i ->
        val bearing = 2.0 * PI * i / steps
        val lat2 = asin(
            sin(lat1) * cos(angular) +
                cos(lat1) * sin(angular) * cos(bearing)
        )
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2)
        )
        LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }
}
