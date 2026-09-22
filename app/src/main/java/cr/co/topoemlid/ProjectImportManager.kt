package cr.co.topoemlid

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

enum class ProjectImportFormat(val label: String) {
    AUTO("Detectar automáticamente"),
    TXT("TXT"),
    CSV("CSV"),
    GEOJSON("GeoJSON"),
    KML("KML"),
    DXF("DXF"),
    SHAPE_ZIP("Shapefile ZIP")
}

enum class ImportSourceCrs(val label: String) {
    PROJECT("Mismo sistema del proyecto"),
    WGS84("WGS 84 geográficas"),
    CRTM05("CRTM05"),
    CR_SIRGAS("CR-SIRGAS / CRTM05")
}

data class ProjectImportOptions(
    val format: ProjectImportFormat = ProjectImportFormat.AUTO,
    val sourceCrs: ImportSourceCrs = ImportSourceCrs.PROJECT,
    val importPoints: Boolean = true,
    val importGeometries: Boolean = true
)

data class ProjectImportResult(
    val pointsAdded: Int,
    val geometriesAdded: Int,
    val message: String
)

object ProjectImportManager {
    fun import(
        context: Context,
        project: TopoProject,
        uri: Uri,
        options: ProjectImportOptions
    ): Result<ProjectImportResult> = runCatching {
        val display = uri.lastPathSegment.orEmpty().lowercase()
        val format = when {
            options.format != ProjectImportFormat.AUTO -> options.format
            display.endsWith(".csv") -> ProjectImportFormat.CSV
            display.endsWith(".txt") -> ProjectImportFormat.TXT
            display.endsWith(".geojson") || display.endsWith(".json") -> ProjectImportFormat.GEOJSON
            display.endsWith(".kml") -> ProjectImportFormat.KML
            display.endsWith(".dxf") -> ProjectImportFormat.DXF
            display.endsWith(".zip") -> ProjectImportFormat.SHAPE_ZIP
            else -> error("No se pudo detectar el formato del archivo.")
        }

        val effectiveCrs = when (options.sourceCrs) {
            ImportSourceCrs.PROJECT -> project.crsName
            ImportSourceCrs.WGS84 -> "WGS 84 geográficas"
            ImportSourceCrs.CRTM05 -> "CRTM05"
            ImportSourceCrs.CR_SIRGAS -> "CR-SIRGAS"
        }

        // Hasta que el motor geodésico transforme oficialmente CRTM05/CR-SIRGAS,
        // solo se importa directamente WGS84. Evita interpretar Este/Norte como lat/lon.
        if (!effectiveCrs.contains("WGS", ignoreCase = true)) {
            error(
                "La importación de coordenadas proyectadas $effectiveCrs quedará habilitada " +
                    "cuando se complete la transformación geodésica del proyecto. " +
                    "No se importaron datos para evitar coordenadas incorrectas."
            )
        }

        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        } ?: error("No se pudo leer el archivo.")

        val points = mutableListOf<SurveyPoint>()
        val geometries = JSONArray()

        when (format) {
            ProjectImportFormat.CSV,
            ProjectImportFormat.TXT -> if (options.importPoints) {
                parseDelimited(text, project.id).forEach { points += it }
            }

            ProjectImportFormat.GEOJSON -> {
                val root = JSONObject(text)
                val features = root.optJSONArray("features") ?: JSONArray()
                for (i in 0 until features.length()) {
                    val feature = features.optJSONObject(i) ?: continue
                    val geometry = feature.optJSONObject("geometry") ?: continue
                    val props = feature.optJSONObject("properties") ?: JSONObject()
                    when (geometry.optString("type")) {
                        "Point" -> if (options.importPoints) {
                            val c = geometry.optJSONArray("coordinates") ?: continue
                            if (c.length() >= 2) {
                                points += SurveyPoint(
                                    id = UUID.randomUUID().toString(),
                                    projectId = project.id,
                                    pointNumber = props.optString("point", (points.size + 1).toString()),
                                    description = props.optString("description", ""),
                                    code = props.optString("code", ""),
                                    antennaHeightM = project.antennaHeightM,
                                    occupationSeconds = 1,
                                    latitude = c.optDouble(1),
                                    longitude = c.optDouble(0),
                                    ellipsoidalHeightM = c.optDouble(2).takeUnless { it.isNaN() }
                                )
                            }
                        }
                        "LineString" -> if (options.importGeometries) {
                            addGeometryFromCoords(
                                geometries,
                                tool = "LINE",
                                coords = geometry.optJSONArray("coordinates")
                            )
                        }
                        "Polygon" -> if (options.importGeometries) {
                            val rings = geometry.optJSONArray("coordinates")
                            addGeometryFromCoords(
                                geometries,
                                tool = "POLYGON",
                                coords = rings?.optJSONArray(0)
                            )
                        }
                    }
                }
            }

            ProjectImportFormat.KML -> {
                if (options.importPoints) {
                    val pointRegex = Regex(
                        "<Placemark[\\s\\S]*?<name>([\\s\\S]*?)</name>[\\s\\S]*?<Point>[\\s\\S]*?<coordinates>([^<]+)</coordinates>[\\s\\S]*?</Point>[\\s\\S]*?</Placemark>",
                        RegexOption.IGNORE_CASE
                    )
                    pointRegex.findAll(text).forEach { m ->
                        val parts = m.groupValues[2].trim().split(",")
                        if (parts.size >= 2) {
                            points += SurveyPoint(
                                id = UUID.randomUUID().toString(),
                                projectId = project.id,
                                pointNumber = stripXml(m.groupValues[1]).ifBlank { (points.size + 1).toString() },
                                description = "",
                                code = "",
                                antennaHeightM = project.antennaHeightM,
                                occupationSeconds = 1,
                                latitude = parts[1].toDoubleOrNull(),
                                longitude = parts[0].toDoubleOrNull(),
                                ellipsoidalHeightM = parts.getOrNull(2)?.toDoubleOrNull()
                            )
                        }
                    }
                }

                if (options.importGeometries) {
                    Regex(
                        "<LineString>[\\s\\S]*?<coordinates>([^<]+)</coordinates>[\\s\\S]*?</LineString>",
                        RegexOption.IGNORE_CASE
                    ).findAll(text).forEach { m ->
                        addGeometryFromKml(geometries, "LINE", m.groupValues[1])
                    }
                    Regex(
                        "<Polygon>[\\s\\S]*?<coordinates>([^<]+)</coordinates>[\\s\\S]*?</Polygon>",
                        RegexOption.IGNORE_CASE
                    ).findAll(text).forEach { m ->
                        addGeometryFromKml(geometries, "POLYGON", m.groupValues[1])
                    }
                }
            }

            ProjectImportFormat.DXF ->
                error("La lectura DXF se añadirá al motor CAD de importación. Todavía no se importó el archivo.")

            ProjectImportFormat.SHAPE_ZIP ->
                error("La importación Shapefile ZIP se añadirá junto con el lector SHP/DBF. Todavía no se importó el archivo.")

            ProjectImportFormat.AUTO -> error("Formato no resuelto.")
        }

