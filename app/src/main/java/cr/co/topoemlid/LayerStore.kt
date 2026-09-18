package cr.co.topoemlid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LayerStore(context: Context) {
    private val prefs = context.getSharedPreferences("project_layers", Context.MODE_PRIVATE)

    fun load(projectId: String): List<LayerItem> {
        val raw = prefs.getString("layers_$projectId", null) ?: return emptyList()
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
        prefs.edit().putString("layers_$projectId", a.toString()).apply()
    }
}
