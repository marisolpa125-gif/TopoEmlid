package cr.co.topoemlid

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.ToneGenerator

class FieldSoundManager(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private val fallback = ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)

    private fun playRaw(name: String, fallbackTone: Int, fallbackMs: Int) {
        val resId = context.resources.getIdentifier(name, "raw", context.packageName)
        if (resId == 0) {
            fallback.startTone(fallbackTone, fallbackMs)
            return
        }
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
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
            fallback.startTone(fallbackTone, fallbackMs)
        }
    }

    fun connected() = playRaw("sound_connected", ToneGenerator.TONE_PROP_ACK, 250)
    fun disconnected() = playRaw("sound_disconnected", ToneGenerator.TONE_PROP_NACK, 900)
    fun fix() = playRaw("sound_fix", ToneGenerator.TONE_PROP_BEEP2, 300)
    fun float() = playRaw("sound_float", ToneGenerator.TONE_PROP_BEEP, 260)
    fun autonomous() = playRaw("sound_autonomous", ToneGenerator.TONE_SUP_ERROR, 700)
    fun pointSaved() = playRaw("sound_point_saved", ToneGenerator.TONE_PROP_ACK, 220)

    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
        fallback.release()
    }
}
