package cr.co.topoemlid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID

private data class NearbyReceiver(
    val name: String,
    val address: String,
    val rssi: Int
)

private fun isLikelyGnssReceiver(name: String): Boolean {
    val n = name.lowercase()
    val markers = listOf(
        "reach", "emlid", "topcon", "hiper", "trimble", "leica", "south", "stonex",
        "hi-target", "hitarget", "chc", "chcnav", "kolida", "spectra",
        "sokkia", "foif", "comnav", "singular", "septentrio", "hemisphere",
        "gnss", "rtk", "gps"
    )
    return markers.any { it in n }
}

private fun receiverBrand(name: String): String {
    val n = name.lowercase()
    return when {
        "emlid" in n || "reach" in n -> "Emlid"
        "topcon" in n || "hiper" in n -> "Topcon"
        "trimble" in n -> "Trimble"
        "leica" in n -> "Leica"
        "south" in n -> "South"
        "stonex" in n -> "Stonex"
        "hi-target" in n || "hitarget" in n -> "Hi-Target"
        "chc" in n || "chcnav" in n -> "CHCNAV"
        "kolida" in n -> "Kolida"
        "sokkia" in n -> "Sokkia"
        "spectra" in n -> "Spectra"
        else -> "GNSS"
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ReceiverSection(
    profiles: List<ReceiverProfile>,
    onProfilesChanged: (List<ReceiverProfile>) -> Unit,
    activeReceiverId: String?,
    onSelectReceiver: (ReceiverProfile) -> Unit,
    ntripProfiles: List<NtripProfile>,
    onNtripProfilesChanged: (List<NtripProfile>) -> Unit,
    gnss: GnssStatus,
    connecting: Boolean,
    lastError: String?,
    onConnect: (ReceiverProfile) -> Unit,
    onDisconnect: () -> Unit
) {
    var tab by remember { mutableStateOf("Receptores") }
    var detailReceiver by remember { mutableStateOf<ReceiverProfile?>(null) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = if (tab == "Receptores") 0 else 1) {
            Tab(selected = tab == "Receptores", onClick = { tab = "Receptores" }, text = { Text("Receptores") })
            Tab(selected = tab == "NTRIP", onClick = { tab = "NTRIP" }, text = { Text("NTRIP / RTK") })
        }

        if (tab == "NTRIP") {
            NtripProfilesScreen(profiles = ntripProfiles, onProfilesChanged = onNtripProfilesChanged)
        } else if (detailReceiver != null) {
            ReceiverDetailScreen(
                receiver = detailReceiver!!,
                isSelected = detailReceiver!!.id == activeReceiverId,
                gnss = gnss,
                connecting = connecting,
                lastError = lastError,
                onBack = { detailReceiver = null },
                onSelect = { onSelectReceiver(detailReceiver!!) },
                onConnect = {
                    onSelectReceiver(detailReceiver!!)
                    onConnect(detailReceiver!!)
                },
                onDisconnect = onDisconnect,
                onForget = {
                    val forgotten = detailReceiver!!
                    onDisconnect()
                    onProfilesChanged(profiles.filterNot { it.address == forgotten.address })
                    if (activeReceiverId == forgotten.id) {
                        // Selection is cleared by returning to the receiver list; a new device can be selected.
                    }
                    detailReceiver = null
                }
            )
        } else {
            ReceiversScreen(
                profiles = profiles,
                onProfilesChanged = onProfilesChanged,
                activeReceiverId = activeReceiverId,
                onSelectReceiver = onSelectReceiver,
                onOpenReceiver = { detailReceiver = it }
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ReceiversScreen(
    profiles: List<ReceiverProfile>,
    onProfilesChanged: (List<ReceiverProfile>) -> Unit,
    activeReceiverId: String?,
    onSelectReceiver: (ReceiverProfile) -> Unit,
    onOpenReceiver: (ReceiverProfile) -> Unit
) {
    val context = LocalContext.current
    val bluetoothManager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val adapter = bluetoothManager.adapter
    val scanner = adapter?.bluetoothLeScanner
    val handler = remember { Handler(Looper.getMainLooper()) }

    var nearby by remember { mutableStateOf<List<NearbyReceiver>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var permissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val allPaired = remember(permissionGranted, adapter?.isEnabled, profiles) {
        if (!permissionGranted || adapter?.isEnabled != true) emptyList()
        else adapter.bondedDevices
            .map {
                val deviceName = runCatching { it.name }.getOrNull()?.takeIf { n -> n.isNotBlank() }
                    ?: "Dispositivo Bluetooth"
                ReceiverProfile(
                    id = profiles.firstOrNull { p -> p.address == it.address }?.id ?: UUID.randomUUID().toString(),
                    name = deviceName,
                    address = it.address,
                    transport = "Bluetooth Classic"
                )
            }
            .sortedWith(
                compareByDescending<ReceiverProfile> { isLikelyGnssReceiver(it.name) }
                    .thenBy { it.name.lowercase() }
            )
    }

    val pairedGnss = allPaired.filter { isLikelyGnssReceiver(it.name) }
    val pairedOther = allPaired.filterNot { isLikelyGnssReceiver(it.name) }

    val callback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = runCatching { device.name }.getOrNull() ?: result.scanRecord?.deviceName ?: return
                if (!isLikelyGnssReceiver(name)) return
                val candidate = NearbyReceiver(name, device.address, result.rssi)
                nearby = (nearby.filterNot { it.address == candidate.address } + candidate).sortedByDescending { it.rssi }
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
            }
        }
    }

    fun stopScan() {
        runCatching { scanner?.stopScan(callback) }
        scanning = false
    }

    fun startScan() {
        if (!permissionGranted || scanner == null || adapter?.isEnabled != true) return
        nearby = emptyList()
        scanning = true
        runCatching { scanner.startScan(callback) }.onFailure { scanning = false }
        handler.postDelayed({ stopScan() }, 6000)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted = result.values.all { it }
        if (permissionGranted) startScan()
    }

    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            stopScan()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Receptores", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            TextButton(
                onClick = {
                    if (!permissionGranted) {
                        permissionLauncher.launch(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        )
                    } else startScan()
                }
            ) { Text(if (scanning) "Buscando…" else "↻ Actualizar") }
        }

        Text("Disponible", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

        if (adapter?.isEnabled != true) {
            InfoCard("Active Bluetooth en la tablet para buscar y conectar receptores.")
        } else if (!permissionGranted) {
            InfoCard("Autorice Bluetooth para detectar receptores GNSS cercanos.")
        }

        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))

        pairedGnss.forEach { p ->
            val stored = profiles.firstOrNull { it.address == p.address } ?: p
            ReceiverCard(
                profile = stored,
                subtitle = "${receiverBrand(stored.name)} • emparejado • conexión NMEA",
                selected = stored.id == activeReceiverId,
                onClick = {
                    if (profiles.none { it.address == stored.address }) onProfilesChanged(profiles + stored)
                    onSelectReceiver(stored)
                    onOpenReceiver(stored)
                }
            )
        }

        nearby.filter { n -> pairedGnss.none { it.address == n.address } }.forEach { r ->
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.name, fontWeight = FontWeight.Bold)
                    Text("Detectado por BLE • señal ${r.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    Text("${receiverBrand(r.name)} detectado. Para recibir NMEA, empareje el receptor primero en Bluetooth de Android.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (pairedOther.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Otros dispositivos Bluetooth emparejados", fontWeight = FontWeight.Bold)
            Text(
                "Si su receptor aparece con un nombre genérico, selecciónelo aquí y Topo Emlid intentará usarlo como GNSS/NMEA.",
                style = MaterialTheme.typography.bodySmall
            )
            pairedOther.forEach { p ->
                val stored = profiles.firstOrNull { it.address == p.address } ?: p
                ReceiverCard(
                    profile = stored,
                    subtitle = "Bluetooth emparejado • probar como GNSS",
                    selected = stored.id == activeReceiverId,
                    onClick = {
                        if (profiles.none { it.address == stored.address }) onProfilesChanged(profiles + stored)
                        onSelectReceiver(stored)
                        onOpenReceiver(stored)
                    }
                )
            }
        }

        if (!scanning && allPaired.isEmpty() && nearby.isEmpty()) {
            InfoCard("No se han encontrado receptores ni dispositivos Bluetooth emparejados. Compruebe que la antena esté encendida, visible y emparejada en Android; luego pulse Actualizar.")
        }

        if (profiles.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("Guardados", fontWeight = FontWeight.Bold)
            profiles.forEach { p ->
                ReceiverCard(
                    profile = p,
                    subtitle = p.transport,
                    selected = p.id == activeReceiverId,
                    onClick = {
                        onSelectReceiver(p)
                        onOpenReceiver(p)
                    }
                )
            }
        }
    }
}

