package cr.co.topoemlid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SurveyPointStore(context: Context) {
    private val prefs = context.getSharedPreferences("survey_points", Context.MODE_PRIVATE)

    fun load(projectId: String): List<SurveyPoint> {
        val raw = prefs.getString("points_$projectId", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                SurveyPoint(
                    id = o.getString("id"),
                    projectId = projectId,
                    pointNumber = o.getString("pointNumber"),
                    description = o.optString("description", ""),
                    code = o.optString("code", ""),
                    antennaHeightM = o.optDouble("antennaHeightM", 0.0),
                    occupationSeconds = o.optInt("occupationSeconds", 1),
                    latitude = if (o.isNull("latitude")) null else o.optDouble("latitude"),
                    longitude = if (o.isNull("longitude")) null else o.optDouble("longitude"),
                    ellipsoidalHeightM = if (o.isNull("ellipsoidalHeightM")) null else o.optDouble("ellipsoidalHeightM"),
                    horizontalAccuracyM = if (o.isNull("horizontalAccuracyM")) null else o.optDouble("horizontalAccuracyM"),
                    verticalAccuracyM = if (o.isNull("verticalAccuracyM")) null else o.optDouble("verticalAccuracyM"),
                    solution = o.optString("solution", "SIN SOLUCIÓN"),
                    satellites = if (o.isNull("satellites")) null else o.optInt("satellites"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(projectId: String, points: List<SurveyPoint>) {
        val a = JSONArray()
        points.forEach { p ->
            a.put(JSONObject().apply {
                put("id", p.id)
                put("pointNumber", p.pointNumber)
                put("description", p.description)
                put("code", p.code)
                put("antennaHeightM", p.antennaHeightM)
                put("occupationSeconds", p.occupationSeconds)
                put("latitude", p.latitude ?: JSONObject.NULL)
                put("longitude", p.longitude ?: JSONObject.NULL)
                put("ellipsoidalHeightM", p.ellipsoidalHeightM ?: JSONObject.NULL)
                put("horizontalAccuracyM", p.horizontalAccuracyM ?: JSONObject.NULL)
                put("verticalAccuracyM", p.verticalAccuracyM ?: JSONObject.NULL)
                put("solution", p.solution)
                put("satellites", p.satellites ?: JSONObject.NULL)
                put("createdAt", p.createdAt)
            })
        }
        prefs.edit().putString("points_$projectId", a.toString()).apply()
    }
}
