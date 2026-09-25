package cr.co.topoemlid

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import kotlin.coroutines.resume

data class ReachBatteryStatus(
    val chargerStatus: String? = null,
    val currentA: Double? = null,
    val stateOfCharge: Int? = null,
    val temperatureC: Double? = null,
    val usbChargerCurrentA: Double? = null,
    val usbChargerVoltageV: Double? = null,
    val voltageV: Double? = null
)

data class ReachDeviceStatus(
    val name: String? = null,
    val firstUsageTimestamp: Long? = null
)

data class ReachWifiStatus(
    val ip: String? = null,
    val ssid: String? = null,
    val security: String? = null,
    val enabled: Boolean? = null,
    val mode: String? = null
)

data class ReachWifiNetwork(
    val ssid: String,
    val security: String? = null,
    val signal: Int? = null,
    val known: Boolean = false
)

data class ReachModemInfo(
    val connected: Boolean? = null,
    val accessTechnology: String? = null,
    val currentMode: String? = null,
    val currentApn: String? = null,
    val availableApns: List<String> = emptyList(),
    val state: String? = null,
    val usageMb: Double? = null,
    val since: String? = null,
    val unlockRetries: Int? = null
)

data class ReachModemSettings(
    val autoconnect: Boolean? = null,
    val dataSharing: Boolean? = null,
    val gsmUpgrades: Boolean? = null,
    val roaming: Boolean? = null,
    val pinConfigured: Boolean? = null
)

enum class ReachLocalAction(val wireName: String) {
    FIND_REACH("find_reach"),
    REBOOT("reboot"),
    SHUTDOWN("shutdown"),
    RESET_RTK("reset_rtk")
}

/**
 * Cliente aislado para la administración local del Reach.
 *
 * No toca Bluetooth/NMEA, BLE ni NTRIP. Usa únicamente HTTP/Socket.IO
 * contra la IP local del receptor (por defecto 192.168.42.1 en modo AP).
 */
