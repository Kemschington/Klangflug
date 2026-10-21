package de.klangflug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

object AudioBridge {
    @Volatile var bytes = ByteArray(256)
    @Volatile var error = ""
}

class CaptureService : Service() {
    companion object {
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }

    private var rec: AudioRecord? = null
    private var proj: MediaProjection? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopCapture()
        AudioBridge.error = ""
        val system = intent?.getBooleanExtra(EXTRA_SYSTEM, true) ?: true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("klangflug", "Klangflug", NotificationManager.IMPORTANCE_LOW)
        )
        val note = Notification.Builder(this, "klangflug")
            .setContentTitle("Klangflug")
            .setContentText("Hört zu …")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        val type = if (system) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(1, note, type)
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val min = AudioRecord.getMinBufferSize(
                44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val size = maxOf(min, 8192)
            if (system) {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val data = intent!!.getParcelableExtra<Intent>(EXTRA_DATA)!!
                val p = mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data)
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { stopSelf() }
                }, null)
                proj = p
                val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                rec = AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(size)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
            } else {
                rec = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(size)
                    .build()
            }
            if (rec?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord nicht bereit")
        } catch (e: Exception) {
            AudioBridge.error = "Aufnahme nicht möglich: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        worker = Thread { loop() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun loop() {
        val r = rec ?: return
        val n = 512
        val pcm = ShortArray(n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        val sm = DoubleArray(n / 2)
        try { r.startRecording() } catch (e: Exception) {
            AudioBridge.error = "Start fehlgeschlagen: ${e.message}"
            return
        }
        while (running) {
            var got = 0
            while (got < n && running) {
                val k = r.read(pcm, got, n - got)
                if (k <= 0) break
                got += k
            }
            if (got < n) {
                if (running) AudioBridge.error = "Kein Audiosignal erhalten."
                break
            }
            for (i in 0 until n) { re[i] = pcm[i] / 32768.0 * win[i]; im[i] = 0.0 }
            Fft.fft(re, im)
            val out = ByteArray(n / 2)
            for (i in 0 until n / 2) {
                val mag = sqrt(re[i] * re[i] + im[i] * im[i]) / n * 4
                sm[i] = 0.72 * sm[i] + 0.28 * mag
                val db = 20 * log10(sm[i] + 1e-9)
                out[i] = ((db + 100) / 70 * 255).coerceIn(0.0, 255.0).toInt().toByte()
            }
            AudioBridge.bytes = out
        }
    }

    private fun stopCapture() {
        running = false
        try { rec?.stop() } catch (e: Exception) { }
        rec?.release(); rec = null
        worker?.join(500); worker = null
        proj?.stop(); proj = null
    }

    override fun onDestroy() {
        stopCapture()
        AudioBridge.bytes = ByteArray(256)
        super.onDestroy()
    }
}