        if (points.isNotEmpty()) {
            val store = SurveyPointStore(context)
            store.save(project.id, store.load(project.id) + points)
        }

        if (geometries.length() > 0) {
            val prefs = context.getSharedPreferences("survey_geometries", Context.MODE_PRIVATE)
            val key = "geometries_${project.id}"
            val current = JSONArray(prefs.getString(key, "[]") ?: "[]")
            for (i in 0 until geometries.length()) current.put(geometries.getJSONObject(i))
            prefs.edit().putString(key, current.toString()).apply()
        }

        ProjectImportResult(
            pointsAdded = points.size,
            geometriesAdded = geometries.length(),
            message = "Importados ${points.size} puntos y ${geometries.length()} figuras."
        )
    }

    private fun parseDelimited(text: String, projectId: String): List<SurveyPoint> {
        val out = mutableListOf<SurveyPoint>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isBlank()) return@forEach
            val sep = when {
                line.contains(';') -> ';'
                line.contains(',') -> ','
                line.contains('\t') -> '\t'
                else -> ' '
            }
            val parts = if (sep == ' ') line.split(Regex("\\s+")) else line.split(sep)
            if (parts.size < 2) return@forEach

            // Compatibilidad inicial: P,LAT,LON[,Z,DESC] o LAT,LON[,Z].
            val firstNum = parts[0].trim().toDoubleOrNull()
            val secondNum = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            val thirdNum = parts.getOrNull(2)?.trim()?.toDoubleOrNull()

            val (pointName, lat, lon, zStart) = if (
                firstNum == null && secondNum != null && thirdNum != null
            ) {
                arrayOf(parts[0].trim(), secondNum, thirdNum, 3)
            } else if (firstNum != null && secondNum != null) {
                arrayOf((out.size + 1).toString(), firstNum, secondNum, 2)
            } else return@forEach

            @Suppress("UNCHECKED_CAST")
            val pName = pointName as String
            val pLat = lat as Double
            val pLon = lon as Double
            val idx = zStart as Int
            if (pLat !in -90.0..90.0 || pLon !in -180.0..180.0) return@forEach
            val z = parts.getOrNull(idx)?.trim()?.toDoubleOrNull()
            val desc = parts.drop(idx + if (z != null) 1 else 0).joinToString(" ").trim()

            out += SurveyPoint(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                pointNumber = pName,
                description = desc,
                code = "",
                antennaHeightM = 0.0,
                occupationSeconds = 1,
                latitude = pLat,
                longitude = pLon,
                ellipsoidalHeightM = z
            )
        }
        return out
    }

    private fun addGeometryFromCoords(array: JSONArray, tool: String, coords: JSONArray?) {
        if (coords == null || coords.length() < 2) return
        val pts = JSONArray()
        for (i in 0 until coords.length()) {
            val c = coords.optJSONArray(i) ?: continue
            if (c.length() < 2) continue
            pts.put(JSONObject().put("lat", c.optDouble(1)).put("lon", c.optDouble(0)))
        }
        if (pts.length() < 2) return
        array.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("tool", tool)
                .put("parallelOffsetM", 1.0)
                .put("points", pts)
        )
    }

    private fun addGeometryFromKml(array: JSONArray, tool: String, raw: String) {
        val pts = JSONArray()
        raw.trim().split(Regex("\\s+")).forEach { token ->
            val p = token.split(",")
            val lon = p.getOrNull(0)?.toDoubleOrNull() ?: return@forEach
            val lat = p.getOrNull(1)?.toDoubleOrNull() ?: return@forEach
            pts.put(JSONObject().put("lat", lat).put("lon", lon))
        }
        if (pts.length() >= 2) {
            array.put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("tool", tool)
                    .put("parallelOffsetM", 1.0)
                    .put("points", pts)
            )
        }
    }

    private fun stripXml(value: String): String =
        value.replace(Regex("<[^>]+>"), "").trim()
}
