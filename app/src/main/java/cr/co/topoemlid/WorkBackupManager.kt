package cr.co.topoemlid

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

data class WorkRestoreResult(
    val project: TopoProject,
    val pointsRestored: Int,
    val geometriesRestored: Int
)

object WorkBackupManager {
    fun restore(context: Context, uri: Uri): Result<WorkRestoreResult> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        } ?: error("No se pudo leer el archivo seleccionado.")

        val root = JSONObject(text)
        val format = root.optString("format", "")
        require(format == "TOPO_EMLID_BACKUP_V1") {
            "El archivo no es un respaldo de trabajo TOPO EMLID compatible."
        }

        val sourceProject = root.optJSONObject("project")
            ?: error("El respaldo no contiene los datos del trabajo.")

        // Siempre crear un ID nuevo para poder abrir el mismo respaldo varias veces
        // sin sobrescribir un trabajo existente en la tablet.
        val newProjectId = UUID.randomUUID().toString()
        val geoidModel = runCatching {
            GeoidModel.valueOf(
                sourceProject.optString("geoidModel", GeoidModel.LOCAL_FILE.name)
            )
        }.getOrDefault(GeoidModel.LOCAL_FILE)

        val project = TopoProject(
            id = newProjectId,
            name = sourceProject.optString("name", "Trabajo restaurado").ifBlank { "Trabajo restaurado" },
            location = sourceProject.optString("location", ""),
            crsName = sourceProject.optString("crsName", "CRTM05"),
            geoidModel = geoidModel,
            // URI de Android no es portable entre tablets. Se conserva el nombre para
            // informar qué geoide usaba, pero el archivo debe volver a seleccionarse.
            geoidFileUri = null,
            geoidFileName = sourceProject.optString("geoidFileName", "").ifBlank { null },
            antennaHeightM = sourceProject.optDouble("antennaHeightM", 2.0),
            ntripProfileName = sourceProject.optString("ntripProfileName", "").ifBlank { null },
            ntripProfileId = null,
            receiverProfileId = null,
            receiverProfileName = sourceProject.optString("receiverProfileName", "").ifBlank { null },
            createdAt = sourceProject.optLong("createdAt", System.currentTimeMillis())
        )

        val pointArray = root.optJSONArray("points") ?: JSONArray()
        val points = (0 until pointArray.length()).mapNotNull { i ->
            val o = pointArray.optJSONObject(i) ?: return@mapNotNull null

            fun nullableDouble(key: String): Double? =
                if (!o.has(key) || o.isNull(key)) null else o.optDouble(key)

            fun nullableInt(key: String): Int? =
                if (!o.has(key) || o.isNull(key)) null else o.optInt(key)

            SurveyPoint(
                id = UUID.randomUUID().toString(),
                projectId = newProjectId,
                pointNumber = o.optString("pointNumber", (i + 1).toString()),
                description = o.optString("description", ""),
                code = o.optString("code", ""),
                antennaHeightM = o.optDouble("antennaHeightM", project.antennaHeightM),
                occupationSeconds = o.optInt("occupationSeconds", 1),
                latitude = nullableDouble("latitude"),
                longitude = nullableDouble("longitude"),
                eastingM = nullableDouble("eastingM"),
                northingM = nullableDouble("northingM"),
                projectCrsName = o.optString("projectCrsName", project.crsName).ifBlank { project.crsName },
                ellipsoidalHeightM = nullableDouble("ellipsoidalHeightM"),
                orthometricHeightM = nullableDouble("orthometricHeightM"),
                geoidUndulationM = nullableDouble("geoidUndulationM"),
                geoidFileName = o.optString("geoidFileName", project.geoidFileName ?: "").ifBlank { null },
                horizontalAccuracyM = nullableDouble("horizontalAccuracyM"),
                verticalAccuracyM = nullableDouble("verticalAccuracyM"),
                solution = o.optString("solution", "SIN SOLUCIÓN"),
                satellites = nullableInt("satellites"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
        SurveyPointStore(context).save(newProjectId, points)

        val geometries = root.optJSONArray("geometries") ?: JSONArray()
        // Los objetos geométricos no dependen del ID del proyecto dentro de su JSON,
        // por lo que pueden restaurarse directamente bajo la nueva clave.
        context.getSharedPreferences("survey_geometries", Context.MODE_PRIVATE)
            .edit()
            .putString("geometries_$newProjectId", geometries.toString())
            .apply()

        WorkRestoreResult(
            project = project,
            pointsRestored = points.size,
            geometriesRestored = geometries.length()
        )
    }
}
