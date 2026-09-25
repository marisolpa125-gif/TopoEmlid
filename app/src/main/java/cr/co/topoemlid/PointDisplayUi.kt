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
import androidx.compose.ui.unit.dp
import org.maplibre.android.maps.Style
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
    val bands = listOf(
        Triple("far", 0f, 14.5f),
        Triple("mid", 14.5f, 17.5f),
        Triple("near", 17.5f, 24f)
    )
    // Mantener los puntos muy visibles en todo nivel de zoom.
    // Al acercarse crecen ligeramente en pantalla en vez de reducirse.
    val sizes = mapOf("far" to 0.82f, "mid" to 0.96f, "near" to 1.10f)

    bands.forEach { (name, minZoom, maxZoom) ->
        val layerId = "$id-icon-$name"
        runCatching { style.removeLayer(layerId) }
        val layer = SymbolLayer(layerId, sourceId).withProperties(
            PropertyFactory.iconImage(iconName),
            PropertyFactory.iconSize(sizes[name] ?: 0.8f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        )
        layer.setMinZoom(minZoom)
        layer.setMaxZoom(maxZoom)
        style.addLayer(layer)
    }

    val midTextId = "$id-text-mid"
    runCatching { style.removeLayer(midTextId) }
    val midText = SymbolLayer(midTextId, sourceId).withProperties(
        PropertyFactory.textField(Expression.get("number")),
        PropertyFactory.textColor(android.graphics.Color.rgb(35, 35, 35)),
        PropertyFactory.textSize(13f),
        PropertyFactory.textOffset(arrayOf(1.55f, 0f)),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
        PropertyFactory.textAllowOverlap(false),
        PropertyFactory.textIgnorePlacement(false)
    )
    midText.setMinZoom(14.5f)
    midText.setMaxZoom(17.5f)
    style.addLayer(midText)

    val nearTextId = "$id-text-near"
    runCatching { style.removeLayer(nearTextId) }
    val nearText = SymbolLayer(nearTextId, sourceId).withProperties(
        PropertyFactory.textField(Expression.get("detail")),
        PropertyFactory.textColor(android.graphics.Color.rgb(35, 35, 35)),
        PropertyFactory.textSize(12.5f),
        PropertyFactory.textOffset(arrayOf(1.45f, 0f)),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
        PropertyFactory.textAllowOverlap(false),
        PropertyFactory.textIgnorePlacement(false)
    )
    nearText.setMinZoom(17.5f)
    style.addLayer(nearText)
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
