package cr.co.topoemlid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val SIRI_WMS =
    "https://siri.snitcr.go.cr/Geoservicios/wms?request=GetCapabilities"

private const val CATASTRO_CARTOGRAFIA_WMS =
    "https://www.snitcr.go.cr/servicios/cartografia/wms?"

private const val SNIT_CURRENT_IGN5_WMS =
    "https://geos.snitcr.go.cr/be/IGN_5/wms?"

private fun builtInNationalCadastreLayers(): List<LayerItem> = listOf(
    LayerItem(
        id = "builtin-catastro-zona1",
        name = "Catastro Nacional • Zona 1",
        type = LayerType.WMS,
        visible = false,
        opacity = 1f,
        url = SIRI_WMS,
        layerName = "catastro",
        imageFormat = "image/png",
        transparent = true,
        crs = "EPSG:3857",
        order = 0
    ),
    LayerItem(
        id = "builtin-catastro-zona2",
        name = "Catastro Nacional • Zona 2",
        type = LayerType.WMS,
        visible = false,
        opacity = 1f,
        url = SIRI_WMS,
        layerName = "catastro_aldia",
        imageFormat = "image/png",
        transparent = true,
        crs = "EPSG:3857",
        order = 1
    ),
    LayerItem(
        id = "builtin-catastro-vias",
        name = "Catastro Nacional • Vías públicas",
        type = LayerType.WMS,
        visible = false,
        opacity = 1f,
        url = SIRI_WMS,
        layerName = "vias_publicas",
        imageFormat = "image/png",
        transparent = true,
        crs = "EPSG:3857",
        order = 2
    ),
    LayerItem(
        id = "builtin-catastro-zona-catastrada",
        name = "Catastro Nacional • Mosaico de predios",
        type = LayerType.WMS,
        visible = false,
        opacity = 1f,
        url = CATASTRO_CARTOGRAFIA_WMS,
        layerName = "zona_catastrada",
        imageFormat = "image/png",
        transparent = true,
        crs = "EPSG:4326",
        order = 3
    ),
    LayerItem(
        id = "builtin-snit-current-ign5-test",
        name = "SNIT actual • Prueba IGN 1:5 mil",
        type = LayerType.WMS,
        visible = false,
        opacity = 1f,
        url = SNIT_CURRENT_IGN5_WMS,
        layerName = "AUTO_GETCAPABILITIES",
        imageFormat = "image/png",
        transparent = true,
        crs = "EPSG:4326",
        order = 4
    )
)

class LayerStore(context: Context) {
    private val prefs = context.getSharedPreferences("project_layers", Context.MODE_PRIVATE)

    fun loadLibrary(): List<LayerItem> {
        val saved = loadKey("global_library")
        val builtIns = builtInNationalCadastreLayers()
        val builtInIds = builtIns.map { it.id }.toSet()

        val merged = builtIns.map { builtIn ->
            saved.firstOrNull { it.id == builtIn.id }?.copy(
                // Keep official endpoint and technical layer protected from
                // accidental edits while preserving visibility/opacity.
                name = builtIn.name,
                type = builtIn.type,
                url = builtIn.url,
                layerName = builtIn.layerName,
                imageFormat = builtIn.imageFormat,
                transparent = builtIn.transparent,
                crs = builtIn.crs
            ) ?: builtIn
        } + saved.filterNot { it.id in builtInIds }

        val ordered = merged.mapIndexed { index, item -> item.copy(order = index) }
        if (ordered != saved) saveKey("global_library", ordered)
        return ordered
    }

    fun saveLibrary(layers: List<LayerItem>) {
        val builtIns = builtInNationalCadastreLayers()
        val builtInIds = builtIns.map { it.id }.toSet()

        val normalizedBuiltIns = builtIns.map { builtIn ->
            layers.firstOrNull { it.id == builtIn.id }?.copy(
                name = builtIn.name,
                type = builtIn.type,
                url = builtIn.url,
                layerName = builtIn.layerName,
                imageFormat = builtIn.imageFormat,
                transparent = builtIn.transparent,
                crs = builtIn.crs
            ) ?: builtIn
        }
        val custom = layers.filterNot { it.id in builtInIds }
        saveKey(
            "global_library",
            (normalizedBuiltIns + custom).mapIndexed { index, item -> item.copy(order = index) }
        )
    }

    fun load(projectId: String): List<LayerItem> {
        return loadKey("layers_$projectId")
    }

    private fun loadKey(key: String): List<LayerItem> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                LayerItem(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    type = runCatching { LayerType.valueOf(o.getString("type")) }.getOrDefault(LayerType.DRAWING),
                    visible = o.optBoolean("visible", true),
                    opacity = o.optDouble("opacity", 1.0).toFloat(),
                    url = o.optString("url", "").ifBlank { null },
                    layerName = o.optString("layerName", "").ifBlank { null },
                    styleName = o.optString("styleName", "").ifBlank { null },
                    imageFormat = o.optString("imageFormat", "image/png"),
                    transparent = o.optBoolean("transparent", true),
                    crs = o.optString("crs", "EPSG:3857"),
                    localUri = o.optString("localUri", "").ifBlank { null },
                    order = o.optInt("order", i)
                )
            }.sortedBy { it.order }
        }.getOrDefault(emptyList())
    }

    fun save(projectId: String, layers: List<LayerItem>) {
        saveKey("layers_$projectId", layers)
    }

    private fun saveKey(key: String, layers: List<LayerItem>) {
        val a = JSONArray()
        layers.forEachIndexed { index, l ->
            a.put(JSONObject().apply {
                put("id", l.id)
                put("name", l.name)
                put("type", l.type.name)
                put("visible", l.visible)
                put("opacity", l.opacity.toDouble())
                put("url", l.url ?: "")
                put("layerName", l.layerName ?: "")
                put("styleName", l.styleName ?: "")
                put("imageFormat", l.imageFormat)
                put("transparent", l.transparent)
                put("crs", l.crs)
                put("localUri", l.localUri ?: "")
                put("order", index)
            })
        }
        prefs.edit().putString(key, a.toString()).apply()
    }
}