@Composable
private fun ReceiverDetailScreen(
    receiver: ReceiverProfile,
    isSelected: Boolean,
    gnss: GnssStatus,
    connecting: Boolean,
    lastError: String?,
    onBack: () -> Unit,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit
) {
    var page by remember(receiver.id) { mutableStateOf(ReceiverPage.HOME) }

    if (page != ReceiverPage.HOME) {
        ReceiverSubPage(
            page = page,
            receiver = receiver,
            gnss = gnss,
            onBack = { page = ReceiverPage.HOME }
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("← Receptores") }
        Text(receiver.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            when {
                connecting -> "Conectando…"
                gnss.connected && gnss.receiverName == receiver.name -> "Conectado"
                else -> "Desconectado"
            },
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(10.dp))

        if (gnss.connected && gnss.receiverName == receiver.name) {
            ReceiverMenuRow("Estado", gnss.solution) { page = ReceiverPage.STATUS }
            ReceiverMenuRow("Entrada de correcciones", "NTRIP / LoRa / apagado") { page = ReceiverPage.CORRECTIONS }
            ReceiverMenuRow("Salida de la base 1", "RTCM3") { page = ReceiverPage.BASE_OUTPUT }
            ReceiverMenuRow("Configuración de la base", "Coordenadas y altura") { page = ReceiverPage.BASE_CONFIG }
            ReceiverMenuRow("Registro", "RINEX / LLH / RTCM3") { page = ReceiverPage.LOGGING }
            ReceiverMenuRow("Wi‑Fi", "Redes y punto de acceso") { page = ReceiverPage.WIFI }
            ReceiverMenuRow("Configuración", "GNSS, Bluetooth y transmisiones") { page = ReceiverPage.SETTINGS }

            Spacer(Modifier.height(12.dp))
            Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Desconectar")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Text("Olvidar en Topo Emlid")
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Receptor seleccionado", fontWeight = FontWeight.Bold)
                    Text(receiver.address)
                    Text(receiver.transport)
                }
            }

            lastError?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(14.dp))
            if (!isSelected) {
                OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
                    Text("Seleccionar receptor")
                }
                Spacer(Modifier.height(8.dp))
            }

            Button(onClick = onConnect, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
                Text(if (connecting) "Conectando…" else "Conectar al software")
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Text("Olvidar en Topo Emlid")
            }
            Text(
                "Esto elimina el receptor guardado de la aplicación. Para quitar también el emparejamiento Bluetooth, hágalo desde Ajustes de Android.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private enum class ReceiverPage {
    HOME, STATUS, CORRECTIONS, BASE_OUTPUT, BASE_CONFIG, LOGGING, WIFI, SETTINGS
}

