package cr.co.topoemlid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var socket: BluetoothSocket? = null
    private var worker: Thread? = null

    var status by mutableStateOf(GnssStatus())
        private set

    var connecting by mutableStateOf(false)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    @SuppressLint("MissingPermission")
    fun connect(profile: ReceiverProfile) {
        disconnect()
        val device = adapter?.bondedDevices?.firstOrNull { it.address == profile.address }
        if (device == null) {
            lastError = "El receptor debe estar emparejado primero en Bluetooth de Android."
            return
        }

        connecting = true
        lastError = null

        worker = thread(name = "reach-nmea") {
            try {
                val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                val s = device.createRfcommSocketToServiceRecord(spp)
                adapter.cancelDiscovery()
                s.connect()
                socket = s

                postStatus(
                    status.copy(
                        connected = true,
                        receiverName = profile.name,
                        solution = "ESPERANDO NMEA"
                    )
                )
                mainHandler.post { connecting = false }

                val reader = BufferedReader(InputStreamReader(s.inputStream))
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
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
                                solution = solution,
                                satellites = gga.satellites,
                                latitude = gga.latitude,
                                longitude = gga.longitude,
                                ellipsoidalHeightM = gga.ellipsoidalHeightM
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
                        postStatus(
                            status.copy(
                                pdop = gsa.pdop,
                                positioningMode = gsa.mode
                            )
                        )
                    }

                    NmeaParser.parseGsv(line)?.let { gsv ->
                        val avg = gsv.snrValues.takeIf { it.isNotEmpty() }?.average()
                        postStatus(
                            status.copy(
                                satellitesInView = gsv.satellitesInView ?: status.satellitesInView,
                                signalNoiseAvgDbHz = avg ?: status.signalNoiseAvgDbHz
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    connecting = false
                    lastError = e.message ?: "No se pudo conectar con el receptor."
                    status = status.copy(connected = false, solution = "SIN SEÑAL")
                }
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
        }
    }

    fun disconnect() {
        worker?.interrupt()
        worker = null
        runCatching { socket?.close() }
        socket = null
        connecting = false
        status = GnssStatus(receiverName = status.receiverName)
    }

    private fun postStatus(newStatus: GnssStatus) {
        mainHandler.post { status = newStatus }
    }
}
