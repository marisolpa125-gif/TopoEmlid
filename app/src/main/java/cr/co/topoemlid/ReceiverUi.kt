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
                onDisconnect = onDisconnect
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

    val pairedReach = remember(permissionGranted, adapter?.isEnabled, profiles) {
        if (!permissionGranted || adapter?.isEnabled != true) emptyList()
        else adapter.bondedDevices
            .filter {
                val n = runCatching { it.name.orEmpty() }.getOrDefault("")
                n.contains("reach", ignoreCase = true) || n.contains("emlid", ignoreCase = true)
            }
            .map {
                ReceiverProfile(
                    id = profiles.firstOrNull { p -> p.address == it.address }?.id ?: UUID.randomUUID().toString(),
                    name = runCatching { it.name }.getOrNull() ?: "Reach",
                    address = it.address,
                    transport = "Bluetooth Classic"
                )
            }
    }

    val callback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = runCatching { device.name }.getOrNull() ?: result.scanRecord?.deviceName ?: return
                if (!name.contains("reach", true) && !name.contains("emlid", true)) return
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
            InfoCard("Autorice Bluetooth para detectar receptores Reach.")
        }

        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))

        pairedReach.forEach { p ->
            val stored = profiles.firstOrNull { it.address == p.address } ?: p
            ReceiverCard(
                profile = stored,
                subtitle = "Emparejado • listo para NMEA",
                selected = stored.id == activeReceiverId,
                onClick = {
                    if (profiles.none { it.address == stored.address }) onProfilesChanged(profiles + stored)
                    onSelectReceiver(stored)
                    onOpenReceiver(stored)
                }
            )
        }

        nearby.filter { n -> pairedReach.none { it.address == n.address } }.forEach { r ->
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.name, fontWeight = FontWeight.Bold)
                    Text("Detectado por BLE • señal ${r.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    Text("Para usar NMEA, empareje este Reach primero en Bluetooth de Android.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!scanning && pairedReach.isEmpty() && nearby.isEmpty()) {
            InfoCard("No se han encontrado receptores. Acérquese al Reach, compruebe que esté encendido y pulse Actualizar.")
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
    onDisconnect: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("← Receptores") }
        Text(receiver.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(receiver.address, style = MaterialTheme.typography.bodySmall)
        Text(receiver.transport, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Estado", fontWeight = FontWeight.Bold)
                Text(
                    when {
                        connecting -> "Conectando…"
                        gnss.connected && gnss.receiverName == receiver.name -> "CONECTADO"
                        else -> "DESCONECTADO"
                    }
                )
                Text("Solución: ${if (gnss.connected) gnss.solution else "—"}")
                Text("Satélites: ${gnss.satellites ?: "—"}")
                Text("Precisión H: ${gnss.horizontalAccuracyM?.let { "%.3f m".format(it) } ?: "—"}")
                Text("Precisión V: ${gnss.verticalAccuracyM?.let { "%.3f m".format(it) } ?: "—"}")
                Text("Latitud: ${gnss.latitude?.let { "%.8f".format(it) } ?: "—"}")
                Text("Longitud: ${gnss.longitude?.let { "%.8f".format(it) } ?: "—"}")
                Text("Altura elipsoidal: ${gnss.ellipsoidalHeightM?.let { "%.3f m".format(it) } ?: "—"}")
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

        if (gnss.connected && gnss.receiverName == receiver.name) {
            Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Desconectar") }
        } else {
            Button(onClick = onConnect, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
                Text(if (connecting) "Conectando…" else "Conectar al software")
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Para recibir coordenadas, configure en Emlid Flow la transmisión de posición por Bluetooth en formato NMEA y empareje el Reach en Bluetooth de Android.",
            style = MaterialTheme.typography.bodySmall
        )
    }
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
