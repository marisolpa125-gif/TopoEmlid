package cr.co.topoemlid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.concurrent.thread

class ReceiverConnectionManager(context: Context) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var socket: BluetoothSocket? = null
    private var gatt: BluetoothGatt? = null
    private var worker: Thread? = null
    private var watchdog: Thread? = null
    private var autoFallbackProfile: ReceiverProfile? = null
    private var requestedProfileId: String? = null
    private var floatStreak = 0
    private val usedSatelliteIds = java.util.Collections.synchronizedSet(linkedSetOf<String>())

    var status by mutableStateOf(GnssStatus())
        private set

    var connecting by mutableStateOf(false)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    @SuppressLint("MissingPermission")
    fun connect(profile: ReceiverProfile) {
        // Ignore a second tap while the same connection is already starting.
        if (connecting && requestedProfileId == profile.id) return
        if (status.connected && status.receiverName == profile.name) return

        try {
            disconnect()
            requestedProfileId = profile.id
            connecting = true
            lastError = null

            when (profile.preferredMode) {
                ReceiverConnectionMode.AUTO -> {
                    connectNmea(profile)
                }
                ReceiverConnectionMode.BLE -> connectBle(profile, allowFallback = false)
                ReceiverConnectionMode.BLUETOOTH_NMEA -> connectNmea(profile)
                ReceiverConnectionMode.WIFI_AP -> prepareWifiMode(
                    profile,
                    transport = "Wi‑Fi AP",
                    message = "Conecte la tablet a la red Wi‑Fi creada por el receptor. La comunicación por API local se habilitará cuando esté configurada la dirección del Reach."
                )
                ReceiverConnectionMode.WIFI_LOCAL -> prepareWifiMode(
                    profile,
                    transport = "Wi‑Fi Red local",
                    message = "Conecte la tablet y el receptor a la misma red Wi‑Fi. La comunicación por API local se habilitará cuando esté configurada la dirección del Reach."
                )
            }
        } catch (t: Throwable) {
            requestedProfileId = null
            connecting = false
            lastError = "No se pudo iniciar la conexión Bluetooth: " +
                (t.message ?: t.javaClass.simpleName)
            status = GnssStatus(
                receiverName = profile.name,
                connectionTransport = "Bluetooth / NMEA",
                solution = "SIN SEÑAL"
            )
        }
    }

    private fun prepareWifiMode(profile: ReceiverProfile, transport: String, message: String) {
        connecting = false
        lastError = message
        postStatus(
            GnssStatus(
                receiverName = profile.name,
                connected = false,
                connectionTransport = transport,
                solution = "WI‑FI PREPARADO"
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectBle(profile: ReceiverProfile, allowFallback: Boolean) {
        val device = runCatching { adapter?.getRemoteDevice(profile.address) }.getOrNull()
        if (device == null) {
            connecting = false
            lastError = "No se pudo obtener el dispositivo BLE. Actualice la lista y vuelva a seleccionarlo."
            return
        }

        postStatus(
            GnssStatus(
                receiverName = profile.name,
                connected = false,
                connectionTransport = "BLE",
                solution = "CONECTANDO BLE"
            )
        )

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        gatt = g
                        mainHandler.post {
                            connecting = false
                            lastError = null
                            status = status.copy(
                                connected = true,
                                receiverName = profile.name,
                                connectionTransport = "BLE",
                                solution = "BLE CONECTADO",
                                nmeaReceiving = false
                            )
                        }
                        runCatching { g.discoverServices() }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        runCatching { g.close() }
                        if (gatt === g) gatt = null

                        if (allowFallback && autoFallbackProfile?.id == profile.id) {
                            autoFallbackProfile = null
                            mainHandler.post {
                                connecting = true
                                status = GnssStatus(
                                    receiverName = profile.name,
                                    connectionTransport = "Bluetooth / NMEA",
                                    solution = "BLE NO DISPONIBLE • PROBANDO NMEA"
                                )
                            }
                            connectNmea(profile.copy(preferredMode = ReceiverConnectionMode.BLUETOOTH_NMEA))
                        } else {
                            mainHandler.post {
                                connecting = false
                                status = status.copy(connected = false, solution = "SIN SEÑAL")
                                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                                    lastError = "No se pudo mantener la conexión BLE (código $statusCode)."
                                }
                            }
                        }
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                    val count = g.services?.size ?: 0
                    mainHandler.post {
                        status = status.copy(
                            connected = true,
                            connectionTransport = "BLE",
                            bleServicesDiscovered = count,
                            solution = "BLE CONECTADO"
                        )
                    }
                }
            }
        }

        try {
            gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            if (allowFallback) {
                autoFallbackProfile = null
                connectNmea(profile.copy(preferredMode = ReceiverConnectionMode.BLUETOOTH_NMEA))
            } else {
                connecting = false
                lastError = e.message ?: "No se pudo iniciar la conexión BLE."
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectNmea(profile: ReceiverProfile) {
        val device = adapter?.bondedDevices?.firstOrNull { it.address == profile.address }
        if (device == null) {
            connecting = false
            lastError = "Para Bluetooth/NMEA, el receptor debe estar emparejado primero en Bluetooth de Android."
            return
        }

        postStatus(
            GnssStatus(
                receiverName = profile.name,
                connected = false,
                connectionTransport = "Bluetooth / NMEA",
                solution = "CONECTANDO"
            )
        )

        worker = thread(name = "gnss-nmea") {
            var ownedSocket: BluetoothSocket? = null
            var openedSuccessfully = false
            try {
                // Ruta probada originalmente con Reach RS2+: SPP estándar directo.
                // Mantenerla simple: no hacer SDP adicional, no preleer el flujo y
                // no alternar entre varios sockets antes de iniciar el lector NMEA.
                adapter?.cancelDiscovery()
                Thread.sleep(450L)

                val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                val s = device.createRfcommSocketToServiceRecord(spp)
                s.connect()

                ownedSocket = s
                socket = s
                openedSuccessfully = true

                postStatus(
                    status.copy(
                        connected = true,
                        receiverName = profile.name,
                        connectionTransport = "Bluetooth / NMEA",
                        solution = "ESPERANDO NMEA"
                    )
                )
                mainHandler.post {
                    connecting = false
                    lastError = null
                }

                val reader = BufferedReader(InputStreamReader(s.inputStream))

                // Watch actual NMEA traffic, not just the Bluetooth socket state.
                // If no sentence arrives for several seconds, force the socket closed
                // so the reader exits and the normal reconnect path can take over.
                watchdog?.interrupt()
                watchdog = thread(name = "gnss-nmea-watchdog") {
                    try {
                        while (!Thread.currentThread().isInterrupted && requestedProfileId == profile.id) {
                            Thread.sleep(2000L)
                            val last = status.lastNmeaAt
                            if (status.connected && last != null && System.currentTimeMillis() - last > 8000L) {
                                mainHandler.post {
                                    if (requestedProfileId == profile.id) {
                                        connecting = true
                                        status = status.copy(
                                            connected = false,
                                            nmeaReceiving = false,
                                            solution = "SIN DATOS • RECONECTANDO"
                                        )
                                        lastError = "Se perdió el flujo NMEA del receptor. Intentando reconectar."
                                    }
                                }
                                runCatching { s.close() }
                                break
                            }
                        }
                    } catch (_: InterruptedException) {
                        // Desconexión manual o cierre normal: salir sin lanzar
                        // una excepción no controlada desde el hilo watchdog.
                    }
                }

                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("$")) {
                        postStatus(
                            status.copy(
                                connected = true,
                                receiverName = profile.name,
                                connectionTransport = "Bluetooth / NMEA",
                                nmeaReceiving = true,
                                lastNmeaSentence = line.take(160),
                                lastNmeaAt = System.currentTimeMillis()
                            )
                        )
                    }

                    NmeaParser.parseGga(line)?.let { gga ->
                        val solution = when (gga.fixQuality) {
                            4 -> {
                                floatStreak = 0
                                "FIX"
                            }
                            5 -> {
                                floatStreak += 1
                                // Avoid a visible FIX/FLOAT flicker caused by one or two
                                // transient GGA samples. A sustained FLOAT still appears.
                                if (status.solution == "FIX" && floatStreak < 4) "FIX" else "FLOAT"
                            }
                            2 -> {
                                floatStreak = 0
                                "DGPS"
                            }
                            1 -> {
                                floatStreak = 0
                                "SINGLE"
                            }
                            else -> {
                                floatStreak = 0
                                "SIN FIX"
                            }
                        }
                        postStatus(
                            status.copy(
                                connected = true,
                                receiverName = profile.name,
                                connectionTransport = "Bluetooth / NMEA",
                                solution = solution,
                                satellites = gga.satellites,
                                latitude = gga.latitude,
                                longitude = gga.longitude,
                                ellipsoidalHeightM = gga.ellipsoidalHeightM,
                                correctionAgeS = gga.correctionAgeS,
                                nmeaReceiving = true,
                                lastNmeaSentence = line.take(160),
                                lastNmeaAt = System.currentTimeMillis()
                            )
                        )
                    }

                    NmeaParser.parseGst(line)?.let { gst ->
                        postStatus(
                            status.copy(
                                horizontalAccuracyM = gst.horizontalAccuracyM,
                                verticalAccuracyM = gst.verticalAccuracyM
                            )
                        )
                    }

                    NmeaParser.parseGsa(line)?.let { gsa ->
                        usedSatelliteIds += gsa.usedSatelliteIds
                        val marked = status.satelliteSignals.map { sat ->
                            sat.copy(usedInFix = sat.id in usedSatelliteIds)
                        }
                        postStatus(
                            status.copy(
                                pdop = gsa.pdop,
                                positioningMode = gsa.mode,
                                satelliteSignals = marked
                            )
                        )
                    }

                    NmeaParser.parseGsv(line)?.let { gsv ->
                        val merged = status.satelliteSignals
                            .associateBy { it.id }
                            .toMutableMap()

                        gsv.satellites.forEach { sat ->
                            merged[sat.id] = SatelliteSignal(
                                id = sat.id,
                                constellation = sat.constellation,
                                snrDbHz = sat.snrDbHz,
                                elevationDeg = sat.elevationDeg,
                                azimuthDeg = sat.azimuthDeg,
                                usedInFix = sat.id in usedSatelliteIds
                            )
                        }

                        val satellites = merged.values
                            .sortedWith(compareBy<SatelliteSignal> { it.constellation }.thenBy { it.id })
                        val snrValues = satellites.mapNotNull { it.snrDbHz }
                        val avg = snrValues.takeIf { it.isNotEmpty() }?.average()

                        postStatus(
                            status.copy(
                                satellitesInView = gsv.satellitesInView ?: status.satellitesInView,
                                signalNoiseAvgDbHz = avg ?: status.signalNoiseAvgDbHz,
                                satelliteSnrValues = snrValues,
                                satelliteSignals = satellites,
                                nmeaReceiving = true,
                                lastNmeaSentence = line.take(160),
                                lastNmeaAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Cerrar el socket durante una desconexión manual hace salir al reader.
                // No lo tratamos como error ni dejamos que una respuesta tardía altere la UI.
                if (requestedProfileId == profile.id) {
                    mainHandler.post {
                        if (requestedProfileId == profile.id) {
                            connecting = false
                            lastError = when {
                                e.message?.contains("read failed", ignoreCase = true) == true ->
                                    "El canal Bluetooth/NMEA se abrió pero Android reportó un fallo de lectura: " + (e.message ?: "sin detalle")
                                else ->
                                    "Bluetooth/NMEA: " + (e.message ?: e.javaClass.simpleName)
                            }
                            status = status.copy(connected = false, solution = "SIN SEÑAL")
                        }
                    }
                }
            } finally {
                watchdog?.let { runCatching { it.interrupt() } }
                watchdog = null

                val mine = ownedSocket
                if (mine != null) {
                    runCatching { mine.close() }
                    if (socket === mine) socket = null
                }

                // Reconectar automáticamente solo si el canal llegó a abrirse y
                // posteriormente se perdió. Si la apertura inicial falló, conservar
                // el error visible y NO volver a pisarlo con "RECONECTANDO".
                val shouldReconnect =
                    openedSuccessfully &&
                    requestedProfileId == profile.id &&
                    !Thread.currentThread().isInterrupted

                if (shouldReconnect) {
                    mainHandler.post {
                        connecting = true
                        status = status.copy(
                            connected = false,
                            receiverName = profile.name,
                            connectionTransport = "Bluetooth / NMEA",
                            solution = "RECONECTANDO",
                            nmeaReceiving = false
                        )
                    }
                    mainHandler.postDelayed({
                        if (requestedProfileId == profile.id && socket == null) {
                            connectNmea(profile)
                        }
                    }, 1800L)
                }
            }
        }
    }

    @Synchronized
    fun sendCorrections(data: ByteArray, length: Int = data.size): Boolean {
        val s = socket ?: return false
        if (!s.isConnected || length <= 0) return false
        return runCatching {
            s.outputStream.write(data, 0, length)
            s.outputStream.flush()
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun disconnect() {
        // Desconexión manual: primero anulamos cualquier reconexión automática.
        // Para Bluetooth/NMEA NO interrumpimos a la fuerza el hilo lector; cerrar
        // el socket hace que readLine() termine por sí solo y evita carreras que
        // podían tumbar el proceso Android al pulsar "Desconectar".
        requestedProfileId = null
        autoFallbackProfile = null
        floatStreak = 0

        val oldWatchdog = watchdog
        val oldSocket = socket
        val oldGatt = gatt

        watchdog = null
        socket = null
        gatt = null

        // No interrumpir el watchdog: Thread.sleep() lanzaba InterruptedException.
        // requestedProfileId == null hará que salga limpiamente tras el ciclo actual.
        runCatching { oldSocket?.close() }
        runCatching { oldGatt?.disconnect() }
        runCatching { oldGatt?.close() }

        // El worker saldrá al cerrarse su socket. Quitamos nuestra referencia
        // únicamente después de haber invalidado requestedProfileId.
        worker = null
        usedSatelliteIds.clear()

        connecting = false
        lastError = null
        status = GnssStatus(receiverName = status.receiverName)
    }

    private fun isEmlidLike(name: String): Boolean {
        val n = name.lowercase()
        return "reach" in n || "emlid" in n
    }

    private fun postStatus(newStatus: GnssStatus) {
        mainHandler.post { status = newStatus }
    }
}
