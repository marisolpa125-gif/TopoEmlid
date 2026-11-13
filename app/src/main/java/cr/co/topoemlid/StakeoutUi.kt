package cr.co.topoemlid

import android.os.SystemClock
import android.media.AudioManager
import android.media.ToneGenerator
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.LineString
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import kotlin.math.*

private data class LineStakeoutProjection(
    val segmentIndex: Int,
    val distanceM: Double,
    val northM: Double,
    val eastM: Double,
    val alongSegmentM: Double,
    val footLat: Double,
    val footLon: Double
)

enum class StakeoutViewMode(val label: String) {
    MAP("Vista de mapa"),
    CLOSE("Vista cercana"),
    PRECISION("Vista mira")
}

enum class StakeoutMode(val label: String, val description: String) {
    POINT("Punto", "Replantear un punto levantado o ingresado"),
    LINE("Línea", "Replantear sobre una línea entre dos puntos"),
    PARALLEL("Línea paralela", "Crear y replantear un offset paralelo a una línea base"),
    POLYLINE("Polilínea", "Replantear a lo largo de varios segmentos"),
    INTERSECTION("Intersección", "Replantear la intersección de dos líneas"),
    BEARING_DISTANCE("Rumbo y distancia", "Crear un objetivo a partir de un punto, rumbo y distancia")
}

