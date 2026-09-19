package cr.co.topoemlid

import android.content.Context

enum class BasemapType(val label: String) {
    NONE("Sin mapa base"),
    BASIC("Mapa básico"),
    MAPBOX_STREETS("Mapbox calles"),
    MAPBOX_SATELLITE("Mapbox satélite")
}

class BasemapStore(context: Context) {
    private val prefs = context.getSharedPreferences("basemap_settings", Context.MODE_PRIVATE)

    fun selected(projectId: String?): BasemapType {
        val projectKey = projectId?.let { "selected_$it" }
        val raw = when {
            projectKey != null && prefs.contains(projectKey) -> prefs.getString(projectKey, null)
            else -> prefs.getString("selected_global", BasemapType.BASIC.name)
        }
        return runCatching { BasemapType.valueOf(raw ?: BasemapType.BASIC.name) }
            .getOrDefault(BasemapType.BASIC)
    }

    fun setSelected(projectId: String?, type: BasemapType) {
        prefs.edit().apply {
            putString("selected_global", type.name)
            if (projectId != null) putString("selected_$projectId", type.name)
        }.apply()
    }

    fun mapboxToken(): String = prefs.getString("mapbox_public_token", "") ?: ""

    fun setMapboxToken(token: String) {
        prefs.edit().putString("mapbox_public_token", token.trim()).apply()
    }
}
