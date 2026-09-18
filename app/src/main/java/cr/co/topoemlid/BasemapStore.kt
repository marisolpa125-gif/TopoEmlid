package cr.co.topoemlid

import android.content.Context

enum class BasemapType(val label: String) {
    BASIC("Mapa básico"),
    MAPBOX_STREETS("Mapbox calles"),
    MAPBOX_SATELLITE("Mapbox satélite")
}

class BasemapStore(context: Context) {
    private val prefs = context.getSharedPreferences("basemap_settings", Context.MODE_PRIVATE)

    fun selected(projectId: String?): BasemapType {
        val raw = prefs.getString("selected_${projectId ?: "global"}", BasemapType.BASIC.name)
        return runCatching { BasemapType.valueOf(raw ?: BasemapType.BASIC.name) }
            .getOrDefault(BasemapType.BASIC)
    }

    fun setSelected(projectId: String?, type: BasemapType) {
        prefs.edit().putString("selected_${projectId ?: "global"}", type.name).apply()
    }

    fun mapboxToken(): String = prefs.getString("mapbox_public_token", "") ?: ""

    fun setMapboxToken(token: String) {
        prefs.edit().putString("mapbox_public_token", token.trim()).apply()
    }
}
