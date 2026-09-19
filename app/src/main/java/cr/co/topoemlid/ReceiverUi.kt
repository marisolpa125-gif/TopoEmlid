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
                onOpenNtrip = { tab = "NTRIP" },
                onModeChanged = { mode ->
                    val current = detailReceiver!!
                    val updated = current.copy(
                        preferredMode = mode,
                        transport = when (mode) {
                            ReceiverConnectionMode.AUTO -> "Automático"
                            ReceiverConnectionMode.BLE -> "BLE"
                            ReceiverConnectionMode.BLUETOOTH_NMEA -> "Bluetooth / NMEA"
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
    gnss: GnssStatus,
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

        Text("Dispositivos emparejados / detectados", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Aparecer aquí no significa que el receptor esté conectado. Topo Emlid muestra los equipos que Android ya tiene emparejados y los que detecta cerca.",
            style = MaterialTheme.typography.bodySmall
        )

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
                subtitle = when {
                    stored.id == activeReceiverId && gnss.connected && gnss.receiverName == stored.name && gnss.connectionTransport == "BLE" ->
                        "${receiverBrand(stored.name)} • BLE conectado"
                    stored.id == activeReceiverId && gnss.connected && gnss.receiverName == stored.name && gnss.nmeaReceiving ->
                        "${receiverBrand(stored.name)} • Bluetooth/NMEA • datos recibiendo"
                    stored.id == activeReceiverId && gnss.connected && gnss.receiverName == stored.name ->
                        "${receiverBrand(stored.name)} • ${gnss.connectionTransport ?: "Bluetooth"} conectado"
                    else ->
                        "${receiverBrand(stored.name)} • ${stored.preferredMode.label} • no conectado"
                },
                selected = stored.id == activeReceiverId,
                onClick = {
                    if (profiles.none { it.address == stored.address }) onProfilesChanged(profiles + stored)
                    onSelectReceiver(stored)
                    onOpenReceiver(stored)
                }
            )
        }

        nearby.filter { n -> pairedGnss.none { it.address == n.address } }.forEach { r ->
            val existing = profiles.firstOrNull { it.address == r.address }
            val candidate = existing ?: ReceiverProfile(
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
                        if (existing == null) onProfilesChanged(profiles + candidate)
                        onSelectReceiver(candidate)
                        onOpenReceiver(candidate)
                    }
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.name, fontWeight = FontWeight.Bold)
                    Text("Detectado por BLE • señal ${r.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    Text("Toque para seleccionar y probar conexión BLE.", style = MaterialTheme.typography.bodySmall)
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
                            ReceiverConnectionMode.AUTO -> "Emlid/Reach: intenta BLE primero; si BLE falla, prueba Bluetooth/NMEA."
                            ReceiverConnectionMode.BLE -> "Bluetooth Low Energy. No requiere salida NMEA para establecer el enlace."
                            ReceiverConnectionMode.BLUETOOTH_NMEA -> "Bluetooth Classic con datos NMEA. Requiere NMEA activado en el receptor."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (gnss.connected && gnss.receiverName == receiver.name) {
            Text("Datos en vivo", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            ReceiverMenuRow("Estado GNSS", gnss.solution) { page = ReceiverPage.STATUS }

            Spacer(Modifier.height(10.dp))
            Text("Correcciones RTK", fontWeight = FontWeight.Bold)
            ReceiverMenuRow("NTRIP / RTK", "Perfiles, caster y mountpoint") { onOpenNtrip() }

            Spacer(Modifier.height(10.dp))
            Text("Configuración avanzada del receptor", fontWeight = FontWeight.Bold)
            ReceiverMenuRow("Entradas y salidas", "LoRa, NTRIP, Bluetooth, TCP, RS-232, RTCM3") { page = ReceiverPage.ADVANCED }

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
    HOME, STATUS, ADVANCED
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
                StatusLine("Solución", gnss.solution)
                StatusLine("Modo de posicionamiento", gnss.positioningMode ?: "—")
                StatusLine("Edad de corrección", gnss.correctionAgeS?.let { "%.1f s".format(it) } ?: "—")

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

            ReceiverPage.ADVANCED -> ReceiverAdvancedPlaceholder()

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
                "Las opciones avanzadas se muestran separadas y marcadas como pendientes hasta que exista control real del receptor.",
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
private fun ReceiverAdvancedPlaceholder() {
    Text("Configuración avanzada del receptor", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        "Estas opciones corresponden a la configuración interna del receptor. Topo Emlid las separa de la conexión y de NTRIP para no confundir funciones reales con controles todavía no implementados.",
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(12.dp))

    listOf(
        "Entrada de correcciones" to "NTRIP a través del receptor / LoRa / apagado",
        "Salida de base" to "LoRa / NTRIP / Bluetooth / TCP / RS-232",
        "Mensajes RTCM3" to "Selección de mensajes y frecuencia",
        "Configuración de base" to "Coordenadas, altura y promedio",
        "Registro" to "RINEX / LLH / RTCM3",
        "Wi-Fi del receptor" to "Redes, hotspot y estado",
        "Transmisión de posición" to "NMEA y otros formatos soportados"
    ).forEach { (title, subtitle) ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
                Text("Pendiente", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "Se habilitará cada opción únicamente cuando Topo Emlid pueda leer y escribir ese ajuste de forma real en el receptor.",
        style = MaterialTheme.typography.bodySmall
    )
}

private fun pageTitle(page: ReceiverPage): String = when (page) {
    ReceiverPage.STATUS -> "Estado GNSS"
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
