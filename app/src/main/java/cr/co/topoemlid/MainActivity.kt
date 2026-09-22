package cr.co.topoemlid

import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import org.maplibre.android.MapLibre
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import java.util.Calendar
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { TopoEmlidRoot() }
    }
}

private enum class AppViewMode(val label: String) {
    DAY("Día"),
    NIGHT("Noche"),
    AUTO("Automático")
}

private val LocalAppViewMode = compositionLocalOf { AppViewMode.AUTO }
private val LocalSetAppViewMode = compositionLocalOf<(AppViewMode) -> Unit> { {} }

@Composable
private fun TopoEmlidRoot() {
    val context = LocalContext.current
    var showStartupSplash by rememberSaveable { mutableStateOf(true) }

    val startupImage = remember {
        runCatching {
            val encoded = (0..2).joinToString(separator = "") { index ->
                val name = "splash/chunk_%02d.txt".format(index)
                context.assets.open(name).bufferedReader().use { it.readText() }
            }
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    LaunchedEffect(Unit) {
        delay(1600L)
        showStartupSplash = false
    }

    if (showStartupSplash) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF020712))
        ) {
            startupImage?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = "Pantalla de inicio TOPO EMLID",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        return
    }
    val prefs = remember { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }
    var mode by remember {
        mutableStateOf(
            runCatching {
                AppViewMode.valueOf(prefs.getString("view_mode", AppViewMode.AUTO.name)!!)
            }.getOrDefault(AppViewMode.AUTO)
        )
    }
    var currentHour by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }

    LaunchedEffect(mode) {
        if (mode == AppViewMode.AUTO) {
            while (true) {
                currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                kotlinx.coroutines.delay(60_000)
            }
        }
    }

    val useDark = when (mode) {
        AppViewMode.DAY -> false
        AppViewMode.NIGHT -> true
        AppViewMode.AUTO -> currentHour < 6 || currentHour >= 18
    }

    val scheme = if (useDark) darkColorScheme() else lightColorScheme()

    CompositionLocalProvider(
        LocalAppViewMode provides mode,
        LocalSetAppViewMode provides { newMode ->
            mode = newMode
            prefs.edit().putString("view_mode", newMode.name).apply()
        }
    ) {
        MaterialTheme(colorScheme = scheme) {
            TopoEmlidApp()
        }
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
    var selectedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf("Receptores") }
    var selectedTool by remember { mutableStateOf(DrawTool.POINT) }
    var showNewProject by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<TopoProject?>(null) }
    val gnss = receiverConnection.status
    val ntripStatus = ntripConnection.status
    val fieldSounds = remember { FieldSoundManager(context) }
    var previousGnssConnected by remember { mutableStateOf<Boolean?>(null) }
    var previousSolution by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { fieldSounds.release() }
    }

    LaunchedEffect(gnss.connected, gnss.solution, gnss.nmeaReceiving) {
        val currentConnected = gnss.connected && gnss.nmeaReceiving
        val oldConnected = previousGnssConnected

        if (oldConnected != null && oldConnected != currentConnected) {
            if (currentConnected) {
                fieldSounds.connected()
            } else {
                fieldSounds.disconnected()
            }
        }

        val normalized = gnss.solution.uppercase()
        val oldSolution = previousSolution?.uppercase()
        if (currentConnected && oldSolution != null && oldSolution != normalized) {
            when (normalized) {
                "FIX" -> fieldSounds.fix()
                "FLOAT" -> fieldSounds.float()
                "SINGLE", "DGPS", "SIN FIX" -> fieldSounds.autonomous()
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
                listOf("Receptores", "Proyecto", "Capas", "Levantamiento", "Replanteo", "Configuración").forEach { item ->
                    NavigationBarItem(
                        selected = page == item,
                        onClick = { page = item },
                        icon = {
                            Text(
                                when (item) {
                                    "Receptores" -> "◉"
                                    "Proyecto" -> "▣"
                                    "Capas" -> "▱"
                                    "Levantamiento" -> "⌖"
                                    "Replanteo" -> "⇢"
                                    else -> "⚙"
                                }
                            )
                        },
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
                "Configuración" -> AppSettingsScreen(
                    onExitApp = {
                        ntripConnection.disconnect()
                        receiverConnection.disconnect()
                        (context as? MainActivity)?.finishAndRemoveTask()
                    }
                )
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
private fun AppSettingsScreen(
    onExitApp: () -> Unit
) {
    val context = LocalContext.current
    val mode = LocalAppViewMode.current
    val setMode = LocalSetAppViewMode.current
    val soundManager = remember { FieldSoundManager(context) }
    var confirmExit by remember { mutableStateOf(false) }
    var soundVersion by remember { mutableIntStateOf(0) }
    var pendingSoundEvent by remember { mutableStateOf<FieldSoundEvent?>(null) }
    val settingsScope = rememberCoroutineScope()
    var shareMobileData by remember { mutableStateOf<Boolean?>(null) }
    var shareMobileDataBusy by remember { mutableStateOf(false) }
    var shareMobileDataMessage by remember { mutableStateOf<String?>(null) }
    var mobileDataEnabled by remember { mutableStateOf<Boolean?>(null) }
    var mobileDataEnabledBusy by remember { mutableStateOf(false) }
    var mobileDataEnabledMessage by remember { mutableStateOf<String?>(null) }
    var mobileRoaming by remember { mutableStateOf<Boolean?>(null) }
    var mobileRoamingBusy by remember { mutableStateOf(false) }
    var mobileRoamingMessage by remember { mutableStateOf<String?>(null) }
    var mobileUpgrades by remember { mutableStateOf<Boolean?>(null) }
    var mobileUpgradesBusy by remember { mutableStateOf(false) }
    var mobileUpgradesMessage by remember { mutableStateOf<String?>(null) }

    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val event = pendingSoundEvent
        pendingSoundEvent = null
        if (uri != null && event != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            var displayName = uri.lastPathSegment ?: "Sonido personalizado"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) {
                    displayName = cursor.getString(idx) ?: displayName
                }
            }
            soundManager.setCustomSound(event, uri, displayName)
            soundVersion++
        }
    }

    DisposableEffect(Unit) {
        onDispose { soundManager.release() }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Cerrar TopoEmlid") },
            text = {
                Text(
                    "Se cerrarán las conexiones NTRIP y Bluetooth/NMEA activas antes de salir."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmExit = false
                        onExitApp()
                    }
                ) { Text("Cerrar aplicación") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmExit = false }) { Text("Cancelar") }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Configuración",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Vista de la aplicación", fontWeight = FontWeight.Bold)
                Text(
                    "Cambia únicamente la interfaz de TopoEmlid. El mapa de Levantamiento y Replanteo conserva siempre sus colores y mapa base.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(8.dp))

                AppViewMode.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { setMode(option) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == option,
                            onClick = { setMode(option) }
                        )
                        Column {
                            Text(option.label)
                            if (option == AppViewMode.AUTO) {
                                Text(
                                    "Día de 6:00 a. m. a 6:00 p. m. • Noche el resto del tiempo",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Sonidos", fontWeight = FontWeight.Bold)
                Text(
                    "Puede asignar un sonido distinto a cada evento. Para avisos cortos se recomiendan WAV u OGG; también puede cargar MP3 o M4A/AAC compatibles con Android.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))

                key(soundVersion) {
                    FieldSoundEvent.entries.forEach { event ->
                        val customName = soundManager.customSoundName(event)
                        Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(event.label, fontWeight = FontWeight.Bold)
                                Text(
                                    customName ?: "Sonido predeterminado de Topo Emlid",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(7.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            pendingSoundEvent = event
                                            soundPicker.launch(arrayOf("audio/*"))
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (customName == null) "Cambiar" else "Reemplazar")
                                    }
                                    OutlinedButton(
                                        onClick = { soundManager.play(event) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Probar")
                                    }
                                }
                                if (customName != null) {
                                    TextButton(
                                        onClick = {
                                            soundManager.clearCustomSound(event)
                                            soundVersion++
                                        }
                                    ) {
                                        Text("Restaurar predeterminado")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("SIM / Datos móviles del Reach", fontWeight = FontWeight.Bold)
                Text(
                    "Aquí se agruparán las opciones de la SIM del receptor y el uso de datos móviles. La interfaz queda preparada sin alterar Bluetooth/NMEA, BLE ni NTRIP.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(10.dp))

                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Usar datos móviles", fontWeight = FontWeight.Bold)
                                Text(
                                    "Activa o desactiva la conexión celular del Reach.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = mobileDataEnabled == true,
                                enabled = !mobileDataEnabledBusy,
                                onCheckedChange = { checked ->
                                    mobileDataEnabledBusy = true
                                    mobileDataEnabledMessage = null
                                    settingsScope.launch {
                                        val result = ReachLocalApiClient("192.168.42.1")
                                            .setMobileDataEnabled(checked)
                                        result.onSuccess {
                                            mobileDataEnabled = checked
                                            mobileDataEnabledMessage =
                                                if (checked) "Datos móviles activados" else "Datos móviles desactivados"
                                        }.onFailure {
                                            mobileDataEnabledMessage = it.message ?: "No se pudo cambiar el estado de datos móviles"
                                        }
                                        mobileDataEnabledBusy = false
                                    }
                                }
                            )
                        }
                        Text(
                            when {
                                mobileDataEnabledBusy -> "Aplicando cambio en el Reach…"
                                mobileDataEnabled == null -> "Estado no leído todavía."
                                mobileDataEnabled == true -> "Datos móviles: activados"
                                else -> "Datos móviles: desactivados"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        mobileDataEnabledMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Compartir Internet por hotspot", fontWeight = FontWeight.Bold)
                                Text(
                                    "SIM del Reach → hotspot Wi‑Fi → tablet",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = shareMobileData == true,
                                enabled = !shareMobileDataBusy,
                                onCheckedChange = { checked ->
                                    shareMobileDataBusy = true
                                    shareMobileDataMessage = null
                                    settingsScope.launch {
                                        val result = ReachLocalApiClient("192.168.42.1")
                                            .setMobileDataSharing(checked)
                                        result.onSuccess {
                                            shareMobileData = checked
                                            shareMobileDataMessage =
                                                if (checked) "Compartir Internet activado" else "Compartir Internet desactivado"
                                        }.onFailure {
                                            shareMobileDataMessage = it.message ?: "No se pudo cambiar la opción"
                                        }
                                        shareMobileDataBusy = false
                                    }
                                }
                            )
                        }
                        Text(
                            when {
                                shareMobileDataBusy -> "Aplicando cambio en el Reach…"
                                shareMobileData == null -> "Estado no leído todavía. Al cambiarlo se enviará la orden directamente al Reach."
                                shareMobileData == true -> "Compartir Internet: activado"
                                else -> "Compartir Internet: desactivado"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        shareMobileDataMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Roaming de datos", fontWeight = FontWeight.Bold)
                                Text(
                                    "Úselo solo si su plan celular lo requiere.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = mobileRoaming == true,
                                enabled = !mobileRoamingBusy,
                                onCheckedChange = { checked ->
                                    mobileRoamingBusy = true
                                    mobileRoamingMessage = null
                                    settingsScope.launch {
                                        val result = ReachLocalApiClient("192.168.42.1")
                                            .setMobileRoaming(checked)
                                        result.onSuccess {
                                            mobileRoaming = checked
                                            mobileRoamingMessage =
                                                if (checked) "Roaming activado" else "Roaming desactivado"
                                        }.onFailure {
                                            mobileRoamingMessage = it.message ?: "No se pudo cambiar el roaming"
                                        }
                                        mobileRoamingBusy = false
                                    }
                                }
                            )
                        }
                        Text(
                            when {
                                mobileRoamingBusy -> "Aplicando cambio en el Reach…"
                                mobileRoaming == null -> "Estado no leído todavía."
                                mobileRoaming == true -> "Roaming: activado"
                                else -> "Roaming: desactivado"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        mobileRoamingMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }

                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Actualizaciones por datos móviles", fontWeight = FontWeight.Bold)
                                Text(
                                    "Permite al Reach descargar actualizaciones usando la SIM cuando no hay Internet por Wi‑Fi.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = mobileUpgrades == true,
                                enabled = !mobileUpgradesBusy,
                                onCheckedChange = { checked ->
                                    mobileUpgradesBusy = true
                                    mobileUpgradesMessage = null
                                    settingsScope.launch {
                                        val result = ReachLocalApiClient("192.168.42.1")
                                            .setMobileGsmUpgrades(checked)
                                        result.onSuccess {
                                            mobileUpgrades = checked
                                            mobileUpgradesMessage =
                                                if (checked) "Actualizaciones por datos móviles activadas"
                                                else "Actualizaciones por datos móviles desactivadas"
                                        }.onFailure {
                                            mobileUpgradesMessage = it.message ?: "No se pudo cambiar esta opción"
                                        }
                                        mobileUpgradesBusy = false
                                    }
                                }
                            )
                        }
                        Text(
                            when {
                                mobileUpgradesBusy -> "Aplicando cambio en el Reach…"
                                mobileUpgrades == null -> "Estado no leído todavía."
                                mobileUpgrades == true -> "Actualizaciones móviles: activadas"
                                else -> "Actualizaciones móviles: desactivadas"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        mobileUpgradesMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                SettingsCard(
                    "APN / Credenciales",
                    "Operador celular",
                    "APN, usuario, contraseña y PIN cuando el proveedor lo requiera."
                )
                SettingsCard(
                    "Uso de datos",
                    "Estadísticas del módem",
                    "Preparado para mostrar consumo de datos cuando terminemos de vincular la ruta local del módem."
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    "Usar datos móviles, Compartir Internet, Roaming y Actualizaciones por datos móviles ya usan las órdenes confirmadas del Reach. APN queda pendiente si luego desea configurarlo desde Topo Emlid.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Aplicación", fontWeight = FontWeight.Bold)
                Text(
                    "Cierre ordenadamente TopoEmlid y sus conexiones activas.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { confirmExit = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cerrar TopoEmlid")
                }
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
    var showExportDialog by remember(project.id) { mutableStateOf(false) }
    var exportFormat by remember(project.id) { mutableStateOf(ProjectExportFormat.CSV) }
    var exportContent by remember(project.id) { mutableStateOf(ProjectExportContent.ALL) }
    var textLayout by remember(project.id) { mutableStateOf(TextPointLayout.POINT_LAT_LON_ELEV_DESC) }
    var textSeparator by remember(project.id) { mutableStateOf(TextSeparator.COMMA) }
    var exportMessage by remember(project.id) { mutableStateOf<String?>(null) }
    var importMessage by remember(project.id) { mutableStateOf<String?>(null) }
    var importSourceCrs by remember(project.id) { mutableStateOf(ImportSourceCrs.PROJECT) }
    var pendingImportUri by remember(project.id) { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember(project.id) { mutableStateOf(false) }

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

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    if (showImportDialog && pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                pendingImportUri = null
            },
            title = { Text("Importar archivo al proyecto") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Sistema de coordenadas del archivo", fontWeight = FontWeight.Bold)
                    ImportSourceCrs.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { importSourceCrs = option }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = importSourceCrs == option,
                                onClick = { importSourceCrs = option }
                            )
                            Text(option.label)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Formatos preparados: TXT, CSV, GeoJSON y KML. DXF y Shapefile ZIP quedan visibles como formatos previstos, pero su lector especializado se completará aparte.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Si el archivo trae puntos, se agregan a este proyecto para replanteo. Si trae líneas o polígonos compatibles, también se agregan como figuras del proyecto.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingImportUri
                        if (uri != null) {
                            val result = ProjectImportManager.import(
                                context = context,
                                project = project,
                                uri = uri,
                                options = ProjectImportOptions(
                                    format = ProjectImportFormat.AUTO,
                                    sourceCrs = importSourceCrs,
                                    importPoints = true,
                                    importGeometries = true
                                )
                            )
                            importMessage = result.fold(
                                onSuccess = { it.message },
                                onFailure = { it.message ?: "No se pudo importar el archivo." }
                            )
                        }
                        showImportDialog = false
                        pendingImportUri = null
                    }
                ) { Text("Importar") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showImportDialog = false
                        pendingImportUri = null
                    }
                ) { Text("Cancelar") }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Exportar trabajo") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Formato", fontWeight = FontWeight.Bold)
                    ProjectExportFormat.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { exportFormat = option }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = exportFormat == option,
                                onClick = { exportFormat = option }
                            )
                            Text(option.label)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("Contenido", fontWeight = FontWeight.Bold)
                    ProjectExportContent.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { exportContent = option }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = exportContent == option,
                                onClick = { exportContent = option }
                            )
                            Text(option.label)
                        }
                    }

                    if (exportFormat == ProjectExportFormat.TXT || exportFormat == ProjectExportFormat.CSV) {
                        Spacer(Modifier.height(8.dp))
                        Text("Orden de campos", fontWeight = FontWeight.Bold)
                        TextPointLayout.entries.forEach { option ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { textLayout = option }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = textLayout == option,
                                    onClick = { textLayout = option }
                                )
                                Text(option.label, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text("Separador", fontWeight = FontWeight.Bold)
                        TextSeparator.entries.forEach { option ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { textSeparator = option }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = textSeparator == option,
                                    onClick = { textSeparator = option }
                                )
                                Text(option.label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Los archivos se guardan en Documentos/TopoEmlid/Trabajos/[proyecto]. " +
                            "Por seguridad, Norte/Este CRTM05 no se exporta todavía hasta validar la transformación geodésica oficial; " +
                            "TXT/CSV usan latitud/longitud WGS84.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val result = ProjectExportManager.export(
                            context = context,
                            project = project,
                            options = ProjectExportOptions(
                                format = exportFormat,
                                content = exportContent,
                                textLayout = textLayout,
                                separator = textSeparator
                            )
                        )
                        exportMessage = result.fold(
                            onSuccess = { "Guardado en $it" },
                            onFailure = { it.message ?: "No se pudo exportar el trabajo." }
                        )
                        showExportDialog = false
                    }
                ) { Text("Exportar") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExportDialog = false }) { Text("Cancelar") }
            }
        )
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

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Exportar / Guardar trabajo", fontWeight = FontWeight.Bold)
                Text(
                    "Exporte solo puntos, solo figuras o todo el trabajo en TXT, CSV, GeoJSON, KML, DXF o respaldo TOPO EMLID.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showExportDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Exportar trabajo")
                }
                exportMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Importar para trabajo / replanteo", fontWeight = FontWeight.Bold)
                Text(
                    "Importe puntos y, cuando el formato lo permita, líneas o polígonos desde archivos externos.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        importPicker.launch(
                            arrayOf(
                                "text/plain",
                                "text/csv",
                                "application/json",
                                "application/geo+json",
                                "application/vnd.google-earth.kml+xml",
                                "application/dxf",
                                "application/zip",
                                "*/*"
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Importar archivo")
                }
                importMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Carpeta recomendada para organizar archivos: Documentos/TopoEmlid/Importar.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

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
