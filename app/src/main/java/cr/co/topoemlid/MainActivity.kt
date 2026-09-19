package cr.co.topoemlid

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import org.maplibre.android.MapLibre
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { MaterialTheme { TopoEmlidApp() } }
    }
}

@Composable
fun TopoEmlidApp() {
    val context = LocalContext.current
    val store = remember { ProjectStore(context) }
    val ntripStore = remember { NtripStore(context) }
    val receiverStore = remember { ReceiverStore(context) }
    val receiverConnection = remember { ReceiverConnectionManager(context) }
    val ntripConnection = remember {
        NtripConnectionManager { data, length ->
            receiverConnection.sendCorrections(data, length)
        }
    }
    val initialProjects = remember { store.loadProjects() }
    var projects by remember { mutableStateOf(initialProjects) }
    var ntripProfiles by remember { mutableStateOf(ntripStore.loadProfiles()) }
    var receiverProfiles by remember { mutableStateOf(receiverStore.loadProfiles()) }
    var activeReceiverId by remember { mutableStateOf(receiverStore.activeReceiverId()) }
    var activeProjectId by remember {
        val stored = store.activeProjectId()
        mutableStateOf(
            when {
                stored != null && initialProjects.any { it.id == stored } -> stored
                initialProjects.size == 1 -> initialProjects.first().id
                else -> null
            }
        )
    }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("Receptores") }
    var selectedTool by remember { mutableStateOf(DrawTool.POINT) }
    var showNewProject by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<TopoProject?>(null) }
    val gnss = receiverConnection.status
    val ntripStatus = ntripConnection.status
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75) }
    var previousGnssConnected by remember { mutableStateOf<Boolean?>(null) }
    var previousSolution by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { toneGenerator.release() }
    }

    LaunchedEffect(gnss.connected, gnss.solution, gnss.nmeaReceiving) {
        val currentConnected = gnss.connected && gnss.nmeaReceiving
        val oldConnected = previousGnssConnected

        if (oldConnected != null && oldConnected != currentConnected) {
            if (currentConnected) {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            } else {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 420)
            }
        }

        val normalized = gnss.solution.uppercase()
        val oldSolution = previousSolution?.uppercase()
        if (currentConnected && oldSolution != null && oldSolution != normalized) {
            when (normalized) {
                "FIX" -> toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
                "FLOAT" -> toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                "SINGLE", "DGPS", "SIN FIX" -> toneGenerator.startTone(ToneGenerator.TONE_SUP_ERROR, 280)
            }
        }

        previousGnssConnected = currentConnected
        previousSolution = gnss.solution
    }

    fun persist(list: List<TopoProject>) {
        projects = list
        store.saveProjects(list)
    }

    val activeProject = projects.firstOrNull { it.id == activeProjectId }

    LaunchedEffect(activeProjectId, projects.size) {
        if (activeProjectId != null) {
            store.setActiveProject(activeProjectId)
        } else if (projects.size == 1) {
            activeProjectId = projects.first().id
            store.setActiveProject(projects.first().id)
        }
    }

    if (showNewProject) {
        NewProjectDialog(
            onDismiss = { showNewProject = false },
            onCreate = { name, location ->
                val p = TopoProject(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                    location = location.trim()
                )
                persist(projects + p)
                activeProjectId = p.id
                store.setActiveProject(p.id)
                selectedProjectId = p.id
                showNewProject = false
            }
        )
    }

    deleteCandidate?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Eliminar proyecto") },
            text = { Text("¿Desea eliminar “${project.name}”? Esta acción no se puede deshacer.") },
            confirmButton = {
                Button(onClick = {
                    val updated = projects.filterNot { it.id == project.id }
                    persist(updated)
                    if (activeProjectId == project.id) {
                        activeProjectId = null
                        store.setActiveProject(null)
                    }
                    if (selectedProjectId == project.id) selectedProjectId = null
                    deleteCandidate = null
                }) { Text("Eliminar") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteCandidate = null }) { Text("Cancelar") }
            }
        )
    }

    Scaffold(
        topBar = { GnssBar(gnss, ntripStatus, activeProject?.name) },
        bottomBar = {
            NavigationBar {
                listOf("Receptores", "Proyecto", "Capas", "Levantamiento", "Replanteo").forEach { item ->
                    NavigationBarItem(
                        selected = page == item,
                        onClick = { page = item },
                        icon = { Text(if (item == "Receptores") "◉" else if (item == "Proyecto") "▣" else if (item == "Capas") "▱" else if (item == "Levantamiento") "⌖" else "⇢") },
                        label = { Text(item) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (page) {
                "Receptores" -> ReceiverSection(
                    profiles = receiverProfiles,
                    onProfilesChanged = {
                        receiverProfiles = it
                        receiverStore.saveProfiles(it)
                    },
                    activeReceiverId = activeReceiverId,
                    onSelectReceiver = { receiver ->
                        activeReceiverId = receiver.id
                        receiverStore.setActiveReceiver(receiver.id)
                    },
                    ntripProfiles = ntripProfiles,
                    onNtripProfilesChanged = {
                        ntripProfiles = it
                        ntripStore.saveProfiles(it)
                    },
                    ntripStatus = ntripStatus,
                    onConnectNtrip = { profile -> ntripConnection.connect(profile) },
                    onDisconnectNtrip = { ntripConnection.disconnect() },
                    gnss = gnss,
                    connecting = receiverConnection.connecting,
                    lastError = receiverConnection.lastError,
                    onConnect = { receiver -> receiverConnection.connect(receiver) },
                    onDisconnect = { receiverConnection.disconnect() }
                )
                "Replanteo" -> StakeoutScreen(activeProject, gnss)
                "Capas" -> ProjectLayersScreen(activeProject)
                "Proyecto" -> {
                    val selected = projects.firstOrNull { it.id == selectedProjectId }
                    if (selected == null) {
                        ProjectHub(
                            projects = projects,
                            activeProjectId = activeProjectId,
                            onCreate = { showNewProject = true },
                            onOpen = { p ->
                                activeProjectId = p.id
                                store.setActiveProject(p.id)
                                page = "Levantamiento"
                            },
                            onEdit = { p -> selectedProjectId = p.id },
                            onView = { p -> selectedProjectId = p.id },
                            onDelete = { p -> deleteCandidate = p }
                        )
                    } else {
                        ProjectDetails(
                            project = selected,
                            isActive = selected.id == activeProjectId,
                            onBack = { selectedProjectId = null },
                            onSetActive = {
                                activeProjectId = selected.id
                                store.setActiveProject(selected.id)
                            },
                            onUpdate = { updated ->
                                persist(projects.map { if (it.id == updated.id) updated else it })
                            },
                            onSaveAndBack = { updated ->
                                persist(projects.map { if (it.id == updated.id) updated else it })
                                selectedProjectId = null
                            },
                            onDelete = { deleteCandidate = selected },
                            ntripProfiles = ntripProfiles
                        )
                    }
                }
                else -> SurveyScreen(
                    project = activeProject,
                    gnss = gnss
                )
            }
        }
    }
}

@Composable
private fun GnssBar(status: GnssStatus, ntrip: NtripLiveStatus, projectName: String?) {
    val rtkColor = when {
        !status.connected -> Color(0xFF757575)
        status.solution.equals("FIX", ignoreCase = true) -> Color(0xFF2E7D32)
        status.solution.equals("FLOAT", ignoreCase = true) -> Color(0xFFF9A825)
        else -> Color(0xFFC62828)
    }
    val ntripColor = if (ntrip.connected || ntrip.connecting) Color(0xFF00ACC1) else Color(0xFF757575)

    Surface(shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(projectName ?: "Sin proyecto activo", fontWeight = FontWeight.Bold)
                    Text(
                        "GNSS: ${if (status.connected) "Conectado" else "Desconectado"} • RTK: ${status.solution}",
                        style = MaterialTheme.typography.bodySmall,
                        color = rtkColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "Sat: ${status.satellites ?: "—"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "NTRIP: ${if (ntrip.connected) "Conectado" else if (ntrip.connecting) "Conectando" else "Desconectado"} • " +
                    "H: ${status.horizontalAccuracyM?.let { "%.3f m".format(it) } ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
                color = ntripColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectHub(
    projects: List<TopoProject>,
    activeProjectId: String?,
    onCreate: () -> Unit,
    onOpen: (TopoProject) -> Unit,
    onEdit: (TopoProject) -> Unit,
    onView: (TopoProject) -> Unit,
    onDelete: (TopoProject) -> Unit
) {
    var menuProject by remember { mutableStateOf<TopoProject?>(null) }

    menuProject?.let { p ->
        AlertDialog(
            onDismissRequest = { menuProject = null },
            title = { Text(p.name) },
            text = {
                Column {
                    TextButton(onClick = { menuProject = null; onOpen(p) }) { Text("Abrir") }
                    TextButton(onClick = { menuProject = null; onEdit(p) }) { Text("Editar") }
                    TextButton(onClick = { menuProject = null; onView(p) }) { Text("Ver información") }
                    TextButton(onClick = { menuProject = null; onDelete(p) }) { Text("Eliminar") }
                }
            },
            confirmButton = {}
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Proyectos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Button(onClick = onCreate) { Text("+ Nuevo") }
        }
        Spacer(Modifier.height(10.dp))
        if (projects.isEmpty()) {
            Text("Aún no hay proyectos. Cree uno para definir su CRS, geoide y demás parámetros.")
        }
        projects.forEach { p ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .combinedClickable(
                        onClick = { onOpen(p) },
                        onLongClick = { menuProject = p }
                    )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        if (p.id == activeProjectId) AssistChip(onClick = {}, label = { Text("ACTIVO") })
                    }
                    if (p.location.isNotBlank()) Text(p.location, style = MaterialTheme.typography.bodySmall)
                    Text("CRS: ${p.crsName} • Geoide: ${p.geoidFileName ?: p.geoidModel.label}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Toque para abrir • Mantenga presionado para más opciones",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo proyecto") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre del proyecto") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Lugar / referencia") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { onCreate(name, location) }) { Text("Crear") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun ProjectDetails(
    project: TopoProject,
    isActive: Boolean,
    onBack: () -> Unit,
    onSetActive: () -> Unit,
    onUpdate: (TopoProject) -> Unit,
    onSaveAndBack: (TopoProject) -> Unit,
    onDelete: () -> Unit,
    ntripProfiles: List<NtripProfile>
) {
    var name by remember(project.id, project.name) { mutableStateOf(project.name) }
    var location by remember(project.id, project.location) { mutableStateOf(project.location) }
    var antennaText by remember(project.id, project.antennaHeightM) { mutableStateOf(project.antennaHeightM.toString()) }
    var geoidFileName by remember(project.id, project.geoidFileName) { mutableStateOf(project.geoidFileName) }
    var geoidFileUri by remember(project.id, project.geoidFileUri) { mutableStateOf(project.geoidFileUri) }
    var crsName by remember(project.id, project.crsName) { mutableStateOf(project.crsName) }
    val context = LocalContext.current

    val geoidPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            var displayName = uri.lastPathSegment ?: "archivo_geoide"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) displayName = cursor.getString(idx) ?: displayName
            }
            geoidFileUri = uri.toString()
            geoidFileName = displayName
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("← Proyectos") }
        Text("Datos del proyecto", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (isActive) Text("Proyecto activo", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Lugar / referencia") }, modifier = Modifier.fillMaxWidth())

        Text("Sistema horizontal", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        CoordinateCatalog.profiles.forEach { crs ->
            Row(Modifier.fillMaxWidth().clickable { crsName = crs.name }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = crsName == crs.name, onClick = { crsName = crs.name })
                Column {
                    Text(crs.name)
                    Text(crs.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text("Geoide", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text(geoidFileName ?: "Sin archivo geoidal local")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { geoidPicker.launch(arrayOf("*/*")) }) { Text("Cargar archivo") }
            if (geoidFileUri != null) OutlinedButton(onClick = { geoidFileUri = null; geoidFileName = null }) { Text("Quitar") }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = antennaText,
            onValueChange = { antennaText = it },
            label = { Text("Altura de antena (m)") },
            singleLine = true
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onSaveAndBack(project.copy(
                    name = name.ifBlank { project.name },
                    location = location,
                    crsName = crsName,
                    geoidFileUri = geoidFileUri,
                    geoidFileName = geoidFileName,
                    antennaHeightM = antennaText.toDoubleOrNull() ?: project.antennaHeightM
                ))
            }) { Text("Guardar proyecto") }

            OutlinedButton(onClick = onBack) { Text("Cancelar") }
        }

        if (!isActive) {
            OutlinedButton(onClick = onSetActive, modifier = Modifier.padding(top = 8.dp)) { Text("Establecer como proyecto activo") }
        }

        OutlinedButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) { Text("Eliminar proyecto") }

        Spacer(Modifier.height(16.dp))
        SettingsCard("Datos guardados", "CRS: ${crsName}", "Geoide: ${geoidFileName ?: project.geoidModel.label} • Altura antena: ${antennaText} m")
        Text("Perfil NTRIP", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        if (ntripProfiles.isEmpty()) {
            Text("No hay perfiles NTRIP guardados. Créelos desde la pestaña NTRIP.")
        } else {
            ntripProfiles.forEach { profile ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onUpdate(project.copy(ntripProfileId = profile.id, ntripProfileName = profile.name))
                    }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = project.ntripProfileId == profile.id,
                        onClick = { onUpdate(project.copy(ntripProfileId = profile.id, ntripProfileName = profile.name)) }
                    )
                    Column {
                        Text(profile.name)
                        Text("${profile.host}:${profile.port} • ${profile.mountPoint}", style = MaterialTheme.typography.bodySmall)
                    }
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
