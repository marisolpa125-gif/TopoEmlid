package cr.co.topoemlid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ReceiverStore(context: Context) {
    private val prefs = context.getSharedPreferences("receiver_profiles", Context.MODE_PRIVATE)

    fun loadProfiles(): List<ReceiverProfile> {
        val raw = prefs.getString("profiles", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ReceiverProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    address = o.getString("address"),
                    transport = o.optString("transport", "Bluetooth"),
                    lastConnectedAt = if (o.has("lastConnectedAt") && !o.isNull("lastConnectedAt")) o.getLong("lastConnectedAt") else null
                )
            }
        }.getOrDefault(emptyList())
    }

    fun activeReceiverId(): String? = prefs.getString("active_receiver_id", null)

    fun setActiveReceiver(id: String?) {
        prefs.edit().apply {
            if (id == null) remove("active_receiver_id") else putString("active_receiver_id", id)
        }.apply()
    }

    fun saveProfiles(profiles: List<ReceiverProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("address", p.address)
                put("transport", p.transport)
                put("lastConnectedAt", p.lastConnectedAt)
            })
        }
        prefs.edit().putString("profiles", array.toString()).apply()
    }
}
