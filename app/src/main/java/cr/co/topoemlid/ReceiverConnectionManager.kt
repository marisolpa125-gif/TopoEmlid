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
    private var autoFallbackProfile: ReceiverProfile? = null
    private val usedSatelliteIds = linkedSetOf<String>()

    var status by mutableStateOf(GnssStatus())
        private set

    var connecting by mutableStateOf(false)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    @SuppressLint("MissingPermission")
    fun connect(profile: ReceiverProfile) {
        disconnect()
        connecting = true
        lastError = null

        when (profile.preferredMode) {
            ReceiverConnectionMode.AUTO -> {
                if (isEmlidLike(profile.name)) {
                    autoFallbackProfile = profile
                    connectBle(profile, allowFallback = true)
                } else {
                    connectNmea(profile)
                }
            }
            ReceiverConnectionMode.BLE -> connectBle(profile, allowFallback = false)
            ReceiverConnectionMode.BLUETOOTH_NMEA -> connectNmea(profile)
        }
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
            try {
                // Give Android's Bluetooth stack a short moment to release any
                // previous RFCOMM session before opening a new one.
                Thread.sleep(450)

                val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                val advertised = device.uuids?.map { it.uuid }.orEmpty()
                val candidates = (listOf(spp) + advertised).distinct()
                adapter?.cancelDiscovery()

                var connectedSocket: BluetoothSocket? = null
                var lastConnectError: Throwable? = null

                // Some rugged Android devices keep the RFCOMM channel busy for
                // a fraction of a second after disconnecting. Retry the complete
                // SPP sequence instead of failing after the first pass.
                repeat(3) { round ->
                    if (connectedSocket != null || Thread.currentThread().isInterrupted) return@repeat

                    for (uuid in candidates) {
                        if (connectedSocket != null) break

                        val attempts = listOf<(UUID) -> BluetoothSocket>(
                            { u -> device.createRfcommSocketToServiceRecord(u) },
                            { u -> device.createInsecureRfcommSocketToServiceRecord(u) }
                        )

                        for (createSocket in attempts) {
                            if (connectedSocket != null || Thread.currentThread().isInterrupted) break
                            val candidate = runCatching { createSocket(uuid) }.getOrNull() ?: continue
                            try {
                                adapter?.cancelDiscovery()
                                candidate.connect()
                                connectedSocket = candidate
                                break
                            } catch (t: Throwable) {
                                lastConnectError = t
                                runCatching { candidate.close() }
                            }
                        }
                    }

                    if (connectedSocket == null && round < 2) {
                        Thread.sleep(700L * (round + 1))
                    }
                }

                val s = connectedSocket
                    ?: throw IllegalStateException(
                        "No se pudo abrir el canal Bluetooth/NMEA con ${profile.name} después de varios intentos. " +
                            "Compruebe que el receptor siga emparejado, que ninguna otra app esté usando su Bluetooth y que NMEA por Bluetooth esté activo.",
                        lastConnectError
                    )
                ownedSocket = s
                socket = s

                postStatus(
                    status.copy(
                        connected = true,
                        receiverName = profile.name,
                        connectionTransport = "Bluetooth / NMEA",
                        solution = "ESPERANDO NMEA"
                    )
                )
                mainHandler.post { connecting = false }

                val reader = BufferedReader(InputStreamReader(s.inputStream))
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
                            4 -> "FIX"
                            5 -> "FLOAT"
                            2 -> "DGPS"
                            1 -> "SINGLE"
                            else -> "SIN FIX"
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
                mainHandler.post {
                    connecting = false
                    lastError = when {
                        e.message?.contains("read failed", ignoreCase = true) == true ->
                            "Bluetooth se abrió, pero el receptor cerró el canal o no entregó datos NMEA. Active la salida NMEA por Bluetooth en el receptor y vuelva a intentar."
                        else -> e.message ?: "No se pudo conectar con el receptor."
                    }
                    status = status.copy(connected = false, solution = "SIN SEÑAL")
                }
            } finally {
                val mine = ownedSocket
                if (mine != null) {
                    runCatching { mine.close() }
                    if (socket === mine) socket = null
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

    fun disconnect() {
        autoFallbackProfile = null
        usedSatelliteIds.clear()

        val oldWorker = worker
        val oldSocket = socket
        val oldGatt = gatt

        worker = null
        socket = null
        gatt = null

        oldWorker?.interrupt()
        runCatching { oldSocket?.close() }
        runCatching { oldGatt?.disconnect() }
        runCatching { oldGatt?.close() }

        connecting = false
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