@Composable
private fun ReceiverMenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun ReceiverSubPage(
    page: ReceiverPage,
    receiver: ReceiverProfile,
    gnss: GnssStatus,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        TextButton(onClick = onBack) { Text("← ${pageTitle(page)}") }

        when (page) {
            ReceiverPage.STATUS -> {
                Text("Resumen del estado", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                StatusLine("Relación señal/ruido", gnss.signalNoiseAvgDbHz?.let { "%.1f dB-Hz".format(it) } ?: "—")
                StatusLine("Satélites a la vista", (gnss.satellitesInView ?: gnss.satellites)?.toString() ?: "—")
                StatusLine("PDOP", gnss.pdop?.let { "%.2f".format(it) } ?: "—")
                StatusLine("Solución", gnss.solution)
                StatusLine("Modo de posicionamiento", gnss.positioningMode ?: "—")

                Spacer(Modifier.height(12.dp))
                Text("Coordenadas y precisión", fontWeight = FontWeight.Bold)
                StatusLine("Latitud", gnss.latitude?.let { "%.8f".format(it) } ?: "—")
                StatusLine("Longitud", gnss.longitude?.let { "%.8f".format(it) } ?: "—")
                StatusLine("Altura elipsoidal", gnss.ellipsoidalHeightM?.let { "%.3f m".format(it) } ?: "—")
                StatusLine("Precisión horizontal", gnss.horizontalAccuracyM?.let { "%.3f m".format(it) } ?: "—")
                StatusLine("Precisión vertical", gnss.verticalAccuracyM?.let { "%.3f m".format(it) } ?: "—")
            }

            ReceiverPage.CORRECTIONS -> ReceiverAdminPlaceholder(
                title = "Entrada de correcciones",
                rows = listOf(
                    "NTRIP a través del dispositivo móvil",
                    "NTRIP a través de Reach",
                    "Radio LoRa",
                    "Apagado"
                )
            )

            ReceiverPage.BASE_OUTPUT -> ReceiverAdminPlaceholder(
                title = "Salida de la base 1",
                rows = listOf(
                    "Apagado",
                    "Radio LoRa",
                    "NTRIP",
                    "Serie RS‑232",
                    "Servidor TCP",
                    "Cliente TCP",
                    "NTRIP local",
                    "Bluetooth"
                )
            )

            ReceiverPage.BASE_CONFIG -> ReceiverAdminPlaceholder(
                title = "Configuración de la base",
                rows = listOf(
                    "Método de introducción de coordenadas",
                    "Altura de la antena",
                    "Tiempo medio",
                    "Marcador de base",
                    "Mensajes RTCM3"
                )
            )

            ReceiverPage.LOGGING -> ReceiverAdminPlaceholder(
                title = "Registro",
                rows = listOf(
                    "Almacenamiento",
                    "RINEX 3.03",
                    "Trayectoria de la posición (LLH)",
                    "Corrección de base (RTCM3)",
                    "Configuración y registros grabados"
                )
            )

            ReceiverPage.WIFI -> ReceiverAdminPlaceholder(
                title = "Wi‑Fi",
                rows = listOf(
                    "Modo de punto de acceso",
                    "Redes disponibles",
                    "Red conectada",
                    "Activar / desactivar Wi‑Fi"
                )
            )

            ReceiverPage.SETTINGS -> ReceiverAdminPlaceholder(
                title = "Configuración",
                rows = listOf(
                    "Salida de la base 2",
                    "Datos móviles",
                    "Bluetooth",
                    "Configuración de GNSS",
                    "Transmisión de posición 1",
                    "Transmisión de posición 2",
                    "Actualizaciones de firmware",
                    "Información del receptor",
                    "Solución de problemas",
                    "Sonidos",
                    "Modo nocturno"
                )
            )

            ReceiverPage.HOME -> Unit
        }

        Spacer(Modifier.height(18.dp))
        Text(
            if (page == ReceiverPage.STATUS)
                "Estos valores se actualizan desde el flujo NMEA real del receptor."
            else
                "Esta pantalla ya forma parte de TopoEmlid. La lectura y modificación real de estos ajustes requiere integrar el canal de administración del Reach; por ahora no se muestran valores inventados.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(value, fontWeight = FontWeight.Medium)
    }
    HorizontalDivider()
}

@Composable
private fun ReceiverAdminPlaceholder(title: String, rows: List<String>) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    rows.forEach { row ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(row)
                Text("›")
            }
        }
    }
}

private fun pageTitle(page: ReceiverPage): String = when (page) {
    ReceiverPage.STATUS -> "Estado"
    ReceiverPage.CORRECTIONS -> "Entrada de correcciones"
    ReceiverPage.BASE_OUTPUT -> "Salida de la base 1"
    ReceiverPage.BASE_CONFIG -> "Configuración de la base"
    ReceiverPage.LOGGING -> "Registro"
    ReceiverPage.WIFI -> "Wi‑Fi"
    ReceiverPage.SETTINGS -> "Configuración"
    ReceiverPage.HOME -> "Receptor"
}

@Composable
private fun ReceiverCard(
    profile: ReceiverProfile,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                Text(profile.address, style = MaterialTheme.typography.bodySmall)
            }
            if (selected) AssistChip(onClick = {}, label = { Text("SELECCIONADO") })
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(text, modifier = Modifier.padding(14.dp))
    }
}
