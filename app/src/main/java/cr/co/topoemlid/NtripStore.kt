package cr.co.topoemlid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class NtripStore(context: Context) {
    private val prefs = context.getSharedPreferences("ntrip_profiles", Context.MODE_PRIVATE)

    fun loadProfiles(): List<NtripProfile> {
        val raw = prefs.getString("profiles", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                NtripProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.optInt("port", 2101),
                    mountPoint = o.optString("mountPoint", ""),
                    username = o.optString("username", ""),
                    password = o.optString("password", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveProfiles(profiles: List<NtripProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("host", p.host)
                put("port", p.port)
                put("mountPoint", p.mountPoint)
                put("username", p.username)
                put("password", p.password)
            })
        }
        prefs.edit().putString("profiles", array.toString()).apply()
    }
}
