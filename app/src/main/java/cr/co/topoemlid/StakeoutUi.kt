package cr.co.topoemlid

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import kotlin.math.*

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
    gnss: GnssStatus
) {
    val context = LocalContext.current
    val pointStore = remember(project?.id) { SurveyPointStore(context) }
    val points = remember(project?.id) {
        project?.let { pointStore.load(it.id) } ?: emptyList()
    }

    var mode by remember { mutableStateOf(StakeoutMode.POINT) }
    var selectedPointId by remember { mutableStateOf<String?>(null) }
    var startPointId by remember { mutableStateOf<String?>(null) }
    var endPointId by remember { mutableStateOf<String?>(null) }
    var offsetText by remember { mutableStateOf("1.00") }
    var bearingText by remember { mutableStateOf("") }
    var distanceText by remember { mutableStateOf("") }
    var showGuidance by remember { mutableStateOf(false) }
    var stakeoutView by remember { mutableStateOf(StakeoutViewMode.MAP) }
    val pointDisplayStore = remember { PointDisplaySettingsStore(context) }
    var pointDisplaySettings by remember { mutableStateOf(pointDisplayStore.load()) }
    var showPointDisplayPanel by remember { mutableStateOf(false) }

    val selectedTarget = points.firstOrNull { it.id == selectedPointId }
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
    val latestStakeoutDistance by rememberUpdatedState(currentStakeoutDistanceM)
    val toneGenerator = remember {
        ToneGenerator(AudioManager.STREAM_MUSIC, 85)
    }

    DisposableEffect(Unit) {
        onDispose {
            toneGenerator.stopTone()
            toneGenerator.release()
        }
    }

    LaunchedEffect(showGuidance, mode, selectedPointId) {
        toneGenerator.stopTone()
        if (!showGuidance || mode != StakeoutMode.POINT || selectedPointId == null) return@LaunchedEffect

        while (true) {
            val distance = latestStakeoutDistance
            if (distance == null || !gnss.connected) {
                delay(500)
                continue
            }

            when {
                distance <= 0.005 -> {
                    // Punto prácticamente coincidente: tono largo. Si se aleja,
                    // vuelve automáticamente a los pulsos según la distancia.
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
                    delay(950)
                }
                distance <= 0.05 -> {
                    // Entre 5 cm y el centro: pulsos muy rápidos.
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
            target = selectedTarget,
            gnss = gnss,
            pointDisplaySettings = pointDisplaySettings,
            viewMode = stakeoutView,
            onViewModeChange = { stakeoutView = it },
            onStop = {
                showGuidance = false
                toneGenerator.stopTone()
            }
        )
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
            OutlinedButton(
                onClick = { showPointDisplayPanel = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text("👁 Visualización")
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
                            showGuidance = false
                            toneGenerator.stopTone()
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
                                toneGenerator.stopTone()
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
                                .clickable { selectedPointId = p.id }
                                .padding(vertical = 6.dp)
                        ) {
                            RadioButton(
                                selected = selectedPointId == p.id,
                                onClick = { selectedPointId = p.id }
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
                PointSingleSelector(points, selectedPointId) { selectedPointId = it }
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
                    showGuidance = false
                    toneGenerator.stopTone()
                } else {
                    showGuidance = true
                }
            },
            enabled = project != null && gnss.connected &&
                (mode != StakeoutMode.POINT || selectedTarget != null),
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

@Composable
private fun StakeoutActiveView(
    project: TopoProject?,
    points: List<SurveyPoint>,
    target: SurveyPoint,
    gnss: GnssStatus,
    pointDisplaySettings: PointDisplaySettings,
    viewMode: StakeoutViewMode,
    onViewModeChange: (StakeoutViewMode) -> Unit,
    onStop: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        when (viewMode) {
            StakeoutViewMode.MAP -> StakeoutMapPreview(
                project, points, target, gnss, pointDisplaySettings,
                modifier = Modifier.fillMaxSize(), closeView = false
            )
            StakeoutViewMode.CLOSE -> StakeoutMapPreview(
                project, points, target, gnss, pointDisplaySettings,
                modifier = Modifier.fillMaxSize(), closeView = true
            )
            StakeoutViewMode.PRECISION -> Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(top = 76.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    StakeoutGuidancePanel(project = project, target = target, gnss = gnss)
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopCenter)
                .padding(10.dp),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Box {
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text(viewMode.label + " ▾")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        StakeoutViewMode.entries.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label) },
                                onClick = {
                                    onViewModeChange(item)
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onStop) { Text("Salir") }
            }
        }

        if (viewMode != StakeoutViewMode.PRECISION) {
            StakeoutCompactOverlay(
                project = project,
                target = target,
                gnss = gnss,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .padding(10.dp)
            )
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

    Card(modifier) {
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
    target: SurveyPoint?,
    gnss: GnssStatus,
    pointDisplaySettings: PointDisplaySettings,
    modifier: Modifier = Modifier.fillMaxWidth().height(280.dp),
    closeView: Boolean = false
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

    fun redrawGuidance(map: MapLibreMap) {
        map.clear()

        map.style?.let { style ->
            ensureTopoSurveyPointLayers(
                style = style,
                prefix = "stakeout-points",
                points = points,
                settings = pointDisplaySettings,
                selectedPointId = target?.id
            )
        }

        val lat = gnss.latitude
        val lon = gnss.longitude
        val tLat = target?.latitude
        val tLon = target?.longitude

        val current = if (lat != null && lon != null) LatLng(lat, lon) else null
        val objective = if (tLat != null && tLon != null) LatLng(tLat, tLon) else null

        // Annotation markers are deliberately used in addition to the adaptive
        // SymbolLayer labels. MapLibre annotations remain visually above raster
        // basemaps/WMS, so neither the target nor the RTK can be hidden by a layer.
        val iconFactory = IconFactory.getInstance(context)
        val normalIcon = iconFactory.fromBitmap(
            makeTopoPointBitmap(android.graphics.Color.rgb(255, 45, 45))
        )
        val targetIcon = iconFactory.fromBitmap(
            makeTopoPointBitmap(android.graphics.Color.rgb(255, 214, 0))
        )
        val rtkIcon = iconFactory.fromBitmap(
            makeTopoPointBitmap(android.graphics.Color.rgb(0, 188, 212))
        )

        points.forEach { point ->
            val pLat = point.latitude ?: return@forEach
            val pLon = point.longitude ?: return@forEach
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(pLat, pLon))
                    .icon(if (point.id == target?.id) targetIcon else normalIcon)
                    .title(
                        if (point.id == target?.id)
                            "OBJETIVO • Punto ${point.pointNumber}"
                        else
                            "Punto ${point.pointNumber}"
                    )
            )
        }

        current?.let {
            map.addMarker(
                MarkerOptions()
                    .position(it)
                    .icon(rtkIcon)
                    .title("RTK • posición actual")
            )
        }

        if (current != null && objective != null) {
            val north = (objective.latitude - current.latitude) * 111132.0
            val east = (objective.longitude - current.longitude) *
                (111320.0 * cos(Math.toRadians(objective.latitude)))
            val totalM = hypot(north, east)
            val dashM = when {
                totalM > 1000.0 -> 40.0
                totalM > 250.0 -> 20.0
                totalM > 50.0 -> 8.0
                else -> 2.0
            }
            val pieces = max(1, ceil(totalM / dashM).toInt())
            for (i in 0 until pieces step 2) {
                val t0 = i.toDouble() / pieces.toDouble()
                val t1 = min(1.0, (i + 1).toDouble() / pieces.toDouble())
                val a = LatLng(
                    current.latitude + (objective.latitude - current.latitude) * t0,
                    current.longitude + (objective.longitude - current.longitude) * t0
                )
                val b = LatLng(
                    current.latitude + (objective.latitude - current.latitude) * t1,
                    current.longitude + (objective.longitude - current.longitude) * t1
                )
                map.addPolyline(
                    PolylineOptions()
                        .add(a)
                        .add(b)
                        .width(7f)
                        .color(android.graphics.Color.rgb(103, 58, 183))
                )
            }
        }
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
                    redrawGuidance(map)

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

                        if (!closeView && distance > 20.0) {
                            val bounds = LatLngBounds.Builder()
                                .include(current)
                                .include(objective)
                                .build()
                            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 70))
                        } else {
                            val zoom = if (closeView) {
                                when {
                                    distance > 5.0 -> 19.0
                                    distance > 1.0 -> 20.5
                                    else -> 21.5
                                }
                            } else {
                                when {
                                    distance > 5.0 -> 18.0
                                    distance > 0.5 -> 19.5
                                    else -> 21.0
                                }
                            }
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(current, zoom))
                        }
                    }
                }
            }
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