class ReachLocalApiClient(
    host: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()
) {
    private val cleanHost = host
        .trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .trimEnd('/')

    val baseUrl: String = "http://$cleanHost"


    companion object {
        /**
         * Busca un Reach accesible dentro de las redes IPv4 locales de la tablet.
         * Se usa cuando el receptor dejó 192.168.42.1 y recibió una IP dinámica
         * al conectarse al hotspot/red Wi-Fi de la tablet.
         */
        suspend fun discoverReachOnLocalNetwork(): String? = withContext(Dispatchers.IO) {
            val localAddresses = buildList {
                val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
                if (interfaces != null) {
                    while (interfaces.hasMoreElements()) {
                        val iface = interfaces.nextElement()
                        if (!runCatching { iface.isUp }.getOrDefault(false) || iface.isLoopback) continue
                        val addresses = iface.inetAddresses
                        while (addresses.hasMoreElements()) {
                            val addr = addresses.nextElement()
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                val bytes = addr.address.map { it.toInt() and 0xFF }
                                val private = bytes[0] == 10 ||
                                    (bytes[0] == 172 && bytes[1] in 16..31) ||
                                    (bytes[0] == 192 && bytes[1] == 168)
                                if (private) add(bytes)
                            }
                        }
                    }
                }
            }.distinct()

            // Primero probar la IP fija del Reach por si ya volvió a modo AP.
            fun looksLikeReach(host: String): Boolean {
                val client = OkHttpClient.Builder()
                    .connectTimeout(280, TimeUnit.MILLISECONDS)
                    .readTimeout(350, TimeUnit.MILLISECONDS)
                    .writeTimeout(350, TimeUnit.MILLISECONDS)
                    .build()

                val request = Request.Builder()
                    .url("http://$host/wifi/status")
                    .get()
                    .header("Accept", "application/json")
                    .build()

                return runCatching {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use false
                        val raw = response.body?.string().orEmpty()
                        if (raw.isBlank()) return@use false
                        val j = JSONObject(raw)
                        j.has("enabled") || j.has("mode") || j.has("current_network")
                    }
                }.getOrDefault(false)
            }

            if (looksLikeReach("192.168.42.1")) return@withContext "192.168.42.1"

            for (bytes in localAddresses) {
                // El hotspot/red local normalmente usa /24. Evitamos volver a
                // explorar la subred propia 192.168.42.x del AP del Reach.
                if (bytes[0] == 192 && bytes[1] == 168 && bytes[2] == 42) continue

                val prefix = "${bytes[0]}.${bytes[1]}.${bytes[2]}"
                val ownLast = bytes[3]
                val pool = Executors.newFixedThreadPool(40)
                val completion = ExecutorCompletionService<String?>(pool)
                try {
                    var submitted = 0
                    for (last in 2..254) {
                        if (last == ownLast) continue
                        val host = "$prefix.$last"
                        completion.submit(java.util.concurrent.Callable<String?> {
                            if (looksLikeReach(host)) host else null
                        })
                        submitted++
                    }

                    repeat(submitted) {
                        val found = runCatching { completion.take().get() }.getOrNull()
                        if (found != null) {
                            pool.shutdownNow()
                            return@withContext found
                        }
                    }
                } finally {
                    pool.shutdownNow()
                }
            }

            null
        }
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .get()
            .header("Accept", "application/json")
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code} en $path")
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) JSONObject() else JSONObject(body)
            }
        } catch (e: java.net.ConnectException) {
            error("No se puede acceder al panel local del Reach en $baseUrl. Conecte la tablet al Wi‑Fi/hotspot del Reach o ponga ambos equipos en la misma red.")
        } catch (e: java.net.SocketTimeoutException) {
            error("El Reach no respondió en $baseUrl. Verifique que la tablet esté conectada al Wi‑Fi/hotspot del Reach.")
        }
    }

    suspend fun battery(): ReachBatteryStatus {
        val j = getJson("/battery")
        return ReachBatteryStatus(
            chargerStatus = j.optString("charger_status").takeIf { it.isNotBlank() },
            currentA = j.optDouble("current").takeUnless { it.isNaN() },
            stateOfCharge = if (j.has("state_of_charge")) j.optInt("state_of_charge") else null,
            temperatureC = j.optDouble("temperature").takeUnless { it.isNaN() },
            usbChargerCurrentA = j.optDouble("usb_charger_current").takeUnless { it.isNaN() },
            usbChargerVoltageV = j.optDouble("usb_charger_voltage").takeUnless { it.isNaN() },
            voltageV = j.optDouble("voltage").takeUnless { it.isNaN() }
        )
    }

    suspend fun device(): ReachDeviceStatus {
        val j = getJson("/device")
        val stats = j.optJSONObject("statistics")
        return ReachDeviceStatus(
            name = j.optString("name").takeIf { it.isNotBlank() },
            firstUsageTimestamp = stats?.optLong("first_usage_timestamp")?.takeIf { it > 0L }
        )
    }

    suspend fun wifiStatus(): ReachWifiStatus {
        val j = getJson("/wifi/status")
        val current = j.optJSONObject("current_network")
        return ReachWifiStatus(
            ip = current?.optString("ip")?.takeIf { it.isNotBlank() },
            ssid = current?.optString("ssid")?.takeIf { it.isNotBlank() },
            security = current?.optString("security")?.takeIf { it.isNotBlank() },
            enabled = if (j.has("enabled")) j.optBoolean("enabled") else null,
            mode = j.optString("mode").takeIf { it.isNotBlank() }
        )
    }


    suspend fun enableWifi(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val current = runCatching { wifiStatus() }.getOrNull()
            if (current?.enabled == true) return@runCatching

            val options = IO.Options().apply {
                forceNew = true
                reconnection = false
                timeout = 3500
            }
            val socket = IO.socket(URI(baseUrl), options)
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    var finished = false
                    fun finish(result: Result<Unit>) {
                        if (finished) return
                        finished = true
                        socket.off()
                        socket.disconnect()
                        if (cont.isActive) {
                            result.fold(
                                onSuccess = { cont.resume(Unit) },
                                onFailure = { cont.cancel(it) }
                            )
                        }
                    }

                    socket.on(Socket.EVENT_CONNECT) {
                        runCatching { socket.emit("task", "turn_on_wifi") }
                            .onSuccess {
                                Thread {
                                    val deadline = System.currentTimeMillis() + 8_000L
                                    var enabledNow = false
                                    while (!enabledNow && System.currentTimeMillis() < deadline) {
                                        Thread.sleep(300L)
                                        enabledNow = runCatching {
                                            kotlinx.coroutines.runBlocking { wifiStatus().enabled == true }
                                        }.getOrDefault(false)
                                    }
                                    if (enabledNow) finish(Result.success(Unit))
                                    else finish(Result.failure(IllegalStateException("El Reach recibió la orden, pero no confirmó Wi‑Fi activo.")))
                                }.start()
                            }
                            .onFailure { finish(Result.failure(it)) }
                    }
                    socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                        val detail = args.firstOrNull()?.toString() ?: "No se pudo abrir Socket.IO"
                        finish(Result.failure(IllegalStateException(detail)))
                    }
                    cont.invokeOnCancellation {
                        socket.off()
                        socket.disconnect()
                    }
                    socket.connect()
                }
            } finally {
                socket.off()
                socket.disconnect()
            }
        }
    }

    suspend fun wifiNetworks(): List<ReachWifiNetwork> = withContext(Dispatchers.IO) {
        enableWifi().getOrThrow()

        // Reach Panel no se limita a leer /wifi/networks: primero solicita
        // un escaneo real por Socket.IO con la acción wifi_scan.
        val options = IO.Options().apply {
            forceNew = true
            reconnection = false
            timeout = 3500
        }
        val socket = IO.socket(URI(baseUrl), options)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                var finished = false
                fun finish(result: Result<Unit>) {
                    if (finished) return
                    finished = true
                    socket.off()
                    socket.disconnect()
                    if (cont.isActive) {
                        result.fold(
                            onSuccess = { cont.resume(Unit) },
                            onFailure = { cont.cancel(it) }
                        )
                    }
                }

                socket.on(Socket.EVENT_CONNECT) {
                    runCatching {
                        socket.emit(
                            "action",
                            JSONObject().put("name", "wifi_scan")
                        )
                    }.onSuccess {
                        Thread {
                            // El escaneo tarda un instante en poblar /wifi/networks.
                            Thread.sleep(1400L)
                            finish(Result.success(Unit))
                        }.start()
                    }.onFailure { finish(Result.failure(it)) }
                }
                socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                    val detail = args.firstOrNull()?.toString() ?: "No se pudo abrir Socket.IO"
                    finish(Result.failure(IllegalStateException(detail)))
                }
                cont.invokeOnCancellation {
                    socket.off()
                    socket.disconnect()
                }
                socket.connect()
            }
        } finally {
            socket.off()
            socket.disconnect()
        }

        fun parseNetworkObject(item: JSONObject): ReachWifiNetwork? {
            val ssid = item.optString("ssid")
                .ifBlank { item.optString("name") }
                .ifBlank { item.optString("network") }
            if (ssid.isBlank()) return null
            val signal = when {
                item.has("signal") -> item.optInt("signal")
                item.has("rssi") -> item.optInt("rssi")
                item.has("quality") -> item.optInt("quality")
                item.has("signal_strength") -> item.optInt("signal_strength")
                item.has("strength") -> item.optInt("strength")
                item.has("level") -> item.optInt("level")
                item.has("signal_level") -> item.optInt("signal_level")
                else -> null
            }
            return ReachWifiNetwork(
                ssid = ssid,
                security = item.optString("security").takeIf { it.isNotBlank() }
                    ?: item.optString("encryption").takeIf { it.isNotBlank() },
                signal = signal,
                known = item.optBoolean("known", item.optBoolean("saved", false))
            )
        }

        fun collectNetworks(value: Any?, out: MutableList<ReachWifiNetwork>) {
            when (value) {
                is String -> if (value.isNotBlank()) out += ReachWifiNetwork(value)
                is JSONObject -> {
                    parseNetworkObject(value)?.let { out += it }
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (child is JSONObject || child is org.json.JSONArray) {
                            collectNetworks(child, out)
                        }
                    }
                }
                is org.json.JSONArray -> {
                    for (i in 0 until value.length()) {
                        collectNetworks(value.opt(i), out)
                    }
                }
            }
        }

        var lastRaw = ""
        var parsed: List<ReachWifiNetwork> = emptyList()
        repeat(4) { attempt ->
            val request = Request.Builder()
                .url(baseUrl + "/wifi/networks")
                .get()
                .header("Accept", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code} en /wifi/networks")
                lastRaw = response.body?.string().orEmpty().trim()
            }

            if (lastRaw.isNotBlank()) {
                val found = mutableListOf<ReachWifiNetwork>()
                runCatching {
                    when {
                        lastRaw.startsWith("[") -> collectNetworks(org.json.JSONArray(lastRaw), found)
                        lastRaw.startsWith("{") -> collectNetworks(JSONObject(lastRaw), found)
                    }
                }
                parsed = found
                    .distinctBy { it.ssid }
                    .sortedWith(
                        compareByDescending<ReachWifiNetwork> { it.signal ?: Int.MIN_VALUE }
                            .thenBy { it.ssid.lowercase() }
                    )
                if (parsed.isNotEmpty()) return@withContext parsed
            }

            if (attempt < 3) Thread.sleep(800L)
        }

        parsed
    }

    suspend fun connectWifiNetwork(
        ssid: String,
        password: String,
        security: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(ssid.isNotBlank()) { "Seleccione una red Wi‑Fi." }
            enableWifi().getOrThrow()

            // Reach Panel primero guarda/actualiza la red por HTTP.
            val network = JSONObject()
                .put("security", security?.takeIf { it.isNotBlank() } ?: "wpa-psk")
                .put("ssid", ssid)
                .put("password", password)

            val payload = JSONObject()
                .put("network", network)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url(baseUrl + "/wifi/networks/saved")
                .post(payload)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()

            http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code} en /wifi/networks/saved")
                }
            }

            // Confirmado en Reach Panel: emitAction("connect_to_wifi_network", {ssid: ...})
            val options = IO.Options().apply {
                forceNew = true
                reconnection = false
                timeout = 3500
            }
            val socket = IO.socket(URI(baseUrl), options)
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    var finished = false
                    fun finish(result: Result<Unit>) {
                        if (finished) return
                        finished = true
                        socket.off()
                        socket.disconnect()
                        if (cont.isActive) {
                            result.fold(
                                onSuccess = { cont.resume(Unit) },
                                onFailure = { cont.cancel(it) }
                            )
                        }
                    }

                    socket.on(Socket.EVENT_CONNECT) {
                        runCatching {
                            val action = JSONObject()
                                .put("name", "connect_to_wifi_network")
                                .put("payload", JSONObject().put("ssid", ssid))
                            socket.emit("action", action)
                        }.onSuccess {
                            // Al aceptar la acción el Reach abandona su hotspot y puede
                            // cambiar de IP, por lo que no esperamos una respuesta HTTP.
                            Thread {
                                Thread.sleep(600L)
                                finish(Result.success(Unit))
                            }.start()
                        }.onFailure { finish(Result.failure(it)) }
                    }
                    socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                        val detail = args.firstOrNull()?.toString() ?: "No se pudo abrir Socket.IO"
                        finish(Result.failure(IllegalStateException(detail)))
                    }
                    cont.invokeOnCancellation {
                        socket.off()
                        socket.disconnect()
                    }
                    socket.connect()
                }
            } finally {
                socket.off()
                socket.disconnect()
            }
        }
    }

    suspend fun startHotspotMode(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val options = IO.Options().apply {
                forceNew = true
                reconnection = false
                timeout = 3500
            }
            val socket = IO.socket(URI(baseUrl), options)
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    var finished = false
                    fun finish(result: Result<Unit>) {
                        if (finished) return
                        finished = true
                        socket.off()
                        socket.disconnect()
                        if (cont.isActive) {
                            result.fold(
                                onSuccess = { cont.resume(Unit) },
                                onFailure = { cont.cancel(it) }
                            )
                        }
                    }

                    socket.on(Socket.EVENT_CONNECT) {
                        runCatching {
                            socket.emit(
                                "action",
                                JSONObject().put("name", "start_hotspot_mode")
                            )
                        }.onSuccess {
                            Thread {
                                // El Reach puede cerrar inmediatamente la conexión
                                // al abandonar la red actual y volver a modo AP.
                                Thread.sleep(650L)
                                finish(Result.success(Unit))
                            }.start()
                        }.onFailure { finish(Result.failure(it)) }
                    }
                    socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                        val detail = args.firstOrNull()?.toString() ?: "No se pudo abrir Socket.IO"
                        finish(Result.failure(IllegalStateException(detail)))
                    }
                    cont.invokeOnCancellation {
                        socket.off()
                        socket.disconnect()
                    }
                    socket.connect()
                }
            } finally {
                socket.off()
                socket.disconnect()
            }
        }
    }

    suspend fun modemInfo(): ReachModemInfo {
        val j = getJson("/modem/1/info")
        val stats = j.optJSONObject("stats")
        val apns = j.optJSONArray("available_apns")
        return ReachModemInfo(
            connected = if (j.has("connected")) j.optBoolean("connected") else null,
            accessTechnology = j.optString("access_technology").takeIf { it.isNotBlank() },
            currentMode = j.optString("current_mode").takeIf { it.isNotBlank() },
            currentApn = j.optString("current_apn").takeIf { it.isNotBlank() },
            availableApns = if (apns == null) emptyList() else
                (0 until apns.length()).mapNotNull { i ->
                    apns.optString(i).takeIf { it.isNotBlank() }
                },
            state = j.optString("state").takeIf { it.isNotBlank() },
            usageMb = when {
                stats?.has("usage_mb") == true -> stats.optDouble("usage_mb").takeUnless { it.isNaN() }
                j.has("usage_mb") -> j.optDouble("usage_mb").takeUnless { it.isNaN() }
                else -> null
            },
            since = stats?.optString("since")?.takeIf { it.isNotBlank() },
            unlockRetries = if (j.has("unlock_retries")) j.optInt("unlock_retries") else null
        )
    }

    suspend fun modemSettings(): ReachModemSettings {
        val j = getJson("/modem/1/settings")
        return ReachModemSettings(
            autoconnect = if (j.has("autoconnect")) j.optBoolean("autoconnect") else null,
            dataSharing = if (j.has("data_sharing")) j.optBoolean("data_sharing") else null,
            gsmUpgrades = if (j.has("gsm_upgrades")) j.optBoolean("gsm_upgrades") else null,
            roaming = if (j.has("roaming")) j.optBoolean("roaming") else null,
            pinConfigured = if (j.has("pin")) !j.isNull("pin") && j.optString("pin").isNotBlank() else null
        )
    }

    suspend fun setMobileAutoconnect(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("autoconnect", enabled)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val path = "/modem/1/settings"
            val request = Request.Builder()
                .url(baseUrl + path)
                .post(payload)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code} en $path")
            }
        }
    }

    suspend fun setMobileDataSharing(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("data_sharing", enabled)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val path = "/modem/1/settings"
            val request = Request.Builder()
                .url(baseUrl + path)
                .post(payload)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code} en $path")
            }
        }
    }

    suspend fun setMobileGsmUpgrades(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("gsm_upgrades", enabled)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val path = "/modem/1/settings"
            val request = Request.Builder()
                .url(baseUrl + path)
                .post(payload)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code} en $path")
            }
        }
    }

    suspend fun setMobileRoaming(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("roaming", enabled)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val path = "/modem/1/settings"
            val request = Request.Builder()
                .url(baseUrl + path)
                .post(payload)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code} en $path")
            }
        }
    }

    suspend fun setMobileDataEnabled(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // "Use mobile data" depende de autoconnect. Primero escribimos el
            // ajuste y comprobamos que el Reach realmente lo guardó antes de
            // mandar la tarea del módem.
            setMobileAutoconnect(enabled).getOrThrow()

            val settingsDeadline = System.currentTimeMillis() + 3_000L
            var autoconnectConfirmed = false
            while (!autoconnectConfirmed && System.currentTimeMillis() < settingsDeadline) {
                val settings = runCatching { modemSettings() }.getOrNull()
                autoconnectConfirmed = settings?.autoconnect == enabled
                if (!autoconnectConfirmed) Thread.sleep(200L)
            }
            if (!autoconnectConfirmed) {
                error(
                    if (enabled)
                        "El Reach no confirmó autoconnect activado."
                    else
                        "El Reach no confirmó autoconnect desactivado."
                )
            }

            val taskName = if (enabled) "modem_connect" else "modem_disconnect"
            val options = IO.Options().apply {
                forceNew = true
                reconnection = false
                timeout = 3500
            }
            val socket = IO.socket(URI(baseUrl), options)
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    var finished = false

                    fun finish(result: Result<Unit>) {
                        if (finished) return
                        finished = true
                        socket.off()
                        socket.disconnect()
                        if (cont.isActive) {
                            result.fold(
                                onSuccess = { cont.resume(Unit) },
                                onFailure = { cont.cancel(it) }
                            )
                        }
                    }

                    socket.on(Socket.EVENT_CONNECT) {
                        runCatching {
                            // Reach Panel usa ws.emitTask(taskName) para
                            // modem_connect y modem_disconnect: ambos se envían
                            // directamente como nombre de tarea.
                            socket.emit("task", taskName)
                        }.onSuccess {
                            Thread {
                                val deadline = System.currentTimeMillis() + 12_000L
                                var matched = false
                                while (!matched && System.currentTimeMillis() < deadline) {
                                    Thread.sleep(300L)
                                    val info = runCatching {
                                        kotlinx.coroutines.runBlocking { modemInfo() }
                                    }.getOrNull()
                                    val connected = info?.connected
                                        ?: when {
                                            info?.state.equals("CONNECTED", ignoreCase = true) -> true
                                            info?.state.equals("DISCONNECTED", ignoreCase = true) -> false
                                            info?.state.equals("UNLOCKED", ignoreCase = true) -> false
                                            else -> null
                                        }
                                    matched = if (enabled) connected == true else connected == false
                                }
                                if (matched) {
                                    finish(Result.success(Unit))
                                } else {
                                    finish(
                                        Result.failure(
                                            IllegalStateException(
                                                if (enabled)
                                                    "El Reach recibió la orden, pero no confirmó la conexión de datos móviles."
                                                else
                                                    "El Reach recibió la orden, pero su módem no llegó a estado desconectado."
                                            )
                                        )
                                    )
                                }
                            }.start()
                        }.onFailure { finish(Result.failure(it)) }
                    }
                    socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                        val detail = args.firstOrNull()?.toString() ?: "No se pudo abrir Socket.IO"
                        finish(Result.failure(IllegalStateException(detail)))
                    }
                    cont.invokeOnCancellation {
                        socket.off()
                        socket.disconnect()
                    }
                    socket.connect()
                }
            } finally {
                socket.off()
                socket.disconnect()
            }
        }
    }

    suspend fun sendAction(action: ReachLocalAction): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Reach Panel usa Socket.IO/Engine.IO 3 y emite:
            // event = "action", payload = {"name":"find_reach|reboot|shutdown|reset_rtk"}
            val options = IO.Options().apply {
                forceNew = true
                reconnection = false
                timeout = 3500
            }
            val socket = IO.socket(URI(baseUrl), options)
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    var finished = false

                    fun finish(result: Result<Unit>) {
                        if (finished) return
                        finished = true
                        socket.off()
                        socket.disconnect()
                        if (cont.isActive) {
                            result.fold(
                                onSuccess = { cont.resume(Unit) },
                                onFailure = { cont.cancel(it) }
                            )
                        }
                    }

                    socket.on(Socket.EVENT_CONNECT) {
                        runCatching {
                            socket.emit("action", JSONObject().put("name", action.wireName))
                        }.onSuccess {
                            // El panel no exige ACK para estas acciones; el envío del frame
                            // se considera suficiente. Damos un instante para vaciar el socket.
                            Thread {
                                Thread.sleep(450)
                                finish(Result.success(Unit))
                            }.start()
                        }.onFailure { finish(Result.failure(it)) }
                    }
                    socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                        val detail = args.firstOrNull()?.toString() ?: "No se pudo abrir Socket.IO"
                        finish(Result.failure(IllegalStateException(detail)))
                    }
                    cont.invokeOnCancellation {
                        socket.off()
                        socket.disconnect()
                    }
                    socket.connect()
                }
            } finally {
                socket.off()
                socket.disconnect()
            }
        }
    }
}
