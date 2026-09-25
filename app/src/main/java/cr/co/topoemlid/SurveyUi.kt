package cr.co.topoemlid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.LineString
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.json.JSONArray
import org.json.JSONObject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.TopologyException
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SurveyScreen(
    project: TopoProject?,
    gnss: GnssStatus
) {
    val context = LocalContext.current
    val snapThresholdPx = with(LocalDensity.current) { 28.dp.toPx() }
    val fieldSounds = remember { FieldSoundManager(context) }
    DisposableEffect(Unit) {
        onDispose { fieldSounds.release() }
    }
    val pointStore = remember(project?.id) { SurveyPointStore(context) }
    val layerStore = remember(project?.id) { LayerStore(context) }
    val basemapStore = remember(project?.id) { BasemapStore(context) }
    var selectedBasemap by remember(project?.id) { mutableStateOf(basemapStore.selected(project?.id)) }
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
    val pointDisplayStore = remember { PointDisplaySettingsStore(context) }
    var pointDisplaySettings by remember { mutableStateOf(pointDisplayStore.load()) }
    var showPointDisplayPanel by remember { mutableStateOf(false) }
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
    var showLineDividePanel by remember { mutableStateOf(false) }
    var lineDivideByParts by remember { mutableStateOf(false) }
    var lineDivideIntervalText by remember { mutableStateOf("20.00") }
    var lineDividePartsText by remember { mutableStateOf("5") }
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
    var parallelLeft by remember { mutableStateOf(true) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var pointPhoto by remember { mutableStateOf<Uri?>(null) }
    var followReceiver by remember { mutableStateOf(false) }
    var initialAutoZoomDone by remember(project?.id) { mutableStateOf(false) }
    var savedMapCamera by remember(project?.id) { mutableStateOf<CameraPosition?>(null) }

    var measuring by remember { mutableStateOf(false) }
    var secondsRemaining by remember { mutableIntStateOf(0) }
    var lastMessage by remember { mutableStateOf<String?>(null) }
    var pendingQuickMeasureCode by remember { mutableStateOf<String?>(null) }
    var quickCodes by remember(project?.id) {
        mutableStateOf(loadQuickCodes(context, project?.id))
    }
    var editingQuickCodeIndex by remember(project?.id) { mutableStateOf<Int?>(null) }
    var editingQuickCodeText by remember(project?.id) { mutableStateOf("") }
    var wmsDiagnostic by remember { mutableStateOf<String?>(null) }
    var wmsTestingId by remember { mutableStateOf<String?>(null) }
    var wmsPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var wmsPreviewTitle by remember { mutableStateOf<String?>(null) }
    var wmsPreviewDetails by remember { mutableStateOf<String?>(null) }
    var wmsPreviewLoadingId by remember { mutableStateOf<String?>(null) }
    val surveyScope = rememberCoroutineScope()

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

    fun persistVisibleLayers(updated: List<LayerItem>) {
        projectLayers = updated
        if (project != null) {
            layerStore.save(project.id, updated)
        } else {
            layerStore.saveLibrary(updated)
        }
    }

    fun redrawCommitted(map: MapLibreMap?) {
        val m = map ?: return
        committedGeometries.forEach { g ->
            drawCommittedGeometry(m, g)
        }
        drawSavedSurveyPointSymbols(m, savedPoints, context)
        drawLiveReceiverPosition(m, gnss, context)
        ensureSurveyGeometryOverlayOnTop(m, committedGeometries)
        ensureSurveyPointOverlayOnTop(m, savedPoints, gnss, pointDisplaySettings)
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
        redrawCommitted(map)
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

    LaunchedEffect(pendingQuickMeasureCode, showConfigPanel) {
        val quickCode = pendingQuickMeasureCode ?: return@LaunchedEffect
        if (!showConfigPanel) {
            code = quickCode
            if (description.isBlank()) description = quickCode
            delay(120)
            startMeasurement()
            pendingQuickMeasureCode = null
        }
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

    LaunchedEffect(gnss.latitude, gnss.longitude, gnss.solution, gnss.connected) {
        if (mapRef != null) {
            redrawActiveAndCommitted()
        }
    }

    LaunchedEffect(lastMessage) {
        if (lastMessage != null) {
            delay(1800)
            lastMessage = null
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
            val lat = gnss.latitude
            val lon = gnss.longitude
            if (lat == null || lon == null) {
                measuring = false
                lastMessage = "No hay coordenadas GNSS válidas para guardar."
                return@LaunchedEffect
            }

            val projected = runCatching {
                ProjectCoordinateEngine.fromWgs84(lat, lon, p.crsName)
            }.getOrElse {
                measuring = false
                lastMessage = "No se pudo transformar al CRS del proyecto: ${it.message}"
                return@LaunchedEffect
            }

            val ellipsoidal = gnss.ellipsoidalHeightM
            var geoidUndulation: Double? = null
            var orthometric: Double? = null
            if (ellipsoidal != null && p.geoidFileUri != null) {
                val geoid = GeoidGridService.undulation(
                    context = context,
                    uriText = p.geoidFileUri,
                    fileName = p.geoidFileName,
                    latitude = lat,
                    longitude = lon
                )
                geoid.onSuccess { sample ->
                    geoidUndulation = sample.undulationM
                    orthometric = ellipsoidal - sample.undulationM
                }.onFailure {
                    measuring = false
                    lastMessage = "Geoide no aplicado: ${it.message}"
                    return@LaunchedEffect
                }
            }

            val saved = SurveyPoint(
                id = UUID.randomUUID().toString(),
                projectId = p.id,
                pointNumber = pointNumber.ifBlank { nextPointNumber(savedPoints) },
                description = description.trim(),
                code = code.trim(),
                antennaHeightM = antennaHeight.toDoubleOrNull() ?: p.antennaHeightM,
                occupationSeconds = seconds.toIntOrNull()?.coerceIn(1, 600) ?: 5,
                latitude = lat,
                longitude = lon,
                eastingM = projected.eastingM,
                northingM = projected.northingM,
                projectCrsName = p.crsName,
                ellipsoidalHeightM = ellipsoidal,
                orthometricHeightM = orthometric,
                geoidUndulationM = geoidUndulation,
                geoidFileName = p.geoidFileName,
                verticalAccuracyM = gnss.verticalAccuracyM,
                horizontalAccuracyM = gnss.horizontalAccuracyM,
                solution = gnss.solution,
                satellites = gnss.satellites
            )

            val updated = savedPoints + saved
            savedPoints = updated
            pointStore.save(p.id, updated)
            fieldSounds.pointSaved()
            mapRef?.let {
                it.clear()
                redrawCommitted(it)
            }
            pointNumber = incrementPointNumber(saved.pointNumber)
            description = ""
            pointPhoto = null
            measuring = false
            lastMessage = "Punto ${saved.pointNumber} guardado"
            delay(1800)
            if (!measuring) lastMessage = null
        }
    }

    var liveProjectCoordinate by remember(project?.id) { mutableStateOf<ProjectCoordinate?>(null) }
    var liveOrthometricHeight by remember(project?.id) { mutableStateOf<Double?>(null) }
    var liveGeoidError by remember(project?.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(
        project?.id,
        project?.crsName,
        project?.geoidFileUri,
        gnss.latitude,
        gnss.longitude,
        gnss.ellipsoidalHeightM
    ) {
        val p = project
        val lat = gnss.latitude
        val lon = gnss.longitude
        if (p == null || lat == null || lon == null) {
            liveProjectCoordinate = null
            liveOrthometricHeight = null
            liveGeoidError = null
        } else {
            liveProjectCoordinate = runCatching {
                ProjectCoordinateEngine.fromWgs84(lat, lon, p.crsName)
            }.getOrNull()

            val h = gnss.ellipsoidalHeightM
            if (h != null && p.geoidFileUri != null) {
                withContext(Dispatchers.IO) {
                    GeoidGridService.undulation(
                        context = context,
                        uriText = p.geoidFileUri,
                        fileName = p.geoidFileName,
                        latitude = lat,
                        longitude = lon
                    )
                }.onSuccess { sample ->
                    liveOrthometricHeight = h - sample.undulationM
                    liveGeoidError = null
                }.onFailure {
                    liveOrthometricHeight = null
                    liveGeoidError = it.message ?: "Geoide no aplicado"
                }
            } else {
                liveOrthometricHeight = null
                liveGeoidError = null
            }
        }
    }

    editingQuickCodeIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { editingQuickCodeIndex = null },
            title = { Text("Editar código rápido") },
            text = {
                Column {
                    Text(
                        "Cambie este código para adaptarlo al tipo de trabajo actual.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editingQuickCodeText,
                        onValueChange = { editingQuickCodeText = it.uppercase() },
                        label = { Text("Código") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = editingQuickCodeText.isNotBlank(),
                    onClick = {
                        val updated = quickCodes.toMutableList()
                        if (index in updated.indices) {
                            updated[index] = editingQuickCodeText.trim().uppercase()
                            quickCodes = updated
                            saveQuickCodes(context, project?.id, updated)
                        }
                        editingQuickCodeIndex = null
                    }
                ) { Text("Guardar") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            quickCodes = defaultSurveyQuickCodes()
                            saveQuickCodes(context, project?.id, quickCodes)
                            editingQuickCodeIndex = null
                        }
                    ) { Text("Restaurar todos") }
                    TextButton(onClick = { editingQuickCodeIndex = null }) { Text("Cancelar") }
                }
            }
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { parentSize = it.size }
    ) {
        key(
            project?.id,
            selectedBasemap,
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

                            // Conserve exactamente la cámara que el usuario dejó (centro,
                            // zoom, inclinación y orientación) cuando el mapa se recrea por
                            // cambios de capas/estilo. Esto evita saltos al volver a una
                            // posición inicial mientras se trabaja con WMS.
                            val rememberedCamera = savedMapCamera
                            if (rememberedCamera != null) {
                                map.moveCamera(
                                    CameraUpdateFactory.newCameraPosition(rememberedCamera)
                                )
                            } else if (gnss.latitude == null || gnss.longitude == null) {
                                // Si aún no existe una vista previa, iniciar sobre Costa Rica.
                                map.moveCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(9.93, -84.08),
                                        8.2
                                    )
                                )
                            }

                            // Always start from a local style so the MapView can render
                            // immediately even if an external style server is slow or unavailable.
                            val baseStyle = Style.Builder()
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

                            map.setStyle(baseStyle) { style ->
                                // A new MapLibre style starts with no WMS ImageSources.
                                // Clear the per-layer URL cache so a previously loaded SNIT
                                // layer is fetched and attached again after screen/style recreation.
                                projectLayers
                                    .filter { it.type == LayerType.WMS }
                                    .forEach { wmsLastSuccessfulUrl.remove(it.id) }

                                addSelectedBasemap(style, selectedBasemap)
                                addProjectRasterLayers(style, projectLayers)
                                refreshViewportWmsLayers(map, projectLayers) {
                                    map.clear()
                                    redrawCommitted(map)
                                }
                                redrawCommitted(map)
                            }

                            map.addOnCameraIdleListener {
                                // Guardar la vista final después de cada gesto/zoom para que
                                // una actualización WMS o recomposición nunca la restablezca.
                                savedMapCamera = map.cameraPosition
                                refreshViewportWmsLayers(map, projectLayers) {
                                    map.clear()
                                    redrawCommitted(map)
                                }
                            }

                            map.addOnMapClickListener { latLng ->
                                val hitIndex = findGeometryAtScreen(map, latLng, committedGeometries)

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
                                    val snap = findSnapTarget(
                                        map = map,
                                        tap = latLng,
                                        savedPoints = savedPoints,
                                        geometries = committedGeometries,
                                        currentToolPoints = toolPoints,
                                        thresholdPx = snapThresholdPx
                                    )
                                    val target = snap?.position ?: latLng
                                    toolPoints = listOf(target)
                                    map.clear()
                                    redrawCommitted(map)
                                    map.addMarker(MarkerOptions().position(target))
                                    showCirclePanel = true
                                    toolResult = if (snap != null) {
                                        "Centro ajustado a ${snap.label}. Indique radio o diámetro."
                                    } else {
                                        "Centro del círculo marcado en el mapa. Indique radio o diámetro."
                                    }
                                    true
                                } else if (activeMapTool == MapFieldTool.PARALLEL) {
                                    val lineIndex = findLineGeometryAtScreen(map, latLng, committedGeometries)
                                    if (lineIndex != null) {
                                        val geometry = committedGeometries[lineIndex]
                                        selectedGeometryIndex = lineIndex
                                        toolPoints = geometryPath(geometry)
                                        showGeometrySelection(lineIndex)
                                        showParallelPanel = true
                                        toolResult = "Línea base seleccionada. Indique separación y lado."
                                        true
                                    } else {
                                        toolResult = "No se encontró una línea o polilínea guardada cerca. Toque directamente sobre uno de sus segmentos."
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
                                        MapFieldTool.DIVIDE -> geometrySupportsArea(geometry) || geometry.tool == MapFieldTool.LINE
                                        else -> false
                                    }
                                    if (acceptsSelection) {
                                        selectedGeometryIndex = hitIndex
                                        showGeometrySelection(hitIndex)
                                        if (activeMapTool == MapFieldTool.DIVIDE) {
                                            toolPoints = geometryPath(geometry)
                                            activeMapTool = MapFieldTool.NONE
                                            if (geometrySupportsArea(geometry)) {
                                                toolResult = "Polígono seleccionado para dividir: " + areaText(geometry)
                                                showDividePanel = true
                                            } else {
                                                toolResult = "Línea seleccionada para dividir • %.2f m".format(polylineDistanceMeters(toolPoints))
                                                showLineDividePanel = true
                                            }
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

                                    // Ajuste inteligente (snap): si el toque cae cerca de un
                                    // punto levantado/importado o del vértice de una figura,
                                    // se usa exactamente esa coordenada. Un toque fuera de la
                                    // tolerancia conserva su posición libre.
                                    val snap = findSnapTarget(
                                        map = map,
                                        tap = latLng,
                                        savedPoints = savedPoints,
                                        geometries = committedGeometries,
                                        currentToolPoints = toolPoints,
                                        thresholdPx = snapThresholdPx
                                    )
                                    val target = snap?.position ?: latLng

                                    val updated = when (activeMapTool) {
                                        MapFieldTool.POINT -> listOf(target)
                                        MapFieldTool.RECTANGLE ->
                                            if (toolPoints.size >= 3) toolPoints else toolPoints + target
                                        else -> toolPoints + target
                                    }

                                    toolPoints = updated
                                    val baseResult = renderFieldTool(
                                        map,
                                        activeMapTool,
                                        updated,
                                        parallelOffsetText.toDoubleOrNull() ?: 1.0
                                    )
                                    toolResult = if (snap != null) {
                                        "Ajustado a ${snap.label}. " + (baseResult ?: "")
                                    } else {
                                        baseResult
                                    }
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
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(project?.name ?: "Sin proyecto activo", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (gnss.connected) {
                        "${gnss.solution} • Sat: ${gnss.satellites ?: "—"} • H: ${gnss.horizontalAccuracyM?.let { "%.3f m".format(it) } ?: "—"} • V: ${gnss.verticalAccuracyM?.let { "%.3f m".format(it) } ?: "—"}"
                    } else {
                        "NO HAY NINGÚN RECEPTOR CONECTADO"
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider(Modifier.padding(vertical = 5.dp))

                val p = project
                val coord = liveProjectCoordinate
                if (p != null) {
                    if (p.crsName == "WGS 84 geográficas") {
                        Text(
                            "CRS: WGS 84 geográficas • Lat: ${gnss.latitude?.let { "%.8f".format(it) } ?: "—"} • Lon: ${gnss.longitude?.let { "%.8f".format(it) } ?: "—"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            "CRS: ${p.crsName} • N: ${coord?.northingM?.let { "%.3f".format(it) } ?: "—"} • E: ${coord?.eastingM?.let { "%.3f".format(it) } ?: "—"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val verticalColor = when {
                        p.geoidFileUri == null -> Color(0xFF546E7A)
                        liveGeoidError != null -> MaterialTheme.colorScheme.error
                        liveOrthometricHeight != null -> Color(0xFF2E7D32)
                        else -> Color(0xFF546E7A)
                    }
                    val verticalText = when {
                        p.geoidFileUri == null ->
                            "Altura: ${gnss.ellipsoidalHeightM?.let { "%.3f m".format(it) } ?: "—"} • Vertical: Elipsoidal"
                        liveGeoidError != null ->
                            "Geoide no aplicado • ${p.geoidFileName ?: "archivo geoidal"}"
                        else ->
                            "Elevación: ${liveOrthometricHeight?.let { "%.3f m".format(it) } ?: "—"} • Vertical: Geoide · ${p.geoidFileName ?: "local"}"
                    }
                    Text(
                        verticalText,
                        style = MaterialTheme.typography.labelSmall,
                        color = verticalColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(y = (-72).dp)
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
            ) { Text(if (followReceiver) "GPS✓" else "GPS") }

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

            SmallFloatingActionButton(
                onClick = { showPointDisplayPanel = true }
            ) { Text("👁") }
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
                .size(58.dp)
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
                containerColor = if (measuring) MaterialTheme.colorScheme.tertiary else Color.White
            ) {
                if (measuring) {
                    Text(
                        secondsRemaining.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    val exactRtkIcon = androidx.compose.ui.graphics.ImageBitmap.imageResource(
                        id = R.drawable.measure_rtk_reference
                    )
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier.size(50.dp)
                    ) {
                        // Use the exact pixels from the image supplied by the user.
                        // Crop only the RTK antenna/controller symbol, excluding the
                        // surrounding white margin and the Shutterstock footer.
                        drawImage(
                            image = exactRtkIcon,
                            srcOffset = androidx.compose.ui.unit.IntOffset(112, 34),
                            srcSize = androidx.compose.ui.unit.IntSize(126, 184),
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = androidx.compose.ui.unit.IntSize(
                                size.width.toInt(),
                                size.height.toInt()
                            )
                        )
                    }
                }
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
                                            } else if (activeMapTool == MapFieldTool.RECTANGLE && toolPoints.size < 3) {
                                                toolResult = "Marque 3 puntos: dos para el ancho y uno para el largo."
                                            } else if (toolPoints.isNotEmpty()) {
                                                val savedGeometry = CommittedGeometry(
                                                    id = editingOriginalGeometry?.id ?: UUID.randomUUID().toString(),
                                                    tool = activeMapTool,
                                                    points = toolPoints,
                                                    parallelOffsetM = parallelOffsetText.toDoubleOrNull() ?: 1.0
                                                )
                                                val updatedGeometries = committedGeometries + savedGeometry
                                                persistGeometries(updatedGeometries)
                                                editingOriginalGeometry = null
                                                toolPoints = emptyList()
                                                activeMapTool = MapFieldTool.NONE
                                                toolResult = null
                                                lastMessage = if (project != null) {
                                                    "Elemento guardado en el proyecto."
                                                } else {
                                                    "Elemento guardado temporalmente en el mapa."
                                                }
                                                mapRef?.let { map ->
                                                    map.clear()
                                                    updatedGeometries.forEach { geometry ->
                                                        drawCommittedGeometry(map, geometry)
                                                    }
                                                    drawSavedSurveyPoints(map, savedPoints, context)
                                                    drawLiveReceiverPosition(map, gnss, context)
                                                    ensureSurveyGeometryOverlayOnTop(map, updatedGeometries)
                                                    ensureSurveyPointOverlayOnTop(map, savedPoints, gnss, pointDisplaySettings)
                                                }
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
                                        mapRef?.let {
                                            it.clear()
                                            redrawCommitted(it)
                                        }
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
                        val compactRtkColor = when {
                            !gnss.connected -> Color(0xFF757575)
                            gnss.solution.equals("FIX", ignoreCase = true) -> Color(0xFF2E7D32)
                            gnss.solution.equals("FLOAT", ignoreCase = true) -> Color(0xFFF9A825)
                            else -> Color(0xFFC62828)
                        }
                        Text(
                            "H ${gnss.horizontalAccuracyM?.let { "%.3f".format(it) } ?: "—"} • V ${gnss.verticalAccuracyM?.let { "%.3f".format(it) } ?: "—"} • ${if (gnss.connected) gnss.solution else "SIN RECEPTOR"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = compactRtkColor,
                            fontWeight = FontWeight.Bold
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

    if (showPointDisplayPanel) {
        PointDisplaySettingsSheet(
            value = pointDisplaySettings,
            onChange = { updated ->
                pointDisplaySettings = updated
                pointDisplayStore.save(updated)
                mapRef?.let { map ->
                    map.clear()
                    redrawCommitted(map)
                }
            },
            onDismiss = { showPointDisplayPanel = false }
        )
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
                                            toolResult = "Toque directamente una línea o polilínea guardada. Se resaltará completa y podrá indicar distancia, izquierda o derecha."
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
                                        toolResult = "Seleccione una línea o un polígono existente. En líneas puede dividir cada cierta distancia o en partes iguales; en polígonos se mantienen las opciones de división actuales."
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
                toolPoints.firstOrNull()?.let { center ->
                    Text(
                        "Centro: %.8f, %.8f".format(center.latitude, center.longitude),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
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
                Text("Crear paralela a línea / polilínea", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (toolPoints.size >= 2)
                        "Línea/polilínea base seleccionada • %.2f m • %d vértices".format(polylineDistanceMeters(toolPoints), toolPoints.size)
                    else
                        "Seleccione primero una línea en el mapa.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text("Lado de la paralela", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = parallelLeft, onClick = { parallelLeft = true })
                    Text("Izquierda")
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = !parallelLeft, onClick = { parallelLeft = false })
                    Text("Derecha")
                }
                Spacer(Modifier.height(6.dp))
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
                        val rawDistance = parallelOffsetText.replace(',', '.').toDoubleOrNull()
                        if (toolPoints.size < 2 || rawDistance == null || rawDistance == 0.0) {
                            toolResult = "Seleccione una línea y escriba una separación válida."
                        } else {
                            val signedDistance = abs(rawDistance) * if (parallelLeft) 1.0 else -1.0
                            val parallel = offsetPolyline(toolPoints, signedDistance)
                            val geometry = CommittedGeometry(
                                tool = MapFieldTool.LINE,
                                points = parallel,
                                parallelOffsetM = signedDistance
                            )
                            persistGeometries(committedGeometries + geometry)
                            showParallelPanel = false
                            activeMapTool = MapFieldTool.NONE
                            toolPoints = emptyList()
                            selectedGeometryIndex = null
                            toolResult = "Paralela guardada con la misma forma de la polilínea • %.2f m a la %s.".format(
                                abs(rawDistance),
                                if (parallelLeft) "izquierda" else "derecha"
                            )
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

    if (showLineDividePanel) {
        ModalBottomSheet(onDismissRequest = { showLineDividePanel = false }) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                val totalLength = polylineDistanceMeters(toolPoints)
                Text("Dividir línea", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Longitud de la línea: %.3f m".format(totalLength),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = !lineDivideByParts,
                        onClick = { lineDivideByParts = false }
                    )
                    Column {
                        Text("Cada cierta distancia")
                        Text("Ejemplo: cada 20 m; el último punto siempre coincide con el final.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!lineDivideByParts) {
                    OutlinedTextField(
                        value = lineDivideIntervalText,
                        onValueChange = { lineDivideIntervalText = it },
                        label = { Text("Distancia entre puntos (m)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = lineDivideByParts,
                        onClick = { lineDivideByParts = true }
                    )
                    Column {
                        Text("En partes iguales")
                        Text("La app calcula automáticamente la distancia decimal de cada tramo.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (lineDivideByParts) {
                    OutlinedTextField(
                        value = lineDividePartsText,
                        onValueChange = { lineDividePartsText = it.filter(Char::isDigit) },
                        label = { Text("Cantidad de divisiones") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val previewDistances = if (lineDivideByParts) {
                    val parts = lineDividePartsText.toIntOrNull()?.coerceIn(1, 500)
                    if (parts != null && totalLength > 0.0) {
                        (1..parts).map { totalLength * it.toDouble() / parts.toDouble() }
                    } else emptyList()
                } else {
                    val interval = lineDivideIntervalText.replace(',', '.').toDoubleOrNull()
                    lineDivisionDistances(totalLength, interval)
                }

                if (previewDistances.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (lineDivideByParts) {
                            val step = totalLength / previewDistances.size.toDouble()
                            "${previewDistances.size} divisiones • %.3f m por tramo".format(step)
                        } else {
                            "${previewDistances.size} puntos de división • final en %.3f m".format(totalLength)
                        },
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        val p = project
                        if (p == null || toolPoints.size < 2 || totalLength <= 0.0) {
                            toolResult = "Abra un proyecto y seleccione una línea válida."
                        } else if (previewDistances.isEmpty()) {
                            toolResult = "Indique una distancia o cantidad de divisiones válida."
                        } else {
                            var nextNumber = nextPointNumber(savedPoints).toIntOrNull()
                                ?: ((savedPoints.mapNotNull { it.pointNumber.toIntOrNull() }.maxOrNull() ?: 0) + 1)
                            val created = previewDistances.mapIndexedNotNull { index, distance ->
                                val position = pointAlongPolyline(toolPoints, distance) ?: return@mapIndexedNotNull null
                                SurveyPoint(
                                    id = UUID.randomUUID().toString(),
                                    projectId = p.id,
                                    pointNumber = (nextNumber++).toString(),
                                    description = "División línea ${index + 1}/${previewDistances.size}",
                                    code = "DIV",
                                    antennaHeightM = p.antennaHeightM,
                                    occupationSeconds = 0,
                                    latitude = position.latitude,
                                    longitude = position.longitude,
                                    solution = "CALCULADO"
                                )
                            }
                            if (created.isNotEmpty()) {
                                val updated = savedPoints + created
                                savedPoints = updated
                                pointStore.save(p.id, updated)
                                pointNumber = nextPointNumber(updated)
                                showLineDividePanel = false
                                selectedGeometryIndex = null
                                toolPoints = emptyList()
                                toolResult = "${created.size} puntos de división guardados y disponibles para replanteo."
                                mapRef?.let { map ->
                                    map.clear()
                                    redrawCommitted(map)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Crear puntos de división") }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showLineDividePanel = false },
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
                                            if (p.eastingM != null && p.northingM != null) {
                                                Text(
                                                    "E: %.3f  N: %.3f  (${p.projectCrsName ?: project.crsName})".format(p.eastingM, p.northingM),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            } else {
                                                Text(
                                                    "Lat: ${p.latitude?.let { "%.8f".format(it) } ?: "—"}  Lon: ${p.longitude?.let { "%.8f".format(it) } ?: "—"}",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                            p.orthometricHeightM?.let {
                                                Text("Elevación geoide: %.3f m".format(it), style = MaterialTheme.typography.labelSmall)
                                            } ?: p.ellipsoidalHeightM?.let {
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

    if (wmsPreviewBitmap != null || wmsPreviewTitle != null) {
        AlertDialog(
            onDismissRequest = {
                wmsPreviewBitmap = null
                wmsPreviewTitle = null
                wmsPreviewDetails = null
            },
            title = { Text(wmsPreviewTitle ?: "Prueba WMS fuera del mapa") },
            text = {
                Column {
                    val bmp = wmsPreviewBitmap
                    if (bmp != null) {
                        Text(
                            "Esta imagen fue descargada por Android y se muestra fuera de MapLibre.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Vista previa WMS",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp, max = 420.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text("No se recibió una imagen válida.")
                    }

                    wmsPreviewDetails?.let { details ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Diagnóstico exacto:",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            details,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    wmsPreviewBitmap = null
                    wmsPreviewTitle = null
                    wmsPreviewDetails = null
                }) { Text("Cerrar") }
            }
        )
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
                    "Cambie el mapa base y active o desactive capas sin salir del Levantamiento.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(14.dp))
                Text("Mapa base", fontWeight = FontWeight.Bold)

                listOf(
                    BasemapType.BASIC,
                    BasemapType.SATELLITE
                ).forEach { type ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedBasemap = type
                                basemapStore.setSelected(project?.id, type)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedBasemap == type,
                            onClick = {
                                selectedBasemap = type
                                basemapStore.setSelected(project?.id, type)
                            }
                        )
                        Text(type.label)
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        selectedBasemap = BasemapType.NONE
                        basemapStore.setSelected(project?.id, BasemapType.NONE)
                        persistVisibleLayers(projectLayers.map { it.copy(visible = false) })
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apagar todo")
                }

                Spacer(Modifier.height(14.dp))
                Text("Capas superpuestas", fontWeight = FontWeight.Bold)

                if (projectLayers.isEmpty()) {
                    Text(
                        if (project == null)
                            "No hay capas WMS/XYZ guardadas en la biblioteca global."
                        else
                            "No hay capas WMS/XYZ guardadas en este proyecto.",
                        style = MaterialTheme.typography.bodySmall
                    )
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
                                            persistVisibleLayers(updated)
                                            mapRef?.let { map ->
                                                refreshViewportWmsLayers(map, updated) {
                                                    map.clear()
                                                    redrawCommitted(map)
                                                }
                                            }
                                        }
                                    )
                                }

                                if (layer.type == LayerType.WMS && layer.visible) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Transparencia WMS: ${(layer.opacity * 100).toInt()}%",
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Slider(
                                        value = layer.opacity,
                                        onValueChange = { value ->
                                            persistVisibleLayers(
                                                projectLayers.map {
                                                    if (it.id == layer.id) it.copy(opacity = value) else it
                                                }
                                            )
                                        },
                                        valueRange = 0.15f..1.0f,
                                        steps = 16,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            0.30f to "Suave",
                                            0.50f to "Medio",
                                            0.75f to "Fuerte"
                                        ).forEach { (value, label) ->
                                            OutlinedButton(
                                                onClick = {
                                                    persistVisibleLayers(
                                                        projectLayers.map {
                                                            if (it.id == layer.id) it.copy(opacity = value) else it
                                                        }
                                                    )
                                                },
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(label)
                                            }
                                        }
                                    }

                                    /*
                                    OutlinedButton(
                                        onClick = {
                                            val map = mapRef
                                            if (map == null) {
                                                wmsDiagnostic = "El mapa aún no está listo."
                                            } else {
                                                val bounds = runCatching {
                                                    map.projection.visibleRegion.latLngBounds
                                                }.getOrNull()
                                                if (bounds == null) {
                                                    wmsDiagnostic = "No se pudo leer el área visible del mapa."
                                                } else {
                                                    val north = bounds.latitudeNorth.coerceIn(-89.0, 89.0)
                                                    val south = bounds.latitudeSouth.coerceIn(-89.0, 89.0)
                                                    val east = bounds.longitudeEast
                                                    val west = bounds.longitudeWest
                                                    val serviceUrl = layer.url.orEmpty()
                                                    wmsPreviewLoadingId = layer.id
                                                    wmsDiagnostic =
                                                        "Prueba A/B: consultando GetCapabilities y descargando fuera de MapLibre…"

                                                    surveyScope.launch {
                                                        val isCurrentSnit = serviceUrl.contains(
                                                            "geos.snitcr.go.cr/be/",
                                                            ignoreCase = true
                                                        )

                                                        if (isCurrentSnit) {
                                                            val result = withContext(Dispatchers.IO) {
                                                                negotiateCurrentSnitWms(
                                                                    layer = layer,
                                                                    north = north,
                                                                    east = east,
                                                                    south = south,
                                                                    west = west
                                                                )
                                                            }
                                                            val details = wmsLayerDiagnostics[layer.id]
                                                                ?: "sin diagnóstico"
                                                            wmsPreviewLoadingId = null
                                                            wmsPreviewBitmap = result?.bitmap
                                                            wmsPreviewDetails = details
                                                            wmsPreviewTitle =
                                                                if (result?.bitmap != null)
                                                                    "Prueba A/B: imagen recibida"
                                                                else
                                                                    "Prueba A/B: sin imagen"
                                                            wmsDiagnostic =
                                                                if (result?.bitmap != null)
                                                                    "Prueba A/B fuera de MapLibre → imagen válida recibida."
                                                                else
                                                                    "Prueba A/B fuera de MapLibre → sin imagen válida."
                                                        } else {
                                                            val uri = when {
                                                                serviceUrl.contains(
                                                                    "siri.snitcr.go.cr/Geoservicios/wms",
                                                                    ignoreCase = true
                                                                ) -> buildLegacyWorkingSiriUrl(
                                                                    layer, north, east, south, west
                                                                )
                                                                serviceUrl.contains(
                                                                    "snitcr.go.cr/servicios/cartografia/wms",
                                                                    ignoreCase = true
                                                                ) -> buildLegacyDirectSnitUrl(
                                                                    layer, north, east, south, west
                                                                )
                                                                else -> buildViewportWmsUrl(
                                                                    layer, north, east, south, west
                                                                )
                                                            }

                                                            if (uri == null) {
                                                                wmsPreviewLoadingId = null
                                                                wmsPreviewBitmap = null
                                                                wmsPreviewDetails =
                                                                    "No se pudo construir la solicitud WMS de prueba."
                                                                wmsPreviewTitle = "Prueba A/B: sin imagen"
                                                                wmsDiagnostic =
                                                                    "No se pudo construir la solicitud WMS de prueba."
                                                            } else {
                                                                val bitmap = withContext(Dispatchers.IO) {
                                                                    downloadSingleWmsBitmap(
                                                                        url = uri,
                                                                        serviceUrl = serviceUrl,
                                                                        qgisLikeHeaders = false
                                                                    )
                                                                }
                                                                val detail = wmsAttemptDiagnostics[uri]
                                                                    ?: "sin diagnóstico"
                                                                wmsPreviewLoadingId = null
                                                                wmsPreviewBitmap = bitmap
                                                                wmsPreviewDetails = detail
                                                                wmsPreviewTitle =
                                                                    if (bitmap != null)
                                                                        "Prueba A/B: imagen recibida"
                                                                    else
                                                                        "Prueba A/B: sin imagen"
                                                                wmsDiagnostic =
                                                                    "Prueba A/B fuera de MapLibre → $detail"
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        enabled = wmsPreviewLoadingId != layer.id,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            if (wmsPreviewLoadingId == layer.id)
                                                "Probando fuera del mapa…"
                                            else
                                                "Prueba A/B WMS fuera de MapLibre"
                                        )
                                    }
                                    */
                                }
                            }
                        }
                }

                wmsDiagnostic?.let {
                    Spacer(Modifier.height(10.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            it,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
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

                Text(
                    "Toque para usarlo. Mantenga presionado un código para editarlo.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))

                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickCodes.forEachIndexed { index, item ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 1.dp,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    code = item
                                    pendingQuickMeasureCode = item
                                    showConfigPanel = false
                                },
                                onLongClick = {
                                    editingQuickCodeIndex = index
                                    editingQuickCodeText = item
                                }
                            )
                        ) {
                            Text(
                                item,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
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


private data class SnapTarget(
    val position: LatLng,
    val label: String,
    val distancePx: Double
)

private fun findSnapTarget(
    map: MapLibreMap,
    tap: LatLng,
    savedPoints: List<SurveyPoint>,
    geometries: List<CommittedGeometry>,
    currentToolPoints: List<LatLng>,
    thresholdPx: Float
): SnapTarget? {
    val tapScreen = map.projection.toScreenLocation(tap)
    val candidates = mutableListOf<Pair<LatLng, String>>()

    // Los puntos levantados o importados tienen prioridad semántica.
    savedPoints.forEach { p ->
        val lat = p.latitude
        val lon = p.longitude
        if (lat != null && lon != null) {
            candidates += LatLng(lat, lon) to "punto ${p.pointNumber}"
        }
    }

    // Todos los vértices de figuras guardadas también pueden recibir snap.
    geometries.forEachIndexed { geometryIndex, geometry ->
        geometryPath(geometry).forEachIndexed { vertexIndex, vertex ->
            candidates += vertex to "vértice ${vertexIndex + 1} de ${geometry.tool.label}"
        }
    }

    // Permite cerrar o enlazar la figura que se está dibujando con sus propios
    // vértices anteriores, útil especialmente en líneas y polígonos.
    currentToolPoints.forEachIndexed { index, vertex ->
        candidates += vertex to "vértice actual ${index + 1}"
    }

    return candidates
        .map { (position, label) ->
            val s = map.projection.toScreenLocation(position)
            val dx = (s.x - tapScreen.x).toDouble()
            val dy = (s.y - tapScreen.y).toDouble()
            SnapTarget(position, label, hypot(dx, dy))
        }
        .filter { it.distancePx <= thresholdPx }
        .minByOrNull { it.distancePx }
}

private fun defaultSurveyQuickCodes(): List<String> = listOf(
    "CALLE", "CORDÓN", "CUNETA", "CAÑO", "ASFALTO", "LASTRE",
    "POSTE", "LOTE", "LINDERO", "CERCA", "ACERA", "MURO",
    "EDIFICIO", "ESQUINA", "EJE", "ALCANTARILLA", "ÁRBOL",
    "HIDRANTE", "CAJA", "TAPA", "TALUD", "PIE TALUD",
    "CORONA", "QUEBRADA", "RÍO", "PUENTE", "PORTÓN",
    "PUNTO CONTROL"
)

private fun loadQuickCodes(context: Context, projectId: String?): List<String> {
    val key = "quick_codes_" + (projectId ?: "global")
    val prefs = context.getSharedPreferences("survey_quick_codes", Context.MODE_PRIVATE)
    val raw = prefs.getString(key, null) ?: return defaultSurveyQuickCodes()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length())
            .map { array.optString(it).trim().uppercase() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?: defaultSurveyQuickCodes()
    }.getOrDefault(defaultSurveyQuickCodes())
}

private fun saveQuickCodes(context: Context, projectId: String?, codes: List<String>) {
    val key = "quick_codes_" + (projectId ?: "global")
    val array = JSONArray()
    codes.forEach { array.put(it.trim().uppercase()) }
    context.getSharedPreferences("survey_quick_codes", Context.MODE_PRIVATE)
        .edit()
        .putString(key, array.toString())
        .apply()
}

private fun ensureSurveyGeometryOverlayOnTop(
    map: MapLibreMap,
    geometries: List<CommittedGeometry>
) {
    val style = map.style ?: return
    val sourceId = "survey-geometries-top-source"
    val layerId = "survey-geometries-top-layer"

    val features = mutableListOf<Feature>()
    geometries.forEach { geometry ->
        when (geometry.tool) {
            MapFieldTool.POINT, MapFieldTool.NONE, MapFieldTool.SELECT -> Unit
            MapFieldTool.PARALLEL -> {
                val base = geometryPath(geometry)
                if (base.size >= 2) {
                    val baseCoords = base.map { Point.fromLngLat(it.longitude, it.latitude) }
                    features += Feature.fromGeometry(LineString.fromLngLats(baseCoords))
                    val parallel = offsetPolyline(base, geometry.parallelOffsetM)
                    if (parallel.size >= 2) {
                        val parallelCoords = parallel.map { Point.fromLngLat(it.longitude, it.latitude) }
                        features += Feature.fromGeometry(LineString.fromLngLats(parallelCoords))
                    }
                }
            }
            else -> {
                val path = geometryPath(geometry)
                if (path.size >= 2) {
                    val closed = geometrySupportsArea(geometry) && path.size >= 3
                    val drawPath = if (closed) path + path.first() else path
                    val coords = drawPath.map { Point.fromLngLat(it.longitude, it.latitude) }
                    features += Feature.fromGeometry(LineString.fromLngLats(coords))
                }
            }
        }
    }

    val source = style.getSourceAs<GeoJsonSource>(sourceId)
    if (source == null) {
        style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(features)))
    } else {
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Recreate at the top of the style stack so WMS/WMTS/XYZ can never cover
    // field drawings. This is independent of the old annotation layer order.
    runCatching { style.removeLayer(layerId) }
    style.addLayer(
        LineLayer(layerId, sourceId).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.rgb(103, 58, 183)),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineOpacity(1f)
        )
    )
}
private fun ensureSurveyPointOverlayOnTop(
    map: MapLibreMap,
    points: List<SurveyPoint>,
    gnss: GnssStatus,
    settings: PointDisplaySettings
) {
    val style = map.style ?: return

    ensureTopoSurveyPointLayers(
        style = style,
        prefix = "survey-points-top",
        points = points,
        settings = settings
    )

    val liveSourceId = "gnss-live-top-source"
    val liveLayerId = "gnss-live-top-layer"
    val liveFeatures = if (
        gnss.connected && gnss.latitude != null && gnss.longitude != null
    ) {
        listOf(
            Feature.fromGeometry(
                Point.fromLngLat(gnss.longitude!!, gnss.latitude!!)
            )
        )
    } else {
        emptyList()
    }

    val liveSource = style.getSourceAs<GeoJsonSource>(liveSourceId)
    if (liveSource == null) {
        style.addSource(
            GeoJsonSource(
                liveSourceId,
                FeatureCollection.fromFeatures(liveFeatures)
            )
        )
    } else {
        liveSource.setGeoJson(FeatureCollection.fromFeatures(liveFeatures))
    }

    val liveColor = when {
        gnss.solution.equals("FIX", true) ->
            android.graphics.Color.rgb(46, 125, 50)
        gnss.solution.equals("FLOAT", true) ->
            android.graphics.Color.rgb(249, 168, 37)
        else ->
            android.graphics.Color.rgb(198, 40, 40)
    }

    runCatching { style.removeLayer(liveLayerId) }
    style.addLayer(
        CircleLayer(liveLayerId, liveSourceId).withProperties(
            PropertyFactory.circleColor(liveColor),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(4f),
            PropertyFactory.circleRadius(10f)
        )
    )
}

private fun drawSavedSurveyPointSymbols(
    map: MapLibreMap,
    points: List<SurveyPoint>,
    context: Context
) {
    val iconFactory = IconFactory.getInstance(context)
    val icon = iconFactory.fromBitmap(
        makeTopoPointBitmap(android.graphics.Color.rgb(255, 45, 45))
    )
    points.forEach { point ->
        val lat = point.latitude ?: return@forEach
        val lon = point.longitude ?: return@forEach
        map.addMarker(
            MarkerOptions()
                .position(LatLng(lat, lon))
                .icon(icon)
                .title("Punto ${point.pointNumber}")
                .snippet(
                    buildString {
                        val detail = point.description.ifBlank { point.code }
                        if (detail.isNotBlank()) append(detail)
                        point.ellipsoidalHeightM?.let {
                            if (isNotBlank()) append(" • ")
                            append("H %.3f m".format(it))
                        }
                    }
                )
        )
    }
}

private fun drawLiveReceiverPosition(
    map: MapLibreMap,
    gnss: GnssStatus,
    context: Context
) {
    val lat = gnss.latitude ?: return
    val lon = gnss.longitude ?: return
    if (!gnss.connected) return

    val fillColor = when {
        gnss.solution.equals("FIX", true) -> android.graphics.Color.rgb(46, 125, 50)
        gnss.solution.equals("FLOAT", true) -> android.graphics.Color.rgb(249, 168, 37)
        else -> android.graphics.Color.rgb(198, 40, 40)
    }

    val size = 68
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.argb(
            72,
            android.graphics.Color.red(fillColor),
            android.graphics.Color.green(fillColor),
            android.graphics.Color.blue(fillColor)
        )
    }
    canvas.drawCircle(size / 2f, size / 2f, 27f, haloPaint)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    canvas.drawCircle(size / 2f, size / 2f, 13f, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 4f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, 14f, paint)

    val icon = IconFactory.getInstance(context).fromBitmap(bitmap)
    map.addMarker(
        MarkerOptions()
            .position(LatLng(lat, lon))
            .icon(icon)
            .title("Posición GNSS • ${gnss.solution}")
    )
}

private fun drawSavedSurveyPoints(
    map: MapLibreMap,
    points: List<SurveyPoint>,
    context: Context
) {
    val iconFactory = IconFactory.getInstance(context)
    points.forEach { point ->
        val lat = point.latitude ?: return@forEach
        val lon = point.longitude ?: return@forEach
        val detail = point.description.ifBlank { point.code.ifBlank { "Punto" } }
        val label = "✕ ${point.pointNumber}  $detail"

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(35, 35, 35)
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
        }
        val width = (textPaint.measureText(label) + 24f).toInt().coerceAtLeast(90)
        val height = 42
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(205, 255, 255, 255)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 9f, 9f, bg)
        canvas.drawText(label, 10f, 29f, textPaint)

        map.addMarker(
            MarkerOptions()
                .position(LatLng(lat, lon))
                .icon(iconFactory.fromBitmap(bitmap))
                .title("Punto ${point.pointNumber}")
                .snippet(detail)
        )
    }
}

private fun lineDivisionDistances(totalLength: Double, interval: Double?): List<Double> {
    if (totalLength <= 0.0 || interval == null || interval <= 0.0) return emptyList()
    val result = mutableListOf<Double>()
    var d = interval
    while (d < totalLength - 1e-6 && result.size < 500) {
        result += d
        d += interval
    }
    if (result.isEmpty() || abs(result.last() - totalLength) > 1e-6) {
        result += totalLength
    }
    return result
}

private fun pointAlongPolyline(points: List<LatLng>, distanceM: Double): LatLng? {
    if (points.size < 2) return null
    val total = polylineDistanceMeters(points)
    if (total <= 0.0) return points.lastOrNull()
    val wanted = distanceM.coerceIn(0.0, total)
    var travelled = 0.0
    for (i in 0 until points.lastIndex) {
        val a = points[i]
        val b = points[i + 1]
        val segment = haversineMeters(a, b)
        if (segment <= 1e-9) continue
        if (travelled + segment >= wanted - 1e-9) {
            val t = ((wanted - travelled) / segment).coerceIn(0.0, 1.0)
            return LatLng(
                a.latitude + (b.latitude - a.latitude) * t,
                a.longitude + (b.longitude - a.longitude) * t
            )
        }
        travelled += segment
    }
    return points.last()
}

private fun nextPointNumber(points: List<SurveyPoint>): String {
    val max = points.mapNotNull { it.pointNumber.toIntOrNull() }.maxOrNull() ?: 0
    return (max + 1).toString()
}

private fun incrementPointNumber(current: String): String {
    return current.toIntOrNull()?.plus(1)?.toString() ?: current
}


private fun probeWmsUrl(url: String): String {
    val isSiri = url.contains("siri.snitcr.go.cr/Geoservicios/wms", ignoreCase = true)
    val attempts = if (isSiri) 4 else 1
    var lastMessage = "No hubo respuesta del WMS."

    repeat(attempts) { attempt ->
        try {
            val requestUrl = if (isSiri) {
                val sep = if (url.contains("?")) "&" else "?"
                url + sep + "_probe=" + System.nanoTime()
            } else url

            val conn = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 12000
                requestMethod = "GET"
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "TopoEmlid/0.3")
                setRequestProperty("Accept", "image/png,image/jpeg,*/*")
                setRequestProperty("Cache-Control", "no-cache")
            }

            val code = conn.responseCode
            val type = conn.contentType ?: "sin Content-Type"
            val length = conn.contentLengthLong
            val finalUrl = conn.url.toString()

            lastMessage = when {
                finalUrl.contains("/Geoservicios/error", ignoreCase = true) ->
                    "SIRI redirigió temporalmente la solicitud a /Geoservicios/error."
                code in 200..299 && type.startsWith("image/") ->
                    "WMS responde correctamente: HTTP $code • $type • " +
                        (if (length > 0) "$length bytes" else "tamaño desconocido") + "."
                code in 200..299 ->
                    "WMS respondió HTTP $code, pero devolvió $type en vez de una imagen."
                else ->
                    "WMS devolvió HTTP $code (" + (conn.responseMessage ?: "error") + ")."
            }
            conn.disconnect()

            if (code in 200..299 && type.startsWith("image/") &&
                !finalUrl.contains("/Geoservicios/error", ignoreCase = true)) {
                return lastMessage
            }

            if (attempt < attempts - 1 && isSiri) Thread.sleep(1200)
        } catch (e: Exception) {
            lastMessage = "Error al consultar WMS: " + (e.message ?: e.javaClass.simpleName)
            if (attempt < attempts - 1 && isSiri) Thread.sleep(1200)
        }
    }

    return if (isSiri) {
        lastMessage + " Se intentó 4 veces. Verifique que la capa técnica sea catastro, catastro_aldia o vias_publicas."
    } else lastMessage
}

fun addSelectedBasemap(
    style: Style,
    basemap: BasemapType
) {
    if (basemap == BasemapType.NONE) return

    val sourceId = "basemap-source"
    val layerId = "basemap-layer"
    val tileUrl = when (basemap) {
        BasemapType.BASIC ->
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        BasemapType.SATELLITE ->
            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        BasemapType.NONE -> return
    }

    runCatching {
        val tileSet = TileSet("2.2.0", tileUrl)
        style.addSource(RasterSource(sourceId, tileSet, 256))
        style.addLayer(
            RasterLayer(layerId, sourceId).withProperties(
                PropertyFactory.rasterOpacity(1f)
            )
        )
    }
}

private fun addRasterBelowFieldOverlays(
    style: Style,
    layer: RasterLayer
) {
    // Cualquier capa cartográfica debe quedar SIEMPRE debajo de los elementos
    // de trabajo de campo. Elegimos la primera capa de puntos conocida como ancla.
    val anchor = style.layers.firstOrNull { existing ->
        val id = existing.id
        id.startsWith("survey-points-top-") ||
            id.startsWith("stakeout-points-") ||
            id == "gnss-live-top-layer" ||
            id.startsWith("stakeout-critical-")
    }?.id

    if (anchor != null) {
        style.addLayerBelow(layer, anchor)
    } else {
        style.addLayer(layer)
    }
}

fun addProjectRasterLayers(
    style: Style,
    layers: List<LayerItem>
) {
    layers
        .filter { it.visible }
        .sortedBy { it.order }
        .forEach { layer ->
            val tileUrl = when (layer.type) {
                LayerType.XYZ, LayerType.WMTS -> layer.url
                else -> null
            } ?: return@forEach

            val sourceId = "project-source-${layer.id}"
            val layerId = "project-layer-${layer.id}"

            runCatching {
                val tileSet = TileSet("2.2.0", tileUrl)
                style.addSource(RasterSource(sourceId, tileSet, 256))
                addRasterBelowFieldOverlays(
                    style,
                    RasterLayer(layerId, sourceId).withProperties(
                        PropertyFactory.rasterOpacity(layer.opacity)
                    )
                )
            }
        }
}

private val wmsLastSuccessfulUrl = ConcurrentHashMap<String, String>()
private val wmsRequestInFlight = ConcurrentHashMap<String, Boolean>()
private val siriWinningStrategy = ConcurrentHashMap<String, String>()
private val wmsAttemptDiagnostics = ConcurrentHashMap<String, String>()
private val wmsLayerDiagnostics = ConcurrentHashMap<String, String>()
private val wmsDesiredVisibility = ConcurrentHashMap<String, Boolean>()

private data class WmsRenderResult(
    val bitmap: Bitmap,
    val requestUrl: String,
    val strategy: String
)

fun refreshViewportWmsLayers(
    map: MapLibreMap,
    layers: List<LayerItem>,
    onLayerUpdated: (() -> Unit)? = null,
    onLayerFailed: ((String) -> Unit)? = null
) {
    val style = map.style ?: return

    // Visibility in the layer switch is authoritative. Remove hidden WMS overlays
    // immediately and remember the desired state so an older background request
    // cannot re-attach a layer after the user has turned it off.
    layers.filter { it.type == LayerType.WMS }.forEach { layer ->
        wmsDesiredVisibility[layer.id] = layer.visible
        if (!layer.visible) {
            val layerId = "project-wms-image-layer-${layer.id}"
            val sourceId = "project-wms-image-source-${layer.id}"
            runCatching { style.removeLayer(layerId) }
            runCatching { style.removeSource(sourceId) }
            wmsLastSuccessfulUrl.remove(layer.id)
            wmsRequestInFlight.remove(layer.id)
        }
    }

    val bounds = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull() ?: return

    val north = bounds.latitudeNorth.coerceIn(-89.0, 89.0)
    val south = bounds.latitudeSouth.coerceIn(-89.0, 89.0)
    val east = bounds.longitudeEast
    val west = bounds.longitudeWest

    if (north <= south || east <= west) return

    val quad = LatLngQuad(
        LatLng(north, west),
        LatLng(north, east),
        LatLng(south, east),
        LatLng(south, west)
    )

    layers
        .filter { it.type == LayerType.WMS && it.visible }
        .sortedBy { it.order }
        .forEach { layer ->
            val serviceUrl = layer.url.orEmpty()
            val isSiri = serviceUrl.contains(
                "siri.snitcr.go.cr/Geoservicios/wms",
                ignoreCase = true
            )
            val isSnitCartography = serviceUrl.contains(
                "snitcr.go.cr/servicios/cartografia/wms",
                ignoreCase = true
            )
            val isCurrentSnit = serviceUrl.contains(
                "geos.snitcr.go.cr/be/",
                ignoreCase = true
            )

            if (isSiri || isSnitCartography) {
                // Use the original MapLibre direct ImageSource route. This deliberately
                // avoids making our own HTTP download a prerequisite for rendering.
                val uri = when {
                    isSiri -> buildLegacyWorkingSiriUrl(
                        layer = layer,
                        north = north,
                        east = east,
                        south = south,
                        west = west
                    )
                    isSnitCartography -> buildLegacyDirectSnitUrl(
                        layer = layer,
                        north = north,
                        east = east,
                        south = south,
                        west = west
                    )
                    else -> buildViewportWmsUrl(
                        layer = layer,
                        north = north,
                        east = east,
                        south = south,
                        west = west
                    )
                } ?: return@forEach

                val sourceId = "project-wms-image-source-${layer.id}"
                val layerId = "project-wms-image-layer-${layer.id}"
                val existing = runCatching {
                    style.getSourceAs<ImageSource>(sourceId)
                }.getOrNull()

                if (wmsDesiredVisibility[layer.id] != true) return@forEach

                val attached = runCatching {
                    if (existing != null) {
                        // Al cambiar el encuadre no reutilizamos la imagen anterior.
                        // MapLibre puede estirar temporalmente el bitmap viejo sobre el
                        // nuevo quad mientras descarga el URI nuevo, creando parcelas
                        // gigantes y franjas fuera de Costa Rica. Se elimina y recrea
                        // la fuente para que la capa quede en blanco hasta recibir la
                        // imagen correspondiente al nuevo BBOX.
                        runCatching { style.removeLayer(layerId) }
                        runCatching { style.removeSource(sourceId) }
                    }
                    style.addSource(ImageSource(sourceId, quad, URI.create(uri)))
                    addRasterBelowFieldOverlays(
                        style,
                        RasterLayer(layerId, sourceId).withProperties(
                            PropertyFactory.rasterOpacity(layer.opacity)
                        )
                    )
                    true
                }.getOrDefault(false)

                if (attached) {
                    wmsLastSuccessfulUrl[layer.id] = uri
                    siriWinningStrategy[layer.id] =
                        when {
                            isSiri ->
                                "RUTA DIRECTA MAPLIBRE • SIRI • WMS 1.1.1 • EPSG:4326"
                            isSnitCartography ->
                                "RUTA DIRECTA MAPLIBRE • SNIT legado • WMS 1.1.1 • EPSG:4326"
                            else ->
                                "RUTA DIRECTA MAPLIBRE • SNIT actual geos.snitcr.go.cr • WMS 1.1.1 • EPSG:4326"
                        }

                    // Let the map render immediately; the probe below is diagnostic only.
                    onLayerUpdated?.invoke()

                    Thread {
                        val probeBitmap = downloadSingleWmsBitmap(
                            url = uri,
                            serviceUrl = serviceUrl,
                            qgisLikeHeaders = false
                        )
                        val detail = wmsAttemptDiagnostics[uri] ?: "sin diagnóstico"
                        wmsLayerDiagnostics[layer.id] =
                            "Prueba HTTP paralela (no bloquea MapLibre) → $detail"
                        if (probeBitmap == null) {
                            // A failed probe does NOT remove or replace the direct MapLibre source.
                            // It only reports what our Java HTTP stack sees on this device.
                        }
                        Handler(Looper.getMainLooper()).post {
                            onLayerUpdated?.invoke()
                        }
                    }.start()
                } else {
                    wmsLayerDiagnostics[layer.id] =
                        "No se pudo adjuntar el ImageSource WMS al estilo de MapLibre."
                    onLayerFailed?.invoke(
                        "No se pudo crear la capa WMS dentro del mapa."
                    )
                }
                return@forEach
            }

            if (wmsRequestInFlight.putIfAbsent(layer.id, true) != null) return@forEach

            Thread {
                try {
                    val serviceUrl = layer.url.orEmpty()
                    val result = when {
                        serviceUrl.contains(
                            "siri.snitcr.go.cr/Geoservicios/wms",
                            ignoreCase = true
                        ) -> negotiateSiriWms(
                            layer = layer,
                            north = north,
                            east = east,
                            south = south,
                            west = west
                        )

                        serviceUrl.contains(
                            "snitcr.go.cr/servicios/cartografia/wms",
                            ignoreCase = true
                        ) -> negotiateLegacySnitCartographyWms(
                            layer = layer,
                            north = north,
                            east = east,
                            south = south,
                            west = west
                        )

                        serviceUrl.contains(
                            "geos.snitcr.go.cr/be/",
                            ignoreCase = true
                        ) -> negotiateCurrentSnitWms(
                            layer = layer,
                            north = north,
                            east = east,
                            south = south,
                            west = west
                        )

                        else -> {
                            val uri = buildViewportWmsUrl(layer, north, east, south, west)
                            if (uri == null) null
                            else downloadSingleWmsBitmap(uri, serviceUrl)?.let {
                                WmsRenderResult(it, uri, "Configuración WMS guardada")
                            }
                        }
                    }

                    if (result == null) {
                        Handler(Looper.getMainLooper()).post {
                            onLayerFailed?.invoke(
                                "No se encontró una combinación WMS válida para esta vista."
                            )
                        }
                        return@Thread
                    }

                    Handler(Looper.getMainLooper()).post {
                        if (wmsDesiredVisibility[layer.id] != true) return@post
                        val currentStyle = map.style ?: return@post
                        val sourceId = "project-wms-image-source-${layer.id}"
                        val layerId = "project-wms-image-layer-${layer.id}"

                        val existing = runCatching {
                            currentStyle.getSourceAs<ImageSource>(sourceId)
                        }.getOrNull()

                        if (existing != null) {
                            runCatching {
                                existing.setCoordinates(quad)
                                existing.setImage(result.bitmap)
                                currentStyle.getLayerAs<RasterLayer>(layerId)?.setProperties(
                                    PropertyFactory.rasterOpacity(layer.opacity)
                                )
                                wmsLastSuccessfulUrl[layer.id] = result.requestUrl
                                onLayerUpdated?.invoke()
                            }
                        } else {
                            runCatching {
                                currentStyle.addSource(
                                    ImageSource(sourceId, quad, result.bitmap)
                                )
                                addRasterBelowFieldOverlays(
                                    currentStyle,
                                    RasterLayer(layerId, sourceId).withProperties(
                                        PropertyFactory.rasterOpacity(layer.opacity)
                                    )
                                )
                                wmsLastSuccessfulUrl[layer.id] = result.requestUrl
                                onLayerUpdated?.invoke()
                            }
                        }
                    }
                } finally {
                    wmsRequestInFlight.remove(layer.id)
                }
            }.start()
        }
}

private fun negotiateSiriWms(
    layer: LayerItem,
    north: Double,
    east: Double,
    south: Double,
    west: Double
): WmsRenderResult? {
    val candidates = buildSiriCandidateRequests(
        layer = layer,
        north = north,
        east = east,
        south = south,
        west = west
    )
    if (candidates.isEmpty()) return null

    val preferred = siriWinningStrategy[layer.id]
    val ordered = if (preferred == null) {
        candidates
    } else {
        candidates.sortedByDescending { it.first == preferred }
    }

    val diagnostics = mutableListOf<String>()

    ordered.forEach { (strategy, url) ->
        val bitmap = downloadSingleWmsBitmap(
            url = url,
            serviceUrl = layer.url.orEmpty(),
            qgisLikeHeaders = true
        )
        diagnostics += "$strategy → ${wmsAttemptDiagnostics[url] ?: "sin diagnóstico"}"

        if (bitmap != null) {
            siriWinningStrategy[layer.id] = strategy
            wmsLayerDiagnostics[layer.id] = diagnostics.joinToString("\n")
            return WmsRenderResult(bitmap, url, strategy)
        }
    }

    wmsLayerDiagnostics[layer.id] = diagnostics.joinToString("\n")
    return null
}

private fun negotiateCurrentSnitWms(
    layer: LayerItem,
    north: Double,
    east: Double,
    south: Double,
    west: Double
): WmsRenderResult? {
    val raw = layer.url?.trim()?.takeIf { it.isNotBlank() } ?: return null

    val capabilities = WmsCapabilitiesClient.load(raw, timeoutMs = 9000)
    val options = capabilities.getOrElse { error ->
        wmsLayerDiagnostics[layer.id] =
            "GetCapabilities falló → ${error.message ?: error.javaClass.simpleName}"
        return null
    }

    if (options.isEmpty()) {
        wmsLayerDiagnostics[layer.id] = "GetCapabilities respondió sin capas publicadas."
        return null
    }

    val requested = layer.layerName.orEmpty().trim()
    val selected = when {
        requested.isNotBlank() &&
            !requested.equals("AUTO_GETCAPABILITIES", true) ->
            options.firstOrNull { it.name.equals(requested, true) }

        else -> options.firstOrNull {
            val haystack = (it.title + " " + it.name).lowercase()
            haystack.contains("cordón") ||
            haystack.contains("cordon") ||
            haystack.contains("caño") ||
            haystack.contains("cano") ||
            haystack.contains("avenida") ||
            haystack.contains("calle")
        } ?: options.first()
    } ?: options.first()

    val base = sanitizeWmsBaseUrl(raw)
    val separator = if (base.contains("?")) {
        if (base.endsWith("?") || base.endsWith("&")) "" else "&"
    } else "?"

    val encodedLayer = java.net.URLEncoder.encode(selected.name, "UTF-8")

    val bbox111 = "%.8f,%.8f,%.8f,%.8f".format(
        java.util.Locale.US,
        west, south, east, north
    )
    val bbox130 = "%.8f,%.8f,%.8f,%.8f".format(
        java.util.Locale.US,
        south, west, north, east
    )

    fun make111(swapxy: Boolean): String = buildString {
        append(base)
        append(separator)
        append("SERVICE=WMS")
        append("&REQUEST=GetMap")
        append("&VERSION=1.1.1")
        append("&LAYERS=")
        append(encodedLayer)
        append("&STYLES=")
        append("&FORMAT=image/png")
        append("&TRANSPARENT=TRUE")
        append("&SRS=EPSG:4326")
        append("&BBOX=")
        append(bbox111)
        append("&WIDTH=1024")
        append("&HEIGHT=1024")
        append("&EXCEPTIONS=application/vnd.ogc.se_xml")
        if (swapxy) append("&SWAPXY=TRUE")
    }

    fun make130(): String = buildString {
        append(base)
        append(separator)
        append("SERVICE=WMS")
        append("&REQUEST=GetMap")
        append("&VERSION=1.3.0")
        append("&LAYERS=")
        append(encodedLayer)
        append("&STYLES=")
        append("&FORMAT=image/png")
        append("&TRANSPARENT=TRUE")
        append("&CRS=EPSG:4326")
        append("&BBOX=")
        append(bbox130)
        append("&WIDTH=1024")
        append("&HEIGHT=1024")
        append("&EXCEPTIONS=XML")
    }

    val candidates = listOf(
        "SNIT actual • ${selected.title} • 1.1.1 • EPSG:4326 • SWAPXY=TRUE" to make111(true),
        "SNIT actual • ${selected.title} • 1.1.1 • EPSG:4326" to make111(false),
        "SNIT actual • ${selected.title} • 1.3.0 • EPSG:4326" to make130()
    )

    val diagnostics = mutableListOf<String>()
    candidates.forEach { (strategy, url) ->
        val bitmap = downloadSingleWmsBitmap(
            url = url,
            serviceUrl = raw,
            qgisLikeHeaders = true
        )
        diagnostics += "$strategy → ${wmsAttemptDiagnostics[url] ?: "sin diagnóstico"}"

        if (bitmap != null) {
            wmsLayerDiagnostics[layer.id] =
                "GetCapabilities detectó ${options.size} capas. " +
                "Seleccionada: ${selected.title} [${selected.name}]\n" +
                diagnostics.joinToString("\n")
            return WmsRenderResult(bitmap, url, strategy)
        }
    }

    wmsLayerDiagnostics[layer.id] =
        "GetCapabilities detectó ${options.size} capas. " +
        "Seleccionada: ${selected.title} [${selected.name}]\n" +
        diagnostics.joinToString("\n")
    return null
}


private fun negotiateLegacySnitCartographyWms(
    layer: LayerItem,
    north: Double,
    east: Double,
    south: Double,
    west: Double
): WmsRenderResult? {
    val raw = layer.url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val layerName = layer.layerName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val base = sanitizeWmsBaseUrl(raw)
    val sep = if (base.contains("?")) {
        if (base.endsWith("?") || base.endsWith("&")) "" else "&"
    } else "?"

    val encodedLayer = java.net.URLEncoder.encode(layerName, "UTF-8")
    val encodedStyle = java.net.URLEncoder.encode(layer.styleName.orEmpty(), "UTF-8")

    val bboxLatLon = "%.8f,%.8f,%.8f,%.8f".format(
        java.util.Locale.US,
        south, west, north, east
    )
    val bboxLonLat = "%.8f,%.8f,%.8f,%.8f".format(
        java.util.Locale.US,
        west, south, east, north
    )

    fun make(version: String, crsKey: String, bbox: String, size: Int): String =
        buildString {
            append(base)
            append(sep)
            append("SERVICE=WMS")
            append("&REQUEST=GetMap")
            append("&VERSION=")
            append(version)
            append("&LAYERS=")
            append(encodedLayer)
            append("&STYLES=")
            append(encodedStyle)
            append("&FORMAT=image/png")
            append("&TRANSPARENT=TRUE")
            append("&")
            append(crsKey)
            append("=EPSG:4326")
            append("&BBOX=")
            append(bbox)
            append("&WIDTH=")
            append(size)
            append("&HEIGHT=")
            append(size)
            append("&EXCEPTIONS=XML")
            append("&_topo=")
            append(System.nanoTime())
        }

    val candidates = listOf(
        "SNIT cartografía 1.3.0 / EPSG:4326 / eje lat-lon" to
            make("1.3.0", "CRS", bboxLatLon, 512),
        "SNIT cartografía 1.1.1 / EPSG:4326 / eje lon-lat" to
            make("1.1.1", "SRS", bboxLonLat, 512),
        "SNIT cartografía 1.3.0 / EPSG:4326 / 256 px" to
            make("1.3.0", "CRS", bboxLatLon, 256)
    )

    val diagnostics = mutableListOf<String>()

    candidates.forEach { (strategy, url) ->
        val bitmap = downloadSingleWmsBitmap(
            url = url,
            serviceUrl = raw,
            qgisLikeHeaders = true
        )
        diagnostics += "$strategy → ${wmsAttemptDiagnostics[url] ?: "sin diagnóstico"}"
        if (bitmap != null) {
            wmsLayerDiagnostics[layer.id] = diagnostics.joinToString("\n")
            return WmsRenderResult(bitmap, url, strategy)
        }
    }

    wmsLayerDiagnostics[layer.id] = diagnostics.joinToString("\n")
    return null
}


private fun buildSiriCandidateRequests(
    layer: LayerItem,
    north: Double,
    east: Double,
    south: Double,
    west: Double
): List<Pair<String, String>> {
    val raw = layer.url?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    val rawLayerName = layer.layerName?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    val layerName = when (
        rawLayerName.trim().lowercase().replace(" ", "").replace("_", "")
    ) {
        "zona1", "catastro" -> "catastro"
        "zona2", "catastroaldia" -> "catastro_aldia"
        "viaspublicas", "viaspúblicas", "vias" -> "vias_publicas"
        else -> rawLayerName
    }

    val base = sanitizeWmsBaseUrl(raw)
    val separator = if (base.contains("?")) {
        if (base.endsWith("?") || base.endsWith("&")) "" else "&"
    } else "?"

    val encodedLayer = java.net.URLEncoder.encode(layerName, "UTF-8")
    val encodedStyle = java.net.URLEncoder.encode(layer.styleName.orEmpty(), "UTF-8")

    fun mx(lon: Double): Double =
        6378137.0 * Math.toRadians(lon.coerceIn(-180.0, 180.0))

    fun my(lat: Double): Double {
        val clipped = lat.coerceIn(-85.05112878, 85.05112878)
        return 6378137.0 * ln(
            tan(Math.PI / 4.0 + Math.toRadians(clipped) / 2.0)
        )
    }

    val bbox3857 = "%.3f,%.3f,%.3f,%.3f".format(
        java.util.Locale.US,
        mx(west), my(south), mx(east), my(north)
    )
    val bbox4326LonLat = "%.8f,%.8f,%.8f,%.8f".format(
        java.util.Locale.US,
        west, south, east, north
    )
    val bbox4326LatLon = "%.8f,%.8f,%.8f,%.8f".format(
        java.util.Locale.US,
        south, west, north, east
    )

    fun make(
        version: String,
        crsKey: String,
        crsValue: String,
        bbox: String,
        size: Int,
        format: String,
        transparent: Boolean,
        qgisDpi: Boolean
    ): String {
        return buildString {
            append(base)
            append(separator)
            append("SERVICE=WMS")
            append("&REQUEST=GetMap")
            append("&VERSION=")
            append(version)
            append("&LAYERS=")
            append(encodedLayer)
            append("&STYLES=")
            append(encodedStyle)
            append("&FORMAT=")
            append(java.net.URLEncoder.encode(format, "UTF-8"))
            append("&TRANSPARENT=")
            append(if (transparent) "TRUE" else "FALSE")
            append("&")
            append(crsKey)
            append("=")
            append(crsValue)
            append("&BBOX=")
            append(bbox)
            append("&WIDTH=")
            append(size)
            append("&HEIGHT=")
            append(size)
            append("&EXCEPTIONS=application/vnd.ogc.se_xml")
            if (qgisDpi) {
                append("&DPI=96")
                append("&MAP_RESOLUTION=96")
                append("&FORMAT_OPTIONS=dpi:96")
            }
            append("&_topo=")
            append(System.nanoTime())
        }
    }

    return listOf(
        "QGIS 1.1.1 / EPSG:3857 / PNG 512" to
            make("1.1.1", "SRS", "EPSG:3857", bbox3857, 512, "image/png", true, true),

        "1.1.1 / EPSG:3857 / PNG 256" to
            make("1.1.1", "SRS", "EPSG:3857", bbox3857, 256, "image/png", true, false),

        "1.1.1 / EPSG:4326 / PNG 512" to
            make("1.1.1", "SRS", "EPSG:4326", bbox4326LonLat, 512, "image/png", true, true),

        "1.3.0 / EPSG:3857 / PNG 512" to
            make("1.3.0", "CRS", "EPSG:3857", bbox3857, 512, "image/png", true, true),

        "1.3.0 / EPSG:4326 eje oficial / PNG 512" to
            make("1.3.0", "CRS", "EPSG:4326", bbox4326LatLon, 512, "image/png", true, true),

        "1.1.1 / EPSG:3857 / JPEG 512" to
            make("1.1.1", "SRS", "EPSG:3857", bbox3857, 512, "image/jpeg", false, true)
    )
}

private fun downloadSingleWmsBitmap(
    url: String,
    serviceUrl: String,
    qgisLikeHeaders: Boolean = false
): Bitmap? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6500
            readTimeout = 9000
            requestMethod = "GET"
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty(
                "User-Agent",
                if (qgisLikeHeaders)
                    "Mozilla/5.0 QGIS/3.40 (Windows 10; TopoEmlid Android)"
                else
                    "TopoEmlid/0.3"
            )
            setRequestProperty("Accept", "image/png,image/jpeg,image/*,*/*;q=0.8")
            setRequestProperty("Accept-Language", "es-CR,es;q=0.9,en;q=0.5")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
            setRequestProperty("Connection", "close")
            if (qgisLikeHeaders) {
                setRequestProperty("Referer", "https://siri.snitcr.go.cr/")
            }
        }

        val code = conn.responseCode
        val finalUrl = conn.url.toString()
        val contentType = conn.contentType ?: "sin Content-Type"

        if (
            code !in 200..299 ||
            finalUrl.contains("/Geoservicios/error", ignoreCase = true)
        ) {
            val redirected = if (finalUrl.contains("/Geoservicios/error", true)) {
                " • redirigido a /Geoservicios/error"
            } else ""
            wmsAttemptDiagnostics[url] = "HTTP $code • $contentType$redirected"
            null
        } else {
            val bytes = conn.inputStream.use { it.readBytes() }
            val png =
                bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
            val jpeg =
                bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()

            if (!png && !jpeg) {
                wmsAttemptDiagnostics[url] =
                    "HTTP $code • $contentType • respuesta no gráfica • ${bytes.size} bytes"
                null
            } else {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    wmsAttemptDiagnostics[url] =
                        "HTTP $code • $contentType • imagen no decodificable • ${bytes.size} bytes"
                    null
                } else {
                    wmsAttemptDiagnostics[url] =
                        "OK HTTP $code • $contentType • ${bytes.size} bytes"
                    bitmap
                }
            }
        }
    } catch (e: java.net.SocketTimeoutException) {
        wmsAttemptDiagnostics[url] = "TIMEOUT • ${e.message ?: "sin respuesta"}"
        null
    } catch (e: Exception) {
        wmsAttemptDiagnostics[url] =
            "ERROR ${e.javaClass.simpleName} • ${e.message ?: "sin detalle"}"
        null
    } finally {
        runCatching { conn?.disconnect() }
    }
}


private fun buildLegacyWorkingSiriUrl(
    layer: LayerItem,
    north: Double,
    east: Double,
    south: Double,
    west: Double
): String? {
    val raw = layer.url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val rawLayerName = layer.layerName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val layerName = when (
        rawLayerName.trim().lowercase().replace(" ", "").replace("_", "")
    ) {
        "zona1", "catastro" -> "catastro"
        "zona2", "catastroaldia" -> "catastro_aldia"
        "viaspublicas", "viaspúblicas", "vias" -> "vias_publicas"
        else -> rawLayerName
    }

    val base = sanitizeWmsBaseUrl(raw)
    val separator = if (base.contains("?")) {
        if (base.endsWith("?") || base.endsWith("&")) "" else "&"
    } else "?"

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
        append("&srs=EPSG:4326")
        append("&bbox=")
        append(
            "%.8f,%.8f,%.8f,%.8f".format(
                java.util.Locale.US,
                west, south, east, north
            )
        )
        append("&width=1024")
        append("&height=1024")
    }
}

private fun buildLegacyDirectSnitUrl(
    layer: LayerItem,
    north: Double,
    east: Double,
    south: Double,
    west: Double
): String? {
    val raw = layer.url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val layerName = layer.layerName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val base = sanitizeWmsBaseUrl(raw)
    val separator = if (base.contains("?")) {
        if (base.endsWith("?") || base.endsWith("&")) "" else "&"
    } else "?"

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
        append("&srs=EPSG:4326")
        append("&bbox=")
        append(
            "%.8f,%.8f,%.8f,%.8f".format(
                java.util.Locale.US,
                west, south, east, north
            )
        )
        append("&width=1024")
        append("&height=1024")
    }
}

private fun buildViewportWmsUrl(
    layer: LayerItem,
    north: Double,
    east: Double,
    south: Double,
    west: Double
): String? {
    val raw = layer.url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val rawLayerName = layer.layerName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val isSiri = raw.contains("siri.snitcr.go.cr/Geoservicios/wms", ignoreCase = true)
    val layerName = if (isSiri) {
        when (rawLayerName.trim().lowercase().replace(" ", "").replace("_", "")) {
            "zona1", "catastro" -> "catastro"
            "zona2", "catastroaldia" -> "catastro_aldia"
            "viaspublicas", "viaspúblicas", "vias" -> "vias_publicas"
            else -> rawLayerName
        }
    } else rawLayerName

    val base = sanitizeWmsBaseUrl(raw)
    val separator = if (base.contains("?")) {
        if (base.endsWith("?") || base.endsWith("&")) "" else "&"
    } else {
        "?"
    }

    val encodedLayer = java.net.URLEncoder.encode(layerName, "UTF-8")
    val encodedStyle = java.net.URLEncoder.encode(layer.styleName.orEmpty(), "UTF-8")
    val encodedFormat = java.net.URLEncoder.encode(layer.imageFormat, "UTF-8")

    fun mercatorX(lon: Double): Double =
        6378137.0 * Math.toRadians(lon.coerceIn(-180.0, 180.0))

    fun mercatorY(lat: Double): Double {
        val clipped = lat.coerceIn(-85.05112878, 85.05112878)
        return 6378137.0 * ln(
            tan(Math.PI / 4.0 + Math.toRadians(clipped) / 2.0)
        )
    }

    // QGIS normalmente negocia el WMS con el CRS del proyecto.
    // Nuestros mapas base OSM/Esri trabajan en Web Mercator, por eso para
    // SIRI pedimos EPSG:3857, que el servicio publica y evita una reproyección
    // adicional del lado del cliente.
    val requestedCrs = if (isSiri) "EPSG:3857" else {
        if (layer.crs.equals("EPSG:4326", true)) "EPSG:4326" else "EPSG:3857"
    }

    val bbox = if (requestedCrs == "EPSG:3857") {
        "%.3f,%.3f,%.3f,%.3f".format(
            java.util.Locale.US,
            mercatorX(west),
            mercatorY(south),
            mercatorX(east),
            mercatorY(north)
        )
    } else {
        "%.8f,%.8f,%.8f,%.8f".format(
            java.util.Locale.US,
            west, south, east, north
        )
    }

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
        append("&srs=")
        append(requestedCrs)
        append("&bbox=")
        append(bbox)
        append("&width=768")
        append("&height=768")
    }
}

private fun buildWmsTileUrl(layer: LayerItem): String? {
    val raw = layer.url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val rawLayerName = layer.layerName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val isSiri = raw.contains("siri.snitcr.go.cr/Geoservicios/wms", ignoreCase = true)

    val layerName = if (isSiri) {
        when (rawLayerName.trim().lowercase().replace(" ", "").replace("_", "")) {
            "zona1", "catastro" -> "catastro"
            "zona2", "catastroaldia" -> "catastro_aldia"
            "viaspublicas", "viaspúblicas", "vias" -> "vias_publicas"
            else -> rawLayerName
        }
    } else rawLayerName

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
        MapFieldTool.LINE, MapFieldTool.DISTANCE -> if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
        MapFieldTool.AREA, MapFieldTool.PERIMETER, MapFieldTool.POLYGON -> {
            if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
            if (points.size >= 3 && tool != MapFieldTool.LINE) drawTransparentPolygon(map, points)
        }
        MapFieldTool.RECTANGLE -> if (points.size >= 3) {
            drawTransparentPolygon(map, rectangleFromControlPoints(points))
        }
        MapFieldTool.CIRCLE -> if (points.size >= 2) {
            map.addMarker(MarkerOptions().position(points[0]))
            val circle = circlePolygon(points[0], haversineMeters(points[0], points[1]), 64)
            drawTransparentPolygon(map, circle)
        }
        MapFieldTool.PARALLEL -> if (points.size >= 2) {
            val base = points.take(2)
            map.addPolyline(PolylineOptions().addAll(base).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
            map.addPolyline(PolylineOptions().addAll(parallelLine(base[0], base[1], parallelOffsetM)).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
        }
        MapFieldTool.DIVIDE_LINE -> if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points.take(2)).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
        MapFieldTool.DIVIDE -> {
            if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
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
            .width(6f)
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
    geometry.tool == MapFieldTool.LINE ||
    geometry.tool == MapFieldTool.DISTANCE

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
    fun shiftedSegment(a: XY, b: XY): Pair<XY, XY> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy).takeIf { it > 1e-9 } ?: 1.0
        val nx = -dy / len
        val ny = dx / len
        val off = XY(nx * offsetM, ny * offsetM)
        return XY(a.x + off.x, a.y + off.y) to XY(b.x + off.x, b.y + off.y)
    }
    fun lineIntersection(a1: XY, a2: XY, b1: XY, b2: XY): XY? {
        val dax = a2.x - a1.x
        val day = a2.y - a1.y
        val dbx = b2.x - b1.x
        val dby = b2.y - b1.y
        val det = dax * dby - day * dbx
        if (abs(det) < 1e-9) return null
        val rx = b1.x - a1.x
        val ry = b1.y - a1.y
        val t = (rx * dby - ry * dbx) / det
        return XY(a1.x + t * dax, a1.y + t * day)
    }

    val xy = points.map(::toLocal)
    val shifted = (0 until xy.lastIndex).map { i -> shiftedSegment(xy[i], xy[i + 1]) }
    val out = mutableListOf<XY>()

    // First endpoint stays perpendicular to the first segment.
    out += shifted.first().first

    // Interior vertices are intersections of adjacent offset segments.
    for (i in 1 until xy.lastIndex) {
        val prev = shifted[i - 1]
        val next = shifted[i]
        val intersection = lineIntersection(prev.first, prev.second, next.first, next.second)

        // Very sharp or nearly parallel corners can create an extreme miter.
        // Fall back to the midpoint between the two shifted vertex positions.
        val candidate = intersection ?: XY(
            (prev.second.x + next.first.x) / 2.0,
            (prev.second.y + next.first.y) / 2.0
        )
        val original = xy[i]
        val miterLength = hypot(candidate.x - original.x, candidate.y - original.y)
        val safe = if (miterLength > abs(offsetM) * 12.0 + 0.01) {
            XY(
                (prev.second.x + next.first.x) / 2.0,
                (prev.second.y + next.first.y) / 2.0
            )
        } else candidate
        out += safe
    }

    // Last endpoint stays perpendicular to the last segment.
    out += shifted.last().second
    return out.map(::toLatLng)
}

private fun rectangleFromControlPoints(points: List<LatLng>): List<LatLng> {
    if (points.size < 3) return points
    val a = points[0]
    val b = points[1]
    val c = points[2]
    val meanLat = Math.toRadians((a.latitude + b.latitude + c.latitude) / 3.0)
    val r = 6371008.8

    fun local(p: LatLng): XY = XY(
        r * Math.toRadians(p.longitude - a.longitude) * cos(meanLat),
        r * Math.toRadians(p.latitude - a.latitude)
    )
    fun geo(p: XY): LatLng = LatLng(
        a.latitude + Math.toDegrees(p.y / r),
        a.longitude + Math.toDegrees(p.x / (r * cos(meanLat)))
    )

    val aa = XY(0.0, 0.0)
    val bb = local(b)
    val cc = local(c)
    val width = hypot(bb.x, bb.y)
    if (width <= 1e-9) return points

    val ux = bb.x / width
    val uy = bb.y / width
    val nx = -uy
    val ny = ux
    val signedLength = cc.x * nx + cc.y * ny
    val offset = XY(nx * signedLength, ny * signedLength)

    return listOf(
        geo(aa),
        geo(bb),
        geo(XY(bb.x + offset.x, bb.y + offset.y)),
        geo(offset)
    )
}

private fun rectangleLengthMeters(points: List<LatLng>): Double {
    if (points.size < 3) return 0.0
    val rect = rectangleFromControlPoints(points)
    return if (rect.size >= 4) haversineMeters(rect[0], rect[3]) else 0.0
}

private fun findLineGeometryAtScreen(
    map: MapLibreMap,
    point: LatLng,
    geometries: List<CommittedGeometry>,
    tolerancePx: Double = 45.0
): Int? {
    val tap = map.projection.toScreenLocation(point)
    var bestIndex: Int? = null
    var bestDistance = Double.POSITIVE_INFINITY

    geometries.forEachIndexed { index, geometry ->
        if (!geometrySupportsParallel(geometry)) return@forEachIndexed
        val path = geometryPath(geometry)
        if (path.size < 2) return@forEachIndexed

        for (i in 0 until path.lastIndex) {
            val a = map.projection.toScreenLocation(path[i])
            val b = map.projection.toScreenLocation(path[i + 1])
            val dx = (b.x - a.x).toDouble()
            val dy = (b.y - a.y).toDouble()
            val denom = dx * dx + dy * dy
            val t = if (denom <= 1e-9) 0.0 else (
                ((tap.x - a.x) * dx + (tap.y - a.y) * dy) / denom
            ).coerceIn(0.0, 1.0)
            val px = a.x + t * dx
            val py = a.y + t * dy
            val d = hypot((tap.x - px).toDouble(), (tap.y - py).toDouble())
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = index
            }
        }
    }
    return if (bestDistance <= tolerancePx) bestIndex else null
}

private fun findLineGeometryAt(
    point: LatLng,
    geometries: List<CommittedGeometry>,
    toleranceM: Double = 25.0
): Int? {
    var bestIndex: Int? = null
    var bestDistance = Double.POSITIVE_INFINITY
    geometries.forEachIndexed { index, geometry ->
        if (!geometrySupportsParallel(geometry)) return@forEachIndexed
        val path = geometryPath(geometry)
        if (path.size < 2) return@forEachIndexed
        val distance = distanceToPathMeters(point, path)
        if (distance <= toleranceM && distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}

private fun geometryPath(geometry: CommittedGeometry): List<LatLng> = when (geometry.tool) {
    MapFieldTool.RECTANGLE -> if (geometry.points.size >= 3) {
        rectangleFromControlPoints(geometry.points)
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

private fun findGeometryAtScreen(
    map: MapLibreMap,
    point: LatLng,
    geometries: List<CommittedGeometry>,
    tolerancePx: Double = 36.0
): Int? {
    val tap = map.projection.toScreenLocation(point)
    var bestIndex: Int? = null
    var bestDistance = Double.POSITIVE_INFINITY

    geometries.forEachIndexed { index, geometry ->
        val path = geometryPath(geometry)
        if (path.isEmpty()) return@forEachIndexed
        val testPath = if (geometrySupportsArea(geometry) && path.size >= 3) path + path.first() else path

        if (testPath.size == 1) {
            val p = map.projection.toScreenLocation(testPath.first())
            val d = hypot((tap.x - p.x).toDouble(), (tap.y - p.y).toDouble())
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = index
            }
        } else {
            for (i in 0 until testPath.lastIndex) {
                val a = map.projection.toScreenLocation(testPath[i])
                val b = map.projection.toScreenLocation(testPath[i + 1])
                val dx = (b.x - a.x).toDouble()
                val dy = (b.y - a.y).toDouble()
                val denom = dx * dx + dy * dy
                val t = if (denom <= 1e-9) 0.0 else (
                    ((tap.x - a.x) * dx + (tap.y - a.y) * dy) / denom
                ).coerceIn(0.0, 1.0)
                val px = a.x + t * dx
                val py = a.y + t * dy
                val d = hypot((tap.x - px).toDouble(), (tap.y - py).toDouble())
                if (d < bestDistance) {
                    bestDistance = d
                    bestIndex = index
                }
            }
        }
    }

    if (bestIndex != null && bestDistance <= tolerancePx) return bestIndex

    // Closed shapes can also be selected by tapping anywhere inside them.
    for (index in geometries.indices.reversed()) {
        val geometry = geometries[index]
        val path = geometryPath(geometry)
        if (geometrySupportsArea(geometry) && path.size >= 3 && pointInPolygon(point, path)) {
            return index
        }
    }
    return null
}
private fun findGeometryAt(point: LatLng, geometries: List<CommittedGeometry>): Int? {
    var nearestIndex: Int? = null
    var nearestDistance = Double.POSITIVE_INFINITY

    // Prioritize what the user actually taps: a visible border or line.
    geometries.forEachIndexed { index, geometry ->
        val path = geometryPath(geometry)
        if (path.isEmpty()) return@forEachIndexed
        val testPath = if (geometrySupportsArea(geometry) && path.size >= 3) path + path.first() else path
        val distance = distanceToPathMeters(point, testPath)
        if (distance <= 20.0 && distance < nearestDistance) {
            nearestDistance = distance
            nearestIndex = index
        }
    }
    if (nearestIndex != null) return nearestIndex

    // If no border was touched, allow selecting a closed figure by tapping inside it.
    for (index in geometries.indices.reversed()) {
        val geometry = geometries[index]
        val path = geometryPath(geometry)
        if (geometrySupportsArea(geometry) && path.size >= 3 && pointInPolygon(point, path)) {
            return index
        }
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
    if (polygon.size < 3 || parts < 2) return listOf(polygon)
    val (xy, meanLat) = toXY(polygon)

    val candidates = mutableListOf<Pair<Double, Double>>()
    val dx = (xy.maxOfOrNull { it.x } ?: 0.0) - (xy.minOfOrNull { it.x } ?: 0.0)
    val dy = (xy.maxOfOrNull { it.y } ?: 0.0) - (xy.minOfOrNull { it.y } ?: 0.0)
    if (dx >= dy) {
        candidates += 1.0 to 0.0
        candidates += 0.0 to 1.0
    } else {
        candidates += 0.0 to 1.0
        candidates += 1.0 to 0.0
    }

    // Also try directions of the parcel edges. This helps concave parcels
    // produce contiguous lots instead of fragmented strips.
    for (i in xy.indices) {
        val a = xy[i]
        val b = xy[(i + 1) % xy.size]
        val ex = b.x - a.x
        val ey = b.y - a.y
        if (hypot(ex, ey) > 0.01) candidates += ex to ey
    }

    var best: List<List<LatLng>>? = null
    var bestError = Double.POSITIVE_INFINITY
    for ((ax, ay) in candidates) {
        val attempt = divideEqualAreaJts(xy, meanLat, parts, ax, ay) ?: continue
        if (attempt.size != parts) continue
        val areas = attempt.map(::polygonAreaMeters2)
        val target = polygonAreaMeters2(polygon) / parts.toDouble()
        if (target <= 0.0) continue
        val maxRelativeError = areas.maxOf { abs(it - target) / target }
        val coveredError = abs(areas.sum() - polygonAreaMeters2(polygon)) / polygonAreaMeters2(polygon)
        val score = maxRelativeError + coveredError
        if (score < bestError) {
            bestError = score
            best = attempt
        }
        if (maxRelativeError <= 0.001 && coveredError <= 0.001) break
    }

    return best ?: divideAxis(polygon, parts, 1.0, 0.0, true)
}

private fun divideEqualAreaJts(
    xy: List<XY>,
    meanLat: Double,
    parts: Int,
    axisX: Double,
    axisY: Double
): List<List<LatLng>>? {
    val len = hypot(axisX, axisY)
    if (len <= 1e-9) return null
    val ux = axisX / len
    val uy = axisY / len
    val vx = -uy
    val vy = ux

    fun toUV(p: XY): Coordinate = Coordinate(
        p.x * ux + p.y * uy,
        p.x * vx + p.y * vy
    )
    fun uvToXY(c: Coordinate): XY = XY(
        c.x * ux + c.y * vx,
        c.x * uy + c.y * vy
    )

    val factory = GeometryFactory()
    val ring = (xy.map(::toUV) + toUV(xy.first())).toTypedArray()
    val source = runCatching {
        factory.createPolygon(factory.createLinearRing(ring))
    }.getOrNull() ?: return null
    if (!source.isValid || source.area <= 1e-8) return null

    val env = source.envelopeInternal
    val pad = max(env.width, env.height).coerceAtLeast(1.0) * 4.0
    fun slab(minU: Double, maxU: Double): Polygon {
        val minV = env.minY - pad
        val maxV = env.maxY + pad
        val coords = arrayOf(
            Coordinate(minU, minV),
            Coordinate(maxU, minV),
            Coordinate(maxU, maxV),
            Coordinate(minU, maxV),
            Coordinate(minU, minV)
        )
        return factory.createPolygon(factory.createLinearRing(coords))
    }

    val totalArea = source.area
    val target = totalArea / parts.toDouble()
    val cuts = mutableListOf(env.minX)

    try {
        for (k in 1 until parts) {
            val wanted = target * k
            var lo = env.minX
            var hi = env.maxX
            repeat(70) {
                val mid = (lo + hi) / 2.0
                val clipped = source.intersection(slab(env.minX - pad, mid))
                if (clipped.area < wanted) lo = mid else hi = mid
            }
            cuts += (lo + hi) / 2.0
        }
        cuts += env.maxX

        val out = mutableListOf<List<LatLng>>()
        for (i in 0 until parts) {
            val geom = source.intersection(slab(cuts[i] - if (i == 0) pad else 0.0, cuts[i + 1] + if (i == parts - 1) pad else 0.0))
            val poly = singlePolygonOrNull(geom) ?: return null
            val coords = poly.exteriorRing.coordinates
            if (coords.size < 4) return null
            val pieceXY = coords.dropLast(1).map(::uvToXY)
            out += fromXY(pieceXY, meanLat)
        }
        return out
    } catch (_: TopologyException) {
        return null
    }
}

private fun singlePolygonOrNull(geometry: Geometry): Polygon? {
    if (geometry.isEmpty) return null
    if (geometry is Polygon) return geometry
    if (geometry.numGeometries == 1 && geometry.getGeometryN(0) is Polygon) {
        return geometry.getGeometryN(0) as Polygon
    }
    return null
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
    val areas = pieces.map(::polygonAreaMeters2)
    val avg = areas.average()
    val spread = if (avg > 0.0) (areas.maxOrNull()!! - areas.minOrNull()!!) / avg * 100.0 else 0.0
    val equality = if (title.contains("iguales", ignoreCase = true)) " • diferencia máx. %.3f%%".format(spread) else ""
    return title + equality + " • " + pieces.mapIndexed { index, p ->
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
    LINE("Línea / polilínea", "Marque dos o más puntos. Puede crear una línea quebrada abierta con varios vértices.", "╱"),
    DISTANCE("Medir distancia", "Toque dos o más puntos. Se mostrará la distancia acumulada.", "↔ m"),
    AREA("Medir área", "Toque tres o más puntos para formar el área.", "A²"),
    PERIMETER("Medir perímetro", "Seleccione una figura cerrada existente o marque tres o más vértices.", "▱"),
    POLYGON("Crear polígono", "Toque tres o más vértices para dibujar el polígono.", "⬡"),
    DIVIDE("Dividir polígono", "Seleccione un polígono existente o márquelo por puntos; la división requiere un polígono cerrado.", "▭┆"),
    DIVIDE_LINE("Línea de división", "Toque dos puntos para definir la línea de corte.", "┆"),
    RECTANGLE("Rectángulo / cuadrado", "Marque dos puntos para el ancho y un tercer punto para definir el largo.", "▭"),
    CIRCLE("Círculo", "Toque el centro; luego indique radio o diámetro.", "○"),
    PARALLEL("Línea paralela", "Seleccione una línea o polilínea guardada; la paralela conservará su misma forma y quiebres.", "∥")
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
                map.addPolyline(PolylineOptions().addAll(points).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
                return "Línea: %.2f m".format(polylineDistanceMeters(points))
            }
            return "Marque otro punto para continuar la línea."
        }

        MapFieldTool.DISTANCE -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
                return "Distancia: %.2f m".format(polylineDistanceMeters(points))
            }
            return "Marque el siguiente punto."
        }

        MapFieldTool.AREA -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
            }
            if (points.size >= 3) {
                drawTransparentPolygon(map, points)
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
                map.addPolyline(PolylineOptions().addAll(closed).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
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
                drawTransparentPolygon(map, points)
                val perimeter = polylineDistanceMeters(points + points.first())
                val area = polygonAreaMeters2(points)
                return "Polígono: área %.2f m² • perímetro %.2f m".format(area, perimeter)
            }
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
            }
            return "Marque al menos 3 vértices."
        }

        MapFieldTool.DIVIDE -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
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
                map.addPolyline(PolylineOptions().addAll(points.take(2)).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
                return "Línea de división trazada. La vista muestra el corte manual."
            }
            return "Marque el segundo punto de la línea de división."
        }

        MapFieldTool.RECTANGLE -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points.take(2)).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
                val width = haversineMeters(points[0], points[1])
                if (points.size == 2) {
                    return "Ancho: %.2f m • Marque el tercer punto para definir el largo.".format(width)
                }
                val rect = rectangleFromControlPoints(points)
                drawTransparentPolygon(map, rect)
                val length = rectangleLengthMeters(points)
                return "Rectángulo: ancho %.2f m • largo %.2f m • área %.2f m²".format(
                    width,
                    length,
                    polygonAreaMeters2(rect)
                )
            }
            return "Marque el segundo punto para definir el ancho."
        }

        MapFieldTool.CIRCLE -> {
            points.firstOrNull()?.let { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                val center = points[0]
                val radius = haversineMeters(center, points[1])
                val circle = circlePolygon(center, radius, 64)
                drawTransparentPolygon(map, circle)
                return "Círculo: radio %.2f m • área %.2f m²".format(
                    radius,
                    PI * radius * radius
                )
            }
            return "Centro marcado. Indique radio o diámetro."
        }

        MapFieldTool.PARALLEL -> {
            points.forEach { map.addMarker(MarkerOptions().position(it)) }
            if (points.size >= 2) {
                val base = points.take(2)
                map.addPolyline(PolylineOptions().addAll(base).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
                val parallel = parallelLine(base[0], base[1], parallelOffsetM)
                map.addPolyline(PolylineOptions().addAll(parallel).width(6f).color(android.graphics.Color.rgb(103, 58, 183)))
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