@Composable
private fun StakeoutGuidancePanel(
    project: TopoProject?,
    target: SurveyPoint?,
    gnss: GnssStatus
) {
    if (target == null || target.latitude == null || target.longitude == null) {
        Card(Modifier.fillMaxWidth()) {
            Text("Seleccione un punto con coordenadas para iniciar la guía.", modifier = Modifier.padding(12.dp))
        }
        return
    }

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
    val northM = (target.latitude - lat) * metersPerDegLat
    val eastM = (target.longitude - lon) * metersPerDegLon
    val distanceM = hypot(northM, eastM)

    val context = LocalContext.current
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

    val stage = when {
        distanceM > 5.0 -> "Aproximación"
        distanceM > 0.5 -> "Zona de tolerancia"
        else -> "Mira de precisión"
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stage, fontWeight = FontWeight.Bold)
            Text("Distancia al punto: %.3f m".format(distanceM))
            Text("Corrección E/O: %.3f m".format(eastM))
            Text("Corrección N/S: %.3f m".format(northM))
            if (verticalDelta != null) {
                val verticalText = when {
                    verticalDelta > 0.005 -> "RELLENO %.3f m".format(verticalDelta)
                    verticalDelta < -0.005 -> "CORTE %.3f m".format(abs(verticalDelta))
                    else -> "COTA OK ±0.005 m"
                }
                Text(verticalText, fontWeight = FontWeight.Bold)
                Text("Diferencia vertical: %.3f m".format(verticalDelta))
                Text("Cota objetivo: %.3f m".format(targetElevation))
                Text("Cota actual: %.3f m".format(currentElevation))
                Text(
                    if (useGeoid) "Vertical: EGM2008 / geoide del proyecto" else "Vertical: elipsoidal",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text("Sin comparación vertical: falta cota objetivo o cota GNSS.")
            }
            Spacer(Modifier.height(12.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxR = min(size.width, size.height) * 0.42f

                    drawCircle(
                        color = Color(0x22000000),
                        radius = maxR,
                        center = center
                    )

                    drawCircle(
                        color = Color(0x33000000),
                        radius = maxR * 0.66f,
                        center = center
                    )

                    drawCircle(
                        color = Color(0x55000000),
                        radius = maxR * 0.33f,
                        center = center
                    )

                    drawLine(
                        color = Color.Black,
                        start = Offset(center.x - maxR, center.y),
                        end = Offset(center.x + maxR, center.y),
                        strokeWidth = 2f
                    )
                    drawLine(
                        color = Color.Black,
                        start = Offset(center.x, center.y - maxR),
                        end = Offset(center.x, center.y + maxR),
                        strokeWidth = 2f
                    )

                    val displayScaleM = when {
                        distanceM > 5.0 -> 10.0
                        distanceM > 0.5 -> 2.0
                        else -> 0.5
                    }
                    val dx = (eastM / displayScaleM * maxR).toFloat().coerceIn(-maxR, maxR)
                    val dy = (-northM / displayScaleM * maxR).toFloat().coerceIn(-maxR, maxR)

                    drawCircle(
                        color = Color.Black,
                        radius = 10f,
                        center = Offset(center.x + dx, center.y + dy)
                    )
                }
            }

            Text(
                when {
                    distanceM > 5.0 -> "Acérquese al objetivo siguiendo el mapa."
                    distanceM > 0.5 -> "Entre en el círculo de tolerancia."
                    else -> "Lleve el punto negro al centro de la mira."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
