package cr.co.topoemlid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class SimOperator(val label: String) {
    AUTO("Automático"),
    KOLBI("kölbi"),
    CLARO("Claro"),
    LIBERTY("Liberty"),
    OTHER("Otro");

    companion object {
        fun detect(name: String?): SimOperator {
            val n = name.orEmpty().lowercase()
            return when {
                "kolbi" in n || "kölbi" in n || "ice" in n -> KOLBI
                "claro" in n -> CLARO
                "liberty" in n || "movistar" in n -> LIBERTY
                else -> OTHER
            }
        }
    }
}

data class SimProfile(
    val id: String,
    val name: String,
    val operator: SimOperator = SimOperator.AUTO,
    val phoneNumber: String = "",
    val apn: String = "",
    val username: String = "",
    val password: String = "",
    val pin: String = "",
    val detectedOperatorName: String? = null
)

class SimProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("sim_profiles", Context.MODE_PRIVATE)

    fun loadProfiles(): List<SimProfile> {
        val raw = prefs.getString("profiles", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SimProfile(
                    id = o.getString("id"),
                    name = o.optString("name", "SIM"),
                    operator = runCatching {
                        SimOperator.valueOf(o.optString("operator", SimOperator.AUTO.name))
                    }.getOrDefault(SimOperator.AUTO),
                    phoneNumber = o.optString("phoneNumber", ""),
                    apn = o.optString("apn", ""),
                    username = o.optString("username", ""),
                    password = o.optString("password", ""),
                    pin = o.optString("pin", ""),
                    detectedOperatorName = o.optString("detectedOperatorName", "").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveProfiles(profiles: List<SimProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("operator", p.operator.name)
                put("phoneNumber", p.phoneNumber)
                put("apn", p.apn)
                put("username", p.username)
                put("password", p.password)
                put("pin", p.pin)
                put("detectedOperatorName", p.detectedOperatorName)
            })
        }
        prefs.edit().putString("profiles", array.toString()).apply()
    }

    fun activeProfileId(): String? = prefs.getString("active_profile_id", null)

    fun setActiveProfile(id: String?) {
        prefs.edit().apply {
            if (id == null) remove("active_profile_id") else putString("active_profile_id", id)
        }.apply()
    }
}
