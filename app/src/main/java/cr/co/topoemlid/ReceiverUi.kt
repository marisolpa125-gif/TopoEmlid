package cr.co.topoemlid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
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
import java.util.UUID
import kotlinx.coroutines.launch

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

private fun rtkStatusColor(gnss: GnssStatus): Color = when {
    !gnss.connected -> Color(0xFF757575)
    gnss.solution.equals("FIX", ignoreCase = true) -> Color(0xFF2E7D32)
    gnss.solution.equals("FLOAT", ignoreCase = true) -> Color(0xFFF9A825)
    else -> Color(0xFFC62828)
}

private fun ntripStatusColor(status: NtripLiveStatus): Color =
    if (status.connected || status.connecting) Color(0xFF00ACC1) else Color(0xFF757575)

@SuppressLint("MissingPermission")
@Composable
fun ReceiverSection(
    profiles: List<ReceiverProfile>,
    onProfilesChanged: (List<ReceiverProfile>) -> Unit,
    activeReceiverId: String?,
    onSelectReceiver: (ReceiverProfile) -> Unit,
    ntripProfiles: List<NtripProfile>,
    onNtripProfilesChanged: (List<NtripProfile>) -> Unit,
    ntripStatus: NtripLiveStatus,
    onConnectNtrip: (NtripProfile) -> Unit,
    onDisconnectNtrip: () -> Unit,
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
            NtripProfilesScreen(
                profiles = ntripProfiles,
                onProfilesChanged = onNtripProfilesChanged,
                liveStatus = ntripStatus,
                onConnect = onConnectNtrip,
                onDisconnect = onDisconnectNtrip
            )
        } else if (detailReceiver != null) {
            ReceiverDetailScreen(
                receiver = detailReceiver!!,
                isSelected = detailReceiver!!.id == activeReceiverId,
                gnss = gnss,
                ntripStatus = ntripStatus,
                connecting = connecting,
                lastError = lastError,
                onBack = { detailReceiver = null },
                onSelect = { onSelectReceiver(detailReceiver!!) },
                onConnect = {
                    onSelectReceiver(detailReceiver!!)
                    onConnect(detailReceiver!!)
                },
                onDisconnect = onDisconnect,
                onOpenNtrip = { tab = "NTRIP" },
                onModeChanged = { mode ->
                    val current = detailReceiver!!
                    val updated = current.copy(
                        preferredMode = mode,
                        transport = when (mode) {
                            ReceiverConnectionMode.AUTO -> "Automático"
                            ReceiverConnectionMode.BLE -> "BLE"
                            ReceiverConnectionMode.BLUETOOTH_NMEA -> "Bluetooth / NMEA"
                            ReceiverConnectionMode.WIFI_AP -> "Wi‑Fi AP"
                            ReceiverConnectionMode.WIFI_LOCAL -> "Wi‑Fi Red local"
                        }
                    )
                    detailReceiver = updated
                    onProfilesChanged(
                        if (profiles.any { it.address == updated.address })
                            profiles.map { if (it.address == updated.address) updated else it }
                        else profiles + updated
                    )
                    onSelectReceiver(updated)
                    onDisconnect()
                },
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
                gnss = gnss,
                onSelectReceiver = onSelectReceiver,
                onOpenReceiver = { detailReceiver = it },
                onConnect = onConnect,
                onDisconnect = onDisconnect
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
    gnss: GnssStatus,
    onSelectReceiver: (ReceiverProfile) -> Unit,
    onOpenReceiver: (ReceiverProfile) -> Unit,
    onConnect: (ReceiverProfile) -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val bluetoothManager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val adapter = bluetoothManager.adapter
    val scanner = adapter?.bluetoothLeScanner
    val handler = remember { Handler(Looper.getMainLooper()) }

    var nearby by remember { mutableStateOf<List<NearbyReceiver>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var connectedActions by remember { mutableStateOf<ReceiverProfile?>(null) }
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
                    transport = profiles.firstOrNull { p -> p.address == it.address }?.transport ?: "Automático",
                    preferredMode = profiles.firstOrNull { p -> p.address == it.address }?.preferredMode
                        ?: ReceiverConnectionMode.AUTO
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
                val known = profiles.firstOrNull { it.address.equals(device.address, ignoreCase = true) }
                val name = runCatching { device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName
                    ?: known?.name
                    ?: return
                if (!isLikelyGnssReceiver(name) && known == null) return
                val candidate = NearbyReceiver(name, device.address, result.rssi)
                nearby = (nearby.filterNot { it.address == candidate.address } + candidate).sortedByDescending { it.rssi }
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
            }
        }
    }

    val classicReceiver = remember(profiles) {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return

                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return

                val known = profiles.firstOrNull { it.address.equals(device.address, ignoreCase = true) }
                val name = runCatching { device.name }.getOrNull()
                    ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    ?: known?.name
                    ?: return

                if (!isLikelyGnssReceiver(name) && known == null) return

                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    .toInt()
                    .let { if (it == Short.MIN_VALUE.toInt()) -100 else it }

                val candidate = NearbyReceiver(name, device.address, rssi)
                nearby = (nearby.filterNot {
                    it.address.equals(candidate.address, ignoreCase = true)
                } + candidate).sortedByDescending { it.rssi }
            }
        }
    }

    fun stopScan() {
        runCatching { scanner?.stopScan(callback) }
        runCatching {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        }
        scanning = false
    }

    fun startScan() {
        if (gnss.connected) return
        if (!permissionGranted || adapter?.isEnabled != true) return

        nearby = emptyList()
        scanning = true

        // Buscar por las dos vías. Reach puede aparecer como BLE para
        // administración o como Bluetooth Classic para NMEA/SPP.
        runCatching { scanner?.startScan(callback) }
        runCatching {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        }

        handler.postDelayed({ stopScan() }, 9000)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted = result.values.all { it }
        if (permissionGranted) startScan()
    }

    DisposableEffect(classicReceiver) {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(classicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(classicReceiver, filter)
        }

        onDispose {
            handler.removeCallbacksAndMessages(null)
            stopScan()
            runCatching { context.unregisterReceiver(classicReceiver) }
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
                enabled = !gnss.connected,
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
            ) {
                Text(
                    when {
                        gnss.connected -> "Conectado"
                        scanning -> "Buscando…"
                        else -> "↻ Actualizar"
                    }
                )
            }
        }

        Text("Receptores detectados cerca", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Aquí solo aparecen receptores detectados durante la búsqueda actual. Topo Emlid busca tanto por BLE como por Bluetooth Classic/NMEA para no perder el Reach.",
            style = MaterialTheme.typography.bodySmall
        )

        if (adapter?.isEnabled != true) {
            InfoCard("Active Bluetooth en la tablet para buscar y conectar receptores.")
        } else if (!permissionGranted) {
            InfoCard("Autorice Bluetooth para detectar receptores GNSS cercanos.")
        }

        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))

        if (gnss.connected) {
            val connectedProfile =
                profiles.firstOrNull { it.id == activeReceiverId } ?:
                profiles.firstOrNull { it.name == gnss.receiverName }

            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable(enabled = connectedProfile != null) {
                        connectedProfile?.let { connectedActions = it }
                    }
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(gnss.receiverName, fontWeight = FontWeight.Bold)
                    Text(
                        "Conectado por ${gnss.connectionTransport ?: "Bluetooth / NMEA"} • ${gnss.solution}",
                        style = MaterialTheme.typography.bodySmall,
                        color = rtkStatusColor(gnss),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Mientras este receptor esté conectado, Topo Emlid no inicia un nuevo escaneo Bluetooth.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Toque este recuadro para información del receptor y opciones de conexión.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        connectedActions?.let { receiver ->
            AlertDialog(
                onDismissRequest = { connectedActions = null },
                title = { Text(receiver.name) },
                text = {
                    Column {
                        Text("Conectado por " + (gnss.connectionTransport ?: "Bluetooth / NMEA"))
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                connectedActions = null
                                onSelectReceiver(receiver)
                                onOpenReceiver(receiver)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Información del receptor")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Desde Información del receptor puede ver estado GNSS, Wi‑Fi, batería y controles locales del Reach sin desconectar Bluetooth/NMEA.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            connectedActions = null
                            onDisconnect()
                        }
                    ) { Text("Desconectar") }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                connectedActions = null
                                onDisconnect()
                                handler.postDelayed({
                                    onSelectReceiver(receiver)
                                    onConnect(receiver)
                                }, 1200L)
                            }
                        ) { Text("Reiniciar conexión") }
                        TextButton(onClick = { connectedActions = null }) { Text("Cancelar") }
                    }
                }
            )
        }

        nearby.forEach { r ->
            val paired = pairedGnss.firstOrNull { it.address == r.address }
            val existing = profiles.firstOrNull { it.address == r.address }
            val candidate = existing ?: paired ?: ReceiverProfile(
                id = UUID.randomUUID().toString(),
                name = r.name,
                address = r.address,
                transport = "BLE",
                preferredMode = ReceiverConnectionMode.BLE
            )
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clickable {
                        stopScan()
                        if (existing == null && profiles.none { it.address == candidate.address }) {
                            onProfilesChanged(profiles + candidate)
                        }
                        onSelectReceiver(candidate)
                        onOpenReceiver(candidate)
                    }
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.name, fontWeight = FontWeight.Bold)
                    Text("Detectado ahora • señal ${r.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    Text("Toque para seleccionar y conectar.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!gnss.connected && !scanning && nearby.isEmpty()) {
            InfoCard("No se detectó ningún receptor GNSS cercano en la última búsqueda. Si la antena está apagada, este es el comportamiento esperado.")
        }

    }
}

