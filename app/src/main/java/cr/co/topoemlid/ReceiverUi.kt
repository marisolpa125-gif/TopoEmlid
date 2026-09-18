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
fun ReceiversScreen(
    profiles: List<ReceiverProfile>,
    onProfilesChanged: (List<ReceiverProfile>) -> Unit,
    activeReceiverId: String?,
    onSelectReceiver: (ReceiverProfile) -> Unit
) {
    val context = LocalContext.current
    val bluetoothManager = remember {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    val adapter = bluetoothManager.adapter
    val scanner = adapter?.bluetoothLeScanner
    val handler = remember { Handler(Looper.getMainLooper()) }

    var nearby by remember { mutableStateOf<List<NearbyReceiver>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var permissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val callback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = runCatching { device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName
                    ?: return
                val isLikelyReach = name.contains("reach", ignoreCase = true) ||
                    name.contains("emlid", ignoreCase = true)
                if (!isLikelyReach) return

                val candidate = NearbyReceiver(
                    name = name,
                    address = device.address,
                    rssi = result.rssi
                )
                nearby = (nearby.filterNot { it.address == candidate.address } + candidate)
                    .sortedByDescending { it.rssi }
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
        runCatching { scanner.startScan(callback) }
            .onFailure { scanning = false }
        handler.postDelayed({ stopScan() }, 6000)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionGranted = result.values.all { it }
        if (permissionGranted) startScan()
    }

    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            stopScan()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Receptores", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            TextButton(
                onClick = {
                    if (!permissionGranted) {
                        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        } else {
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        permissionLauncher.launch(permissions)
                    } else {
                        startScan()
                    }
                }
            ) {
                Text(if (scanning) "Buscando…" else "↻ Actualizar")
            }
        }

        Text("Disponibles", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

        if (adapter?.isEnabled != true) {
            Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(
                    "Active Bluetooth en la tablet para buscar receptores.",
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else if (!permissionGranted) {
            Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(
                    "Autorice el acceso a Bluetooth para buscar receptores Reach cercanos.",
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else if (!scanning && nearby.isEmpty()) {
            Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No se han encontrado receptores", fontWeight = FontWeight.Bold)
                    Text(
                        "Acérquese al receptor, compruebe que esté encendido y pulse Actualizar.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))
        }

        nearby.forEach { r ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clickable {
                        val existing = profiles.firstOrNull { it.address == r.address }
                        val profile = existing ?: ReceiverProfile(
                            id = UUID.randomUUID().toString(),
                            name = r.name,
                            address = r.address,
                            transport = "Bluetooth BLE"
                        )
                        if (existing == null) onProfilesChanged(profiles + profile)
                        onSelectReceiver(profile)
                    }
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.name, fontWeight = FontWeight.Bold)
                    Text("Bluetooth • señal ${r.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    Text(r.address, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (profiles.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("Guardados", fontWeight = FontWeight.Bold)
            profiles.forEach { p ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clickable { onSelectReceiver(p) }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold)
                            Text(p.transport, style = MaterialTheme.typography.bodySmall)
                            Text(p.address, style = MaterialTheme.typography.bodySmall)
                        }
                        if (p.id == activeReceiverId) {
                            AssistChip(onClick = {}, label = { Text("SELECCIONADO") })
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "La búsqueda localiza receptores Reach cercanos. La conexión NMEA de trabajo y el flujo RTK se completarán en el módulo de comunicación del receptor.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
