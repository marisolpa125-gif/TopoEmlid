package cr.co.topoemlid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

data class PointDisplaySettings(
    val showNumber: Boolean = true,
    val showDescription: Boolean = true,
    val showElevation: Boolean = true
)

class PointDisplaySettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("point_display_settings", Context.MODE_PRIVATE)

    fun load(): PointDisplaySettings = PointDisplaySettings(
        showNumber = prefs.getBoolean("show_number", true),
        showDescription = prefs.getBoolean("show_description", true),
        showElevation = prefs.getBoolean("show_elevation", true)
    )

    fun save(value: PointDisplaySettings) {
        prefs.edit()
            .putBoolean("show_number", value.showNumber)
            .putBoolean("show_description", value.showDescription)
            .putBoolean("show_elevation", value.showElevation)
            .apply()
    }
}


@Composable
fun AlwaysVisiblePointOverlay(
    map: MapLibreMap?,
    cameraVersion: Int,
    points: List<SurveyPoint>,
    gnss: GnssStatus,
    settings: PointDisplaySettings,
    modifier: Modifier = Modifier,
    selectedPointId: String? = null
) {
    val refreshToken = cameraVersion

    androidx.compose.foundation.Canvas(modifier = modifier) {
        refreshToken.hashCode()
        val currentMap = map ?: return@Canvas
        val native = drawContext.canvas.nativeCanvas
        val zoom = currentMap.cameraPosition.zoom

        val scale = when {
            zoom < 13.0 -> 0.58f
            zoom < 15.0 -> 0.68f
            zoom < 17.0 -> 0.82f
            zoom < 19.0 -> 0.96f
            else -> 1.10f
        }

        val pointRadius = 11f * scale
        val crossHalf = 20f * scale
        val stroke = 3.2f * scale
        val haloStroke = 6.0f * scale

        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = haloStroke
            color = android.graphics.Color.WHITE
        }
        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }

        val textSizePx = when {
            zoom < 13.0 -> 8.5f
            zoom < 15.0 -> 9.5f
            zoom < 17.0 -> 11.0f
            zoom < 19.0 -> 12.5f
            else -> 14.0f
        } * density

        val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.rgb(25, 25, 25)
            textSize = textSizePx
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val textHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * density
            color = android.graphics.Color.WHITE
            textSize = textSizePx
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        points.forEach { p ->
            val lat = p.latitude ?: return@forEach
            val lon = p.longitude ?: return@forEach
            val screen = currentMap.projection.toScreenLocation(LatLng(lat, lon))
            val x = screen.x
            val y = screen.y
            if (x < -120f || y < -120f || x > size.width + 120f || y > size.height + 120f) {
                return@forEach
            }

            pointPaint.color = if (p.id == selectedPointId) {
                android.graphics.Color.rgb(255, 193, 7)
            } else {
                android.graphics.Color.rgb(230, 45, 45)
            }

            native.drawCircle(x, y, pointRadius, haloPaint)
            native.drawLine(x - crossHalf, y, x + crossHalf, y, haloPaint)
            native.drawLine(x, y - crossHalf, x, y + crossHalf, haloPaint)
            native.drawCircle(x, y, pointRadius, pointPaint)
            native.drawLine(x - crossHalf, y, x + crossHalf, y, pointPaint)
            native.drawLine(x, y - crossHalf, x, y + crossHalf, pointPaint)

            val labelParts = mutableListOf<String>()
            if (settings.showNumber && p.pointNumber.isNotBlank()) {
                labelParts += "P" + p.pointNumber
            }
            if (settings.showDescription) {
                val desc = p.description.ifBlank { p.code }
                if (desc.isNotBlank()) labelParts += desc
            }
            if (settings.showElevation) {
                val elevation = p.orthometricHeightM ?: p.ellipsoidalHeightM
                if (elevation != null) labelParts += "%.3f m".format(elevation)
            }

            if (labelParts.isNotEmpty()) {
                val label = labelParts.joinToString(" • ")
                val tx = x + crossHalf + 5f * density
                val ty = y + textSizePx * 0.35f
                native.drawText(label, tx, ty, textHalo)
                native.drawText(label, tx, ty, textFill)
            }
        }

        if (gnss.connected && gnss.latitude != null && gnss.longitude != null) {
            val screen = currentMap.projection.toScreenLocation(
                LatLng(gnss.latitude!!, gnss.longitude!!)
            )
            val x = screen.x
            val y = screen.y
            val color = when {
                gnss.solution.contains("FIX", ignoreCase = true) ->
                    android.graphics.Color.rgb(46, 125, 50)
                gnss.solution.contains("FLOAT", ignoreCase = true) ->
                    android.graphics.Color.rgb(249, 168, 37)
                else ->
                    android.graphics.Color.rgb(198, 40, 40)
            }

            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = color
            }
            val receiverHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = android.graphics.Color.argb(
                    82,
                    android.graphics.Color.red(color),
                    android.graphics.Color.green(color),
                    android.graphics.Color.blue(color)
                )
            }
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f * scale
                this.color = android.graphics.Color.WHITE
            }

            native.drawCircle(x, y, 24f * scale, receiverHalo)
            native.drawCircle(x, y, 11f * scale, fill)
            native.drawCircle(x, y, 12f * scale, outline)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointDisplaySettingsSheet(
    value: PointDisplaySettings,
    onChange: (PointDisplaySettings) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text("Visualización de puntos", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Automático por zoom: lejos solo símbolo; zoom medio número; cerca muestra los datos seleccionados. Las etiquetas evitan superponerse cuando no hay espacio.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(14.dp))

            PointDisplaySwitch("Número", value.showNumber) {
                onChange(value.copy(showNumber = it))
            }
            PointDisplaySwitch("Descripción", value.showDescription) {
                onChange(value.copy(showDescription = it))
            }
            PointDisplaySwitch("Elevación", value.showElevation) {
                onChange(value.copy(showElevation = it))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Símbolo normal: rojo vivo. Punto seleccionado / objetivo de replanteo: amarillo.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Listo")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PointDisplaySwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

fun ensureTopoSurveyPointLayers(
    style: Style,
    prefix: String,
    points: List<SurveyPoint>,
    settings: PointDisplaySettings,
    selectedPointId: String? = null
) {
    val valid = points.filter { it.latitude != null && it.longitude != null }
    val normal = valid.filterNot { it.id == selectedPointId }
    val selected = valid.filter { it.id == selectedPointId }

    ensurePointImages(style)
    ensurePointGroup(style, "${prefix}-normal", normal, settings, selected = false)
    ensurePointGroup(style, "${prefix}-selected", selected, settings, selected = true)
}

private fun ensurePointImages(style: Style) {
    if (style.getImage("topo-point-red") == null) {
        style.addImage("topo-point-red", makeTopoPointBitmap(android.graphics.Color.rgb(255, 45, 45)))
    }
    if (style.getImage("topo-point-yellow") == null) {
        style.addImage("topo-point-yellow", makeTopoPointBitmap(android.graphics.Color.rgb(255, 214, 0)))
    }
    if (style.getImage("topo-rtk-cyan") == null) {
        style.addImage("topo-rtk-cyan", makeTopoPointBitmap(android.graphics.Color.rgb(0, 188, 212)))
    }
}

fun makeTopoPointBitmap(color: Int): Bitmap {
    val size = 64
    val center = size / 2f
    val circleRadius = 13f
    val crossHalf = 23f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        this.color = android.graphics.Color.WHITE
    }
    val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        this.color = color
    }

    canvas.drawCircle(center, center, circleRadius, halo)
    canvas.drawLine(center - crossHalf, center, center + crossHalf, center, halo)
    canvas.drawLine(center, center - crossHalf, center, center + crossHalf, halo)

    canvas.drawCircle(center, center, circleRadius, main)
    canvas.drawLine(center - crossHalf, center, center + crossHalf, center, main)
    canvas.drawLine(center, center - crossHalf, center, center + crossHalf, main)
    return bitmap
}

