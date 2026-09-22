package cr.co.topoemlid

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ProjectExportFormat(val label: String, val extension: String, val mime: String) {
    TXT("TXT", "txt", "text/plain"),
    CSV("CSV", "csv", "text/csv"),
    GEOJSON("GeoJSON", "geojson", "application/geo+json"),
    KML("KML", "kml", "application/vnd.google-earth.kml+xml"),
    DXF("DXF", "dxf", "application/dxf"),
    BACKUP("Respaldo TOPO EMLID", "json", "application/json")
}

enum class ProjectExportContent(val label: String) {
    POINTS("Solo puntos"),
    GEOMETRIES("Solo líneas / polígonos / figuras"),
    ALL("Puntos + líneas / polígonos / figuras")
}

enum class TextPointLayout(val label: String) {
    POINT_LAT_LON_ELEV_DESC("Punto, Latitud, Longitud, Elevación, Descripción"),
    POINT_LON_LAT_ELEV_DESC("Punto, Longitud, Latitud, Elevación, Descripción"),
    POINT_LAT_LON("Punto, Latitud, Longitud"),
    POINT_LON_LAT("Punto, Longitud, Latitud"),
    LAT_LON("Latitud, Longitud"),
    LON_LAT("Longitud, Latitud"),
    POINT_DESC("Punto, Descripción")
}

enum class TextSeparator(val label: String, val value: String) {
    COMMA("Coma", ","),
    SEMICOLON("Punto y coma", ";"),
    TAB("Tabulación", "\t"),
    SPACE("Espacio", " ")
}

data class ProjectExportOptions(
    val format: ProjectExportFormat,
    val content: ProjectExportContent = ProjectExportContent.ALL,
    val textLayout: TextPointLayout = TextPointLayout.POINT_LAT_LON_ELEV_DESC,
    val separator: TextSeparator = TextSeparator.COMMA
)

object ProjectExportManager {
    fun export(
        context: Context,
        project: TopoProject,
        options: ProjectExportOptions
    ): Result<String> = runCatching {
        val points = SurveyPointStore(context).load(project.id)
        val geometriesRaw = context
            .getSharedPreferences("survey_geometries", Context.MODE_PRIVATE)
            .getString("geometries_${project.id}", "[]")
            ?: "[]"

        val body = when (options.format) {
            ProjectExportFormat.TXT,
            ProjectExportFormat.CSV -> buildDelimited(points, options)
            ProjectExportFormat.GEOJSON -> buildGeoJson(points, geometriesRaw, options.content)
            ProjectExportFormat.KML -> buildKml(points, geometriesRaw, options.content)
            ProjectExportFormat.DXF -> buildDxf(points, geometriesRaw, options.content)
            ProjectExportFormat.BACKUP -> buildBackup(project, points, geometriesRaw)
        }

        val safeName = project.name
            .trim()
            .ifBlank { "Trabajo" }
            .replace(Regex("[^A-Za-z0-9ÁÉÍÓÚáéíóúÑñ _-]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${safeName}_${stamp}.${options.format.extension}"
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/TopoEmlid/Trabajos/$safeName"

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("La exportación directa a Documentos requiere Android 10 o superior.")
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, options.format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values)
            ?: error("Android no permitió crear el archivo en Documentos.")

        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            } ?: error("No se pudo abrir el archivo para escritura.")

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw t
        }

