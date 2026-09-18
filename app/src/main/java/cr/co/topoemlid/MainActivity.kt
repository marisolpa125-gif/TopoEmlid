package cr.co.topoemlid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TopoEmlidApp() } }
    }
}

@Composable
fun TopoEmlidApp() {
    var selectedTool by remember { mutableStateOf(DrawTool.POINT) }
    var selectedCrs by remember { mutableStateOf(CoordinateCatalog.profiles.first()) }
    var geoid by remember { mutableStateOf(GeoidModel.LOCAL_FILE) }
    var geoidFile by remember { mutableStateOf<GeoidFileConfig?>(null) }
    var page by remember { mutableStateOf("Mapa") }
    val gnss = remember { GnssStatus() }

    Scaffold(
        topBar = { GnssBar(gnss) },
        bottomBar = {
            NavigationBar {
                listOf("Mapa","Capas","Proyecto").forEach { item ->
                    NavigationBarItem(
                        selected = page == item,
                        onClick = { page = item },
                        icon = { Text(if (item=="Mapa") "⌖" else if (item=="Capas") "▱" else "⚙") },
                        label = { Text(item) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (page) {
                "Capas" -> LayersScreen()
                "Proyecto" -> ProjectSettings(
                    crs = selectedCrs,
                    onCrs = { selectedCrs = it },
                    geoid = geoid,
                    onGeoid = { geoid = it },
                    geoidFile = geoidFile,
                    onGeoidFile = { geoidFile = it }
                )
                else -> MapWorkspace(selectedTool) { selectedTool = it }
            }
        }
    }
}

@Composable
private fun GnssBar(status: GnssStatus) {
    Surface(shadowElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status.receiverName, fontWeight = FontWeight.Bold)
                Text(if (status.connected) status.solution else "Desconectado")
            }
            Text("H: ${status.horizontalAccuracyM?.let { "%.3f m".format(it) } ?: "—"} • Sat: ${status.satellites ?: "—"}")
        }
    }
}

@Composable
private fun MapWorkspace(selectedTool: DrawTool, onTool: (DrawTool) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE7ECEF)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MAPA / CAD", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Mapa preparado para MapLibre y servicios WMS/WMTS/XYZ")
                Spacer(Modifier.height(12.dp))
                Text("Herramienta activa: ${selectedTool.label}")
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp)) {
            DrawTool.entries.forEach { tool ->
                FilterChip(
                    selected = tool == selectedTool,
                    onClick = { onTool(tool) },
                    label = { Text(tool.label) },
                    modifier = Modifier.padding(horizontal = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun LayersScreen() {
    var layers by remember {
        mutableStateOf(listOf(
            LayerItem("Puntos GNSS"), LayerItem("Linderos"), LayerItem("Construcciones"),
            LayerItem("Propuesta división"), LayerItem("Replanteo", false)
        ))
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Capas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Preparado para DXF, KML/KMZ, GeoJSON, CSV, WMS, WMTS y XYZ.")
        Spacer(Modifier.height(12.dp))
        layers.forEachIndexed { index, layer ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    layers = layers.toMutableList().also {
                        it[index] = layer.copy(visible = !layer.visible)
                    }
                }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = layer.visible, onCheckedChange = null)
                Text(layer.name)
            }
        }
        Button(onClick = { layers = layers + LayerItem("Nueva capa") }) { Text("+ Nueva capa") }
    }
}

@Composable
private fun ProjectSettings(
    crs: CrsProfile,
    onCrs: (CrsProfile) -> Unit,
    geoid: GeoidModel,
    onGeoid: (GeoidModel) -> Unit,
    geoidFile: GeoidFileConfig?,
    onGeoidFile: (GeoidFileConfig?) -> Unit
) {
    val context = LocalContext.current
    val geoidPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            var name = uri.lastPathSegment ?: "archivo_geoide"
            var size: Long? = null
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (ni >= 0) name = cursor.getString(ni) ?: name
                    if (si >= 0 && !cursor.isNull(si)) size = cursor.getLong(si)
                }
            }
            onGeoid(GeoidModel.LOCAL_FILE)
            onGeoidFile(GeoidFileConfig(uri.toString(), name, context.contentResolver.getType(uri), size))
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Configuración del proyecto", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        SettingsCard("Receptor", "Emlid Reach RS2+", "Bluetooth / NMEA")
        SettingsCard("Correcciones RTK", "Perfil NTRIP", "Servidor, puerto, mountpoint y credenciales")

        Text("Sistema horizontal", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        CoordinateCatalog.profiles.forEach { item ->
            Row(
                Modifier.fillMaxWidth().clickable { onCrs(item) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = item == crs, onClick = { onCrs(item) })
                Column {
                    Text(item.name)
                    Text(item.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text("Sistema vertical", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        GeoidModel.entries.forEach { item ->
            Row(
                Modifier.fillMaxWidth().clickable { onGeoid(item) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = item == geoid, onClick = { onGeoid(item) })
                Text(item.label)
            }
        }

        if (geoid == GeoidModel.LOCAL_FILE) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Archivo de geoide", fontWeight = FontWeight.Bold)
                    Text(geoidFile?.displayName ?: "No se ha seleccionado ningún archivo.")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { geoidPicker.launch(arrayOf("*/*")) }) {
                            Text(if (geoidFile == null) "Cargar archivo" else "Cambiar archivo")
                        }
                        if (geoidFile != null) {
                            OutlinedButton(onClick = { onGeoidFile(null) }) { Text("Quitar") }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Preparado para cargar EGM2008 u otra cuadrícula geoidal local.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, value: String, subtitle: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(value)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