private fun pointFeature(point: SurveyPoint, settings: PointDisplaySettings): Feature {
    val feature = Feature.fromGeometry(Point.fromLngLat(point.longitude!!, point.latitude!!))
    val number = if (settings.showNumber) point.pointNumber else ""
    val nearParts = mutableListOf<String>()
    if (settings.showNumber && point.pointNumber.isNotBlank()) nearParts += point.pointNumber
    if (settings.showDescription) {
        val text = point.description.ifBlank { point.code }
        if (text.isNotBlank()) nearParts += text
    }
    if (settings.showElevation) {
        point.ellipsoidalHeightM?.let { nearParts += "%.3f m".format(it) }
    }
    feature.addStringProperty("number", number)
    feature.addStringProperty("detail", nearParts.joinToString("  "))
    return feature
}

private fun ensurePointGroup(
    style: Style,
    id: String,
    points: List<SurveyPoint>,
    settings: PointDisplaySettings,
    selected: Boolean
) {
    val sourceId = "$id-source"
    val features = points.map { pointFeature(it, settings) }
    val source = style.getSourceAs<GeoJsonSource>(sourceId)
    if (source == null) {
        style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(features)))
    } else {
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    val iconName = if (selected) "topo-point-yellow" else "topo-point-red"

    val iconBands = listOf(
        Triple("far", 0f, 15f),
        Triple("mid", 15f, 18f),
        Triple("near", 18f, 25f)
    )
    val iconSizes = mapOf("far" to 0.68f, "mid" to 0.84f, "near" to 1.02f)

    iconBands.forEach { (name, minZoom, maxZoom) ->
        val layerId = "$id-icon-$name"
        runCatching { style.removeLayer(layerId) }
        val layer = SymbolLayer(layerId, sourceId).withProperties(
            PropertyFactory.iconImage(iconName),
            PropertyFactory.iconSize(iconSizes[name] ?: 0.8f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        )
        layer.setMinZoom(minZoom)
        layer.setMaxZoom(maxZoom)
        style.addLayer(layer)
    }

    val anyTextEnabled =
        settings.showNumber || settings.showDescription || settings.showElevation

    listOf("far","mid","near").forEach { name ->
        runCatching { style.removeLayer("$id-text-$name") }
    }

    if (anyTextEnabled) {
        val textBands = listOf(
            Triple("far", 0f, 15f),
            Triple("mid", 15f, 18f),
            Triple("near", 18f, 25f)
        )
        val textSizes = mapOf("far" to 8.5f, "mid" to 10.5f, "near" to 13f)
        val offsets = mapOf("far" to 1.20f, "mid" to 1.35f, "near" to 1.55f)

        textBands.forEach { (name, minZoom, maxZoom) ->
            val layerId = "$id-text-$name"
            val layer = SymbolLayer(layerId, sourceId).withProperties(
                PropertyFactory.textField(Expression.get("detail")),
                PropertyFactory.textColor(android.graphics.Color.rgb(25, 25, 25)),
                PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
                PropertyFactory.textHaloWidth(if (name == "far") 1.5f else 2.5f),
                PropertyFactory.textSize(textSizes[name] ?: 11f),
                PropertyFactory.textOffset(arrayOf(offsets[name] ?: 1.4f, 0f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true)
            )
            layer.setMinZoom(minZoom)
            layer.setMaxZoom(maxZoom)
            style.addLayer(layer)
        }
    }
}

fun ensureTopoRtkLayer(
    style: Style,
    prefix: String,
    latitude: Double?,
    longitude: Double?
) {
    ensurePointImages(style)

    val sourceId = "$prefix-source"
    val layerId = "$prefix-layer"
    val features = if (latitude != null && longitude != null) {
        listOf(Feature.fromGeometry(Point.fromLngLat(longitude, latitude)))
    } else {
        emptyList()
    }

    val source = style.getSourceAs<GeoJsonSource>(sourceId)
    if (source == null) {
        style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(features)))
    } else {
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    runCatching { style.removeLayer(layerId) }
    style.addLayer(
        SymbolLayer(layerId, sourceId).withProperties(
            PropertyFactory.iconImage("topo-rtk-cyan"),
            PropertyFactory.iconSize(0.82f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        )
    )
}