@Composable
private fun ReceiverDetailScreen(
    receiver: ReceiverProfile,
    isSelected: Boolean,
    gnss: GnssStatus,
    ntripStatus: NtripLiveStatus,
    connecting: Boolean,
    lastError: String?,
    onBack: () -> Unit,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenNtrip: () -> Unit,
    onModeChanged: (ReceiverConnectionMode) -> Unit,
    onForget: () -> Unit
) {
    var page by remember(receiver.id) { mutableStateOf(ReceiverPage.HOME) }

    if (page != ReceiverPage.HOME) {
        ReceiverSubPage(
            page = page,
            receiver = receiver,
            gnss = gnss,
            ntripStatus = ntripStatus,
            onPauseReceiverForBle = onDisconnect,
            onResumeReceiverAfterBle = { onConnect() },
            onBack = { page = ReceiverPage.HOME }
        )
        return
    }

    val localApiMode =
        receiver.preferredMode == ReceiverConnectionMode.WIFI_AP ||
        receiver.preferredMode == ReceiverConnectionMode.WIFI_LOCAL

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

        Text("Método de conexión", fontWeight = FontWeight.Bold)
        ReceiverConnectionMode.entries.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !connecting) { onModeChanged(mode) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = receiver.preferredMode == mode,
                    onClick = { if (!connecting) onModeChanged(mode) }
                )
                Column {
                    Text(mode.label)
                    Text(
                        when (mode) {
                            ReceiverConnectionMode.AUTO -> "Prioriza Bluetooth/NMEA para mantener telemetría GNSS real; BLE queda como alternativa."
                            ReceiverConnectionMode.BLE -> "Bluetooth Low Energy. No requiere salida NMEA para establecer el enlace."
                            ReceiverConnectionMode.BLUETOOTH_NMEA -> "Bluetooth Classic con datos NMEA. Requiere NMEA activado en el receptor."
                            ReceiverConnectionMode.WIFI_AP -> "La tablet se conecta directamente a la red Wi‑Fi creada por el receptor (modo punto de acceso)."
                            ReceiverConnectionMode.WIFI_LOCAL -> "La tablet y el receptor están en la misma red Wi‑Fi. Este modo queda preparado para usar la API local del receptor."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if ((gnss.connected && gnss.receiverName == receiver.name) || localApiMode) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Panel del receptor", fontWeight = FontWeight.Bold)
                            Text(
                                gnss.connectionTransport ?: receiver.preferredMode.label,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        AssistChip(
                            onClick = {},
                            label = { Text(gnss.solution) }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (gnss.connected && gnss.receiverName == receiver.name)
                            "Receptor conectado y disponible para trabajo de campo."
                        else
                            "Modo Wi‑Fi seleccionado. La administración local funciona de forma independiente de Bluetooth/NMEA.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Estado y posicionamiento", fontWeight = FontWeight.Bold)
            ReceiverMenuRow(
                "Estado GNSS",
                gnss.solution,
                subtitleColor = rtkStatusColor(gnss)
            ) { page = ReceiverPage.STATUS }

            Spacer(Modifier.height(10.dp))
            Text("Correcciones RTK", fontWeight = FontWeight.Bold)
            ReceiverMenuRow("NTRIP / RTK", "Perfiles, caster y mountpoint") { onOpenNtrip() }

            Spacer(Modifier.height(10.dp))
            Text("Receptor", fontWeight = FontWeight.Bold)
            ReceiverMenuRow(
                "Wi‑Fi del receptor",
                "Solo estado local; la selección de red está en Configuración"
            ) { page = ReceiverPage.WIFI }
            ReceiverMenuRow(
                "Entradas y salidas",
                "LoRa, NTRIP, Bluetooth, TCP, RS-232, RTCM3"
            ) { page = ReceiverPage.ADVANCED }
            ReceiverMenuRow(
                "Información del receptor",
                "Modelo, conexión y funciones del equipo"
            ) { page = ReceiverPage.INFO }

            Spacer(Modifier.height(12.dp))
            if (gnss.connected && gnss.receiverName == receiver.name) {
                Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Desconectar")
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Text("Olvidar en Topo Emlid")
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Receptor seleccionado", fontWeight = FontWeight.Bold)
                    Text(receiver.address)
                    Text("Método: ${receiver.preferredMode.label}")
                    Text(receiver.transport, style = MaterialTheme.typography.bodySmall)
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
    HOME, STATUS, WIFI, INFO, ADVANCED
}

@Composable
private fun ReceiverMenuRow(
    title: String,
    subtitle: String,
    subtitleColor: Color = Color.Unspecified,
    onClick: () -> Unit
) {
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
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    fontWeight = if (subtitleColor != Color.Unspecified) FontWeight.Bold else FontWeight.Normal
                )
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
    ntripStatus: NtripLiveStatus,
    onPauseReceiverForBle: () -> Unit,
    onResumeReceiverAfterBle: () -> Unit,
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
                StatusLine("Conexión", gnss.connectionTransport ?: if (gnss.connected) "Conectado" else "Desconectado")
                if (gnss.connectionTransport == "BLE") {
                    StatusLine("Servicios BLE", gnss.bleServicesDiscovered?.toString() ?: "Detectando…")
                    Text(
                        "BLE confirma el enlace con el receptor. Los datos GNSS por BLE dependen del protocolo del fabricante; Topo Emlid no mostrará datos que no haya recibido.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    StatusLine("Flujo NMEA", if (gnss.nmeaReceiving) "RECIBIENDO" else "ESPERANDO")
                }
                StatusLine("Relación señal/ruido", gnss.signalNoiseAvgDbHz?.let { "%.1f dB-Hz".format(it) } ?: "—")
                StatusLine("Satélites a la vista", (gnss.satellitesInView ?: gnss.satellites)?.toString() ?: "—")
                StatusLine("Satélites usados", gnss.satellites?.toString() ?: "—")
                StatusLine("PDOP", gnss.pdop?.let { "%.2f".format(it) } ?: "—")
                StatusLine("Solución", gnss.solution, valueColor = rtkStatusColor(gnss))
                StatusLine("Modo de posicionamiento", gnss.positioningMode ?: "—")
                StatusLine("Edad de corrección", gnss.correctionAgeS?.let { "%.1f s".format(it) } ?: "—")

                Spacer(Modifier.height(14.dp))
                Text("Correcciones NTRIP / RTCM", fontWeight = FontWeight.Bold)
                StatusLine(
                    "NTRIP",
                    when {
                        ntripStatus.connecting -> "CONECTANDO"
                        ntripStatus.connected -> "CONECTADO"
                        else -> "DESCONECTADO"
                    },
                    valueColor = ntripStatusColor(ntripStatus)
                )
                StatusLine("Perfil", ntripStatus.profileName ?: "—")
                StatusLine("Caster", ntripStatus.caster ?: "—")
                StatusLine("Mountpoint", ntripStatus.mountPoint ?: "—")
                StatusLine("RTCM recibido", if (ntripStatus.bytesReceived > 0L) "${ntripStatus.bytesReceived} bytes" else "—")
                StatusLine("RTCM enviado al receptor", if (ntripStatus.bytesForwarded > 0L) "${ntripStatus.bytesForwarded} bytes" else "—")
                ntripStatus.lastError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(14.dp))
                Text("Señal de satélites", fontWeight = FontWeight.Bold)
                Text(
                    "Altura de barra = SNR/CN0. Rojo: débil • amarillo: media • verde: buena.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))

                if (gnss.satelliteSignals.isEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "Aún no se han recibido datos GSV del receptor.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .height(190.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        gnss.satelliteSignals.forEach { sat ->
                            val snr = sat.snrDbHz ?: 0.0
                            val strengthColor = when {
                                snr < 21.0 -> Color(0xFFD32F2F)
                                snr < 36.0 -> Color(0xFFF9A825)
                                else -> Color(0xFF388E3C)
                            }
                            val barHeight = ((snr / 60.0).coerceIn(0.05, 1.0).toFloat() * 135f).dp

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier.width(36.dp)
                            ) {
                                Text(
                                    sat.snrDbHz?.let { "%.0f".format(it) } ?: "—",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Spacer(Modifier.height(3.dp))
                                Box(
                                    Modifier
                                        .width(24.dp)
                                        .height(barHeight)
                                        .background(strengthColor)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    sat.id,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (sat.usedInFix) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    if (sat.usedInFix) "●" else "○",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    val good = gnss.satelliteSignals.count { (it.snrDbHz ?: 0.0) >= 36.0 }
                    val medium = gnss.satelliteSignals.count { (it.snrDbHz ?: 0.0) in 21.0..<36.0 }
                    val weak = gnss.satelliteSignals.count { (it.snrDbHz ?: 0.0) < 21.0 }
                    Text(
                        "Buena: $good • Media: $medium • Débil: $weak • ● usado en solución • ○ visible",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("Coordenadas y precisión", fontWeight = FontWeight.Bold)
                StatusLine("Latitud", gnss.latitude?.let { "%.8f".format(it) } ?: "—")
                StatusLine("Longitud", gnss.longitude?.let { "%.8f".format(it) } ?: "—")
                StatusLine("Altura elipsoidal", gnss.ellipsoidalHeightM?.let { "%.3f m".format(it) } ?: "—")
                StatusLine("Precisión horizontal", gnss.horizontalAccuracyM?.let { "%.3f m".format(it) } ?: "—")
                StatusLine("Precisión vertical", gnss.verticalAccuracyM?.let { "%.3f m".format(it) } ?: "—")

                Spacer(Modifier.height(12.dp))
                Text("Diagnóstico NMEA", fontWeight = FontWeight.Bold)
                Text(
                    gnss.lastNmeaSentence ?: "Todavía no se ha recibido ninguna trama NMEA.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            ReceiverPage.WIFI -> ReceiverWifiLocalPanel(receiver)
            ReceiverPage.INFO -> ReceiverInfoLocalPanel(receiver, gnss)
            ReceiverPage.ADVANCED -> ReceiverAdvancedIoPanel(
                receiver = receiver,
                onPauseReceiverForBle = onPauseReceiverForBle,
                onResumeReceiverAfterBle = onResumeReceiverAfterBle
            )

            ReceiverPage.HOME -> Unit
        }

        Spacer(Modifier.height(18.dp))
        Text(
            if (page == ReceiverPage.STATUS)
                if (gnss.connectionTransport == "BLE")
                    "La conexión BLE es real. Los campos GNSS solo se completarán cuando Topo Emlid pueda leer telemetría compatible del receptor; no se mostrarán valores simulados."
                else
                    "Estos valores se actualizan únicamente con datos reales recibidos del receptor por NMEA. Si aparece ESPERANDO, Topo Emlid tiene Bluetooth pero todavía no está recibiendo tramas de posición."
            else
                "Estas pantallas organizan las funciones del receptor sin cambiar la conexión actual. Los controles pendientes solo se habilitarán cuando exista comunicación real con el equipo.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(
            value,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
    HorizontalDivider()
}

@Composable
private fun ReceiverAdminPlaceholder(title: String, rows: List<String>) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        "Esta sección todavía no controla la configuración interna del receptor. Se muestra como referencia y quedará habilitada cuando exista comunicación real con la administración del equipo.",
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(12.dp))
    rows.forEach { row ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(row)
                Text("Pendiente", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ReceiverWifiLocalPanel(receiver: ReceiverProfile) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val localPrefs = remember { context.getSharedPreferences("reach_local_hosts", Context.MODE_PRIVATE) }
    val hostKey = remember(receiver.id) { "host_${receiver.id}" }
    var host by remember(receiver.id) {
        mutableStateOf(localPrefs.getString(hostKey, null) ?: "192.168.42.1")
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var wifi by remember { mutableStateOf<ReachWifiStatus?>(null) }

    Text("Wi‑Fi del receptor", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        "Aquí se muestra únicamente el estado de la red local del Reach. Para buscar y seleccionar una red Wi‑Fi, use Configuración → SIM / Datos móviles de la tablet.",
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(10.dp))

    OutlinedTextField(
        value = host,
        onValueChange = {
            host = it.trim()
            localPrefs.edit().putString(hostKey, host).apply()
        },
        label = { Text("IP local del Reach") },
        placeholder = { Text("192.168.42.1") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        enabled = host.isNotBlank() && !loading,
        onClick = {
            loading = true
            error = null
            scope.launch {
                runCatching { ReachLocalApiClient(host).wifiStatus() }
                    .onSuccess { wifi = it }
                    .onFailure { error = it.message ?: "No se pudo leer Wi‑Fi del receptor." }
                loading = false
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (loading) "Leyendo…" else "Leer estado Wi‑Fi")
    }

    wifi?.let {
        Spacer(Modifier.height(12.dp))
        StatusLine("IP", it.ip ?: "—")
        StatusLine("SSID", it.ssid ?: "—")
        StatusLine("Wi‑Fi", when (it.enabled) { true -> "ACTIVO"; false -> "INACTIVO"; null -> "—" })
        StatusLine("Modo", it.mode ?: "—")
    }

    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "Bluetooth/NMEA y NTRIP permanecen separados de esta lectura.",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun ReceiverInfoLocalPanel(receiver: ReceiverProfile, gnss: GnssStatus) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val localPrefs = remember { context.getSharedPreferences("reach_local_hosts", Context.MODE_PRIVATE) }
    val hostKey = remember(receiver.id) { "host_${receiver.id}" }
    var host by remember(receiver.id) {
        mutableStateOf(
            localPrefs.getString(hostKey, null)
                ?: if (receiver.preferredMode == ReceiverConnectionMode.WIFI_AP) "192.168.42.1" else ""
        )
    }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var battery by remember { mutableStateOf<ReachBatteryStatus?>(null) }
    var device by remember { mutableStateOf<ReachDeviceStatus?>(null) }
    var wifi by remember { mutableStateOf<ReachWifiStatus?>(null) }
    var confirmAction by remember { mutableStateOf<ReachLocalAction?>(null) }

    fun runAction(action: ReachLocalAction) {
        if (host.isBlank()) return
        loading = true
        error = null
        message = null
        scope.launch {
            val result = ReachLocalApiClient(host).sendAction(action)
            result
                .onSuccess {
                    message = when (action) {
                        ReachLocalAction.FIND_REACH -> "Orden enviada: el receptor debe parpadear sus luces."
                        ReachLocalAction.REBOOT -> "Orden de reinicio enviada."
                        ReachLocalAction.SHUTDOWN -> "Orden de apagado enviada."
                        ReachLocalAction.RESET_RTK -> "Orden de reinicio RTK enviada."
                    }
                }
                .onFailure { error = it.message ?: "No se pudo enviar la orden al Reach." }
            loading = false
        }
    }

    Text("Información del receptor", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))

    StatusLine("Nombre guardado", receiver.name)
    StatusLine("Método seleccionado", receiver.preferredMode.label)
    StatusLine("Transporte GNSS", gnss.connectionTransport ?: receiver.transport)

    OutlinedTextField(
        value = host,
        onValueChange = {
            host = it.trim()
            localPrefs.edit().putString(hostKey, host).apply()
        },
        label = { Text("IP o nombre local del Reach") },
        placeholder = { Text("192.168.42.1 o ReachRover.local") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(8.dp))
    Button(
        enabled = host.isNotBlank() && !loading,
        onClick = {
            loading = true
            error = null
            message = null
            scope.launch {
                val client = ReachLocalApiClient(host)
                val result = runCatching {
                    Triple(client.battery(), client.device(), client.wifiStatus())
                }
                result
                    .onSuccess {
                        battery = it.first
                        device = it.second
                        wifi = it.third
                        message = "API local conectada correctamente."
                    }
                    .onFailure { error = it.message ?: "No se pudo conectar con la API local del Reach." }
                loading = false
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (loading) "Comunicando…" else "Leer información local")
    }

    device?.let {
        Spacer(Modifier.height(12.dp))
        Text("Dispositivo", fontWeight = FontWeight.Bold)
        StatusLine("Nombre Reach", it.name ?: "—")
    }

    battery?.let {
        Spacer(Modifier.height(10.dp))
        Text("Batería", fontWeight = FontWeight.Bold)
        StatusLine("Carga", it.stateOfCharge?.let { p -> "$p %" } ?: "—")
        StatusLine("Voltaje", it.voltageV?.let { v -> "%.2f V".format(v) } ?: "—")
        StatusLine("Corriente", it.currentA?.let { a -> "%.2f A".format(a) } ?: "—")
        StatusLine("Temperatura", it.temperatureC?.let { t -> "%.1f °C".format(t) } ?: "—")
        StatusLine("Estado", it.chargerStatus ?: "—")
    }

    wifi?.let {
        Spacer(Modifier.height(10.dp))
        Text("Red local", fontWeight = FontWeight.Bold)
        StatusLine("IP", it.ip ?: "—")
        StatusLine("SSID", it.ssid ?: "—")
        StatusLine("Modo", it.mode ?: "—")
    }

    Spacer(Modifier.height(14.dp))
    Text("Control local del Reach", fontWeight = FontWeight.Bold)
    Text(
        "Comandos confirmados en Reach Panel por Socket.IO. No modifican la lógica Bluetooth/NMEA ni NTRIP de Topo Emlid.",
        style = MaterialTheme.typography.bodySmall
    )

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { runAction(ReachLocalAction.FIND_REACH) },
        enabled = host.isNotBlank() && !loading,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Parpadear luces / Identificar") }

    OutlinedButton(
        onClick = { confirmAction = ReachLocalAction.RESET_RTK },
        enabled = host.isNotBlank() && !loading,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Reiniciar RTK") }

    OutlinedButton(
        onClick = { confirmAction = ReachLocalAction.REBOOT },
        enabled = host.isNotBlank() && !loading,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Reiniciar receptor") }

    Button(
        onClick = { confirmAction = ReachLocalAction.SHUTDOWN },
        enabled = host.isNotBlank() && !loading,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
    ) { Text("Apagar receptor") }

    message?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = {
                Text(
                    when (action) {
                        ReachLocalAction.REBOOT -> "¿Reiniciar receptor?"
                        ReachLocalAction.SHUTDOWN -> "¿Apagar receptor?"
                        ReachLocalAction.RESET_RTK -> "¿Reiniciar RTK?"
                        ReachLocalAction.FIND_REACH -> "¿Identificar receptor?"
                    }
                )
            },
            text = {
                Text(
                    when (action) {
                        ReachLocalAction.REBOOT -> "El Reach perderá conexión unos instantes y volverá a iniciar."
                        ReachLocalAction.SHUTDOWN -> "El Reach se apagará por completo y deberá encenderlo físicamente."
                        ReachLocalAction.RESET_RTK -> "Se reiniciará el motor/estado RTK del receptor."
                        ReachLocalAction.FIND_REACH -> "Las luces del Reach parpadearán."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAction = null
                        runAction(action)
                    }
                ) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun ReceiverAdvancedIoPanel(
    receiver: ReceiverProfile,
    onPauseReceiverForBle: () -> Unit,
    onResumeReceiverAfterBle: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(receiver.address, receiver.name) {
        ReachBleAdminClient(context, receiver.address, receiver.name)
    }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf<ReachLoraConfig?>(null) }
    var frequencyMhz by remember { mutableStateOf("902.0") }
    var airRate by remember { mutableStateOf(9.11) }

    val airRates = listOf(0.81, 1.46, 2.60, 4.56, 9.11, 18.23)
    val frequencyPresets = listOf(902.0, 905.0, 910.0, 915.0, 920.0, 925.0, 928.0)

    suspend fun <T> withBle(label: String, block: suspend (ReachBleAdminClient) -> T): T {
        message = "Pausando Bluetooth/NMEA para $label…"
        onPauseReceiverForBle()
        kotlinx.coroutines.delay(1_800L)
        return try {
            client.ensureConnected().getOrThrow()
            message = "BLE conectado temporalmente para $label."
            block(client)
        } finally {
            client.close()
            kotlinx.coroutines.delay(700L)
            message = "Restaurando Bluetooth/NMEA…"
            onResumeReceiverAfterBle()
            kotlinx.coroutines.delay(900L)
            message = "Bluetooth/NMEA restaurado."
        }
    }

    fun loadLora() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                withBle("leer LoRa") { it.readLoraConfiguration().getOrThrow() }
            }.onSuccess { cfg ->
                current = cfg
                frequencyMhz = "%.1f".format(cfg.frequencyHz / 1_000_000.0)
                airRate = cfg.airRateKbps
                message = "Configuración LoRa leída del Reach."
            }.onFailure {
                error = it.message ?: "No se pudo leer la configuración LoRa."
            }
            busy = false
        }
    }

    fun saveChannel(channel: String, label: String) {
        if (busy) return
        val mhz = frequencyMhz.replace(',', '.').toDoubleOrNull()
        if (mhz == null || mhz !in 902.0..928.0) {
            error = "La frecuencia debe estar entre 902.0 y 928.0 MHz."
            return
        }
        val hz = (mhz * 1_000_000.0).toInt()
        busy = true
        error = null
        scope.launch {
            runCatching {
                withBle("configurar $label") {
                    it.setLoraCorrectionChannel(channel, airRate, hz).getOrThrow()
                }
            }.onSuccess {
                message = "$label configurada en LoRa: ${"%.1f".format(mhz)} MHz · ${"%.2f".format(airRate)} kb/s."
            }.onFailure {
                error = it.message ?: "No se pudo guardar $label por LoRa."
            }
            busy = false
        }
    }

    DisposableEffect(client) {
        onDispose { client.close() }
    }

    Text("Entradas y salidas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        "Configuración real del radio LoRa del Reach. La app pausa Bluetooth/NMEA unos segundos, aplica el ajuste por BLE y vuelve a conectar NMEA automáticamente.",
        style = MaterialTheme.typography.bodySmall
    )

    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = { loadLora() },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (busy) "Comunicando…" else "Leer configuración LoRa del Reach")
    }

    current?.let { cfg ->
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Estado actual", fontWeight = FontWeight.Bold)
                StatusLine("Frecuencia", "%.1f MHz".format(cfg.frequencyHz / 1_000_000.0))
                StatusLine("Velocidad", "%.2f kb/s".format(cfg.airRateKbps))
                StatusLine("Entrada de correcciones", if (cfg.inputIsLora) "LoRa" else "Otra / desactivada")
                StatusLine("Salida 1", if (cfg.output1IsLora) "LoRa" else "Otra / desactivada")
                StatusLine("Salida 2", if (cfg.output2IsLora) "LoRa" else "Otra / desactivada")
                StatusLine(
                    "Enlace LoRa",
                    when (cfg.connected) {
                        true -> "CONECTADO"
                        false -> "SIN ENLACE"
                        null -> "NO REPORTADO"
                    }
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Text("Frecuencia LoRa", fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = frequencyMhz,
        onValueChange = { value ->
            frequencyMhz = value.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
        },
        label = { Text("MHz") },
        supportingText = { Text("Rango del RS2+: 902.0–928.0 MHz") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        frequencyPresets.forEach { f ->
            AssistChip(
                onClick = { frequencyMhz = "%.1f".format(f) },
                label = { Text("%.1f".format(f)) }
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Velocidad de transmisión", fontWeight = FontWeight.Bold)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        airRates.forEach { rate ->
            FilterChip(
                selected = airRate == rate,
                onClick = { airRate = rate },
                label = { Text("${"%.2f".format(rate)} kb/s") }
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Text("Aplicar a", fontWeight = FontWeight.Bold)
    Button(
        onClick = { saveChannel("input", "Entrada de correcciones") },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Entrada de correcciones → LoRa (rover)") }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { saveChannel("output1", "Salida de base 1") },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Salida de base 1 → LoRa") }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { saveChannel("output2", "Salida de base 2") },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Salida de base 2 → LoRa") }

    message?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.height(12.dp))
    Text("Mensajes RTCM3", fontWeight = FontWeight.Bold)
    Text(
        "La selección detallada de mensajes y frecuencias RTCM3 se mantiene separada. Este bloque configura primero el canal LoRa de entrada o salida.",
        style = MaterialTheme.typography.bodySmall
    )
}

private fun pageTitle(page: ReceiverPage): String = when (page) {
    ReceiverPage.STATUS -> "Estado GNSS"
    ReceiverPage.WIFI -> "Wi‑Fi"
    ReceiverPage.INFO -> "Información"
    ReceiverPage.ADVANCED -> "Configuración avanzada"
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
