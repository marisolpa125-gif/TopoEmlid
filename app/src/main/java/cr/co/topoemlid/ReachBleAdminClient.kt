package cr.co.topoemlid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater

data class ReachLoraConfig(
    val airRateKbps: Double,
    val frequencyHz: Int,
    val inputIsLora: Boolean,
    val output1IsLora: Boolean,
    val output2IsLora: Boolean,
    val connected: Boolean?
)

/**
 * Canal BLE de administración del Reach.
 *
 * Es independiente del socket Bluetooth Classic/NMEA que usa ReceiverConnectionManager.
 * Se usa solo para las órdenes cortas de administración de Wi‑Fi observadas en Emlid Flow.
 */
class ReachBleAdminClient(
    context: Context,
    private val address: String,
    private val receiverName: String? = null
) {
    companion object {
        private val SERVICE_UUID = UUID.fromString("ab0ba111-b9d7-4d3a-a624-21fb75fc0000")
        private val EVENT_UUID = UUID.fromString("ab0ba111-b9d7-4d3a-a624-21fb75fc0001")
        private val API_UUID = UUID.fromString("ab0ba111-b9d7-4d3a-a624-21fb75fc0002")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val appContext = context.applicationContext
    private val adapter =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var eventCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var apiCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var negotiatedMtu: Int = 23

    private var connectWaiter: CompletableDeferred<Unit>? = null
    private var writeWaiter: CompletableDeferred<Boolean>? = null
    private var descriptorWaiter: CompletableDeferred<Boolean>? = null
    @Volatile private var apiNotificationsReady: Boolean = false
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    private val rxLock = Any()
    private var inFrame = false
    private var escaped = false
    private val rxFrame = ByteArrayOutputStream()

    val connected: Boolean
        get() = gatt != null && eventCharacteristic != null

    @SuppressLint("MissingPermission")
    suspend fun ensureConnected(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (connected) return@runCatching

            val scanned = scanForReachDevices()
            val classicFallback = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
            val candidates = buildList {
                addAll(scanned)
                if (classicFallback != null && none { it.address.equals(classicFallback.address, true) }) {
                    add(classicFallback)
                }
            }

            if (candidates.isEmpty()) {
                error("No se encontró ningún anuncio BLE compatible con el Reach.")
            }

            var lastError: Throwable? = null

            // Ahora que Bluetooth/NMEA ya se pausa antes de llegar aquí, probamos
            // primero conexión GATT directa (autoConnect=false). Si Android no la
            // completa, hacemos un segundo intento con autoConnect=true.
            for ((candidateIndex, device) in candidates.take(4).withIndex()) {
                val modes = listOf(false, true)
                for ((modeIndex, autoConnect) in modes.withIndex()) {
                    try {
                        closeGattOnly()

                        // Después de cerrar NMEA y/o de un intento GATT previo,
                        // dejar un margen para que el stack Bluetooth libere recursos.
                        delay(
                            when {
                                candidateIndex == 0 && modeIndex == 0 -> 1_800L
                                modeIndex == 1 -> 1_200L
                                else -> 900L
                            }
                        )

                        val waiter = CompletableDeferred<Unit>()
                        connectWaiter = waiter

                        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            device.connectGatt(
                                appContext,
                                autoConnect,
                                callback,
                                android.bluetooth.BluetoothDevice.TRANSPORT_LE
                            )
                        } else {
                            device.connectGatt(appContext, autoConnect, callback)
                        }

                        try {
                            withTimeout(if (autoConnect) 12_000L else 8_000L) {
                                waiter.await()
                            }
                        } finally {
                            if (connectWaiter === waiter) connectWaiter = null
                        }

                        return@runCatching
                    } catch (t: Throwable) {
                        lastError = t
                        closeGattOnly()
                    }
                }
            }

            val attempted = minOf(candidates.size, 4)
            throw IllegalStateException(
                (lastError?.message ?: "No se pudo abrir el canal BLE del Reach.") +
                    " Se probaron $attempted anuncio(s) BLE en modo directo y automático.",
                lastError
            )
        }
    }

    private data class BleCandidate(
        val device: android.bluetooth.BluetoothDevice,
        val score: Int
    )

    @SuppressLint("MissingPermission")
    private suspend fun scanForReachDevices(): List<android.bluetooth.BluetoothDevice> =
        suspendCancellableCoroutine { cont ->
            val scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                cont.resume(emptyList()) { _ -> }
                return@suspendCancellableCoroutine
            }

            val candidates = linkedMapOf<String, BleCandidate>()
            var finished = false
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            lateinit var callback: ScanCallback
            fun finish() {
                if (finished) return
                finished = true
                runCatching { scanner.stopScan(callback) }
                handler.removeCallbacksAndMessages(null)
                val ordered = candidates.values
                    .sortedByDescending { it.score }
                    .map { it.device }
                if (cont.isActive) cont.resume(ordered) { _ -> }
            }

            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    val record = result.scanRecord
                    val advertisedServices = record?.serviceUuids?.map { it.uuid }.orEmpty()
                    val name = runCatching { device.name }.getOrNull()
                        ?: record?.deviceName
                        ?: ""

                    val exactAddress = device.address.equals(address, ignoreCase = true)
                    val serviceMatch = advertisedServices.contains(SERVICE_UUID)
                    val genericNameMatch =
                        name.contains("Reach", ignoreCase = true) ||
                        name.contains("Emlid", ignoreCase = true)
                    val selectedNameMatch =
                        !receiverName.isNullOrBlank() &&
                        name.contains(receiverName, ignoreCase = true)

                    val score = when {
                        serviceMatch -> 100
                        exactAddress -> 80
                        selectedNameMatch -> 60
                        genericNameMatch -> 40
                        else -> 0
                    }

                    if (score > 0) {
                        val previous = candidates[device.address]
                        if (previous == null || score > previous.score) {
                            candidates[device.address] = BleCandidate(device, score)
                        }
                        // Un anuncio con el servicio exacto ya es suficiente.
                        if (score == 100) finish()
                    }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    finish()
                }
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            runCatching { scanner.startScan(null, settings, callback) }
                .onFailure {
                    finish()
                    return@suspendCancellableCoroutine
                }

            // Dar tiempo a ver todos los anuncios en lugar de tomar el primer
            // dispositivo cuyo nombre se parezca a Reach.
            handler.postDelayed({ finish() }, 6_000L)
            cont.invokeOnCancellation { finish() }
        }

        suspend fun scanWifiNetworks(): Result<List<ReachWifiNetwork>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureConnected().getOrThrow()

            // Dar un pequeño margen a las notificaciones GATT después de conectar.
            delay(350L)
            sendAction("wifi_scan").getOrThrow()
            delay(1_800L)

            var last: List<ReachWifiNetwork> = emptyList()
            var lastError: Throwable? = null

            repeat(4) { attempt ->
                val response = runCatching {
                    apiRequest("GET", "/wifi/networks", null)
                }.onFailure {
                    lastError = it
                }.getOrNull()

                if (response != null) {
                    last = parseNetworks(response)
                    if (last.isNotEmpty()) return@runCatching last
                }

                // Si una respuesta BLE se perdió, mantener la misma sesión GATT
                // y consultar de nuevo en lugar de cerrar todo inmediatamente.
                if (attempt < 3) delay(900L)
            }

            if (last.isEmpty() && lastError != null) {
                throw IllegalStateException(
                    "El Reach abrió BLE pero no respondió la lista Wi‑Fi después de varios intentos: " +
                        (lastError?.message ?: "sin detalle"),
                    lastError
                )
            }
            last
        }
    }

    suspend fun connectWifiNetwork(
        ssid: String,
        password: String,
        security: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(ssid.isNotBlank()) { "Seleccione una red Wi‑Fi." }
            ensureConnected().getOrThrow()

            val network = JSONObject()
                .put("security", security?.takeIf { it.isNotBlank() } ?: "wpa-psk")
                .put("ssid", ssid)
                .put("password", password)

            apiRequest(
                method = "POST",
                endpoint = "/wifi/networks/saved/",
                payload = JSONObject().put("network", network)
            )

            sendAction(
                "connect_to_wifi_network",
                JSONObject().put("ssid", ssid)
            ).getOrThrow()
        }
    }

    suspend fun startHotspotMode(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureConnected().getOrThrow()
            sendAction("start_hotspot_mode").getOrThrow()
        }
    }

    suspend fun readLoraConfiguration(): Result<ReachLoraConfig> = withContext(Dispatchers.IO) {
        runCatching {
            ensureConnected().getOrThrow()
            val config = apiRequest("GET", "/configuration/", null)
            val state = runCatching { apiRequest("GET", "/lora/state", null) }.getOrNull()

            fun findObjectByKey(root: Any?, key: String): JSONObject? {
                when (root) {
                    is JSONObject -> {
                        if (root.has(key) && root.opt(key) is JSONObject) {
                            return root.optJSONObject(key)
                        }
                        val it = root.keys()
                        while (it.hasNext()) {
                            val found = findObjectByKey(root.opt(it.next()), key)
                            if (found != null) return found
                        }
                    }
                    is JSONArray -> for (i in 0 until root.length()) {
                        val found = findObjectByKey(root.opt(i), key)
                        if (found != null) return found
                    }
                }
                return null
            }

            fun findIo(root: Any?, key: String): JSONObject? = findObjectByKey(root, key)

            val input = findIo(config, "base_corrections")
            val out1 = findIo(config, "output")
            val out2 = findIo(config, "output2")

            val lora = sequenceOf(input, out1, out2)
                .mapNotNull { it?.optJSONObject("settings")?.optJSONObject("lora") }
                .firstOrNull()
                ?: findObjectByKey(config, "lora")
                ?: error("El Reach no reportó configuración LoRa.")

            val air = when {
                lora.has("air_rate") -> lora.optDouble("air_rate", Double.NaN)
                lora.has("airRate") -> lora.optDouble("airRate", Double.NaN)
                else -> Double.NaN
            }
            val freq = when {
                lora.has("frequency") -> lora.optInt("frequency", 0)
                else -> 0
            }
            if (!air.isFinite() || freq <= 0) {
                error("El Reach respondió LoRa, pero faltan frecuencia o velocidad.")
            }

            fun isLora(obj: JSONObject?): Boolean {
                val t = obj?.optString("io_type", obj.optString("ioType", ""))?.lowercase()
                return t == "lora"
            }

            val connected = state?.let {
                fun findConnected(v: Any?): Boolean? {
                    when (v) {
                        is JSONObject -> {
                            if (v.has("connected")) return v.optBoolean("connected")
                            val ks = v.keys()
                            while (ks.hasNext()) {
                                val r = findConnected(v.opt(ks.next()))
                                if (r != null) return r
                            }
                        }
                        is JSONArray -> for (i in 0 until v.length()) {
                            val r = findConnected(v.opt(i))
                            if (r != null) return r
                        }
                    }
                    return null
                }
                findConnected(it)
            }

            ReachLoraConfig(
                airRateKbps = air,
                frequencyHz = freq,
                inputIsLora = isLora(input),
                output1IsLora = isLora(out1),
                output2IsLora = isLora(out2),
                connected = connected
            )
        }
    }

    suspend fun setLoraCorrectionChannel(
        channel: String,
        airRateKbps: Double,
        frequencyHz: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureConnected().getOrThrow()
            require(channel in setOf("input", "output1", "output2")) { "Canal LoRa no válido." }

            val lora = JSONObject()
                .put("air_rate", airRateKbps)
                .put("frequency", frequencyHz)
                .put("output_power", JSONObject.NULL)

            val settings = JSONObject().put("lora", lora)
            val payload = JSONObject()
                .put("io_type", "lora")
                .put("settings", settings)
                .put("nmea_settings", JSONObject.NULL)

            val endpoint = when (channel) {
                "input" -> "/configuration/correction_input/base_corrections"
                "output1" -> "/configuration/output"
                else -> "/configuration/output2"
            }

            apiRequest("POST", endpoint, payload)
            Unit
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        pendingRequests.values.forEach {
            if (!it.isCompleted) it.completeExceptionally(IllegalStateException("BLE cerrado"))
        }
        pendingRequests.clear()
        closeGattOnly()
    }

    private suspend fun sendAction(name: String, payload: JSONObject? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("event", "action")
                    .put(
                        "payload",
                        JSONObject()
                            .put("name", name)
                            .apply {
                                if (payload != null) put("payload", payload)
                            }
                    )

                writeMessage(
                    eventCharacteristic ?: error("Canal BLE de acciones no disponible."),
                    body.toString().toByteArray(Charsets.UTF_8)
                )
            }
        }

    private suspend fun apiRequest(
        method: String,
        endpoint: String,
        payload: JSONObject?
    ): JSONObject {
        ensureApiNotifications()
        val id = UUID.randomUUID().toString()
        val request = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("endpoint", endpoint)
            .put("headers", JSONArray())
            .put("payload", payload ?: JSONObject.NULL)

        val waiter = CompletableDeferred<JSONObject>()
        pendingRequests[id] = waiter
        try {
            writeMessage(
                apiCharacteristic ?: error("Canal BLE de API no disponible."),
                request.toString().toByteArray(Charsets.UTF_8)
            )
            return withTimeout(6_000L) { waiter.await() }
        } finally {
            pendingRequests.remove(id)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureApiNotifications() {
        if (apiNotificationsReady) return

        val currentGatt = gatt ?: error("Canal BLE desconectado.")
        val characteristic = apiCharacteristic
            ?: error("Canal BLE de API no disponible.")

        val enabledLocally = currentGatt.setCharacteristicNotification(characteristic, true)
        if (!enabledLocally) error("Android no pudo habilitar notificaciones BLE del Reach.")

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: error("El Reach no expuso el descriptor de notificaciones BLE.")

        val indication =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value =
            if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        val waiter = CompletableDeferred<Boolean>()
        descriptorWaiter = waiter
        val started = if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeDescriptor(descriptor, value) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                currentGatt.writeDescriptor(descriptor)
            }
        }

        if (!started) {
            descriptorWaiter = null
            error("Android rechazó la activación de notificaciones BLE.")
        }

        val ok = try {
            withTimeout(3_000L) { waiter.await() }
        } finally {
            if (descriptorWaiter === waiter) descriptorWaiter = null
        }

        if (!ok) error("El Reach rechazó las notificaciones BLE.")
        apiNotificationsReady = true
    }

    private fun parseNetworks(root: Any?): List<ReachWifiNetwork> {
        val found = linkedMapOf<String, ReachWifiNetwork>()

        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
                is JSONObject -> {
                    val ssid = value.optString("ssid", "").trim()
                    if (ssid.isNotEmpty()) {
                        val signal = listOf("signal", "rssi", "quality", "signal_strength", "strength", "level", "signal_level")
                            .firstNotNullOfOrNull { key ->
                                if (value.has(key) && !value.isNull(key)) {
                                    runCatching { value.getInt(key) }.getOrNull()
                                } else null
                            }
                        val security = value.optString("security", "").takeIf { it.isNotBlank() }
                        val known = value.optBoolean("known", value.optBoolean("saved", false))
                        found[ssid] = ReachWifiNetwork(
                            ssid = ssid,
                            security = security,
                            signal = signal,
                            known = known
                        )
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) visit(value.opt(keys.next()))
                }
            }
        }

        visit(root)
        return found.values.sortedBy { it.ssid.lowercase() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeMessage(
        characteristic: BluetoothGattCharacteristic,
        plain: ByteArray
    ) {
        val compressed = deflate(plain)
        val framed = frame(compressed)
        val maxChunk = (negotiatedMtu - 3).coerceAtLeast(20)

        var offset = 0
        while (offset < framed.size) {
            val end = minOf(offset + maxChunk, framed.size)
            val chunk = framed.copyOfRange(offset, end)
            val waiter = CompletableDeferred<Boolean>()
            writeWaiter = waiter

            val started = writeCharacteristic(characteristic, chunk)
            if (!started) {
                writeWaiter = null
                error("Android rechazó la escritura BLE al Reach.")
            }

            val ok = try {
                withTimeout(3_000L) { waiter.await() }
            } finally {
                if (writeWaiter === waiter) writeWaiter = null
            }
            if (!ok) error("El Reach rechazó un fragmento BLE.")
            offset = end
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        val currentGatt = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = value
                currentGatt.writeCharacteristic(characteristic)
            }
        }
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(6)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count <= 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    out.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun frame(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 8)
        out.write(0x7E)
        data.forEach { b ->
            val v = b.toInt() and 0xFF
            if (v == 0x7E || v == 0x7D) {
                out.write(0x7D)
                out.write(v)
            } else {
                out.write(v)
            }
        }
        out.write(0x7E)
        return out.toByteArray()
    }

    private fun onIncoming(bytes: ByteArray) {
        synchronized(rxLock) {
            bytes.forEach { raw ->
                val value = raw.toInt() and 0xFF
                when {
                    value == 0x7E -> {
                        if (inFrame && rxFrame.size() > 0) {
                            val packet = rxFrame.toByteArray()
                            rxFrame.reset()
                            escaped = false
                            dispatchPacket(packet)
                        }
                        inFrame = true
                        rxFrame.reset()
                        escaped = false
                    }
                    !inFrame -> Unit
                    escaped -> {
                        rxFrame.write(value)
                        escaped = false
                    }
                    value == 0x7D -> escaped = true
                    else -> rxFrame.write(value)
                }
            }
        }
    }

    private fun dispatchPacket(packet: ByteArray) {
        val json = runCatching {
            JSONObject(inflate(packet).toString(Charsets.UTF_8))
        }.getOrNull() ?: return

        val id = findRequestId(json) ?: return
        pendingRequests[id]?.let { waiter ->
            if (!waiter.isCompleted) waiter.complete(json)
        }
    }

    private fun findRequestId(obj: JSONObject): String? {
        listOf("id", "request_id", "requestId").forEach { key ->
            val value = obj.optString(key, "")
            if (value.isNotBlank()) return value
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val nested = obj.opt(keys.next())
            if (nested is JSONObject) {
                val found = findRequestId(nested)
                if (found != null) return found
            }
        }
        return null
    }

    private fun refreshGattCache(target: BluetoothGatt?) {
        if (target == null) return
        runCatching {
            val method = target.javaClass.getMethod("refresh")
            method.isAccessible = true
            method.invoke(target)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattOnly() {
        val old = gatt
        gatt = null
        eventCharacteristic = null
        apiCharacteristic = null
        apiNotificationsReady = false
        negotiatedMtu = 23
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt = g
                    // Solo una operación GATT a la vez. Antes se pedía MTU y se
                    // descubrían servicios simultáneamente, lo que puede provocar
                    // desconexiones en algunos Android/Reach.
                    val started = runCatching { g.discoverServices() }.getOrDefault(false)
                    if (!started) {
                        connectWaiter?.takeIf { !it.isCompleted }?.completeExceptionally(
                            IllegalStateException("Android no pudo iniciar el descubrimiento BLE del Reach.")
                        )
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    eventCharacteristic = null
                    apiCharacteristic = null
                    if (status == 133) refreshGattCache(g)
                    if (gatt === g) gatt = null
                    apiNotificationsReady = false
                    val detail = if (status == BluetoothGatt.GATT_SUCCESS) "" else " (GATT $status)"
                    val message = if (status == 133)
                        "Android cerró la conexión BLE antes de completar el enlace (GATT 133). Se volverá a intentar buscando primero el anuncio BLE del Reach."
                    else
                        "Se perdió el enlace BLE de administración con el Reach$detail."
                    connectWaiter?.takeIf { !it.isCompleted }?.completeExceptionally(
                        IllegalStateException(message)
                    )
                    pendingRequests.values.forEach {
                        if (!it.isCompleted) {
                            it.completeExceptionally(
                                IllegalStateException("Se perdió el enlace BLE de administración con el Reach.")
                            )
                        }
                    }
                    runCatching { g.close() }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) negotiatedMtu = mtu
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectWaiter?.takeIf { !it.isCompleted }?.completeExceptionally(
                    IllegalStateException("El Reach no permitió descubrir sus servicios BLE.")
                )
                return
            }

            val service = g.getService(SERVICE_UUID)
            val event = service?.getCharacteristic(EVENT_UUID)
            val api = service?.getCharacteristic(API_UUID)
            if (service == null || event == null || api == null) {
                connectWaiter?.takeIf { !it.isCompleted }?.completeExceptionally(
                    IllegalStateException("El Reach no expuso el servicio BLE de administración esperado.")
                )
                return
            }

            eventCharacteristic = event
            apiCharacteristic = api
            apiNotificationsReady = false
            connectWaiter?.takeIf { !it.isCompleted }?.complete(Unit)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            onIncoming(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onIncoming(value)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            descriptorWaiter?.takeIf { !it.isCompleted }
                ?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeWaiter?.takeIf { !it.isCompleted }?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }
}
