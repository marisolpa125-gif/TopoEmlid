package cr.co.topoemlid

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipInputStream

enum class ProjectImportFormat(val label: String) {
    AUTO("Detectar automáticamente"),
    TXT("TXT"),
    CSV("CSV"),
    GEOJSON("GeoJSON"),
    KML("KML"),
    DXF("DXF"),
    SHAPE_ZIP("Shapefile ZIP"),
    DWG("DWG")
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
    val textLayout: TextPointLayout = TextPointLayout.POINT_NORTH_EAST_ELEV_DESC,
    val separator: TextSeparator = TextSeparator.COMMA,
    val importPoints: Boolean = true,
    val importLines: Boolean = true,
    val importPolygons: Boolean = true
)

data class ProjectImportResult(
    val pointsAdded: Int,
    val geometriesAdded: Int,
    val message: String
)

data class ProjectImportPreview(
    val format: ProjectImportFormat,
    val points: Int,
    val lines: Int,
    val polygons: Int
)

object ProjectImportManager {

    fun preview(context: Context, uri: Uri): Result<ProjectImportPreview> = runCatching {
        val display = displayName(context, uri)
        val format = detectFormat(display, ProjectImportFormat.AUTO)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("No se pudo leer el archivo.")
        val text = if (format in setOf(
                ProjectImportFormat.TXT, ProjectImportFormat.CSV,
                ProjectImportFormat.GEOJSON, ProjectImportFormat.KML, ProjectImportFormat.DXF
            )
        ) bytes.toString(Charsets.UTF_8) else ""

        when (format) {
            ProjectImportFormat.GEOJSON -> {
                val features = JSONObject(text).optJSONArray("features") ?: JSONArray()
                var p = 0; var l = 0; var g = 0
                for (i in 0 until features.length()) {
                    when (features.optJSONObject(i)?.optJSONObject("geometry")?.optString("type")) {
                        "Point", "MultiPoint" -> p++
                        "LineString", "MultiLineString" -> l++
                        "Polygon", "MultiPolygon" -> g++
                    }
                }
                ProjectImportPreview(format, p, l, g)
            }
            ProjectImportFormat.KML -> ProjectImportPreview(
                format,
                Regex("<Point[\\s>]", RegexOption.IGNORE_CASE).findAll(text).count(),
                Regex("<LineString[\\s>]", RegexOption.IGNORE_CASE).findAll(text).count(),
                Regex("<Polygon[\\s>]", RegexOption.IGNORE_CASE).findAll(text).count()
            )
            ProjectImportFormat.DXF -> {
                val upper = text.uppercase()
                ProjectImportPreview(
                    format,
                    Regex("(?m)^\\s*POINT\\s*$").findAll(upper).count(),
                    Regex("(?m)^\\s*LINE\\s*$").findAll(upper).count() +
                        Regex("(?m)^\\s*LWPOLYLINE\\s*$").findAll(upper).count(),
                    Regex("(?m)^\\s*HATCH\\s*$").findAll(upper).count()
                )
            }
            ProjectImportFormat.SHAPE_ZIP -> previewShapeZip(bytes)
            ProjectImportFormat.TXT, ProjectImportFormat.CSV ->
                ProjectImportPreview(format, text.lineSequence().count { it.isNotBlank() }, 0, 0)
            ProjectImportFormat.DWG -> ProjectImportPreview(format, 0, 0, 0)
            ProjectImportFormat.AUTO -> error("Formato no resuelto.")
        }
    }

