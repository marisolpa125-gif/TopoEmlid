package cr.co.topoemlid

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri

enum class FieldSoundEvent(
    val key: String,
    val label: String,
    val rawName: String,
    val fallbackTone: Int,
    val fallbackMs: Int
) {
    CONNECTED("connected", "Receptor conectado", "sound_connected", ToneGenerator.TONE_PROP_ACK, 250),
    DISCONNECTED("disconnected", "Receptor desconectado", "sound_disconnected", ToneGenerator.TONE_PROP_NACK, 900),
    FIX("fix", "Solución FIX", "sound_fix", ToneGenerator.TONE_PROP_BEEP2, 300),
    FLOAT("float", "Solución FLOAT", "sound_float", ToneGenerator.TONE_PROP_BEEP, 260),
    AUTONOMOUS("autonomous", "SINGLE / DGPS / Sin FIX", "sound_autonomous", ToneGenerator.TONE_SUP_ERROR, 700),
    POINT_SAVED("point_saved", "Punto guardado", "sound_point_saved", ToneGenerator.TONE_PROP_ACK, 220)
}

class FieldSoundManager(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private val fallback = ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
    private val prefs = context.getSharedPreferences("field_sound_settings", Context.MODE_PRIVATE)

    fun customSoundUri(event: FieldSoundEvent): String? =
        prefs.getString("uri_${event.key}", null)

    fun customSoundName(event: FieldSoundEvent): String? =
        prefs.getString("name_${event.key}", null)

    fun setCustomSound(event: FieldSoundEvent, uri: Uri, displayName: String?) {
        prefs.edit()
            .putString("uri_${event.key}", uri.toString())
            .putString("name_${event.key}", displayName ?: uri.lastPathSegment ?: "Sonido personalizado")
            .apply()
    }

    fun clearCustomSound(event: FieldSoundEvent) {
        prefs.edit()
            .remove("uri_${event.key}")
            .remove("name_${event.key}")
            .apply()
    }

    private fun playEvent(event: FieldSoundEvent) {
        val custom = customSoundUri(event)
        if (!custom.isNullOrBlank()) {
            val played = runCatching {
                stopCurrent()
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context, Uri.parse(custom))
                    setVolume(1.0f, 1.0f)
                    setOnCompletionListener {
                        it.release()
                        if (mediaPlayer === it) mediaPlayer = null
                    }
                    prepare()
                    start()
                }
                mediaPlayer = player
                true
            }.getOrDefault(false)

            if (played) return
        }

        playRaw(event)
    }

    private fun playRaw(event: FieldSoundEvent) {
        val resId = context.resources.getIdentifier(event.rawName, "raw", context.packageName)
        if (resId == 0) {
            fallback.startTone(event.fallbackTone, event.fallbackMs)
            return
        }
        runCatching {
            stopCurrent()
            mediaPlayer = MediaPlayer.create(context, resId)?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setVolume(1.0f, 1.0f)
                setOnCompletionListener {
                    it.release()
                    if (mediaPlayer === it) mediaPlayer = null
                }
                start()
            }
        }.onFailure {
            fallback.startTone(event.fallbackTone, event.fallbackMs)
        }
    }

    private fun stopCurrent() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    fun play(event: FieldSoundEvent) = playEvent(event)

    fun connected() = playEvent(FieldSoundEvent.CONNECTED)
    fun disconnected() = playEvent(FieldSoundEvent.DISCONNECTED)
    fun fix() = playEvent(FieldSoundEvent.FIX)
    fun float() = playEvent(FieldSoundEvent.FLOAT)
    fun autonomous() = playEvent(FieldSoundEvent.AUTONOMOUS)
    fun pointSaved() = playEvent(FieldSoundEvent.POINT_SAVED)

    fun release() {
        stopCurrent()
        fallback.release()
    }
}
