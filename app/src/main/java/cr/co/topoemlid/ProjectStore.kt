package cr.co.topoemlid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("topo_projects", Context.MODE_PRIVATE)

    fun loadProjects(): List<TopoProject> {
        val raw = prefs.getString("projects", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                TopoProject(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    location = o.optString("location", ""),
                    crsName = o.optString("crsName", "CRTM05"),
                    geoidModel = runCatching {
                        GeoidModel.valueOf(o.optString("geoidModel", GeoidModel.LOCAL_FILE.name))
                    }.getOrDefault(GeoidModel.LOCAL_FILE),
                    geoidFileUri = o.optString("geoidFileUri", "").ifBlank { null },
                    geoidFileName = o.optString("geoidFileName", "").ifBlank { null },
                    antennaHeightM = o.optDouble("antennaHeightM", 2.0),
                    ntripProfileName = o.optString("ntripProfileName", "").ifBlank { null },
                    ntripProfileId = o.optString("ntripProfileId", "").ifBlank { null },
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveProjects(projects: List<TopoProject>) {
        val array = JSONArray()
        projects.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("location", p.location)
                put("crsName", p.crsName)
                put("geoidModel", p.geoidModel.name)
                put("geoidFileUri", p.geoidFileUri ?: "")
                put("geoidFileName", p.geoidFileName ?: "")
                put("antennaHeightM", p.antennaHeightM)
                put("ntripProfileName", p.ntripProfileName ?: "")
                put("ntripProfileId", p.ntripProfileId ?: "")
                put("createdAt", p.createdAt)
            })
        }
        prefs.edit().putString("projects", array.toString()).apply()
    }

    fun activeProjectId(): String? = prefs.getString("active_project_id", null)

    fun setActiveProject(id: String?) {
        prefs.edit().apply {
            if (id == null) remove("active_project_id") else putString("active_project_id", id)
        }.apply()
    }
}