@Composable
fun StakeoutScreen(
    project: TopoProject?,
    gnss: GnssStatus,
    ntrip: NtripLiveStatus,
    selectedPointId: String? = null,
    onSelectedPointIdChanged: (String?) -> Unit = {},
    onActiveChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val pointStore = remember(project?.id) { SurveyPointStore(context) }
    val points = remember(project?.id) {
        project?.let { pointStore.load(it.id) } ?: emptyList()
    }
    val geometries = remember(project?.id) {
        project?.id?.let { loadCommittedGeometries(context, it) } ?: emptyList()
    }

    var mode by remember { mutableStateOf(StakeoutMode.POINT) }
    var selectedGeometryId by remember { mutableStateOf<String?>(null) }
    var showMapPicker by remember { mutableStateOf(false) }
    var targetToleranceM by rememberSaveable { mutableDoubleStateOf(0.02) }
    var startPointId by remember { mutableStateOf<String?>(null) }
    var endPointId by remember { mutableStateOf<String?>(null) }
    var offsetText by remember { mutableStateOf("1.00") }
    var bearingText by remember { mutableStateOf("") }
    var distanceText by remember { mutableStateOf("") }
    var showGuidance by remember { mutableStateOf(false) }

    LaunchedEffect(showGuidance) {
        onActiveChanged(showGuidance)
    }

    DisposableEffect(Unit) {
        onDispose { onActiveChanged(false) }
    }

    val pointDisplayStore = remember { PointDisplaySettingsStore(context) }
    var pointDisplaySettings by remember { mutableStateOf(pointDisplayStore.load()) }
    var showPointDisplayPanel by remember { mutableStateOf(false) }

    val selectedTarget = points.firstOrNull { it.id == selectedPointId }
    val selectedGeometry = geometries.firstOrNull { it.id == selectedGeometryId }
    val currentStakeoutDistanceM = run {
        val lat = gnss.latitude
        val lon = gnss.longitude
        val tLat = selectedTarget?.latitude
        val tLon = selectedTarget?.longitude
        if (lat != null && lon != null && tLat != null && tLon != null) {
            val north = (tLat - lat) * 111132.0
            val east = (tLon - lon) * (111320.0 * cos(Math.toRadians(tLat)))
            hypot(north, east)
        } else null
    }
    // Proyección al punto más cercano de un segmento, con distancia métrica local.
    val lineVertices = selectedGeometry?.let { geometryPath(it) }.orEmpty()
    val lineSegments = if (lineVertices.size >= 2) {
        lineVertices.zipWithNext() + if (selectedGeometry != null &&
            geometrySupportsArea(selectedGeometry) && lineVertices.size > 2)
            listOf(lineVertices.last() to lineVertices.first()) else emptyList()
    } else emptyList()
    val nearestLine = if (gnss.latitude != null && gnss.longitude != null) {
        val lat0 = gnss.latitude!!
        val lon0 = gnss.longitude!!
        val scaleE = 111320.0 * cos(Math.toRadians(lat0))
        lineSegments.mapIndexed { index, pair ->
            val ax = (pair.first.longitude - lon0) * scaleE
            val ay = (pair.first.latitude - lat0) * 111132.0
            val bx = (pair.second.longitude - lon0) * scaleE
            val by = (pair.second.latitude - lat0) * 111132.0
            val vx = bx - ax
            val vy = by - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 > 0.000001) ((-ax) * vx + (-ay) * vy) / len2 else 0.0
            val clamped = t.coerceIn(0.0, 1.0)
            val ex = ax + clamped * vx
            val ny = ay + clamped * vy
            LineStakeoutProjection(index + 1, hypot(ex, ny), ny, ex,
                hypot(vx, vy) * clamped,
                lat0 + ny / 111132.0, lon0 + ex / scaleE)
        }.minByOrNull { it.distanceM }
    } else null
    val activeDistanceM = if (mode == StakeoutMode.POINT) currentStakeoutDistanceM else nearestLine?.distanceM
    val latestStakeoutDistance by rememberUpdatedState(activeDistanceM)
    val latestGnssSolution by rememberUpdatedState(gnss.solution)
    val latestGnssConnected by rememberUpdatedState(gnss.connected)
    val latestTolerance by rememberUpdatedState(targetToleranceM)
    val toneGenerator = remember {
        ToneGenerator(AudioManager.STREAM_MUSIC, 85)
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { toneGenerator.stopTone() }
            runCatching { toneGenerator.release() }
        }
    }

    LaunchedEffect(showGuidance, mode, selectedPointId, selectedGeometryId) {
        runCatching { toneGenerator.stopTone() }
        if (!showGuidance || (mode == StakeoutMode.POINT && selectedPointId == null)) return@LaunchedEffect

        var continuousCenterTone = false
        try {
            while (true) {
                val distance = latestStakeoutDistance
                val isFix = latestGnssConnected &&
                    latestGnssSolution.contains("FIX", ignoreCase = true) &&
                    !latestGnssSolution.contains("NO FIX", ignoreCase = true)
                val inside = distance != null && distance <= latestTolerance && isFix
                if (inside) {
                    if (!continuousCenterTone) {
                        toneGenerator.startTone(ToneGenerator.TONE_DTMF_0)
                        continuousCenterTone = true
                    }
                    delay(80)
                    continue
                }
                if (continuousCenterTone) {
                    toneGenerator.stopTone()
                    continuousCenterTone = false
                }
                // Conservar las voces GNSS existentes: no añadir avisos para FLOAT.
                if (distance == null || !latestGnssConnected ||
                    latestGnssSolution.contains("FLOAT", ignoreCase = true)) {
                    delay(200)
                    continue
                }
                when {
                    distance <= 0.05 -> {
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
                        delay(150)
                    }
                    distance <= 0.50 -> {
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                        delay(230)
                    }
                    distance <= 2.0 -> {
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
                        delay(400)
                    }
                    distance <= 5.0 -> {
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
                        delay(650)
                    }
                    distance <= 20.0 -> {
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
                        delay(900)
                    }
                    else -> {
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                        delay(1250)
                    }
                }
            }
        } finally {
            runCatching { toneGenerator.stopTone() }
        }
    }

    if (showMapPicker) {
        StakeoutMapPicker(
            project = project,
            points = points,
            geometries = geometries,
            gnss = gnss,
            pointDisplaySettings = pointDisplaySettings,
            selectedPointId = selectedPointId,
            selectedGeometryId = selectedGeometryId,
            onPointSelected = { id ->
                onSelectedPointIdChanged(id)
                selectedGeometryId = null
                mode = StakeoutMode.POINT
            },
            onGeometrySelected = { id, tool ->
                selectedGeometryId = id
                onSelectedPointIdChanged(null)
                mode = when (tool) {
                    MapFieldTool.LINE, MapFieldTool.DISTANCE -> StakeoutMode.LINE
                    MapFieldTool.PARALLEL -> StakeoutMode.PARALLEL
                    else -> StakeoutMode.POLYLINE
                }
            },
            onUseSelection = { showMapPicker = false },
            onCancel = { showMapPicker = false }
        )
        return
    }

    if (showPointDisplayPanel) {
        PointDisplaySettingsSheet(
            value = pointDisplaySettings,
            onChange = { updated ->
                pointDisplaySettings = updated
                pointDisplayStore.save(updated)
            },
            onDismiss = { showPointDisplayPanel = false }
        )
    }

    if (showGuidance && mode == StakeoutMode.POINT && selectedTarget != null) {
        StakeoutActiveView(
            project = project,
            points = points,
            geometries = geometries,
            target = selectedTarget,
            gnss = gnss,
            pointDisplaySettings = pointDisplaySettings,
            ntrip = ntrip,
            onTargetSelected = { id -> onSelectedPointIdChanged(id) },
            toleranceM = targetToleranceM,
            onToleranceChanged = { targetToleranceM = it },
            onStop = {
                onActiveChanged(false)
                showGuidance = false
                runCatching { toneGenerator.stopTone() }
            }
        )
        return
    }

    if (showGuidance && mode != StakeoutMode.POINT && selectedGeometry != null) {
        Column(Modifier.fillMaxSize()) {
            StakeoutMapPreview(project, points, geometries, null, gnss, pointDisplaySettings,
                modifier = Modifier.weight(1f),
                perpendicularFoot = nearestLine?.let { LatLng(it.footLat, it.footLon) })
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Replanteo de línea / perímetro", fontWeight = FontWeight.Bold)
                    Text("Distancia perpendicular: " +
                        (nearestLine?.let { "%.3f m".format(it.distanceM) } ?: "Sin posición"))
                    Text("Tramo: " + (nearestLine?.segmentIndex ?: "—"))
                    Text("Desde inicio del tramo: " +
                        (nearestLine?.let { "%.3f m".format(it.alongSegmentM) } ?: "—"))
                    Text("Norte / Sur: " +
                        (nearestLine?.let { "%.3f m".format(it.northM) } ?: "—"))
                    Text("Este / Oeste: " +
                        (nearestLine?.let { "%.3f m".format(it.eastM) } ?: "—"))
                    Button(onClick = { showGuidance = false; onActiveChanged(false);
                        runCatching { toneGenerator.stopTone() } }) { Text("Finalizar") }
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column {
                Text("Replanteo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(project?.name ?: "Sin proyecto activo", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showMapPicker = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text("⌖ Escoger del mapa")
                }
                OutlinedButton(
                    onClick = { showPointDisplayPanel = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text("👁 Visualización")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Tipo de replanteo", fontWeight = FontWeight.Bold)

        StakeoutMode.entries.forEach { item ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        if (mode != item) {
                            onActiveChanged(false)
                            showGuidance = false
                            runCatching { toneGenerator.stopTone() }
                        }
                        mode = item
                    }
            ) {
                Row(Modifier.padding(12.dp)) {
                    RadioButton(
                        selected = mode == item,
                        onClick = {
                            if (mode != item) {
                                showGuidance = false
                                runCatching { toneGenerator.stopTone() }
                            }
                            mode = item
                        }
                    )
                    Column {
                        Text(item.label, fontWeight = FontWeight.Bold)
                        Text(item.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when (mode) {
            StakeoutMode.POINT -> {
                Text("Punto objetivo", fontWeight = FontWeight.Bold)
                if (points.isEmpty()) {
                    Text("No hay puntos guardados en este proyecto.")
                } else {
                    points.forEach { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelectedPointIdChanged(p.id) }
                                .padding(vertical = 6.dp)
                        ) {
                            RadioButton(
                                selected = selectedPointId == p.id,
                                onClick = { onSelectedPointIdChanged(p.id) }
                            )
                            Column {
                                Text("Punto ${p.pointNumber}")
                                Text(p.description.ifBlank { p.code.ifBlank { "Sin descripción" } }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            StakeoutMode.LINE, StakeoutMode.PARALLEL -> {
                PointPairSelector(
                    points = points,
                    startPointId = startPointId,
                    endPointId = endPointId,
                    onStart = { startPointId = it },
                    onEnd = { endPointId = it }
                )
                if (mode == StakeoutMode.PARALLEL) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = offsetText,
                        onValueChange = { offsetText = it },
                        label = { Text("Offset paralelo (m)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            StakeoutMode.POLYLINE -> {
                Text("Polilínea", fontWeight = FontWeight.Bold)
                Text("Aquí podrás seleccionar varios puntos o una polilínea de una capa del proyecto.")
            }

            StakeoutMode.INTERSECTION -> {
                Text("Intersección de líneas", fontWeight = FontWeight.Bold)
                Text("Aquí podrás seleccionar dos líneas existentes y replantear su punto de cruce.")
            }

            StakeoutMode.BEARING_DISTANCE -> {
                Text("Rumbo y distancia", fontWeight = FontWeight.Bold)
                PointSingleSelector(points, selectedPointId) { onSelectedPointIdChanged(it) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = bearingText,
                    onValueChange = { bearingText = it },
                    label = { Text("Rumbo / azimut") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = distanceText,
                    onValueChange = { distanceText = it },
                    label = { Text("Distancia (m)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        selectedGeometry?.let { geometry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Elemento escogido del mapa", fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            geometrySupportsArea(geometry) -> "Polígono / figura cerrada"
                            geometry.tool == MapFieldTool.LINE || geometry.tool == MapFieldTool.DISTANCE -> "Línea"
                            geometry.tool == MapFieldTool.PARALLEL -> "Línea paralela"
                            else -> "Geometría"
                        }
                    )
                    Text(
                        "Vértices: " + geometryPath(geometry).size,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Estado GNSS", fontWeight = FontWeight.Bold)
                Text(if (gnss.connected) gnss.solution else "SIN RECEPTOR")
                Text("Satélites: ${gnss.satellites ?: "—"}")
                Text("Precisión H: ${gnss.horizontalAccuracyM?.let { "%.3f m".format(it) } ?: "—"}")
                Text("Precisión V: ${gnss.verticalAccuracyM?.let { "%.3f m".format(it) } ?: "—"}")
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (showGuidance) {
                    onActiveChanged(false)
                    showGuidance = false
                    runCatching { toneGenerator.stopTone() }
                } else {
                    onActiveChanged(true)
                    showGuidance = true
                }
            },
            enabled = project != null && gnss.connected &&
                (
                    (mode == StakeoutMode.POINT && selectedTarget != null) ||
                    (mode != StakeoutMode.POINT && selectedGeometry != null)
                ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showGuidance) "Detener replanteo" else "Iniciar replanteo")
        }

        if (!gnss.connected) {
            Text(
                "Conecte el receptor para iniciar navegación de replanteo.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}


private fun ensureStakeoutPickerOverlayOnTop(
    map: MapLibreMap,
    points: List<SurveyPoint>,
    geometries: List<CommittedGeometry>,
    selectedPointId: String?,
    selectedGeometryId: String?
) {
    val style = map.style ?: return

    fun upsertSource(id: String, features: List<Feature>) {
        val existing = style.getSourceAs<GeoJsonSource>(id)
        if (existing == null) {
            style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(features)))
        } else {
            existing.setGeoJson(FeatureCollection.fromFeatures(features))
        }
    }

    // Geometrías normales y seleccionada, siempre arriba de WMS/XYZ.
    val normalGeometryFeatures = mutableListOf<Feature>()
    val selectedGeometryFeatures = mutableListOf<Feature>()

    geometries.forEach { geometry ->
        val path = geometryPath(geometry)
        if (path.isEmpty()) return@forEach
        val drawPath = if (geometrySupportsArea(geometry) && path.size >= 3) {
            path + path.first()
        } else {
            path
        }
        if (drawPath.size >= 2) {
            val feature = Feature.fromGeometry(
                LineString.fromLngLats(
                    drawPath.map { Point.fromLngLat(it.longitude, it.latitude) }
                )
            )
            if (geometry.id == selectedGeometryId) {
                selectedGeometryFeatures += feature
            } else {
                normalGeometryFeatures += feature
            }
        }
    }

    val normalGeometrySource = "stakeout-picker-geometry-source"
    val normalGeometryLayer = "stakeout-picker-geometry-layer"
    val selectedGeometrySource = "stakeout-picker-selected-geometry-source"
    val selectedGeometryLayer = "stakeout-picker-selected-geometry-layer"

    upsertSource(normalGeometrySource, normalGeometryFeatures)
    upsertSource(selectedGeometrySource, selectedGeometryFeatures)

    runCatching { style.removeLayer(normalGeometryLayer) }
    style.addLayer(
        LineLayer(normalGeometryLayer, normalGeometrySource).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.rgb(103, 58, 183)),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineOpacity(1f)
        )
    )

    runCatching { style.removeLayer(selectedGeometryLayer) }
    style.addLayer(
        LineLayer(selectedGeometryLayer, selectedGeometrySource).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.rgb(255, 193, 7)),
            PropertyFactory.lineWidth(10f),
            PropertyFactory.lineOpacity(1f)
        )
    )

    // Puntos normales y punto seleccionado.
    val normalPointFeatures = mutableListOf<Feature>()
    val selectedPointFeatures = mutableListOf<Feature>()

    points.forEach { surveyPoint ->
        val lat = surveyPoint.latitude ?: return@forEach
        val lon = surveyPoint.longitude ?: return@forEach
        val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
        if (surveyPoint.id == selectedPointId) {
            selectedPointFeatures += feature
        } else {
            normalPointFeatures += feature
        }
    }

    val normalPointSource = "stakeout-picker-point-source"
    val normalPointLayer = "stakeout-picker-point-layer"
    val selectedPointSource = "stakeout-picker-selected-point-source"
    val selectedPointLayer = "stakeout-picker-selected-point-layer"

    upsertSource(normalPointSource, normalPointFeatures)
    upsertSource(selectedPointSource, selectedPointFeatures)

    runCatching { style.removeLayer(normalPointLayer) }
    style.addLayer(
        CircleLayer(normalPointLayer, normalPointSource).withProperties(
            PropertyFactory.circleColor(android.graphics.Color.rgb(211, 47, 47)),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleRadius(9f)
        )
    )

    runCatching { style.removeLayer(selectedPointLayer) }
    style.addLayer(
        CircleLayer(selectedPointLayer, selectedPointSource).withProperties(
            PropertyFactory.circleColor(android.graphics.Color.rgb(255, 193, 7)),
            PropertyFactory.circleStrokeColor(android.graphics.Color.BLACK),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleRadius(13f)
        )
    )
}

@Composable
private fun StakeoutMapPicker(
    project: TopoProject?,
    points: List<SurveyPoint>,
    geometries: List<CommittedGeometry>,
    gnss: GnssStatus,
    pointDisplaySettings: PointDisplaySettings,
    selectedPointId: String?,
    selectedGeometryId: String?,
    onPointSelected: (String) -> Unit,
    onGeometrySelected: (String, MapFieldTool) -> Unit,
    onUseSelection: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val layerStore = remember(project?.id) { LayerStore(context) }
    val basemapStore = remember(project?.id) { BasemapStore(context) }

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

    val layers = remember(project?.id) { loadEffectiveLayers() }
    val basemap = remember(project?.id) { basemapStore.selected(project?.id) }
    var localSelectedPointId by remember(selectedPointId) { mutableStateOf(selectedPointId) }
    var localSelectedGeometryId by remember(selectedGeometryId) { mutableStateOf(selectedGeometryId) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }

    fun redraw(map: MapLibreMap) {
        map.clear()

        val iconFactory = IconFactory.getInstance(context)
        val normalPointIcon = iconFactory.fromBitmap(
            makeTopoPointBitmap(android.graphics.Color.rgb(211, 47, 47))
        )
        val selectedPointIcon = iconFactory.fromBitmap(makeStakeoutTargetBitmap())
        val receiverColor = when {
            gnss.solution.contains("FIX", ignoreCase = true) ->
                android.graphics.Color.rgb(46, 125, 50)
            gnss.solution.contains("FLOAT", ignoreCase = true) ->
                android.graphics.Color.rgb(251, 192, 45)
            else ->
                android.graphics.Color.rgb(211, 47, 47)
        }
        val receiverIcon = iconFactory.fromBitmap(makeTopoPointBitmap(receiverColor))

        geometries.forEach { geometry ->
            val path = geometryPath(geometry)
            if (path.isEmpty()) return@forEach
            val selected = geometry.id == localSelectedGeometryId
            val color = if (selected) {
                android.graphics.Color.rgb(255, 193, 7)
            } else {
                android.graphics.Color.rgb(103, 58, 183)
            }
            val drawPath = if (geometrySupportsArea(geometry) && path.size >= 3) {
                path + path.first()
            } else {
                path
            }
            if (drawPath.size >= 2) {
                map.addPolyline(
                    PolylineOptions()
                        .addAll(drawPath)
                        .width(if (selected) 10f else 6f)
                        .color(color)
                )
            } else {
                map.addMarker(
                    MarkerOptions()
                        .position(drawPath.first())
                        .icon(if (selected) selectedPointIcon else normalPointIcon)
                )
            }
        }

        points.forEach { surveyPoint ->
            val lat = surveyPoint.latitude ?: return@forEach
            val lon = surveyPoint.longitude ?: return@forEach
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(lat, lon))
                    .icon(if (surveyPoint.id == localSelectedPointId) selectedPointIcon else normalPointIcon)
                    .title("Punto " + surveyPoint.pointNumber)
            )
        }

        if (gnss.connected && gnss.latitude != null && gnss.longitude != null) {
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(gnss.latitude!!, gnss.longitude!!))
                    .icon(receiverIcon)
                    .title("RTK • posición actual")
            )
        }

        map.style?.let { style ->
            ensureTopoSurveyPointLayers(
                style = style,
                prefix = "stakeout-points-picker",
                points = points,
                settings = pointDisplaySettings,
                selectedPointId = localSelectedPointId
            )
        }

        ensureStakeoutPickerOverlayOnTop(
            map = map,
            points = points,
            geometries = geometries,
            selectedPointId = localSelectedPointId,
            selectedGeometryId = localSelectedGeometryId
        )
    }

    fun findPointAtScreen(map: MapLibreMap, tapped: LatLng, tolerancePx: Double = 42.0): SurveyPoint? {
        val tap = map.projection.toScreenLocation(tapped)
        return points.mapNotNull { surveyPoint ->
            val lat = surveyPoint.latitude ?: return@mapNotNull null
            val lon = surveyPoint.longitude ?: return@mapNotNull null
            val screen = map.projection.toScreenLocation(LatLng(lat, lon))
            val d = hypot(
                (tap.x - screen.x).toDouble(),
                (tap.y - screen.y).toDouble()
            )
            surveyPoint to d
        }.filter { it.second <= tolerancePx }
            .minByOrNull { it.second }
            ?.first
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapContext ->
                MapView(mapContext).apply {
                    onCreate(null)
                    onStart()
                    onResume()
                    getMapAsync { map ->
                        mapRef = map

                        val center = when {
                            gnss.latitude != null && gnss.longitude != null ->
                                LatLng(gnss.latitude!!, gnss.longitude!!)
                            points.firstOrNull { it.latitude != null && it.longitude != null } != null -> {
                                val p = points.first { it.latitude != null && it.longitude != null }
                                LatLng(p.latitude!!, p.longitude!!)
                            }
                            geometries.firstOrNull()?.let { geometryPath(it).firstOrNull() } != null ->
                                geometryPath(geometries.first()).first()
                            else -> LatLng(9.93, -84.08)
                        }

                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, 18.0))

                        val baseStyle = Style.Builder().fromJson(
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
                            addSelectedBasemap(style, basemap)
                            addProjectRasterLayers(style, layers)
                            refreshViewportWmsLayers(map, layers) { redraw(map) }
                            redraw(map)
                        }

                        map.addOnCameraIdleListener {
                            refreshViewportWmsLayers(map, layers) { redraw(map) }
                        }

                        map.addOnMapClickListener { tapped ->
                            val foundPoint = findPointAtScreen(map, tapped)
                            if (foundPoint != null) {
                                localSelectedPointId = foundPoint.id
                                localSelectedGeometryId = null
                                onPointSelected(foundPoint.id)
                                redraw(map)
                                true
                            } else {
                                val geometryIndex = findGeometryAtScreen(
                                    map = map,
                                    point = tapped,
                                    geometries = geometries,
                                    tolerancePx = 44.0
                                )
                                val geometry = geometryIndex?.let { geometries.getOrNull(it) }
                                if (geometry != null) {
                                    localSelectedPointId = null
                                    localSelectedGeometryId = geometry.id
                                    onGeometrySelected(geometry.id, geometry.tool)
                                    redraw(map)
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    }
                }
            },
            update = {
                mapRef?.let { redraw(it) }
            }
        )

        Surface(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopCenter)
                .fillMaxWidth()
                .padding(10.dp),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Escoger del mapa", fontWeight = FontWeight.Bold)
                Text(
                    "Toque un punto, línea o figura. El elemento seleccionado se resalta en amarillo.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(10.dp),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Column(Modifier.padding(12.dp)) {
                val selectedText = when {
                    localSelectedPointId != null -> {
                        val p = points.firstOrNull { it.id == localSelectedPointId }
                        "Seleccionado: Punto " + (p?.pointNumber ?: "")
                    }
                    localSelectedGeometryId != null -> {
                        val g = geometries.firstOrNull { it.id == localSelectedGeometryId }
                        "Seleccionado: " + when {
                            g == null -> "Geometría"
                            geometrySupportsArea(g) -> "Polígono / figura cerrada"
                            g.tool == MapFieldTool.LINE || g.tool == MapFieldTool.DISTANCE -> "Línea"
                            g.tool == MapFieldTool.PARALLEL -> "Línea paralela"
                            else -> "Geometría"
                        }
                    }
                    else -> "Toque un elemento para seleccionarlo"
                }
                Text(selectedText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancelar") }
                    Button(
                        onClick = onUseSelection,
                        enabled = localSelectedPointId != null || localSelectedGeometryId != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("Usar este elemento") }
                }
            }
        }
    }
}

@Composable
private fun StakeoutActiveView(
    project: TopoProject?,
    points: List<SurveyPoint>,
    geometries: List<CommittedGeometry>,
    target: SurveyPoint,
    gnss: GnssStatus,
    pointDisplaySettings: PointDisplaySettings,
    ntrip: NtripLiveStatus,
    onTargetSelected: (String) -> Unit,
    toleranceM: Double,
    onToleranceChanged: (Double) -> Unit,
    onStop: () -> Unit
) {
    val currentDistanceM = run {
        val lat = gnss.latitude
        val lon = gnss.longitude
        val tLat = target.latitude
        val tLon = target.longitude
        if (lat != null && lon != null && tLat != null && tLon != null) {
            val north = (tLat - lat) * 111132.0
            val east = (tLon - lon) * (111320.0 * cos(Math.toRadians(tLat)))
            hypot(north, east)
        } else null
    }

    var viewMode by remember(target.id) { mutableStateOf(StakeoutViewMode.MAP) }
    var showTargetList by remember { mutableStateOf(false) }

    val orderedPoints = remember(points) {
        points.sortedWith(
            compareBy<SurveyPoint> { it.pointNumber.toLongOrNull() ?: Long.MAX_VALUE }
                .thenBy { it.pointNumber.lowercase() }
        )
    }
    val currentPointIndex = orderedPoints.indexOfFirst { it.id == target.id }
    val previousPoint = if (currentPointIndex > 0) orderedPoints[currentPointIndex - 1] else null
    val nextPoint =
        if (currentPointIndex >= 0 && currentPointIndex < orderedPoints.lastIndex) {
            orderedPoints[currentPointIndex + 1]
        } else null

    if (showTargetList) {
        AlertDialog(
            onDismissRequest = { showTargetList = false },
            title = { Text("Lista de puntos") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    orderedPoints.forEach { point ->
                        OutlinedButton(
                            onClick = {
                                onTargetSelected(point.id)
                                showTargetList = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    "Punto ${point.pointNumber}",
                                    fontWeight = if (point.id == target.id) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    point.description.ifBlank { point.code.ifBlank { "Sin descripción" } },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { showTargetList = false }) {
                    Text("Cerrar")
                }
            }
        )
    }

    LaunchedEffect(currentDistanceM, target.id) {
        val d = currentDistanceM ?: return@LaunchedEffect
        viewMode = when (viewMode) {
            StakeoutViewMode.MAP -> when {
                d <= 4.8 -> StakeoutViewMode.PRECISION
                d <= 14.5 -> StakeoutViewMode.CLOSE
                else -> StakeoutViewMode.MAP
            }
            StakeoutViewMode.CLOSE -> when {
                d <= 4.8 -> StakeoutViewMode.PRECISION
                d >= 15.5 -> StakeoutViewMode.MAP
                else -> StakeoutViewMode.CLOSE
            }
            StakeoutViewMode.PRECISION -> when {
                d >= 5.5 -> StakeoutViewMode.CLOSE
                else -> StakeoutViewMode.PRECISION
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (viewMode) {
            StakeoutViewMode.MAP -> StakeoutMapPreview(
                project, points, geometries, target, gnss, pointDisplaySettings,
                modifier = Modifier.fillMaxSize(), closeView = false
            )
            StakeoutViewMode.CLOSE -> StakeoutMapPreview(
                project, points, geometries, target, gnss, pointDisplaySettings,
                modifier = Modifier.fillMaxSize(), closeView = true
            )
            StakeoutViewMode.PRECISION -> Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(top = 168.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    StakeoutGuidancePanel(
                        project = project,
                        target = target,
                        gnss = gnss,
                        toleranceM = toleranceM,
                        onToleranceChanged = onToleranceChanged
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopCenter)
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            StakeoutStatusHeader(
                project = project,
                gnss = gnss,
                ntrip = ntrip
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    viewMode.label + (currentDistanceM?.let { " • " + "%.2f m".format(it) } ?: ""),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Column(
                    modifier = Modifier.width(112.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalButton(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Salir", style = MaterialTheme.typography.labelMedium)
                    }
                    FilledTonalButton(
                        onClick = { nextPoint?.let { onTargetSelected(it.id) } },
                        enabled = nextPoint != null,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Siguiente", style = MaterialTheme.typography.labelMedium)
                    }
                    FilledTonalButton(
                        onClick = { previousPoint?.let { onTargetSelected(it.id) } },
                        enabled = previousPoint != null,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Anterior", style = MaterialTheme.typography.labelMedium)
                    }
                    FilledTonalButton(
                        onClick = { showTargetList = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Lista", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (viewMode != StakeoutViewMode.PRECISION) {
            StakeoutCompactOverlay(
                project = project,
                target = target,
                gnss = gnss,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .padding(top = 146.dp, start = 10.dp, end = 10.dp)
            )
        }
    }
}

@Composable
private fun StakeoutStatusHeader(
    project: TopoProject?,
    gnss: GnssStatus,
    ntrip: NtripLiveStatus
) {
    val context = LocalContext.current

    val coordinate = remember(project?.id, project?.crsName, gnss.latitude, gnss.longitude) {
        val lat = gnss.latitude
        val lon = gnss.longitude
        if (project != null && lat != null && lon != null) {
            runCatching { ProjectCoordinateEngine.fromWgs84(lat, lon, project.crsName) }.getOrNull()
        } else null
    }

    val geoidSample = remember(
        project?.id,
        project?.geoidFileUri,
        gnss.latitude,
        gnss.longitude,
        gnss.ellipsoidalHeightM
    ) {
        val lat = gnss.latitude
        val lon = gnss.longitude
        val h = gnss.ellipsoidalHeightM
        if (project != null && !project.geoidFileUri.isNullOrBlank() && lat != null && lon != null && h != null) {
            GeoidGridService.undulation(
                context = context,
                uriText = project.geoidFileUri,
                fileName = project.geoidFileName,
                latitude = lat,
                longitude = lon
            )
        } else null
    }

    val orthometricHeight = if (geoidSample?.isSuccess == true) {
        val h = gnss.ellipsoidalHeightM
        val n = geoidSample.getOrNull()?.undulationM
        if (h != null && n != null) h - n else null
    } else null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(project?.name ?: "Sin proyecto activo", style = MaterialTheme.typography.titleMedium)

            Text(
                if (gnss.connected) {
                    "GNSS: CONECTADO • " + gnss.solution +
                        " • Sat: " + (gnss.satellites?.toString() ?: "—") +
                        " • H: " + (gnss.horizontalAccuracyM?.let { "%.3f m".format(it) } ?: "—") +
                        " • V: " + (gnss.verticalAccuracyM?.let { "%.3f m".format(it) } ?: "—")
                } else {
                    "GNSS: DESCONECTADO"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                if (ntrip.connected) {
                    "NTRIP: CONECTADO" +
                        (ntrip.profileName?.let { " • $it" } ?: "") +
                        (ntrip.mountPoint?.let { " • $it" } ?: "")
                } else if (ntrip.connecting) {
                    "NTRIP: CONECTANDO"
                } else {
                    "NTRIP: DESCONECTADO"
                },
                style = MaterialTheme.typography.labelSmall
            )

            gnss.ggaRateHz?.let { hz ->
                Text(
                    "Actualización de posición: %.1f Hz%s".format(
                        hz,
                        if (hz < 2.0) " • LENTA" else ""
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (hz < 2.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            val p = project
            if (p != null) {
                if (p.crsName == "WGS 84 geográficas") {
                    Text(
                        "CRS: WGS 84 geográficas • Lat: " +
                            (gnss.latitude?.let { "%.8f".format(it) } ?: "—") +
                            " • Lon: " +
                            (gnss.longitude?.let { "%.8f".format(it) } ?: "—"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        "CRS: " + p.crsName +
                            " • N: " + (coordinate?.northingM?.let { "%.3f".format(it) } ?: "—") +
                            " • E: " + (coordinate?.eastingM?.let { "%.3f".format(it) } ?: "—"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                val verticalText = when {
                    p.geoidFileUri.isNullOrBlank() ->
                        "Altura: " + (gnss.ellipsoidalHeightM?.let { "%.3f m".format(it) } ?: "—") +
                            " • Vertical: Elipsoidal"
                    geoidSample?.isFailure == true ->
                        "Geoide no aplicado • " + (p.geoidFileName ?: "archivo geoidal")
                    else ->
                        "Elevación: " + (orthometricHeight?.let { "%.3f m".format(it) } ?: "—") +
                            " • Vertical: Geoide · " + (p.geoidFileName ?: "local")
                }

                Text(
                    verticalText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StakeoutCompactOverlay(
    project: TopoProject?,
    target: SurveyPoint,
    gnss: GnssStatus,
    modifier: Modifier = Modifier
) {
    val lat = gnss.latitude
    val lon = gnss.longitude
    val tLat = target.latitude
    val tLon = target.longitude
    if (lat == null || lon == null || tLat == null || tLon == null) return

    val northM = (tLat - lat) * 111132.0
    val eastM = (tLon - lon) * (111320.0 * cos(Math.toRadians(tLat)))
    val distanceM = hypot(northM, eastM)

    val context = LocalContext.current
    val useGeoid = !project?.geoidFileUri.isNullOrBlank()
    val targetElevation = if (useGeoid) {
        target.orthometricHeightM ?: target.ellipsoidalHeightM?.let { h ->
            GeoidGridService.undulation(
                context, project?.geoidFileUri, project?.geoidFileName, tLat, tLon
            ).getOrNull()?.let { h - it.undulationM }
        }
    } else target.ellipsoidalHeightM

    val currentElevation = if (useGeoid) {
        gnss.ellipsoidalHeightM?.let { h ->
            GeoidGridService.undulation(
                context, project?.geoidFileUri, project?.geoidFileName, lat, lon
            ).getOrNull()?.let { h - it.undulationM }
        }
    } else gnss.ellipsoidalHeightM

    val dz = if (targetElevation != null && currentElevation != null) targetElevation - currentElevation else null

    Card(
        modifier = modifier,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("Punto " + target.pointNumber + " • " + "%.3f m".format(distanceM), fontWeight = FontWeight.Bold)
            Text("E/O " + "%.3f".format(eastM) + " m • N/S " + "%.3f".format(northM) + " m")
            if (dz != null) {
                Text(
                    when {
                        dz > 0.005 -> "RELLENO " + "%.3f".format(dz) + " m"
                        dz < -0.005 -> "CORTE " + "%.3f".format(abs(dz)) + " m"
                        else -> "COTA OK ±0.005 m"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun makeStakeoutTargetBitmap(): Bitmap {
    val size = 72
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)

    val yellow = android.graphics.Color.rgb(255, 214, 0)
    val black = android.graphics.Color.BLACK

    val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = yellow
        style = Paint.Style.STROKE
        strokeWidth = 7f
    }
    val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = yellow
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = black
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    val cx = size / 2f
    val cy = size / 2f
    canvas.drawCircle(cx, cy, 20f, outer)
    canvas.drawCircle(cx, cy, 20f, outline)

    canvas.drawLine(cx - 30f, cy, cx - 10f, cy, cross)
    canvas.drawLine(cx + 10f, cy, cx + 30f, cy, cross)
    canvas.drawLine(cx, cy - 30f, cx, cy - 10f, cross)
    canvas.drawLine(cx, cy + 10f, cx, cy + 30f, cross)

    return bitmap
}

@Composable
private fun PointSingleSelector(
    points: List<SurveyPoint>,
    selectedPointId: String?,
    onSelect: (String) -> Unit
) {
    if (points.isEmpty()) {
        Text("No hay puntos disponibles.")
        return
    }
    Text("Punto base", fontWeight = FontWeight.Bold)
    points.forEach { p ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onSelect(p.id) }
                .padding(vertical = 4.dp)
        ) {
            RadioButton(selected = selectedPointId == p.id, onClick = { onSelect(p.id) })
            Text("Punto ${p.pointNumber}")
        }
    }
}

@Composable
private fun PointPairSelector(
    points: List<SurveyPoint>,
    startPointId: String?,
    endPointId: String?,
    onStart: (String) -> Unit,
    onEnd: (String) -> Unit
) {
    if (points.isEmpty()) {
        Text("No hay puntos disponibles.")
        return
    }

    Text("Punto inicial", fontWeight = FontWeight.Bold)
    points.forEach { p ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onStart(p.id) }
                .padding(vertical = 3.dp)
        ) {
            RadioButton(selected = startPointId == p.id, onClick = { onStart(p.id) })
            Text("Punto ${p.pointNumber}")
        }
    }

    Spacer(Modifier.height(10.dp))
    Text("Punto final", fontWeight = FontWeight.Bold)
    points.forEach { p ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onEnd(p.id) }
                .padding(vertical = 3.dp)
        ) {
            RadioButton(selected = endPointId == p.id, onClick = { onEnd(p.id) })
            Text("Punto ${p.pointNumber}")
        }
    }
}


@Composable
private fun StakeoutMapPreview(
    project: TopoProject?,
    points: List<SurveyPoint>,
    geometries: List<CommittedGeometry>,
    target: SurveyPoint?,
    gnss: GnssStatus,
    pointDisplaySettings: PointDisplaySettings,
    modifier: Modifier = Modifier.fillMaxWidth().height(280.dp),
    closeView: Boolean = false,
    perpendicularFoot: LatLng? = null
) {
    val context = LocalContext.current
    val layerStore = remember(project?.id) { LayerStore(context) }
    val basemapStore = remember(project?.id) { BasemapStore(context) }

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

    val layers = remember(project?.id) { loadEffectiveLayers() }
    val basemap = remember(project?.id) { basemapStore.selected(project?.id) }
    var topOverlayMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var topOverlayCameraVersion by remember { mutableIntStateOf(0) }
    var lastAutoFitTargetId by remember { mutableStateOf<String?>(null) }

    fun redrawGuidance(map: MapLibreMap) {
        // No limpiar el mapa aquí. El WMS y el GNSS se actualizan de forma
        // independiente para evitar el parpadeo/brinco visual durante replanteo.
        ensureSurveyGeometryOverlayOnTop(map, geometries)
        ensureSurveyPointOverlayOnTop(map, points, gnss, pointDisplaySettings)

        map.style?.let { style ->
            ensureTopoSurveyPointLayers(
                style = style,
                prefix = "stakeout-points",
                points = points,
                settings = pointDisplaySettings,
                selectedPointId = target?.id
            )
        }

        ensureStakeoutGuidanceLineOnTop(map, gnss, target)
        ensureStakeoutPerpendicularOnTop(map, gnss, perpendicularFoot)
        ensureStakeoutCriticalOverlayOnTop(map, target, gnss)
    }


    fun redrawDynamicGuidance(map: MapLibreMap) {
        // Ruta rápida para cada nueva solución GNSS:
        // no borrar anotaciones ni reconstruir puntos/figuras estáticos.
        ensureSurveyPointOverlayOnTop(map, points, gnss, pointDisplaySettings)
        ensureStakeoutGuidanceLineOnTop(map, gnss, target)
        ensureStakeoutCriticalOverlayOnTop(map, target, gnss)
    }
    val lat = gnss.latitude
    val lon = gnss.longitude
    val tLat = target?.latitude
    val tLon = target?.longitude

    val distanceM = if (lat != null && lon != null && tLat != null && tLon != null) {
        val north = (tLat - lat) * 111132.0
        val east = (tLon - lon) * (111320.0 * cos(Math.toRadians(tLat)))
        hypot(north, east)
    } else null

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapContext ->
                MapView(mapContext).apply {
                    onCreate(null)
                    onStart()
                    onResume()
                    getMapAsync { map ->
                        topOverlayMap = map
                        map.addOnCameraMoveListener {
                            topOverlayCameraVersion += 1
                        }
                        val center = when {
                            lat != null && lon != null -> LatLng(lat, lon)
                            tLat != null && tLon != null -> LatLng(tLat, tLon)
                            else -> LatLng(9.93, -84.08)
                        }

                        val zoom = if (closeView) {
                            when {
                                distanceM == null -> 19.0
                                distanceM > 5.0 -> 19.0
                                distanceM > 1.0 -> 20.5
                                else -> 21.5
                            }
                        } else {
                            when {
                                distanceM == null -> 8.0
                                distanceM > 500.0 -> 14.0
                                distanceM > 100.0 -> 16.0
                                distanceM > 20.0 -> 17.5
                                else -> 19.0
                            }
                        }

                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, zoom))

                        val baseStyle = Style.Builder().fromJson(
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
                            addSelectedBasemap(style, basemap)
                            addProjectRasterLayers(style, layers)

                            refreshViewportWmsLayers(map, layers) {
                                // La WMS puede terminar de cargar después:
                                // se vuelven a dibujar GNSS, objetivo y línea al final.
                                redrawGuidance(map)
                            }

                            redrawGuidance(map)
                        }

                        map.addOnCameraIdleListener {
                            refreshViewportWmsLayers(map, layers) {
                                redrawGuidance(map)
                            }
                        }
                    }
                }
            },
            update = { mapView ->
                mapView.getMapAsync { map ->
                    redrawDynamicGuidance(map)

                    val currentLat = gnss.latitude
                    val currentLon = gnss.longitude
                    val targetLat = target?.latitude
                    val targetLon = target?.longitude
                    if (currentLat != null && currentLon != null && targetLat != null && targetLon != null) {
                        val current = LatLng(currentLat, currentLon)
                        val objective = LatLng(targetLat, targetLon)
                        val distance = hypot(
                            (targetLat - currentLat) * 111132.0,
                            (targetLon - currentLon) * (111320.0 * cos(Math.toRadians(targetLat)))
                        )

                        val visibleBounds = runCatching {
                            map.projection.visibleRegion.latLngBounds
                        }.getOrNull()
                        val targetChanged = lastAutoFitTargetId != target?.id
                        val receiverOutside =
                            visibleBounds?.contains(current) == false
                        val objectiveOutside =
                            visibleBounds?.contains(objective) == false
                        val shouldRefit =
                            targetChanged || receiverOutside || objectiveOutside

                        if (shouldRefit) {
                            if (!closeView) {
                                // Solo reajustar cuando haga falta. Así una solución GNSS
                                // nueva no dispara un nuevo BBOX WMS en cada segundo.
                                val bounds = LatLngBounds.Builder()
                                    .include(current)
                                    .include(objective)
                                    .build()
                                map.moveCamera(
                                    CameraUpdateFactory.newLatLngBounds(
                                        bounds,
                                        210
                                    )
                                )
                            } else {
                                if (distance > 1.0) {
                                    val bounds = LatLngBounds.Builder()
                                        .include(current)
                                        .include(objective)
                                        .build()
                                    map.moveCamera(
                                        CameraUpdateFactory.newLatLngBounds(
                                            bounds,
                                            160
                                        )
                                    )
                                } else {
                                    map.moveCamera(
                                        CameraUpdateFactory.newLatLngZoom(current, 21.5)
                                    )
                                }
                            }
                            lastAutoFitTargetId = target?.id
                        }
                    }
                }
            }
        )

        AlwaysVisiblePointOverlay(
            map = topOverlayMap,
            cameraVersion = topOverlayCameraVersion,
            points = points,
            gnss = gnss,
            settings = pointDisplaySettings,
            selectedPointId = target?.id,
            modifier = Modifier.fillMaxSize()
        )

        if (lat != null && lon != null && tLat != null && tLon != null) {
            val north = (tLat - lat) * 111132.0
            val east = (tLon - lon) * (111320.0 * cos(Math.toRadians(tLat)))
            val distance = hypot(north, east)

            val direction = buildString {
                if (north > 0.05) append("N ")
                if (north < -0.05) append("S ")
                if (east > 0.05) append("E")
                if (east < -0.05) append("O")
                if (isBlank()) append("CENTRO")
            }.trim()

            Surface(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .padding(8.dp),
                tonalElevation = 6.dp
            ) {
                Text(
                    if (distance <= 0.005)
                        "OBJETIVO ALCANZADO"
                    else
                        "Muévase: $direction • %.2f m".format(distance),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}



private fun ensureStakeoutPerpendicularOnTop(
    map: MapLibreMap, gnss: GnssStatus, foot: LatLng?
) {
    val style = map.style ?: return
    val sourceId = "stakeout-perpendicular-source"
    val layerId = "stakeout-perpendicular-layer"
    val features = if (gnss.latitude != null && gnss.longitude != null && foot != null) {
        listOf(Feature.fromGeometry(LineString.fromLngLats(listOf(
            Point.fromLngLat(gnss.longitude!!, gnss.latitude!!),
            Point.fromLngLat(foot.longitude, foot.latitude)
        ))))
    } else emptyList()
    val source = style.getSourceAs<GeoJsonSource>(sourceId)
    if (source == null) {
        style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(features)))
    } else source.setGeoJson(FeatureCollection.fromFeatures(features))
    if (style.getLayer(layerId) == null) {
        style.addLayer(LineLayer(layerId, sourceId).withProperties(
            PropertyFactory.lineColor(android.graphics.Color.rgb(0, 188, 212)),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f))
        ))
    }
}

private fun ensureStakeoutGuidanceLineOnTop(
    map: MapLibreMap,
    gnss: GnssStatus,
    target: SurveyPoint?
) {
    val style = map.style ?: return
    val lat = gnss.latitude
    val lon = gnss.longitude
    val tLat = target?.latitude
    val tLon = target?.longitude

    val sourceId = "stakeout-guidance-line-source"
    val layerId = "stakeout-guidance-line-layer"

    val features = mutableListOf<Feature>()
    if (lat != null && lon != null && tLat != null && tLon != null) {
        val north = (tLat - lat) * 111132.0
        val east = (tLon - lon) * (111320.0 * cos(Math.toRadians(tLat)))
        val totalM = hypot(north, east)
        val dashM = when {
            totalM > 1000.0 -> 40.0
            totalM > 250.0 -> 20.0
            totalM > 50.0 -> 8.0
            totalM > 10.0 -> 3.0
            else -> 1.0
        }
        val pieces = max(1, ceil(totalM / dashM).toInt())
        for (i in 0 until pieces step 2) {
            val t0 = i.toDouble() / pieces.toDouble()
            val t1 = min(1.0, (i + 1).toDouble() / pieces.toDouble())
            val a = Point.fromLngLat(
                lon + (tLon - lon) * t0,
                lat + (tLat - lat) * t0
            )
            val b = Point.fromLngLat(
                lon + (tLon - lon) * t1,
                lat + (tLat - lat) * t1
            )
            features += Feature.fromGeometry(LineString.fromLngLats(listOf(a, b)))
        }
    }

    val source = style.getSourceAs<GeoJsonSource>(sourceId)
    if (source == null) {
        style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(features)))
    } else {
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    if (style.getLayer(layerId) == null) {
        style.addLayer(
            LineLayer(layerId, sourceId).withProperties(
                PropertyFactory.lineColor(android.graphics.Color.rgb(103, 58, 183)),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineOpacity(1f)
            )
        )
    }
}

private fun ensureStakeoutCriticalOverlayOnTop(
    map: MapLibreMap,
    target: SurveyPoint?,
    gnss: GnssStatus
) {
    val style = map.style ?: return

    // Objetivo seleccionado: mira amarilla como capa de estilo superior.
    val targetSourceId = "stakeout-critical-target-source"
    val targetLayerId = "stakeout-critical-target-layer"
    val targetFeatures = if (target?.latitude != null && target.longitude != null) {
        listOf(
            Feature.fromGeometry(
                Point.fromLngLat(target.longitude!!, target.latitude!!)
            )
        )
    } else emptyList()

    val targetSource = style.getSourceAs<GeoJsonSource>(targetSourceId)
    if (targetSource == null) {
        style.addSource(GeoJsonSource(targetSourceId, FeatureCollection.fromFeatures(targetFeatures)))
    } else {
        targetSource.setGeoJson(FeatureCollection.fromFeatures(targetFeatures))
    }

    if (style.getImage("stakeout-critical-target-image") == null) {
        style.addImage("stakeout-critical-target-image", makeStakeoutTargetBitmap())
    }
    if (style.getLayer(targetLayerId) == null) {
        style.addLayer(
            SymbolLayer(targetLayerId, targetSourceId).withProperties(
                PropertyFactory.iconImage("stakeout-critical-target-image"),
                PropertyFactory.iconSize(1.05f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
    }

    // Posición actual: color por solución GNSS, siempre por encima del mapa/WMS.
    val gnssSourceId = "stakeout-critical-gnss-source"
    val gnssLayerId = "stakeout-critical-gnss-layer"
    val gnssFeatures = if (gnss.connected && gnss.latitude != null && gnss.longitude != null) {
        listOf(
            Feature.fromGeometry(
                Point.fromLngLat(gnss.longitude!!, gnss.latitude!!)
            )
        )
    } else emptyList()

    val gnssSource = style.getSourceAs<GeoJsonSource>(gnssSourceId)
    if (gnssSource == null) {
        style.addSource(GeoJsonSource(gnssSourceId, FeatureCollection.fromFeatures(gnssFeatures)))
    } else {
        gnssSource.setGeoJson(FeatureCollection.fromFeatures(gnssFeatures))
    }

    val color = when {
        gnss.solution.contains("FIX", ignoreCase = true) ->
            android.graphics.Color.rgb(46, 125, 50)
        gnss.solution.contains("FLOAT", ignoreCase = true) ->
            android.graphics.Color.rgb(251, 192, 45)
        else ->
            android.graphics.Color.rgb(211, 47, 47)
    }

    val gnssLayer = style.getLayerAs<CircleLayer>(gnssLayerId)
    if (gnssLayer == null) {
        style.addLayer(
            CircleLayer(gnssLayerId, gnssSourceId).withProperties(
                PropertyFactory.circleColor(color),
                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                PropertyFactory.circleStrokeWidth(4f),
                PropertyFactory.circleRadius(11f)
            )
        )
    } else {
        gnssLayer.setProperties(
            PropertyFactory.circleColor(color),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(4f),
            PropertyFactory.circleRadius(11f)
        )
    }
}

@Composable
private fun StakeoutGuidancePanel(
    project: TopoProject?,
    target: SurveyPoint?,
    gnss: GnssStatus,
    toleranceM: Double,
    onToleranceChanged: (Double) -> Unit
) {
    if (target == null || target.latitude == null || target.longitude == null) {
        Card(Modifier.fillMaxWidth()) {
            Text("Seleccione un punto con coordenadas para iniciar la guía.", modifier = Modifier.padding(12.dp))
        }
        return
    }

    val context = LocalContext.current
    val lat = gnss.latitude
    val lon = gnss.longitude
    if (!gnss.connected || lat == null || lon == null) {
        Card(Modifier.fillMaxWidth()) {
            Text("Conecte el receptor y espere una posición GNSS válida.", modifier = Modifier.padding(12.dp))
        }
        return
    }

    val metersPerDegLat = 111132.0
    val metersPerDegLon = 111320.0 * cos(Math.toRadians(target.latitude))

    val projectedDelta = if (
        project != null &&
        project.crsName != "WGS 84 geográficas" &&
        target.eastingM != null &&
        target.northingM != null
    ) {
        runCatching {
            val currentProjected = ProjectCoordinateEngine.fromWgs84(lat, lon, project.crsName)
            val currentE = currentProjected.eastingM
            val currentN = currentProjected.northingM
            if (currentE != null && currentN != null) {
                (target.northingM - currentN) to (target.eastingM - currentE)
            } else null
        }.getOrNull()
    } else null

    val rawNorthM = projectedDelta?.first ?: ((target.latitude - lat) * metersPerDegLat)
    val rawEastM = projectedDelta?.second ?: ((target.longitude - lon) * metersPerDegLon)
    val rawDistanceM = hypot(rawNorthM, rawEastM)
    val magneticDeclinationDeg = remember(lat, lon, gnss.ellipsoidalHeightM) {
        GeomagneticField(
            lat.toFloat(),
            lon.toFloat(),
            (gnss.ellipsoidalHeightM ?: 0.0).toFloat(),
            System.currentTimeMillis()
        ).declination.toDouble()
    }

    // Rumbo de la tablet medido en reposo. Si falta el sensor, mantener norte arriba.
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val rotationSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    val magneticSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }
    val gravitySensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val compassAvailable = rotationSensor != null || (magneticSensor != null && gravitySensor != null)
    var headingMagneticDeg by remember { mutableDoubleStateOf(0.0) }
    var compassEnabled by remember { mutableStateOf(true) }
    DisposableEffect(sensorManager, rotationSensor, magneticSensor, gravitySensor) {
        var gravity: FloatArray? = null
        var magnetic: FloatArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val matrix = FloatArray(9)
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR ->
                        SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    Sensor.TYPE_ACCELEROMETER -> {
                        gravity = event.values.clone()
                        if (magnetic == null || !SensorManager.getRotationMatrix(matrix, null, gravity, magnetic)) return
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magnetic = event.values.clone()
                        if (gravity == null || !SensorManager.getRotationMatrix(matrix, null, gravity, magnetic)) return
                    }
                    else -> return
                }
                // Compensar la orientación de pantalla; no usar el rumbo GNSS de marcha.
                val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.rotation
                val (axisX, axisY) = when (displayRotation) {
                    android.view.Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    android.view.Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    android.view.Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                val remapped = FloatArray(9)
                if (!SensorManager.remapCoordinateSystem(matrix, axisX, axisY, remapped)) return
                val angles = FloatArray(3)
                SensorManager.getOrientation(remapped, angles)
                headingMagneticDeg = (Math.toDegrees(angles[0].toDouble()) + 360.0) % 360.0
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (compassAvailable) {
            if (rotationSensor != null) {
                sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            } else if (gravitySensor != null && magneticSensor != null) {
                sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
                sensorManager.registerListener(listener, magneticSensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // La posición RTK cruda se conserva para cálculos/guardado. La mira usa
    // una ventana mediana corta para rechazar saltos aislados de varios centímetros.
    var recentDeltas by remember(target.id) {
        mutableStateOf(listOf(rawNorthM to rawEastM))
    }
    var displayNorthM by remember(target.id) { mutableDoubleStateOf(rawNorthM) }
    var displayEastM by remember(target.id) { mutableDoubleStateOf(rawEastM) }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    LaunchedEffect(rawNorthM, rawEastM, target.id) {
        recentDeltas = (recentDeltas + (rawNorthM to rawEastM)).takeLast(5)

        val medianNorth = median(recentDeltas.map { it.first })
        val medianEast = median(recentDeltas.map { it.second })
        val jumpFromMedian = hypot(rawNorthM - medianNorth, rawEastM - medianEast)

        if (rawDistanceM <= 1.0) {
            // Replanteo fino: seguir la época RTK más reciente casi en tiempo real.
            // Solo rechazar un salto claramente aislado respecto a las últimas épocas.
            val useRaw = recentDeltas.size < 3 || jumpFromMedian <= 0.20
            val targetNorth = if (useRaw) rawNorthM else medianNorth
            val targetEast = if (useRaw) rawEastM else medianEast
            val alpha = if (rawDistanceM <= 0.20) 0.92 else 0.86

            displayNorthM += (targetNorth - displayNorthM) * alpha
            displayEastM += (targetEast - displayEastM) * alpha
        } else {
            // Lejos del punto: algo de suavizado hace la guía más estable.
            displayNorthM += (medianNorth - displayNorthM) * 0.78
            displayEastM += (medianEast - displayEastM) * 0.78
        }

        // Zona muerta mínima: no esconder desplazamientos de centímetros.
        if (abs(displayNorthM) < 0.001) displayNorthM = 0.0
        if (abs(displayEastM) < 0.001) displayEastM = 0.0
    }

    // Para la distancia y el estado de llegada, en la mira fina usamos la
    // posición RTK actual. Lejos del punto usamos la mediana estable.
    val medianNorthM = median(recentDeltas.map { it.first })
    val medianEastM = median(recentDeltas.map { it.second })
    val stableNorthM = if (rawDistanceM <= 1.0) rawNorthM else medianNorthM
    val stableEastM = if (rawDistanceM <= 1.0) rawEastM else medianEastM
    val stableDistanceM = hypot(stableNorthM, stableEastM)

    // La mira nunca "teletransporta" el punto al centro.
    // Siempre muestra la posición estable calculada.
    val isRtkFix = gnss.connected && gnss.solution.contains("FIX", ignoreCase = true)
    val isRtkFloat = gnss.connected && gnss.solution.contains("FLOAT", ignoreCase = true)
    val withinTolerance = rawDistanceM <= toleranceM
    var centeredSinceMs by remember(target.id) { mutableStateOf<Long?>(null) }
    var pointCentered by remember(target.id) { mutableStateOf(false) }
    LaunchedEffect(target.id, rawDistanceM, isRtkFix) {
        if (!isRtkFix || rawDistanceM > 0.005) {
            centeredSinceMs = null
            pointCentered = false
        } else {
            if (centeredSinceMs == null) centeredSinceMs = SystemClock.elapsedRealtime()
            val remaining = 1000L - (SystemClock.elapsedRealtime() - (centeredSinceMs ?: 0L))
            if (remaining > 0L) delay(remaining)
            pointCentered = isRtkFix && rawDistanceM <= 0.005 &&
                SystemClock.elapsedRealtime() - (centeredSinceMs ?: 0L) >= 1000L
        }
    }

    // Los cálculos de replanteo conservan el delta verdadero del proyecto.
    // La guía numérica gira con la misma orientación magnética de la mira.
    val trueHeadingRad = Math.toRadians(
        if (compassAvailable) headingMagneticDeg + magneticDeclinationDeg else 0.0
    )
    val northM = if (compassEnabled) rawNorthM else rawEastM * sin(trueHeadingRad) + rawNorthM * cos(trueHeadingRad)
    val eastM = if (compassEnabled) rawEastM else rawEastM * cos(trueHeadingRad) - rawNorthM * sin(trueHeadingRad)
    val distanceM = rawDistanceM


    val useGeoid = !project?.geoidFileUri.isNullOrBlank()
    val targetElevation = if (useGeoid) {
        target.orthometricHeightM ?: target.ellipsoidalHeightM?.let { h ->
            GeoidGridService.undulation(
                context, project?.geoidFileUri, project?.geoidFileName,
                target.latitude, target.longitude
            ).getOrNull()?.let { h - it.undulationM }
        }
    } else target.ellipsoidalHeightM

    val currentElevation = if (useGeoid) {
        gnss.ellipsoidalHeightM?.let { h ->
            GeoidGridService.undulation(
                context, project?.geoidFileUri, project?.geoidFileName,
                lat, lon
            ).getOrNull()?.let { h - it.undulationM }
        }
    } else gnss.ellipsoidalHeightM

    val verticalDelta = if (targetElevation != null && currentElevation != null) {
        targetElevation - currentElevation
    } else null

    // Una sola convención en toda la vista:
    // northM/eastM representan el desplazamiento que debe hacer la antena
    // desde su posición actual hasta el objetivo.
    val nsLabel = when {
        northM > 0.0005 -> if (!compassEnabled && compassAvailable) "ADELANTE" else "AL NORTE"
        northM < -0.0005 -> if (!compassEnabled && compassAvailable) "ATRÁS" else "AL SUR"
        else -> "N/S OK"
    }
    val ewLabel = when {
        eastM > 0.0005 -> if (!compassEnabled && compassAvailable) "DERECHA" else "AL ESTE"
        eastM < -0.0005 -> if (!compassEnabled && compassAvailable) "IZQUIERDA" else "AL OESTE"
        else -> "E/O OK"
    }
    val verticalLabel = when {
        verticalDelta == null -> "SIN COTA"
        verticalDelta > 0.005 -> "RELLENO"
        verticalDelta < -0.005 -> "CORTE"
        else -> "COTA OK"
    }

    val correctionDirection = buildString {
        val ns = when {
            northM > 0.0005 -> "NORTE"
            northM < -0.0005 -> "SUR"
            else -> ""
        }
        val ew = when {
            eastM > 0.0005 -> "ESTE"
            eastM < -0.0005 -> "OESTE"
            else -> ""
        }
        append(
            when {
                ns.isNotEmpty() && ew.isNotEmpty() -> "$ns-$ew"
                ns.isNotEmpty() -> ns
                ew.isNotEmpty() -> ew
                else -> "CENTRO"
            }
        )
    }


    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Mira de precisión",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Distancia estable al objetivo: %.3f m".format(stableDistanceM),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                if (!compassAvailable) "Sin brújula: norte arriba" else if (compassEnabled) "Norte magnético: guía N/S/E/O" else "Guía relativa: adelante/atrás/izquierda/derecha",
                style = MaterialTheme.typography.labelSmall
            )

            if (compassAvailable) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Seguir norte magnético")
                    Switch(checked = compassEnabled, onCheckedChange = { compassEnabled = it })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Tolerancia de llegada", fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    0.005 to "5 mm",
                    0.01 to "1 cm",
                    0.02 to "2 cm",
                    0.05 to "5 cm"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = abs(toleranceM - value) < 0.0001,
                        onClick = { onToleranceChanged(value) },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    tonalElevation = 3.dp,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Column(
                        Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Text(
                            when {
                                northM > 0.0005 -> "↑"
                                northM < -0.0005 -> "↓"
                                else -> "✓"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(nsLabel, fontWeight = FontWeight.Bold)
                        Text("%.3f m".format(abs(northM)))
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
                    tonalElevation = 3.dp,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Column(
                        Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Text(
                            when {
                                eastM > 0.0005 -> "→"
                                eastM < -0.0005 -> "←"
                                else -> "✓"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(ewLabel, fontWeight = FontWeight.Bold)
                        Text("%.3f m".format(abs(eastM)))
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
                    tonalElevation = 3.dp,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Column(
                        Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Text(
                            when {
                                verticalDelta == null -> "—"
                                verticalDelta > 0.005 -> "↑"
                                verticalDelta < -0.005 -> "↓"
                                else -> "✓"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(verticalLabel, fontWeight = FontWeight.Bold)
                        Text(verticalDelta?.let { "%.3f m".format(abs(it)) } ?: "—")
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                if (correctionDirection == "CENTRO")
                    "ANTENA SOBRE EL PUNTO"
                else
                    "MOVER ANTENA AL $correctionDirection",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Centro = punto a replantear. El punto amarillo/verde = posición actual de la antena.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxR = min(size.width, size.height) * 0.39f

                        drawCircle(
                            color = Color(0xFFE7EEF7),
                            radius = maxR,
                            center = center
                        )
                        drawCircle(
                            color = Color(0xFFB7C8DD),
                            radius = maxR,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                        )
                        drawCircle(
                            color = Color(0xFF90A9C4),
                            radius = maxR * 0.67f,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                        )
                        drawCircle(
                            color = Color(0xFF6F8EAE),
                            radius = maxR * 0.34f,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                        )

                        // Rosa giratoria según rumbo magnético de la tablet.
                        // Centro = objetivo; marcador = posición actual de la antena.
                        val declinationRad = Math.toRadians(if (compassAvailable) magneticDeclinationDeg else 0.0)
                        val headingRad = Math.toRadians(if (compassAvailable) headingMagneticDeg else 0.0)

                        fun projectToMagnetic(worldEast: Double, worldNorth: Double): Pair<Double, Double> {
                            val magEast =
                                worldEast * cos(declinationRad) - worldNorth * sin(declinationRad)
                            val magNorth =
                                worldNorth * cos(declinationRad) + worldEast * sin(declinationRad)
                            val screenEast = magEast * cos(headingRad) - magNorth * sin(headingRad)
                            val screenNorth = magNorth * cos(headingRad) + magEast * sin(headingRad)
                            return screenEast to screenNorth
                        }

                        fun magneticToScreen(magEast: Double, magNorth: Double, scalePx: Float): Offset =
                            Offset(
                                center.x + (magEast * scalePx).toFloat(),
                                center.y - (magNorth * scalePx).toFloat()
                            )

                        drawLine(
                            color = Color(0xFF355A7A),
                            start = Offset(center.x - maxR, center.y),
                            end = Offset(center.x + maxR, center.y),
                            strokeWidth = 2.5f
                        )
                        drawLine(
                            color = Color(0xFF355A7A),
                            start = Offset(center.x, center.y - maxR),
                            end = Offset(center.x, center.y + maxR),
                            strokeWidth = 2.5f
                        )

                        val labelPaint = Paint().apply {
                            color = android.graphics.Color.rgb(31, 41, 55)
                            textSize = 34f
                            textAlign = Paint.Align.CENTER
                            isFakeBoldText = true
                        }
                        val labelR = maxR * 1.12f
                        listOf(
                            Triple("N", 0.0, 1.0),
                            Triple("E", 1.0, 0.0),
                            Triple("S", 0.0, -1.0),
                            Triple("O", -1.0, 0.0)
                        ).forEach { (label, e, n) ->
                            // Mismo norte verdadero/magnético y orientación que el marcador.
                            val (screenE, screenN) = projectToMagnetic(e, n)
                            val p = magneticToScreen(screenE, screenN, labelR)
                            drawContext.canvas.nativeCanvas.drawText(
                                label,
                                p.x,
                                p.y + labelPaint.textSize * 0.35f,
                                labelPaint
                            )
                        }

                        // El centro es el objetivo. El marcador usa el vector opuesto
                        // a la corrección para mostrar la posición de la antena.
                        val antennaEast = -rawEastM
                        val antennaNorth = -rawNorthM
                        val distanceM = hypot(antennaEast, antennaNorth)
                        val markerRadius = 14f
                        val markerTravelPx = (maxR - markerRadius - 3f).coerceAtLeast(0f)

                        // La curva radial fija deja espacio al entrar en la mira y
                        // da más recorrido visual a los últimos centímetros.
                        val (screenEast, screenNorth) = projectToMagnetic(
                            antennaEast / (distanceM + 0.75),
                            antennaNorth / (distanceM + 0.75)
                        )
                        val marker = magneticToScreen(screenEast, screenNorth, markerTravelPx)

                        drawCircle(
                            color = if (withinTolerance && isRtkFix) Color(0xFF2E7D32) else Color(0xFFFFC107),
                            radius = markerRadius,
                            center = marker
                        )
                        drawCircle(
                            color = Color(0xFF1F2937),
                            radius = markerRadius,
                            center = marker,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                        )

                        drawLine(
                            color = Color(0xFF1F2937),
                            start = Offset(center.x - 11f, center.y),
                            end = Offset(center.x + 11f, center.y),
                            strokeWidth = 3f
                        )
                        drawLine(
                            color = Color(0xFF1F2937),
                            start = Offset(center.x, center.y - 11f),
                            end = Offset(center.x, center.y + 11f),
                            strokeWidth = 3f
                        )
                    }

                    if (withinTolerance) {
                        Text(
                            if (pointCentered) "PUNTO CENTRADO" else if (isRtkFloat) "DENTRO DE TOLERANCIA — RTK FLOAT: PRECISIÓN NO CONFIRMADA" else if (isRtkFix) "DENTRO DE TOLERANCIA" else "CERCA DEL OBJETIVO — PRECISIÓN NO CONFIRMADA",
                            modifier = Modifier
                                .align(androidx.compose.ui.Alignment.TopCenter)
                                .padding(top = 42.dp),
                            color = Color(0xFF1B5E20),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }


                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                when {
                    withinTolerance && isRtkFix ->
                        "OBJETIVO ALCANZADO • dentro de la tolerancia seleccionada (%s)"
                            .format(
                                when {
                                    toleranceM <= 0.0051 -> "5 mm"
                                    toleranceM <= 0.0101 -> "1 cm"
                                    toleranceM <= 0.0201 -> "2 cm"
                                    else -> "5 cm"
                                }
                            )
                    withinTolerance ->
                        "Dentro de tolerancia geométrica; precisión GNSS no confirmada."
                    distanceM <= 0.05 ->
                        "Ajuste final: siga la mira en centímetros o milímetros."
                    distanceM <= 0.5 ->
                        "Lleve el punto amarillo al centro."
                    else ->
                        "Acérquese siguiendo la mira y las correcciones Norte/Sur y Este/Oeste."
                },
                fontWeight = if (withinTolerance) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium
            )

            if (verticalDelta != null && targetElevation != null && currentElevation != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Cota objetivo: %.3f m • Cota actual: %.3f m".format(
                        targetElevation,
                        currentElevation
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (useGeoid) "Vertical: geoide del proyecto" else "Vertical: elipsoidal",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