    fun import(
        context: Context,
        project: TopoProject,
        uri: Uri,
        options: ProjectImportOptions
    ): Result<ProjectImportResult> = runCatching {
        val display = displayName(context, uri)
        val format = detectFormat(display, options.format)
        if (format == ProjectImportFormat.DWG) {
            error(
                "DWG fue detectado, pero TOPO EMLID todavía no incluye un lector DWG seguro. " +
                    "Convierta el archivo a DXF para conservar coordenadas y geometrías correctamente."
            )
        }

        val effectiveCrs = when (options.sourceCrs) {
            ImportSourceCrs.PROJECT -> project.crsName
            ImportSourceCrs.WGS84 -> "WGS 84 geográficas"
            ImportSourceCrs.CRTM05 -> "CRTM05"
            ImportSourceCrs.CR_SIRGAS -> "CR-SIRGAS"
        }

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("No se pudo leer el archivo.")
        val text = if (format in setOf(
                ProjectImportFormat.CSV, ProjectImportFormat.TXT,
                ProjectImportFormat.GEOJSON, ProjectImportFormat.KML,
                ProjectImportFormat.DXF
            )
        ) bytes.toString(Charsets.UTF_8) else ""

        val points = mutableListOf<SurveyPoint>()
        val geometries = JSONArray()

        when (format) {
            ProjectImportFormat.TXT, ProjectImportFormat.CSV ->
                parseDelimited(text, project, effectiveCrs, options, points, geometries)

            ProjectImportFormat.GEOJSON ->
                parseGeoJson(text, project, effectiveCrs, options, points, geometries)

            ProjectImportFormat.KML ->
                parseKml(text, project, effectiveCrs, options, points, geometries)

            ProjectImportFormat.DXF ->
                parseDxf(text, project, effectiveCrs, options, points, geometries)

            ProjectImportFormat.SHAPE_ZIP ->
                parseShapefileZip(bytes, project, effectiveCrs, options, points, geometries)

            ProjectImportFormat.DWG, ProjectImportFormat.AUTO -> error("Formato no resuelto.")
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

        val lineCount = (0 until geometries.length()).count {
            geometries.getJSONObject(it).optString("tool") != "POLYGON"
        }
        val polygonCount = geometries.length() - lineCount
        ProjectImportResult(
            pointsAdded = points.size,
            geometriesAdded = geometries.length(),
            message = "Importados ${points.size} puntos, $lineCount líneas y $polygonCount polígonos."
        )
    }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            }
        }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }.lowercase()

    private fun detectFormat(display: String, requested: ProjectImportFormat): ProjectImportFormat {
        if (requested != ProjectImportFormat.AUTO) return requested
        return when {
            display.endsWith(".csv") -> ProjectImportFormat.CSV
            display.endsWith(".txt") -> ProjectImportFormat.TXT
            display.endsWith(".geojson") || display.endsWith(".json") -> ProjectImportFormat.GEOJSON
            display.endsWith(".kml") -> ProjectImportFormat.KML
            display.endsWith(".dxf") -> ProjectImportFormat.DXF
            display.endsWith(".zip") -> ProjectImportFormat.SHAPE_ZIP
            display.endsWith(".dwg") -> ProjectImportFormat.DWG
            else -> error("No se pudo detectar el formato del archivo.")
        }
    }

    private fun sourceToWgs84(x: Double, y: Double, crs: String): ProjectCoordinate? =
        runCatching {
            if (crs.contains("WGS", ignoreCase = true)) {
                if (y !in -90.0..90.0 || x !in -180.0..180.0) return@runCatching null
                ProjectCoordinate(null, null, y, x)
            } else {
                ProjectCoordinateEngine.toWgs84(x, y, crs)
            }
        }.getOrNull()

    private fun buildPoint(
        project: TopoProject,
        number: String,
        description: String,
        code: String,
        latitude: Double,
        longitude: Double,
        elevation: Double?
    ): SurveyPoint {
        val projected = runCatching {
            ProjectCoordinateEngine.fromWgs84(latitude, longitude, project.crsName)
        }.getOrNull()
        val geoidal = project.geoidFileUri != null
        return SurveyPoint(
            id = UUID.randomUUID().toString(),
            projectId = project.id,
            pointNumber = number,
            description = description,
            code = code,
            antennaHeightM = 0.0,
            occupationSeconds = 1,
            latitude = latitude,
            longitude = longitude,
            eastingM = projected?.eastingM,
            northingM = projected?.northingM,
            projectCrsName = project.crsName,
            ellipsoidalHeightM = if (geoidal) null else elevation,
            orthometricHeightM = if (geoidal) elevation else null,
            geoidFileName = project.geoidFileName,
            solution = "IMPORTADO"
        )
    }

    private fun parseDelimited(
        text: String,
        project: TopoProject,
        effectiveCrs: String,
        options: ProjectImportOptions,
        pointsOut: MutableList<SurveyPoint>,
        geometriesOut: JSONArray
    ) {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return

        fun split(line: String): List<String> {
            val sep = options.separator.value
            val raw = if (sep == " ") line.split(Regex("\\s+")) else line.split(sep)
            return raw.map { it.trim().trim('"').replace("\"\"", "\"") }
        }

        val first = split(lines.first()).map { it.uppercase() }
        if (first.contains("TIPO") && first.contains("LATITUD") && first.contains("LONGITUD")) {
            parseTopoMixed(lines, ::split, project, options, pointsOut, geometriesOut)
            return
        }

        if (!options.importPoints) return
        val fields = options.textLayout.fields
        require(fields.isNotEmpty()) { "Seleccione un orden de columnas válido para TXT/CSV." }

        lines.forEach { line ->
            val parts = split(line)
            if (parts.size < fields.size) return@forEach
            val values = fields.mapIndexed { idx, field -> field to parts.getOrNull(idx).orEmpty() }.toMap()

            val point = values[PointExportField.POINT].orEmpty().ifBlank { (pointsOut.size + 1).toString() }
            val desc = values[PointExportField.DESCRIPTION].orEmpty()
            val z = values[PointExportField.ELEVATION]?.replace(',', '.')?.toDoubleOrNull()

            val latDirect = values[PointExportField.LATITUDE]?.replace(',', '.')?.toDoubleOrNull()
            val lonDirect = values[PointExportField.LONGITUDE]?.replace(',', '.')?.toDoubleOrNull()
            val north = values[PointExportField.NORTH]?.replace(',', '.')?.toDoubleOrNull()
            val east = values[PointExportField.EAST]?.replace(',', '.')?.toDoubleOrNull()

            val coord = when {
                latDirect != null && lonDirect != null ->
                    sourceToWgs84(lonDirect, latDirect, "WGS 84 geográficas")
                north != null && east != null ->
                    sourceToWgs84(east, north, effectiveCrs)
                else -> null
            } ?: return@forEach

            pointsOut += buildPoint(
                project, point, desc, "",
                coord.latitude, coord.longitude, z
            )
        }
    }

    private fun parseTopoMixed(
        lines: List<String>,
        split: (String) -> List<String>,
        project: TopoProject,
        options: ProjectImportOptions,
        pointsOut: MutableList<SurveyPoint>,
        geometriesOut: JSONArray
    ) {
        val header = split(lines.first()).map { it.uppercase() }
        fun idx(name: String) = header.indexOf(name)
        val typeIdx = idx("TIPO"); val idIdx = idx("ID"); val vertexIdx = idx("VERTICE")
        val latIdx = idx("LATITUD"); val lonIdx = idx("LONGITUD")
        val elevIdx = idx("ELEVACION"); val descIdx = idx("DESCRIPCION"); val toolIdx = idx("HERRAMIENTA")

        data class TempGeom(val tool: String, val vertices: MutableList<Pair<Int, Pair<Double, Double>>> = mutableListOf())
        val groups = linkedMapOf<String, TempGeom>()

        lines.drop(1).forEach { line ->
            val p = split(line)
            fun col(i: Int) = if (i >= 0) p.getOrNull(i).orEmpty() else ""
            val lat = col(latIdx).replace(',', '.').toDoubleOrNull() ?: return@forEach
            val lon = col(lonIdx).replace(',', '.').toDoubleOrNull() ?: return@forEach
            when (col(typeIdx).uppercase()) {
                "PUNTO" -> if (options.importPoints) {
                    pointsOut += buildPoint(
                        project,
                        col(idIdx).ifBlank { (pointsOut.size + 1).toString() },
                        col(descIdx), "", lat, lon,
                        col(elevIdx).replace(',', '.').toDoubleOrNull()
                    )
                }
                "FIGURA" -> {
                    val tool = col(toolIdx).ifBlank { "LINE" }.uppercase()
                    val allowed = (tool == "POLYGON" && options.importPolygons) ||
                        (tool != "POLYGON" && options.importLines)
                    if (allowed) {
                        val id = col(idIdx).ifBlank { "geometry-${groups.size + 1}" }
                        groups.getOrPut(id) { TempGeom(tool) }
                            .vertices += (col(vertexIdx).toIntOrNull() ?: Int.MAX_VALUE) to (lat to lon)
                    }
                }
            }
        }
        groups.forEach { (id, g) ->
            val pts = JSONArray()
            g.vertices.sortedBy { it.first }.forEach { (_, c) ->
                pts.put(JSONObject().put("lat", c.first).put("lon", c.second))
            }
            if (pts.length() >= 2) addGeometry(geometriesOut, id, g.tool, pts)
        }
    }

    private fun parseGeoJson(
        text: String,
        project: TopoProject,
        effectiveCrs: String,
        options: ProjectImportOptions,
        pointsOut: MutableList<SurveyPoint>,
        geometriesOut: JSONArray
    ) {
        val features = JSONObject(text).optJSONArray("features") ?: JSONArray()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val props = feature.optJSONObject("properties") ?: JSONObject()
            when (geometry.optString("type")) {
                "Point" -> if (options.importPoints) {
                    val c = geometry.optJSONArray("coordinates") ?: continue
                    if (c.length() >= 2) {
                        val coord = sourceToWgs84(c.optDouble(0), c.optDouble(1), effectiveCrs) ?: continue
                        pointsOut += buildPoint(
                            project,
                            props.optString("point", (pointsOut.size + 1).toString()),
                            props.optString("description", ""),
                            props.optString("code", ""),
                            coord.latitude, coord.longitude,
                            c.optDouble(2).takeUnless { it.isNaN() }
                        )
                    }
                }
                "LineString" -> if (options.importLines) {
                    coordsToGeometry(geometriesOut, "LINE", geometry.optJSONArray("coordinates"), effectiveCrs)
                }
                "Polygon" -> if (options.importPolygons) {
                    coordsToGeometry(
                        geometriesOut, "POLYGON",
                        geometry.optJSONArray("coordinates")?.optJSONArray(0), effectiveCrs
                    )
                }
            }
        }
    }

    private fun parseKml(
        text: String,
        project: TopoProject,
        effectiveCrs: String,
        options: ProjectImportOptions,
        pointsOut: MutableList<SurveyPoint>,
        geometriesOut: JSONArray
    ) {
        if (options.importPoints) {
            val pointRegex = Regex(
                "<Placemark[\\s\\S]*?<name>([\\s\\S]*?)</name>[\\s\\S]*?<Point>[\\s\\S]*?<coordinates>([^<]+)</coordinates>[\\s\\S]*?</Point>[\\s\\S]*?</Placemark>",
                RegexOption.IGNORE_CASE
            )
            pointRegex.findAll(text).forEach { m ->
                val p = m.groupValues[2].trim().split(",")
                val x = p.getOrNull(0)?.toDoubleOrNull()
                val y = p.getOrNull(1)?.toDoubleOrNull()
                val coord = if (x != null && y != null) sourceToWgs84(x, y, effectiveCrs) else null
                if (coord != null) {
                    pointsOut += buildPoint(
                        project,
                        stripXml(m.groupValues[1]).ifBlank { (pointsOut.size + 1).toString() },
                        "", "", coord.latitude, coord.longitude,
                        p.getOrNull(2)?.toDoubleOrNull()
                    )
                }
            }
        }
        if (options.importLines) {
            Regex("<LineString>[\\s\\S]*?<coordinates>([^<]+)</coordinates>[\\s\\S]*?</LineString>", RegexOption.IGNORE_CASE)
                .findAll(text).forEach { addKmlGeometry(geometriesOut, "LINE", it.groupValues[1], effectiveCrs) }
        }
        if (options.importPolygons) {
            Regex("<Polygon>[\\s\\S]*?<coordinates>([^<]+)</coordinates>[\\s\\S]*?</Polygon>", RegexOption.IGNORE_CASE)
                .findAll(text).forEach { addKmlGeometry(geometriesOut, "POLYGON", it.groupValues[1], effectiveCrs) }
        }
    }

    private fun parseDxf(
        text: String,
        project: TopoProject,
        effectiveCrs: String,
        options: ProjectImportOptions,
        pointsOut: MutableList<SurveyPoint>,
        geometriesOut: JSONArray
    ) {
        val raw = text.replace("\r", "").split("\n")
        if (raw.size < 2) error("DXF vacío o no es DXF ASCII.")
        data class PairCode(val code: Int, val value: String)
        val pairs = mutableListOf<PairCode>()
        var i = 0
        while (i + 1 < raw.size) {
            raw[i].trim().toIntOrNull()?.let { pairs += PairCode(it, raw[i + 1].trim()) }
            i += 2
        }

        fun addPoint(x: Double?, y: Double?, z: Double?) {
            if (!options.importPoints || x == null || y == null) return
            val coord = sourceToWgs84(x, y, effectiveCrs) ?: return
            pointsOut += buildPoint(
                project, (pointsOut.size + 1).toString(), "", "",
                coord.latitude, coord.longitude, z
            )
        }

        fun addLine(rawPts: List<Pair<Double, Double>>, closed: Boolean) {
            val polygon = closed && rawPts.size >= 3
            if ((polygon && !options.importPolygons) || (!polygon && !options.importLines)) return
            val pts = JSONArray()
            rawPts.forEach { (x, y) ->
                sourceToWgs84(x, y, effectiveCrs)?.let {
                    pts.put(JSONObject().put("lat", it.latitude).put("lon", it.longitude))
                }
            }
            if (pts.length() >= 2) addGeometry(
                geometriesOut, UUID.randomUUID().toString(),
                if (polygon) "POLYGON" else "LINE", pts
            )
        }

        var p = 0
        while (p < pairs.size) {
            if (pairs[p].code != 0) { p++; continue }
            val type = pairs[p].value.uppercase()
            var q = p + 1
            while (q < pairs.size && pairs[q].code != 0) q++
            val e = pairs.subList(p + 1, q)
            when (type) {
                "POINT" -> addPoint(
                    e.firstOrNull { it.code == 10 }?.value?.toDoubleOrNull(),
                    e.firstOrNull { it.code == 20 }?.value?.toDoubleOrNull(),
                    e.firstOrNull { it.code == 30 }?.value?.toDoubleOrNull()
                )
                "LINE" -> {
                    val x1=e.firstOrNull{it.code==10}?.value?.toDoubleOrNull()
                    val y1=e.firstOrNull{it.code==20}?.value?.toDoubleOrNull()
                    val x2=e.firstOrNull{it.code==11}?.value?.toDoubleOrNull()
                    val y2=e.firstOrNull{it.code==21}?.value?.toDoubleOrNull()
                    if (x1!=null&&y1!=null&&x2!=null&&y2!=null) addLine(listOf(x1 to y1,x2 to y2),false)
                }
                "LWPOLYLINE" -> {
                    val xs=e.filter{it.code==10}.mapNotNull{it.value.toDoubleOrNull()}
                    val ys=e.filter{it.code==20}.mapNotNull{it.value.toDoubleOrNull()}
                    val count=minOf(xs.size,ys.size)
                    val closed=((e.firstOrNull{it.code==70}?.value?.toIntOrNull()?:0) and 1)==1
                    addLine((0 until count).map{xs[it] to ys[it]},closed)
                }
            }
            p = q
        }
    }

    private fun parseShapefileZip(
        bytes: ByteArray,
        project: TopoProject,
        effectiveCrs: String,
        options: ProjectImportOptions,
        pointsOut: MutableList<SurveyPoint>,
        geometriesOut: JSONArray
    ) {
        val shpFiles = readShpFiles(bytes)
        if (shpFiles.isEmpty()) error("El ZIP no contiene ningún archivo .shp.")

        shpFiles.forEach { shp ->
            if (shp.size < 100 || beInt(shp,0) != 9994) return@forEach
            var offset=100
            while(offset+8<=shp.size){
                val contentBytes=beInt(shp,offset+4)*2
                val body=offset+8
                if(contentBytes<4||body+contentBytes>shp.size) break
                when(val type=leInt(shp,body)){
                    1 -> if(options.importPoints && body+20<=shp.size){
                        val coord=sourceToWgs84(leDouble(shp,body+4),leDouble(shp,body+12),effectiveCrs)
                        if(coord!=null) pointsOut += buildPoint(project,(pointsOut.size+1).toString(),"","",coord.latitude,coord.longitude,null)
                    }
                    3,5 -> {
                        val allowed=(type==3&&options.importLines)||(type==5&&options.importPolygons)
                        if(allowed && body+44<=shp.size){
                            val numParts=leInt(shp,body+36); val numPoints=leInt(shp,body+40)
                            val partsStart=body+44; val pointsStart=partsStart+numParts*4
                            if(numParts>=1&&numPoints>=2&&pointsStart+numPoints*16<=body+contentBytes){
                                val starts=IntArray(numParts){leInt(shp,partsStart+it*4)}
                                for(pi in 0 until numParts){
                                    val from=starts[pi]; val to=if(pi+1<numParts) starts[pi+1] else numPoints
                                    val pts=JSONArray()
                                    for(j in from until to){
                                        val po=pointsStart+j*16
                                        sourceToWgs84(leDouble(shp,po),leDouble(shp,po+8),effectiveCrs)?.let {
                                            pts.put(JSONObject().put("lat",it.latitude).put("lon",it.longitude))
                                        }
                                    }
                                    if(type==5 && pts.length()>=3){
                                        val f=pts.getJSONObject(0); val l=pts.getJSONObject(pts.length()-1)
                                        if(f.optDouble("lat")==l.optDouble("lat")&&f.optDouble("lon")==l.optDouble("lon")) pts.remove(pts.length()-1)
                                    }
                                    if(pts.length()>=2) addGeometry(geometriesOut,UUID.randomUUID().toString(),if(type==5)"POLYGON" else "LINE",pts)
                                }
                            }
                        }
                    }
                    8 -> if(options.importPoints && body+40<=shp.size){
                        val n=leInt(shp,body+36); val start=body+40
                        repeat(n){j->
                            val po=start+j*16
                            sourceToWgs84(leDouble(shp,po),leDouble(shp,po+8),effectiveCrs)?.let {
                                pointsOut += buildPoint(project,(pointsOut.size+1).toString(),"","",it.latitude,it.longitude,null)
                            }
                        }
                    }
                }
                offset=body+contentBytes
            }
        }
    }

    private fun coordsToGeometry(array: JSONArray, tool: String, coords: JSONArray?, crs: String) {
        if (coords == null || coords.length() < 2) return
        val pts = JSONArray()
        for (i in 0 until coords.length()) {
            val c = coords.optJSONArray(i) ?: continue
            if (c.length() < 2) continue
            sourceToWgs84(c.optDouble(0), c.optDouble(1), crs)?.let {
                pts.put(JSONObject().put("lat", it.latitude).put("lon", it.longitude))
            }
        }
        if (pts.length() >= 2) addGeometry(array, UUID.randomUUID().toString(), tool, pts)
    }

    private fun addKmlGeometry(array: JSONArray, tool: String, raw: String, crs: String) {
        val pts=JSONArray()
        raw.trim().split(Regex("\\s+")).forEach { token ->
            val p=token.split(",")
            val x=p.getOrNull(0)?.toDoubleOrNull() ?: return@forEach
            val y=p.getOrNull(1)?.toDoubleOrNull() ?: return@forEach
            sourceToWgs84(x,y,crs)?.let { pts.put(JSONObject().put("lat",it.latitude).put("lon",it.longitude)) }
        }
        if(pts.length()>=2) addGeometry(array,UUID.randomUUID().toString(),tool,pts)
    }

    private fun addGeometry(array: JSONArray, id: String, tool: String, pts: JSONArray) {
        array.put(
            JSONObject()
                .put("id",id)
                .put("tool",tool)
                .put("parallelOffsetM",1.0)
                .put("points",pts)
        )
    }

    private fun readShpFiles(bytes: ByteArray): List<ByteArray> {
        val out=mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while(true){
                val e=zip.nextEntry ?: break
                if(!e.isDirectory && e.name.lowercase().endsWith(".shp")) out += zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    private fun previewShapeZip(bytes: ByteArray): ProjectImportPreview {
        var p=0; var l=0; var g=0
        readShpFiles(bytes).forEach { shp ->
            if(shp.size<100||beInt(shp,0)!=9994) return@forEach
            var offset=100
            while(offset+8<=shp.size){
                val cb=beInt(shp,offset+4)*2; val body=offset+8
                if(cb<4||body+cb>shp.size) break
                when(leInt(shp,body)){1,8->p++;3->l++;5->g++}
                offset=body+cb
            }
        }
        return ProjectImportPreview(ProjectImportFormat.SHAPE_ZIP,p,l,g)
    }

    private fun leInt(b: ByteArray, o: Int)=ByteBuffer.wrap(b,o,4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun leDouble(b: ByteArray, o: Int)=ByteBuffer.wrap(b,o,8).order(ByteOrder.LITTLE_ENDIAN).double
    private fun beInt(b: ByteArray, o: Int)=ByteBuffer.wrap(b,o,4).order(ByteOrder.BIG_ENDIAN).int
    private fun stripXml(value:String)=value.replace(Regex("<[^>]+>"),"").trim()
}