        "Documentos/TopoEmlid/Trabajos/$safeName/$fileName"
    }

    private fun buildDelimited(
        points: List<SurveyPoint>,
        options: ProjectExportOptions
    ): String {
        val sep = options.separator.value
        fun clean(value: String): String {
            val escaped = value.replace("\"", "\"\"")
            return if (sep == "," || sep == ";") ""$escaped"" else escaped.replace("\n", " ")
        }
        fun v(d: Double?, decimals: Int): String =
            d?.let { "%.${decimals}f".format(Locale.US, it) } ?: ""

        val rows = points.map { p ->
            when (options.textLayout) {
                TextPointLayout.POINT_LAT_LON_ELEV_DESC -> listOf(
                    p.pointNumber, v(p.latitude, 8), v(p.longitude, 8), v(p.ellipsoidalHeightM, 3), p.description
                )
                TextPointLayout.POINT_LON_LAT_ELEV_DESC -> listOf(
                    p.pointNumber, v(p.longitude, 8), v(p.latitude, 8), v(p.ellipsoidalHeightM, 3), p.description
                )
                TextPointLayout.POINT_LAT_LON -> listOf(p.pointNumber, v(p.latitude, 8), v(p.longitude, 8))
                TextPointLayout.POINT_LON_LAT -> listOf(p.pointNumber, v(p.longitude, 8), v(p.latitude, 8))
                TextPointLayout.LAT_LON -> listOf(v(p.latitude, 8), v(p.longitude, 8))
                TextPointLayout.LON_LAT -> listOf(v(p.longitude, 8), v(p.latitude, 8))
                TextPointLayout.POINT_DESC -> listOf(p.pointNumber, p.description)
            }.joinToString(sep) { clean(it) }
        }
        return rows.joinToString("\n", postfix = if (rows.isEmpty()) "" else "\n")
    }

    private fun buildGeoJson(
        points: List<SurveyPoint>,
        geometriesRaw: String,
        content: ProjectExportContent
    ): String {
        val features = JSONArray()

        if (content != ProjectExportContent.GEOMETRIES) {
            points.forEach { p ->
                val lat = p.latitude ?: return@forEach
                val lon = p.longitude ?: return@forEach
                features.put(JSONObject().apply {
                    put("type", "Feature")
                    put("geometry", JSONObject().apply {
                        put("type", "Point")
                        put("coordinates", JSONArray().apply {
                            put(lon); put(lat)
                            p.ellipsoidalHeightM?.let { put(it) }
                        })
                    })
                    put("properties", JSONObject().apply {
                        put("point", p.pointNumber)
                        put("description", p.description)
                        put("code", p.code)
                        put("solution", p.solution)
                    })
                })
            }
        }

        if (content != ProjectExportContent.POINTS) {
            parseGeometryItems(geometriesRaw).forEach { g ->
                if (g.points.isEmpty()) return@forEach
                val polygonLike = g.tool in setOf("AREA", "PERIMETER", "POLYGON", "RECTANGLE", "CIRCLE", "DIVIDE")
                val coords = JSONArray()
                g.points.forEach { (lat, lon) ->
                    coords.put(JSONArray().apply { put(lon); put(lat) })
                }
                if (polygonLike && g.points.size >= 3) {
                    val first = g.points.first()
                    val last = g.points.last()
                    if (first != last) coords.put(JSONArray().apply { put(first.second); put(first.first) })
                }
                features.put(JSONObject().apply {
                    put("type", "Feature")
                    put("geometry", JSONObject().apply {
                        if (polygonLike && g.points.size >= 3) {
                            put("type", "Polygon")
                            put("coordinates", JSONArray().put(coords))
                        } else if (g.points.size == 1) {
                            put("type", "Point")
                            put("coordinates", coords.getJSONArray(0))
                        } else {
                            put("type", "LineString")
                            put("coordinates", coords)
                        }
                    })
                    put("properties", JSONObject().apply {
                        put("tool", g.tool)
                        put("id", g.id)
                    })
                })
            }
        }

        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString(2)
    }

    private fun buildKml(
        points: List<SurveyPoint>,
        geometriesRaw: String,
        content: ProjectExportContent
    ): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")

        if (content != ProjectExportContent.GEOMETRIES) {
            points.forEach { p ->
                val lat = p.latitude ?: return@forEach
                val lon = p.longitude ?: return@forEach
                val z = p.ellipsoidalHeightM ?: 0.0
                sb.append("<Placemark><name>").append(esc(p.pointNumber)).append("</name>")
                sb.append("<description>").append(esc(p.description)).append("</description>")
                sb.append("<Point><coordinates>$lon,$lat,$z</coordinates></Point></Placemark>\n")
            }
        }

        if (content != ProjectExportContent.POINTS) {
            parseGeometryItems(geometriesRaw).forEach { g ->
                if (g.points.size < 2) return@forEach
                val polygonLike = g.tool in setOf("AREA", "PERIMETER", "POLYGON", "RECTANGLE", "CIRCLE", "DIVIDE")
                val pts = if (polygonLike && g.points.size >= 3 && g.points.first() != g.points.last()) g.points + g.points.first() else g.points
                val coords = pts.joinToString(" ") { (lat, lon) -> "$lon,$lat,0" }
                sb.append("<Placemark><name>").append(esc(g.tool)).append("</name>")
                if (polygonLike && g.points.size >= 3) {
                    sb.append("<Polygon><outerBoundaryIs><LinearRing><coordinates>")
                        .append(coords)
                        .append("</coordinates></LinearRing></outerBoundaryIs></Polygon>")
                } else {
                    sb.append("<LineString><coordinates>").append(coords).append("</coordinates></LineString>")
                }
                sb.append("</Placemark>\n")
            }
        }

        sb.append("</Document></kml>")
        return sb.toString()
    }

    private fun buildDxf(
        points: List<SurveyPoint>,
        geometriesRaw: String,
        content: ProjectExportContent
    ): String {
        // DXF geográfico: X=longitud, Y=latitud. No se etiqueta como CRTM05
        // hasta que la transformación oficial esté implementada.
        val sb = StringBuilder("0\nSECTION\n2\nENTITIES\n")
        if (content != ProjectExportContent.GEOMETRIES) {
            points.forEach { p ->
                val lat = p.latitude ?: return@forEach
                val lon = p.longitude ?: return@forEach
                val z = p.ellipsoidalHeightM ?: 0.0
                sb.append("0\nPOINT\n8\nPUNTOS\n10\n$lon\n20\n$lat\n30\n$z\n")
                sb.append("0\nTEXT\n8\nETIQUETAS\n10\n$lon\n20\n$lat\n40\n0.00005\n1\n")
                    .append(p.pointNumber.replace("\n", " ")).append("\n")
            }
        }
        if (content != ProjectExportContent.POINTS) {
            parseGeometryItems(geometriesRaw).forEach { g ->
                if (g.points.size < 2) return@forEach
                val polygonLike = g.tool in setOf("AREA", "PERIMETER", "POLYGON", "RECTANGLE", "CIRCLE", "DIVIDE")
                sb.append("0\nLWPOLYLINE\n8\nFIGURAS\n90\n${g.points.size}\n70\n${if (polygonLike) 1 else 0}\n")
                g.points.forEach { (lat, lon) ->
                    sb.append("10\n$lon\n20\n$lat\n")
                }
            }
        }
        sb.append("0\nENDSEC\n0\nEOF\n")
        return sb.toString()
    }

    private fun buildBackup(
        project: TopoProject,
        points: List<SurveyPoint>,
        geometriesRaw: String
    ): String {
        val pointArray = JSONArray()
        points.forEach { p ->
            pointArray.put(JSONObject().apply {
                put("id", p.id)
                put("pointNumber", p.pointNumber)
                put("description", p.description)
                put("code", p.code)
                put("antennaHeightM", p.antennaHeightM)
                put("occupationSeconds", p.occupationSeconds)
                put("latitude", p.latitude)
                put("longitude", p.longitude)
                put("ellipsoidalHeightM", p.ellipsoidalHeightM)
                put("horizontalAccuracyM", p.horizontalAccuracyM)
                put("verticalAccuracyM", p.verticalAccuracyM)
                put("solution", p.solution)
                put("satellites", p.satellites)
                put("createdAt", p.createdAt)
            })
        }
        return JSONObject().apply {
            put("format", "TOPO_EMLID_BACKUP_V1")
            put("project", JSONObject().apply {
                put("id", project.id)
                put("name", project.name)
                put("location", project.location)
                put("crsName", project.crsName)
                put("geoidFileName", project.geoidFileName)
                put("antennaHeightM", project.antennaHeightM)
                put("createdAt", project.createdAt)
            })
            put("points", pointArray)
            put("geometries", JSONArray(geometriesRaw))
        }.toString(2)
    }

    private data class RawGeometry(
        val id: String,
        val tool: String,
        val points: List<Pair<Double, Double>>
    )

    private fun parseGeometryItems(raw: String): List<RawGeometry> = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val pts = o.optJSONArray("points") ?: JSONArray()
            RawGeometry(
                id = o.optString("id", "geometry-$i"),
                tool = o.optString("tool", "LINE"),
                points = (0 until pts.length()).map { j ->
                    val p = pts.getJSONObject(j)
                    p.getDouble("lat") to p.getDouble("lon")
                }
            )
        }
    }.getOrDefault(emptyList())
}
