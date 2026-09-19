package cr.co.topoemlid

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
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
import org.json.JSONArray
import org.json.JSONObject
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
    fun loadEffectiveLayers(): List<LayerItem> {
        val saved = project?.let { layerStore.load(it.id) }.orEmpty()
        val global = layerStore.loadLibrary()
        if (project == null) return global
        val fromLibrary = global.map { lib ->
            saved.firstOrNull { it.id == lib.id } ?: lib.copy(visible = false)
        }
        val projectOnly = saved.filter { s -> global.none { it.id == s.id } }
        return (fromLibrary + projectOnly).mapIndexed { index, item -> item.copy(order = index) }
    }
    var projectLayers by remember(project?.id) {
        mutableStateOf(loadEffectiveLayers())
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
    var showPointsPanel by remember { mutableStateOf(false) }
    var showPointEditor by remember { mutableStateOf(false) }
    var editingPointId by remember { mutableStateOf<String?>(null) }
    var editPointNumber by remember { mutableStateOf("") }
    var editPointDescription by remember { mutableStateOf("") }
    var editPointCode by remember { mutableStateOf("") }
    var editPointLat by remember { mutableStateOf("") }
    var editPointLon by remember { mutableStateOf("") }
    var editPointHeight by remember { mutableStateOf("") }
    var showToolsPanel by remember { mutableStateOf(false) }
    var showDividePanel by remember { mutableStateOf(false) }
    var showCirclePanel by remember { mutableStateOf(false) }
    var showParallelPanel by remember { mutableStateOf(false) }
    var circleValueText by remember { mutableStateOf("8.00") }
    var circleUseDiameter by remember { mutableStateOf(false) }
    var divideMode by remember { mutableStateOf(DivideMode.EQUAL_AREA) }
    var dividePartsText by remember { mutableStateOf("2") }
    var divideFrontEdge by remember { mutableIntStateOf(0) }
    var divisionPreview by remember(project?.id) { mutableStateOf<List<List<LatLng>>>(emptyList()) }
    var activeMapTool by remember { mutableStateOf(MapFieldTool.NONE) }
    var toolPoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var committedGeometries by remember(project?.id) {
        mutableStateOf(project?.id?.let { loadCommittedGeometries(context, it) } ?: emptyList())
    }
    var selectedGeometryIndex by remember(project?.id) { mutableStateOf<Int?>(null) }
    var editingOriginalGeometry by remember(project?.id) { mutableStateOf<CommittedGeometry?>(null) }
    var toolResult by remember { mutableStateOf<String?>(null) }
    var parallelOffsetText by remember { mutableStateOf("1.00") }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var pointPhoto by remember { mutableStateOf<Uri?>(null) }
    var followReceiver by remember { mutableStateOf(false) }
    var initialAutoZoomDone by remember(project?.id) { mutableStateOf(false) }

    var measuring by remember { mutableStateOf(false) }
    var secondsRemaining by remember { mutableIntStateOf(0) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    var dragOffset by remember { mutableStateOf(Offset(40f, 300f)) }
    var parentSize by remember { mutableStateOf(IntSize.Zero) }
    var buttonSize by remember { mutableStateOf(IntSize.Zero) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pointPhoto = uri
    }

    fun openPointEditor(point: SurveyPoint? = null) {
        editingPointId = point?.id
        editPointNumber = point?.pointNumber ?: nextPointNumber(savedPoints)
        editPointDescription = point?.description.orEmpty()
        editPointCode = point?.code.orEmpty()
        editPointLat = point?.latitude?.let { "%.8f".format(it) }.orEmpty()
        editPointLon = point?.longitude?.let { "%.8f".format(it) }.orEmpty()
        editPointHeight = point?.ellipsoidalHeightM?.let { "%.3f".format(it) }.orEmpty()
        showPointEditor = true
    }

    fun persistGeometries(items: List<CommittedGeometry>) {
        committedGeometries = items
        project?.id?.let { saveCommittedGeometries(context, it, items) }
    }

    fun redrawCommitted(map: MapLibreMap?) {
        val m = map ?: return
        committedGeometries.forEach { g ->
            drawCommittedGeometry(m, g)
        }
    }

    fun showGeometrySelection(index: Int?) {
        val map = mapRef ?: return
        map.clear()
        redrawCommitted(map)
        index?.let { i ->
            committedGeometries.getOrNull(i)?.let { drawSelectionOverlay(map, it) }
        }
    }

    fun redrawActiveAndCommitted() {
        val map = mapRef ?: return
        map.clear()
        committedGeometries.forEach { drawCommittedGeometry(map, it) }
        if (activeMapTool != MapFieldTool.NONE && toolPoints.isNotEmpty()) {
            drawActiveGeometry(map, activeMapTool, toolPoints, parallelOffsetText.toDoubleOrNull() ?: 1.0)
        }
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

    LaunchedEffect(gnss.connected, gnss.latitude, gnss.longitude, mapRef, project?.id) {
        if (!initialAutoZoomDone && gnss.connected) {
            val lat = gnss.latitude
            val lon = gnss.longitude
            val map = mapRef
            if (lat != null && lon != null && map != null) {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 18.0)
                )
                initialAutoZoomDone = true
            }
        }
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
                latitude = gnss.latitude,
                longitude = gnss.longitude,
                ellipsoidalHeightM = gnss.ellipsoidalHeightM,
                verticalAccuracyM = gnss.verticalAccuracyM,
                horizontalAccuracyM = gnss.horizontalAccuracyM,
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
                                redrawCommitted(map)
                            }

                            map.addOnMapClickListener { latLng ->
                                val hitIndex = findGeometryAt(latLng, committedGeometries)

                                if (activeMapTool == MapFieldTool.NONE || activeMapTool == MapFieldTool.SELECT) {
                                    if (hitIndex != null) {
                                        selectedGeometryIndex = hitIndex
                                        activeMapTool = MapFieldTool.NONE
                                        toolResult = "Figura seleccionada. Use las opciones para medir, editar o eliminar."
                                        showGeometrySelection(hitIndex)
                                        true
                                    } else {
                                        selectedGeometryIndex = null
                                        if (activeMapTool == MapFieldTool.SELECT) {
                                            toolResult = "No se encontró una figura en ese punto. Toque directamente sobre la línea o borde."
                                        }
                                        false
                                    }
                                } else if (activeMapTool == MapFieldTool.CIRCLE) {
                                    selectedGeometryIndex = null
                                    toolPoints = listOf(latLng)
                                    showCirclePanel = true
                                    toolResult = "Centro del círculo seleccionado. Indique radio o diámetro."
                                    true
                                } else if (activeMapTool == MapFieldTool.PARALLEL && hitIndex != null) {
                                    val geometry = committedGeometries[hitIndex]
                                    if (geometrySupportsParallel(geometry)) {
                                        selectedGeometryIndex = hitIndex
                                        toolPoints = geometryPath(geometry)
                                        showGeometrySelection(hitIndex)
                                        showParallelPanel = true
                                        toolResult = "Línea base seleccionada. Indique la separación de la paralela."
                                        true
                                    } else {
                                        toolResult = "Para crear una paralela seleccione una línea."
                                        false
                                    }
                                } else if (
                                    hitIndex != null &&
                                    (activeMapTool == MapFieldTool.AREA ||
                                     activeMapTool == MapFieldTool.DISTANCE ||
                                     activeMapTool == MapFieldTool.PERIMETER ||
                                     activeMapTool == MapFieldTool.DIVIDE)
                                ) {
                                    val geometry = committedGeometries[hitIndex]
                                    val acceptsSelection = when (activeMapTool) {
                                        MapFieldTool.AREA -> geometrySupportsArea(geometry)
                                        MapFieldTool.DISTANCE -> geometrySupportsDistance(geometry)
                                        MapFieldTool.PERIMETER -> geometrySupportsArea(geometry)
                                        MapFieldTool.DIVIDE -> geometrySupportsArea(geometry)
                                        else -> false
                                    }
                                    if (acceptsSelection) {
                                        selectedGeometryIndex = hitIndex
                                        showGeometrySelection(hitIndex)
                                        if (activeMapTool == MapFieldTool.DIVIDE) {
                                            toolPoints = geometryPath(geometry)
                                            activeMapTool = MapFieldTool.NONE
                                            toolResult = "Polígono seleccionado para dividir: " + areaText(geometry)
                                            showDividePanel = true
                                        } else {
                                            toolResult = when (activeMapTool) {
                                                MapFieldTool.AREA -> areaText(geometry)
                                                MapFieldTool.DISTANCE -> distanceText(geometry)
                                                MapFieldTool.PERIMETER -> perimeterText(geometry)
                                                else -> null
                                            }
                                            activeMapTool = MapFieldTool.NONE
                                        }
                                        true
                                    } else {
                                        toolResult = "La figura seleccionada no sirve para esta medición."
                                        false
                                    }
                                } else {
                                    selectedGeometryIndex = null
                                    val updated = when (activeMapTool) {
                                        MapFieldTool.POINT -> listOf(latLng)
                                        MapFieldTool.RECTANGLE ->
                                            if (toolPoints.size >= 2) listOf(latLng) else toolPoints + latLng
                                        else -> toolPoints + latLng
                                    }

                                    toolPoints = updated
                                    toolResult = renderFieldTool(map, activeMapTool, updated, parallelOffsetText.toDoubleOrNull() ?: 1.0)
                                    committedGeometries.forEach { drawCommittedGeometry(map, it) }
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
                onClick = {
                    projectLayers = loadEffectiveLayers()
                    showLayersPanel = true
                }
            ) { Text("▱") }
        }

        SmallFloatingActionButton(
            onClick = { showToolsPanel = true },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp),
            shape = CircleShape
        ) {
            Text("🛠")
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
            selectedGeometryIndex?.let { index ->
                committedGeometries.getOrNull(index)?.let { geometry ->
                    Surface(tonalElevation = 5.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "Seleccionado: ${geometry.tool.label}",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    if (geometry.tool == MapFieldTool.CIRCLE && geometry.points.size >= 2) {
                                        val r = haversineMeters(geometry.points[0], geometry.points[1])
                                        Text(
                                            "Radio: %.2f m • Diámetro: %.2f m".format(r, r * 2.0),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                TextButton(onClick = { selectedGeometryIndex = null }) { Text("Cerrar") }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (geometrySupportsDistance(geometry)) {
                                    OutlinedButton(
                                        onClick = { toolResult = distanceText(geometry) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) { Text(if (geometrySupportsArea(geometry)) "Perímetro" else "Distancia") }
                                }
                                if (geometrySupportsArea(geometry)) {
                                    OutlinedButton(
                                        onClick = { toolResult = areaText(geometry) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) { Text("Área") }
                                }
                                if (geometrySupportsParallel(geometry)) {
                                    OutlinedButton(
                                        onClick = {
                                            toolPoints = geometryPath(geometry)
                                            activeMapTool = MapFieldTool.PARALLEL
                                            showParallelPanel = true
                                            toolResult = "Indique la separación de la línea paralela."
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) { Text("Paralela") }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        editingOriginalGeometry = geometry
                                        val without = committedGeometries.filterIndexed { i, _ -> i != index }
                                        persistGeometries(without)
                                        selectedGeometryIndex = null
                                        activeMapTool = geometry.tool
                                        toolPoints = geometry.points
                                        parallelOffsetText = geometry.parallelOffsetM.toString()
                                        toolResult = "Editando ${geometry.tool.label}. Puede agregar puntos, usar ↶ para quitar el último, Cancelar o Listo."
                                        mapRef?.clear()
                                        redrawCommitted(mapRef)
                                        mapRef?.let {
                                            drawActiveGeometry(it, activeMapTool, toolPoints, geometry.parallelOffsetM)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Editar") }
                                Button(
                                    onClick = {
                                        persistGeometries(committedGeometries.filterIndexed { i, _ -> i != index })
                                        selectedGeometryIndex = null
                                        toolResult = "Elemento eliminado."
                                        mapRef?.clear()
                                        redrawCommitted(mapRef)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Eliminar") }
                            }
                        }
                    }
                }
            }

            toolResult?.let { result ->
                Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        if (activeMapTool != MapFieldTool.NONE) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${activeMapTool.symbol} ${activeMapTool.label}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            if (toolPoints.isNotEmpty()) {
                                                toolPoints = toolPoints.dropLast(1)
                                                toolResult = if (toolPoints.isEmpty()) {
                                                    activeMapTool.instructions
                                                } else {
                                                    renderFieldTool(
                                                        mapRef ?: return@OutlinedButton,
                                                        activeMapTool,
                                                        toolPoints,
                                                        parallelOffsetText.toDoubleOrNull() ?: 1.0
                                                    )
                                                }
                                                committedGeometries.forEach { g -> mapRef?.let { drawCommittedGeometry(it, g) } }
                                            }
                                        },
                                        enabled = toolPoints.isNotEmpty(),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) { Text("↶") }
                                    OutlinedButton(
                                        onClick = {
                                            editingOriginalGeometry?.let { original ->
                                                persistGeometries(committedGeometries + original)
                                            }
                                            editingOriginalGeometry = null
                                            toolPoints = emptyList()
                                            toolResult = null
                                            activeMapTool = MapFieldTool.NONE
                                            mapRef?.clear()
                                            redrawCommitted(mapRef)
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) { Text("Cancelar") }
                                    Button(
                                        onClick = {
                                            if (activeMapTool == MapFieldTool.DIVIDE) {
                                                if (toolPoints.size >= 3) {
                                                    activeMapTool = MapFieldTool.NONE
                                                    toolResult = "Polígono fijado para dividir: %.2f m²".format(polygonAreaMeters2(toolPoints))
                                                    showDividePanel = true
                                                } else {
                                                    toolResult = "Para dividir se necesita un polígono con al menos 3 vértices."
                                                }
                                            } else if (toolPoints.isNotEmpty()) {
                                                val savedGeometry = CommittedGeometry(
                                                    id = editingOriginalGeometry?.id ?: UUID.randomUUID().toString(),
                                                    tool = activeMapTool,
                                                    points = toolPoints,
                                                    parallelOffsetM = parallelOffsetText.toDoubleOrNull() ?: 1.0
                                                )
                                                persistGeometries(committedGeometries + savedGeometry)
                                                editingOriginalGeometry = null
                                                toolPoints = emptyList()
                                                toolResult = if (project != null) {
                                                    "Elemento guardado en el proyecto."
                                                } else {
                                                    "Elemento guardado temporalmente en el mapa."
                                                }
                                                activeMapTool = MapFieldTool.NONE
                                                mapRef?.clear()
                                                redrawCommitted(mapRef)
                                            }
                                        },
                                        enabled = toolPoints.isNotEmpty(),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) { Text("Listo") }
                                }
                            }
                        }
                        Text(result, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (divisionPreview.isNotEmpty()) {
                Surface(tonalElevation = 5.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "División en vista previa: ${divisionPreview.size} lotes",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "Puede guardarla en el proyecto o crear puntos topográficos en todos los vértices.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = {
                                    val newGeometries = divisionPreview.map { piece ->
                                        CommittedGeometry(
                                            tool = MapFieldTool.POLYGON,
                                            points = piece,
                                            parallelOffsetM = 0.0
                                        )
                                    }
                                    persistGeometries(committedGeometries + newGeometries)
                                    divisionPreview = emptyList()
                                    toolPoints = emptyList()
                                    selectedGeometryIndex = null
                                    toolResult = "División guardada en el proyecto."
                                    mapRef?.clear()
                                    redrawCommitted(mapRef)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Guardar división") }

                            OutlinedButton(
                                onClick = {
                                    val p = project
                                    if (p == null) {
                                        toolResult = "Abra o cree un proyecto para guardar puntos."
                                    } else {
                                        val vertices = uniqueDivisionVertices(divisionPreview)
                                        var next = nextPointNumber(savedPoints).toIntOrNull() ?: (savedPoints.size + 1)
                                        val newPoints = vertices.map { vertex ->
                                            SurveyPoint(
                                                id = UUID.randomUUID().toString(),
                                                projectId = p.id,
                                                pointNumber = (next++).toString(),
                                                description = "Vértice de división",
                                                code = "DIV",
                                                antennaHeightM = 0.0,
                                                occupationSeconds = 0,
                                                latitude = vertex.latitude,
                                                longitude = vertex.longitude,
                                                ellipsoidalHeightM = null,
                                                horizontalAccuracyM = null,
                                                verticalAccuracyM = null,
                                                solution = "CALCULADO",
                                                satellites = null
                                            )
                                        }
                                        val updated = savedPoints + newPoints
                                        savedPoints = updated
                                        pointStore.save(p.id, updated)
                                        pointNumber = nextPointNumber(updated)
                                        toolResult = "${newPoints.size} puntos de vértice creados y guardados en el proyecto."
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Crear puntos") }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                divisionPreview = emptyList()
                                mapRef?.clear()
                                redrawCommitted(mapRef)
                                toolResult = "Vista previa de división descartada."
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("Descartar vista previa") }
                    }
                }
            }

            lastMessage?.let { message ->
                Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Text(message, modifier = Modifier.padding(10.dp))
                }
            }

            Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = pointNumber,
                            onValueChange = { pointNumber = it },
                            label = { Text("Pto") },
                            singleLine = true,
                            modifier = Modifier.weight(0.75f)
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.uppercase() },
                            label = { Text("Código") },
                            singleLine = true,
                            modifier = Modifier.weight(1.35f)
                        )
                        OutlinedTextField(
                            value = antennaHeight,
                            onValueChange = { antennaHeight = it },
                            label = { Text("Alt. m") },
                            singleLine = true,
                            modifier = Modifier.weight(0.9f)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "H ${gnss.horizontalAccuracyM?.let { "%.3f".format(it) } ?: "—"} • V ${gnss.verticalAccuracyM?.let { "%.3f".format(it) } ?: "—"} • ${if (gnss.connected) gnss.solution else "SIN RECEPTOR"}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(
                                onClick = { showPointsPanel = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) { Text("Puntos", style = MaterialTheme.typography.labelSmall) }
                            TextButton(
                                onClick = { showConfigPanel = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) { Text("Más", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }

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
                Text("Herramientas", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Seleccione una herramienta. Esta ventana se cerrará automáticamente y podrá trabajar directamente sobre el mapa.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(12.dp))

                val tools = listOf(
                    MapFieldTool.SELECT,
                    MapFieldTool.POINT,
                    MapFieldTool.LINE,
                    MapFieldTool.DISTANCE,
                    MapFieldTool.AREA,
                    MapFieldTool.PERIMETER,
                    MapFieldTool.POLYGON,
                    MapFieldTool.DIVIDE,
                    MapFieldTool.RECTANGLE,
                    MapFieldTool.CIRCLE,
                    MapFieldTool.PARALLEL
                )

                Spacer(Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = 2,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tools.forEach { tool ->
                        OutlinedCard(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 82.dp)
                                .clickable {
                                    if (tool == MapFieldTool.SELECT) {
                                        selectedGeometryIndex = null
                                        activeMapTool = MapFieldTool.SELECT
                                        toolPoints = emptyList()
                                        toolResult = "Toque una línea, polígono, rectángulo o círculo guardado para seleccionarlo."
                                        mapRef?.clear()
                                        redrawCommitted(mapRef)
                                        showToolsPanel = false
                                    } else if (tool == MapFieldTool.PARALLEL) {
                                        val selected = selectedGeometryIndex?.let { committedGeometries.getOrNull(it) }
                                        activeMapTool = MapFieldTool.PARALLEL
                                        if (selected != null && geometrySupportsParallel(selected)) {
                                            toolPoints = geometryPath(selected)
                                            showToolsPanel = false
                                            showParallelPanel = true
                                            toolResult = "Línea base seleccionada. Indique la separación."
                                        } else {
                                            toolPoints = emptyList()
                                            toolResult = "Toque la línea guardada a la que desea crearle una paralela."
                                            mapRef?.clear()
                                            redrawCommitted(mapRef)
                                            showToolsPanel = false
                                        }
                                    } else if (tool == MapFieldTool.DIVIDE) {
                                        editingOriginalGeometry?.let { original ->
                                            persistGeometries(committedGeometries + original)
                                        }
                                        editingOriginalGeometry = null
                                        selectedGeometryIndex = null
                                        activeMapTool = MapFieldTool.DIVIDE
                                        toolPoints = emptyList()
                                        toolResult = "Seleccione un polígono existente o marque sus vértices. Para dividir se necesita un polígono cerrado. Cuando termine de marcarlo, pulse Listo."
                                        mapRef?.clear()
                                        redrawCommitted(mapRef)
                                        showToolsPanel = false
                                    } else {
                                        editingOriginalGeometry?.let { original ->
                                            persistGeometries(committedGeometries + original)
                                        }
                                        editingOriginalGeometry = null
                                        selectedGeometryIndex = null
                                        activeMapTool = tool
                                        toolPoints = emptyList()
                                        toolResult = if (
                                            tool == MapFieldTool.AREA ||
                                            tool == MapFieldTool.DISTANCE ||
                                            tool == MapFieldTool.PERIMETER
                                        ) {
                                            tool.instructions + " También puede tocar una figura ya dibujada."
                                        } else tool.instructions
                                        mapRef?.clear()
                                        redrawCommitted(mapRef)
                                        showToolsPanel = false
                                    }
                                }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(tool.symbol, style = MaterialTheme.typography.headlineMedium)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    tool.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))



                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showCirclePanel) {
        ModalBottomSheet(onDismissRequest = {
            showCirclePanel = false
            activeMapTool = MapFieldTool.NONE
            toolPoints = emptyList()
        }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Crear círculo", style = MaterialTheme.typography.headlineSmall)
                Text("Centro marcado. Indique el tamaño exacto.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !circleUseDiameter, onClick = { circleUseDiameter = false })
                    Text("Radio")
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = circleUseDiameter, onClick = { circleUseDiameter = true })
                    Text("Diámetro")
                }
                OutlinedTextField(
                    value = circleValueText,
                    onValueChange = { circleValueText = it },
                    label = { Text(if (circleUseDiameter) "Diámetro (m)" else "Radio (m)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val center = toolPoints.firstOrNull()
                        val value = circleValueText.replace(',', '.').toDoubleOrNull()
                        if (center == null || value == null || value <= 0.0) {
                            toolResult = "Indique un valor válido mayor que cero."
                        } else {
                            val radius = if (circleUseDiameter) value / 2.0 else value
                            val edge = destinationPoint(center, radius, 90.0)
                            val geometry = CommittedGeometry(
                                tool = MapFieldTool.CIRCLE,
                                points = listOf(center, edge),
                                parallelOffsetM = 0.0
                            )
                            persistGeometries(committedGeometries + geometry)
                            showCirclePanel = false
                            activeMapTool = MapFieldTool.NONE
                            toolPoints = emptyList()
                            toolResult = "Círculo guardado • radio %.2f m • diámetro %.2f m".format(radius, radius * 2.0)
                            mapRef?.clear()
                            redrawCommitted(mapRef)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Crear y guardar círculo") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        showCirclePanel = false
                        activeMapTool = MapFieldTool.NONE
                        toolPoints = emptyList()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancelar") }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showParallelPanel) {
        ModalBottomSheet(onDismissRequest = { showParallelPanel = false }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Crear línea paralela", style = MaterialTheme.typography.headlineSmall)
                Text("La línea base ya está seleccionada.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = parallelOffsetText,
                    onValueChange = { parallelOffsetText = it },
                    label = { Text("Separación (m)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val distance = parallelOffsetText.replace(',', '.').toDoubleOrNull()
                        if (toolPoints.size < 2 || distance == null || distance == 0.0) {
                            toolResult = "Seleccione una línea y escriba una separación válida."
                        } else {
                            val parallel = offsetPolyline(toolPoints, distance)
                            val geometry = CommittedGeometry(
                                tool = MapFieldTool.LINE,
                                points = parallel,
                                parallelOffsetM = distance
                            )
                            persistGeometries(committedGeometries + geometry)
                            showParallelPanel = false
                            activeMapTool = MapFieldTool.NONE
                            toolPoints = emptyList()
                            selectedGeometryIndex = null
                            toolResult = "Línea paralela guardada a %.2f m.".format(abs(distance))
                            mapRef?.clear()
                            redrawCommitted(mapRef)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Crear paralela") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        showParallelPanel = false
                        activeMapTool = MapFieldTool.NONE
                        toolPoints = emptyList()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancelar") }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showDividePanel) {
        ModalBottomSheet(onDismissRequest = { showDividePanel = false }) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                Text("Dividir polígono", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (toolPoints.size >= 3)
                        "Polígono actual: " + "%.2f".format(polygonAreaMeters2(toolPoints)) + " m²"
                    else
                        "Primero dibuje un polígono con al menos 3 vértices.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                DivideMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = divideMode == mode, onClick = { divideMode = mode })
                        Column {
                            Text(mode.label)
                            Text(mode.help, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (divideMode == DivideMode.EQUAL_AREA || divideMode == DivideMode.EQUAL_FRONT) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dividePartsText,
                        onValueChange = { dividePartsText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Cantidad de lotes") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (divideMode == DivideMode.EQUAL_FRONT && toolPoints.size >= 3) {
                    Spacer(Modifier.height(8.dp))
                    Text("Seleccione el lado que será el frente", fontWeight = FontWeight.Bold)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        toolPoints.indices.forEach { i ->
                            val j = (i + 1) % toolPoints.size
                            AssistChip(
                                onClick = { divideFrontEdge = i },
                                label = { Text("Lado " + (i + 1) + ": " + "%.1f".format(haversineMeters(toolPoints[i], toolPoints[j])) + " m") }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val map = mapRef
                        if (toolPoints.size < 3 || map == null) {
                            toolResult = "Primero cree un polígono y luego vuelva a Dividir."
                        } else {
                            val parts = dividePartsText.toIntOrNull()?.coerceIn(2, 20) ?: 2
                            val result = when (divideMode) {
                                DivideMode.EQUAL_AREA -> dividePolygonEqualArea(toolPoints, parts)
                                DivideMode.EQUAL_FRONT -> dividePolygonEqualFront(toolPoints, parts, divideFrontEdge)
                                DivideMode.BY_LINE -> emptyList()
                            }
                            if (divideMode == DivideMode.BY_LINE) {
                                activeMapTool = MapFieldTool.DIVIDE_LINE
                                toolResult = "Trace dos puntos sobre el mapa para definir la línea de corte."
                            } else {
                                divisionPreview = result
                                map.clear()
                                redrawCommitted(map)
                                renderDivisionPolygons(map, result)
                                toolResult = divisionSummary(divideMode.label, result) + " • Use Guardar división para conservarla."
                            }
                        }
                        showDividePanel = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (divideMode == DivideMode.BY_LINE) "Trazar línea de división" else "Vista previa de división")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showDividePanel = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancelar")
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showPointsPanel) {
        ModalBottomSheet(onDismissRequest = { showPointsPanel = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Puntos del proyecto", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${savedPoints.size} puntos guardados",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = {
                            if (project == null) {
                                toolResult = "Abra o cree un proyecto para crear puntos."
                            } else {
                                openPointEditor(null)
                            }
                        }
                    ) { Text("+ Punto") }
                }

                Spacer(Modifier.height(10.dp))

                if (project == null) {
                    Text("No hay un proyecto activo.")
                } else if (savedPoints.isEmpty()) {
                    Text("Todavía no hay puntos guardados en este proyecto.")
                } else {
                    savedPoints
                        .sortedWith(compareBy<SurveyPoint> { it.pointNumber.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.pointNumber })
                        .forEach { p ->
                            OutlinedCard(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Punto ${p.pointNumber}", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "${p.code.ifBlank { "SIN CÓDIGO" }} • ${p.solution}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            if (p.description.isNotBlank()) {
                                                Text(p.description, style = MaterialTheme.typography.bodySmall)
                                            }
                                            Text(
                                                "Lat: ${p.latitude?.let { "%.8f".format(it) } ?: "—"}  Lon: ${p.longitude?.let { "%.8f".format(it) } ?: "—"}",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                            p.ellipsoidalHeightM?.let {
                                                Text("H elipsoidal: %.3f m".format(it), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                if (p.latitude != null && p.longitude != null) {
                                                    mapRef?.animateCamera(
                                                        CameraUpdateFactory.newLatLngZoom(
                                                            LatLng(p.latitude, p.longitude),
                                                            19.0
                                                        )
                                                    )
                                                    showPointsPanel = false
                                                } else {
                                                    toolResult = "Este punto no tiene coordenadas."
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Ver") }
                                        OutlinedButton(
                                            onClick = { openPointEditor(p) },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Editar") }
                                        Button(
                                            onClick = {
                                                val updated = savedPoints.filterNot { it.id == p.id }
                                                savedPoints = updated
                                                pointStore.save(project.id, updated)
                                                pointNumber = nextPointNumber(updated)
                                                toolResult = "Punto ${p.pointNumber} eliminado."
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Borrar") }
                                    }
                                }
                            }
                        }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showPointsPanel = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cerrar") }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showPointEditor) {
        ModalBottomSheet(onDismissRequest = { showPointEditor = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (editingPointId == null) "Crear punto por coordenadas" else "Editar punto",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "Los puntos creados o modificados aquí se identifican como EDITADO.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = editPointNumber,
                    onValueChange = { editPointNumber = it },
                    label = { Text("Número / nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = editPointDescription,
                    onValueChange = { editPointDescription = it },
                    label = { Text("Descripción") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = editPointCode,
                    onValueChange = { editPointCode = it.uppercase() },
                    label = { Text("Código") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = editPointLat,
                        onValueChange = { editPointLat = it },
                        label = { Text("Latitud") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = editPointLon,
                        onValueChange = { editPointLon = it },
                        label = { Text("Longitud") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = editPointHeight,
                    onValueChange = { editPointHeight = it },
                    label = { Text("Altura elipsoidal (m), opcional") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val p = project
                        val lat = editPointLat.replace(',', '.').toDoubleOrNull()
                        val lon = editPointLon.replace(',', '.').toDoubleOrNull()
                        val h = editPointHeight.replace(',', '.').toDoubleOrNull()
                        if (p == null) {
                            toolResult = "Abra o cree un proyecto primero."
                        } else if (editPointNumber.isBlank()) {
                            toolResult = "Indique el número o nombre del punto."
                        } else if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                            toolResult = "Revise latitud y longitud."
                        } else {
                            val existing = editingPointId?.let { id -> savedPoints.firstOrNull { it.id == id } }
                            val edited = SurveyPoint(
                                id = existing?.id ?: UUID.randomUUID().toString(),
                                projectId = p.id,
                                pointNumber = editPointNumber.trim(),
                                description = editPointDescription.trim(),
                                code = editPointCode.trim(),
                                antennaHeightM = existing?.antennaHeightM ?: 0.0,
                                occupationSeconds = existing?.occupationSeconds ?: 0,
                                latitude = lat,
                                longitude = lon,
                                ellipsoidalHeightM = h,
                                horizontalAccuracyM = existing?.horizontalAccuracyM,
                                verticalAccuracyM = existing?.verticalAccuracyM,
                                solution = "EDITADO",
                                satellites = existing?.satellites,
                                createdAt = existing?.createdAt ?: System.currentTimeMillis()
                            )
                            val updated = if (existing == null) {
                                savedPoints + edited
                            } else {
                                savedPoints.map { if (it.id == existing.id) edited else it }
                            }
                            savedPoints = updated
                            pointStore.save(p.id, updated)
                            pointNumber = nextPointNumber(updated)
                            showPointEditor = false
                            toolResult = "Punto ${edited.pointNumber} guardado como EDITADO."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Guardar punto") }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showPointEditor = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancelar") }
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
                    "Active o desactive las capas globales y las del proyecto sin salir del Levantamiento.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(12.dp))

                if (project == null) {
                    Text("No hay un proyecto activo.")
                } else if (projectLayers.isEmpty()) {
                    Text("No hay capas guardadas en la biblioteca global ni en este proyecto.")
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


private data class CommittedGeometry(
    val id: String = UUID.randomUUID().toString(),
    val tool: MapFieldTool,
    val points: List<LatLng>,
    val parallelOffsetM: Double
)

private fun drawCommittedGeometry(map: MapLibreMap, geometry: CommittedGeometry) {
    drawActiveGeometry(map, geometry.tool, geometry.points, geometry.parallelOffsetM)
}

private fun drawActiveGeometry(
    map: MapLibreMap,
    tool: MapFieldTool,
    points: List<LatLng>,
    parallelOffsetM: Double
) {
    when (tool) {
        MapFieldTool.POINT -> points.lastOrNull()?.let { map.addMarker(MarkerOptions().position(it)) }
        MapFieldTool.LINE, MapFieldTool.DISTANCE -> if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(4f))
        MapFieldTool.AREA, MapFieldTool.PERIMETER, MapFieldTool.POLYGON -> {
            if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(4f))
            if (points.size >= 3 && tool != MapFieldTool.LINE) drawTransparentPolygon(map, points)
        }
        MapFieldTool.RECTANGLE -> if (points.size >= 2) {
            val a = points[0]; val b = points[1]
            val rect = listOf(
                LatLng(a.latitude, a.longitude),
                LatLng(a.latitude, b.longitude),
                LatLng(b.latitude, b.longitude),
                LatLng(b.latitude, a.longitude)
            )
            drawTransparentPolygon(map, rect)
        }
        MapFieldTool.CIRCLE -> if (points.size >= 2) {
            val circle = circlePolygon(points[0], haversineMeters(points[0], points[1]), 64)
            drawTransparentPolygon(map, circle)
        }
        MapFieldTool.PARALLEL -> if (points.size >= 2) {
            val base = points.take(2)
            map.addPolyline(PolylineOptions().addAll(base).width(4f))
            map.addPolyline(PolylineOptions().addAll(parallelLine(base[0], base[1], parallelOffsetM)).width(4f))
        }
        MapFieldTool.DIVIDE_LINE -> if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points.take(2)).width(4f))
        MapFieldTool.DIVIDE -> {
            if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(4f))
            if (points.size >= 3) drawTransparentPolygon(map, points)
        }
        MapFieldTool.SELECT, MapFieldTool.NONE -> Unit
    }
}

private fun drawTransparentPolygon(map: MapLibreMap, points: List<LatLng>) {
    if (points.size < 3) return
    // Intentionally no Polygon annotation: only the closed border is drawn,
    // so the basemap and raster layers remain fully visible inside the figure.
    map.addPolyline(
        PolylineOptions()
            .addAll(points + points.first())
            .width(4f)
            .color(android.graphics.Color.rgb(103, 58, 183))
    )
}

private fun drawSelectionOverlay(map: MapLibreMap, geometry: CommittedGeometry) {
    val path = geometryPath(geometry)
    if (path.isEmpty()) return
    val drawPath = if (geometrySupportsArea(geometry) && path.size >= 3) path + path.first() else path
    if (drawPath.size >= 2) {
        map.addPolyline(
            PolylineOptions()
                .addAll(drawPath)
                .width(8f)
                .color(android.graphics.Color.rgb(255, 193, 7))
        )
    } else {
        map.addMarker(MarkerOptions().position(path.first()))
    }
}

private fun geometrySupportsParallel(geometry: CommittedGeometry): Boolean =
    geometry.tool == MapFieldTool.LINE || geometry.tool == MapFieldTool.DISTANCE

private fun destinationPoint(start: LatLng, distanceM: Double, bearingDeg: Double): LatLng {
    val r = 6371008.8
    val brng = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(start.latitude)
    val lon1 = Math.toRadians(start.longitude)
    val dr = distanceM / r
    val lat2 = asin(sin(lat1) * cos(dr) + cos(lat1) * sin(dr) * cos(brng))
    val lon2 = lon1 + atan2(
        sin(brng) * sin(dr) * cos(lat1),
        cos(dr) - sin(lat1) * sin(lat2)
    )
    return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

private fun offsetPolyline(points: List<LatLng>, offsetM: Double): List<LatLng> {
    if (points.size < 2) return points
    val meanLat = Math.toRadians(points.map { it.latitude }.average())
    val r = 6371008.8
    val originLat = points.first().latitude
    val originLon = points.first().longitude
    fun toLocal(p: LatLng): XY = XY(
        r * Math.toRadians(p.longitude - originLon) * cos(meanLat),
        r * Math.toRadians(p.latitude - originLat)
    )
    fun toLatLng(p: XY): LatLng = LatLng(
        originLat + Math.toDegrees(p.y / r),
        originLon + Math.toDegrees(p.x / (r * cos(meanLat)))
    )
    val xy = points.map(::toLocal)
    val out = mutableListOf<XY>()
    for (i in xy.indices) {
        val prev = xy[(i - 1).coerceAtLeast(0)]
        val next = xy[(i + 1).coerceAtMost(xy.lastIndex)]
        val dx = next.x - prev.x
        val dy = next.y - prev.y
        val len = hypot(dx, dy).takeIf { it > 1e-9 } ?: 1.0
        val nx = -dy / len
        val ny = dx / len
        out += XY(xy[i].x + nx * offsetM, xy[i].y + ny * offsetM)
    }
    return out.map(::toLatLng)
}

private fun geometryPath(geometry: CommittedGeometry): List<LatLng> = when (geometry.tool) {
    MapFieldTool.RECTANGLE -> if (geometry.points.size >= 2) {
        val a = geometry.points[0]
        val b = geometry.points[1]
        listOf(
            LatLng(a.latitude, a.longitude),
            LatLng(a.latitude, b.longitude),
            LatLng(b.latitude, b.longitude),
            LatLng(b.latitude, a.longitude)
        )
    } else geometry.points
    MapFieldTool.CIRCLE -> if (geometry.points.size >= 2) {
        circlePolygon(
            geometry.points[0],
            haversineMeters(geometry.points[0], geometry.points[1]),
            64
        )
    } else geometry.points
    else -> geometry.points
}

private fun geometrySupportsArea(geometry: CommittedGeometry): Boolean =
    geometry.tool == MapFieldTool.AREA ||
    geometry.tool == MapFieldTool.POLYGON ||
    geometry.tool == MapFieldTool.RECTANGLE ||
    geometry.tool == MapFieldTool.CIRCLE

private fun geometrySupportsDistance(geometry: CommittedGeometry): Boolean =
    geometry.tool != MapFieldTool.POINT &&
    geometry.tool != MapFieldTool.DIVIDE &&
    geometry.tool != MapFieldTool.DIVIDE_LINE

private fun areaText(geometry: CommittedGeometry): String {
    val area = polygonAreaMeters2(geometryPath(geometry))
    return if (area >= 10000.0) {
        "Área: %.2f m² (%.4f ha)".format(area, area / 10000.0)
    } else {
        "Área: %.2f m²".format(area)
    }
}

private fun perimeterText(geometry: CommittedGeometry): String {
    val path = geometryPath(geometry)
    if (path.size < 2) return "Perímetro no disponible."
    return "Perímetro: %.2f m".format(polylineDistanceMeters(path + path.first()))
}

private fun distanceText(geometry: CommittedGeometry): String {
    return if (geometrySupportsArea(geometry)) {
        perimeterText(geometry)
    } else {
        "Distancia: %.2f m".format(polylineDistanceMeters(geometryPath(geometry)))
    }
}

private fun findGeometryAt(point: LatLng, geometries: List<CommittedGeometry>): Int? {
    for (index in geometries.indices.reversed()) {
        val geometry = geometries[index]
        val path = geometryPath(geometry)
        if (path.isEmpty()) continue
        if (geometrySupportsArea(geometry) && path.size >= 3 && pointInPolygon(point, path)) {
            return index
        }
        val closed = if (geometrySupportsArea(geometry) && path.size >= 3) path + path.first() else path
        if (distanceToPathMeters(point, closed) <= 10.0) return index
    }
    return null
}

private fun pointInPolygon(point: LatLng, polygon: List<LatLng>): Boolean {
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val xi = polygon[i].longitude
        val yi = polygon[i].latitude
        val xj = polygon[j].longitude
        val yj = polygon[j].latitude
        val intersects = ((yi > point.latitude) != (yj > point.latitude)) &&
            (point.longitude < (xj - xi) * (point.latitude - yi) / ((yj - yi).takeIf { abs(it) > 1e-12 } ?: 1e-12) + xi)
        if (intersects) inside = !inside
        j = i
    }
    return inside
}

private fun distanceToPathMeters(point: LatLng, path: List<LatLng>): Double {
    if (path.isEmpty()) return Double.POSITIVE_INFINITY
    if (path.size == 1) return haversineMeters(point, path[0])
    val meanLat = Math.toRadians(point.latitude)
    val r = 6371008.8
    fun xy(p: LatLng): XY = XY(
        r * Math.toRadians(p.longitude - point.longitude) * cos(meanLat),
        r * Math.toRadians(p.latitude - point.latitude)
    )
    var best = Double.POSITIVE_INFINITY
    for (i in 0 until path.lastIndex) {
        val a = xy(path[i])
        val b = xy(path[i + 1])
        val dx = b.x - a.x
        val dy = b.y - a.y
        val denom = dx * dx + dy * dy
        val t = if (denom <= 1e-12) 0.0 else (-(a.x * dx + a.y * dy) / denom).coerceIn(0.0, 1.0)
        val px = a.x + t * dx
        val py = a.y + t * dy
        best = min(best, hypot(px, py))
    }
    return best
}

private fun loadCommittedGeometries(context: android.content.Context, projectId: String): List<CommittedGeometry> {
    val raw = context.getSharedPreferences("survey_geometries", android.content.Context.MODE_PRIVATE)
        .getString("geometries_$projectId", null) ?: return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val tool = runCatching { MapFieldTool.valueOf(o.getString("tool")) }.getOrNull()
                ?: return@mapNotNull null
            val pts = o.getJSONArray("points")
            val points = (0 until pts.length()).map { j ->
                val p = pts.getJSONObject(j)
                LatLng(p.getDouble("lat"), p.getDouble("lon"))
            }
            CommittedGeometry(
                id = o.optString("id", UUID.randomUUID().toString()),
                tool = tool,
                points = points,
                parallelOffsetM = o.optDouble("parallelOffsetM", 1.0)
            )
        }
    }.getOrDefault(emptyList())
}

private fun saveCommittedGeometries(
    context: android.content.Context,
    projectId: String,
    geometries: List<CommittedGeometry>
) {
    val array = JSONArray()
    geometries.forEach { geometry ->
        array.put(JSONObject().apply {
            put("id", geometry.id)
            put("tool", geometry.tool.name)
            put("parallelOffsetM", geometry.parallelOffsetM)
            put("points", JSONArray().apply {
                geometry.points.forEach { point ->
                    put(JSONObject().apply {
                        put("lat", point.latitude)
                        put("lon", point.longitude)
                    })
                }
            })
        })
    }
    context.getSharedPreferences("survey_geometries", android.content.Context.MODE_PRIVATE)
        .edit()
        .putString("geometries_$projectId", array.toString())
        .apply()
}

private fun uniqueDivisionVertices(pieces: List<List<LatLng>>, toleranceM: Double = 0.02): List<LatLng> {
    val unique = mutableListOf<LatLng>()
    pieces.flatten().forEach { candidate ->
        if (unique.none { haversineMeters(it, candidate) <= toleranceM }) {
            unique += candidate
        }
    }
    return unique
}

private enum class DivideMode(val label: String, val help: String) {
    EQUAL_AREA("Áreas iguales", "Divide en 2 o más lotes con áreas aproximadamente iguales."),
    EQUAL_FRONT("Frentes iguales", "Seleccione el frente y divídalo en distancias iguales."),
    BY_LINE("Por línea de corte", "Trace una línea de corte manual sobre el polígono.")
}


private fun toXY(points: List<LatLng>): Pair<List<XY>, Double> {
    val meanLat = Math.toRadians(points.map { it.latitude }.average())
    val r = 6371008.8
    return points.map {
        XY(r * Math.toRadians(it.longitude) * cos(meanLat), r * Math.toRadians(it.latitude))
    } to meanLat
}

private fun fromXY(points: List<XY>, meanLat: Double): List<LatLng> {
    val r = 6371008.8
    return points.map { LatLng(Math.toDegrees(it.y / r), Math.toDegrees(it.x / (r * cos(meanLat)))) }
}

private fun areaXY(poly: List<XY>): Double {
    if (poly.size < 3) return 0.0
    var s = 0.0
    for (i in poly.indices) {
        val j = (i + 1) % poly.size
        s += poly[i].x * poly[j].y - poly[j].x * poly[i].y
    }
    return abs(s) / 2.0
}

private fun clipPlane(poly: List<XY>, nx: Double, ny: Double, c: Double, keepLess: Boolean): List<XY> {
    if (poly.isEmpty()) return emptyList()
    fun inside(p: XY): Boolean {
        val v = nx * p.x + ny * p.y - c
        return if (keepLess) v <= 1e-8 else v >= -1e-8
    }
    fun cross(a: XY, b: XY): XY {
        val da = nx * a.x + ny * a.y - c
        val db = nx * b.x + ny * b.y - c
        val t = da / (da - db)
        return XY(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }
    val out = mutableListOf<XY>()
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        val ia = inside(a)
        val ib = inside(b)
        when {
            ia && ib -> out += b
            ia && !ib -> out += cross(a, b)
            !ia && ib -> { out += cross(a, b); out += b }
        }
    }
    return out
}

private fun divideAxis(
    polygon: List<LatLng>,
    parts: Int,
    axisX: Double,
    axisY: Double,
    equalArea: Boolean
): List<List<LatLng>> {
    val (xy, meanLat) = toXY(polygon)
    if (xy.size < 3 || parts < 2) return listOf(polygon)

    val len = hypot(axisX, axisY).takeIf { it > 1e-9 } ?: return listOf(polygon)
    val nx = axisX / len
    val ny = axisY / len
    val totalArea = areaXY(xy)
    if (totalArea <= 1e-8) return listOf(polygon)

    /*
     * Important: split the REMAINING polygon sequentially.
     * The old implementation generated every strip independently from the
     * original polygon. Small numerical differences at neighbouring cuts
     * could leave slivers/gaps or mismatched borders. By cutting the remainder
     * and carrying it forward, each new parcel shares the exact same cut
     * vertices with the next parcel, so the pieces cover the complete polygon.
     */
    val result = mutableListOf<List<XY>>()
    var remaining = xy

    val originalVals = xy.map { nx * it.x + ny * it.y }
    val originalMin = originalVals.minOrNull() ?: return listOf(polygon)
    val originalMax = originalVals.maxOrNull() ?: return listOf(polygon)

    for (partIndex in 0 until parts - 1) {
        if (remaining.size < 3) break

        val remainingVals = remaining.map { nx * it.x + ny * it.y }
        val minV = remainingVals.minOrNull() ?: break
        val maxV = remainingVals.maxOrNull() ?: break
        if (maxV - minV <= 1e-8) break

        val cut = if (equalArea) {
            val targetArea = totalArea / parts.toDouble()
            val remainingArea = areaXY(remaining)
            val wanted = targetArea.coerceAtMost(remainingArea)
            var lo = minV
            var hi = maxV
            repeat(60) {
                val mid = (lo + hi) / 2.0
                val leftArea = areaXY(clipPlane(remaining, nx, ny, mid, true))
                if (leftArea < wanted) lo = mid else hi = mid
            }
            (lo + hi) / 2.0
        } else {
            // Equal-front mode keeps the original frontage spacing.
            val cutNumber = partIndex + 1
            originalMin + (originalMax - originalMin) * cutNumber.toDouble() / parts.toDouble()
        }

        val piece = clipPlane(remaining, nx, ny, cut, true)
        val rest = clipPlane(remaining, nx, ny, cut, false)

        if (piece.size < 3 || rest.size < 3) {
            // Never throw away any residual area. Keep everything that remains
            // as the final parcel instead of producing an incomplete division.
            break
        }

        result += piece
        remaining = rest
    }

    if (remaining.size >= 3) result += remaining

    // If for any reason the requested count could not be achieved safely,
    // return the complete polygon rather than an incomplete set with gaps.
    if (result.size < 2) return listOf(polygon)

    return result.map { fromXY(it, meanLat) }
}

private fun dividePolygonEqualArea(polygon: List<LatLng>, parts: Int): List<List<LatLng>> {
    val (xy, _) = toXY(polygon)
    val dx = (xy.maxOfOrNull { it.x } ?: 0.0) - (xy.minOfOrNull { it.x } ?: 0.0)
    val dy = (xy.maxOfOrNull { it.y } ?: 0.0) - (xy.minOfOrNull { it.y } ?: 0.0)
    return if (dx >= dy) divideAxis(polygon, parts, 1.0, 0.0, true) else divideAxis(polygon, parts, 0.0, 1.0, true)
}

private fun dividePolygonEqualFront(polygon: List<LatLng>, parts: Int, edge: Int): List<List<LatLng>> {
    val i = edge.coerceIn(0, polygon.lastIndex)
    val j = (i + 1) % polygon.size
    val (xy, _) = toXY(polygon)
    val a = xy[i]
    val b = xy[j]
    return divideAxis(polygon, parts, b.x - a.x, b.y - a.y, false)
}

private fun renderDivisionPolygons(map: MapLibreMap, pieces: List<List<LatLng>>) {
    pieces.forEach { p ->
        if (p.size >= 3) {
            drawTransparentPolygon(map, p)
            map.addPolyline(PolylineOptions().addAll(p + p.first()).width(4f))
        }
    }
}

private fun divisionSummary(title: String, pieces: List<List<LatLng>>): String {
    if (pieces.isEmpty()) return title + ": no se pudo generar la división."
    return title + " • " + pieces.mapIndexed { index, p ->
        "Lote " + (index + 1) + ": " + "%.2f".format(polygonAreaMeters2(p)) + " m²"
    }.joinToString(" • ")
}

private enum class MapFieldTool(
    val label: String,
    val instructions: String,
    val symbol: String
) {
    NONE("", "", ""),
    SELECT("Seleccionar", "Toque una figura guardada para seleccionarla.", "☝"),
    POINT("Crear punto", "Toque el mapa para crear un punto.", "●"),
    LINE("Crear línea", "Toque varios puntos para crear la línea.", "╱"),
    DISTANCE("Medir distancia", "Toque dos o más puntos. Se mostrará la distancia acumulada.", "↔ m"),
    AREA("Medir área", "Toque tres o más puntos para formar el área.", "A²"),
    PERIMETER("Medir perímetro", "Seleccione una figura cerrada existente o marque tres o más vértices.", "▱"),
    POLYGON("Crear polígono", "Toque tres o más vértices para dibujar el polígono.", "⬡"),
    DIVIDE("Dividir polígono", "Seleccione un polígono existente o márquelo por puntos; la división requiere un polígono cerrado.", "▭┆"),
    DIVIDE_LINE("Línea de división", "Toque dos puntos para definir la línea de corte.", "┆"),
    RECTANGLE("Rectángulo / cuadrado", "Toque dos esquinas opuestas.", "▭"),
    CIRCLE("Círculo", "Toque el centro; luego indique radio o diámetro.", "○"),
    PARALLEL("Línea paralela", "Seleccione una línea guardada y luego indique la separación.", "∥")
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
        MapFieldTool.SELECT -> return "Toque una figura guardada para seleccionarla."

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

        MapFieldTool.DIVIDE -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points).width(4f))
            }
            if (points.size >= 3) {
                drawTransparentPolygon(map, points)
                return "Polígono para dividir: %.2f m² • Pulse Listo para fijarlo.".format(polygonAreaMeters2(points))
            }
            return "Marque al menos 3 vértices del polígono que desea dividir."
        }

        MapFieldTool.DIVIDE_LINE -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points.take(2)).width(4f))
                return "Línea de división trazada. La vista muestra el corte manual."
            }
            return "Marque el segundo punto de la línea de división."
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
