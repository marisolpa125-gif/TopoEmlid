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
import java.util.concurrent.TimeUnit
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

enum class ReachLocalAction(val wireName: String) {
    FIND_REACH("find_reach"),
    REBOOT("reboot"),
    SHUTDOWN("reach_shutdown"),
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

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .get()
            .header("Accept", "application/json")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} en $path")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) JSONObject() else JSONObject(body)
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
                            // Reach Panel: emitTask(e,t) -> socket event "task", first arg = task name.
                            socket.emit("task", taskName)
                        }.onSuccess {
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

    suspend fun sendAction(action: ReachLocalAction): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Reach Panel usa Socket.IO/Engine.IO 3 y emite:
            // event = "action", payload = {"name":"find_reach|reboot|reach_shutdown|reset_rtk"}
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
