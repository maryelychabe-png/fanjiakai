package com.voicekb.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voicekb.app.Prefs.sensitivity
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

/**
 * 前台常驻服务：全天候录音 → 静音切段 → 写 WAV 进队列 → 联网上传。
 * 锁屏/后台也持续运行（前台服务 + WakeLock）。
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val BROADCAST_AMP = "com.voicekb.app.AMP"     // 给波形界面发振幅
        const val EXTRA_AMP = "amp"
        const val EXTRA_STATE = "state"
        private const val TAG = "VoiceKB/Rec"
        private const val CH_ID = "voicekb_rec"
        private const val NOTI_ID = 1

        const val SAMPLE_RATE = 16000
        private const val FRAME = 1600                       // 100ms @16k
        private const val MAX_SEG_SEC = 30                   // 单段最长
        private const val MIN_SPEECH_SEC = 0.6               // 太短不入库
        private const val SILENCE_TAIL_SEC = 0.8             // 静音多久算一句结束
        private const val PREROLL_SEC = 0.3                  // 句首预滚，避免吃字
    }

    private var job: Job? = null
    private var uploaderStarted = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var queueDir: File

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        queueDir = File(filesDir, "queue").apply { mkdirs() }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        startForeground(NOTI_ID, buildNotification("正在聆听…"))
        acquireWake()
        if (job == null || job?.isActive != true) {
            job = scope.launch { recordLoop() }
        }
        // 后台周期性重试上传队列（防止之前断网堆积），只起一个
        if (!uploaderStarted) {
            uploaderStarted = true
            scope.launch {
                while (isActive) {
                    Uploader.drainQueue(this@RecorderService, queueDir)
                    delay(30_000)
                }
            }
        }
        return START_STICKY   // 被系统杀掉后尽量重启
    }

    private suspend fun recordLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, FRAME * 2 * 4)
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: SecurityException) {
            Log.e(TAG, "无录音权限", e); stopSelf(); return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败"); stopSelf(); return
        }
        recorder.startRecording()
        broadcastState("聆听中…")

        val frame = ShortArray(FRAME)
        val seg = ByteArrayOutputStream()
        val preroll = ArrayDeque<ByteArray>()           // 预滚环形缓冲
        val prerollFrames = (PREROLL_SEC * SAMPLE_RATE / FRAME).toInt().coerceAtLeast(1)
        var inSpeech = false
        var silenceSec = 0.0
        var segSec = 0.0
        val frameSec = FRAME.toDouble() / SAMPLE_RATE

        try {
            while (coroutineContext.isActive) {
                val n = recorder.read(frame, 0, FRAME)
                if (n <= 0) continue
                val rms = rms(frame, n)
                broadcastAmp((rms / 4000f).coerceIn(0f, 1f))

                val gain = sensitivity.coerceIn(0.2f, 3f)
                val threshold = 1200f / gain                // 灵敏度越高，阈值越低
                val bytes = toBytes(frame, n)

                if (rms > threshold) {                      // —— 有人声 ——
                    if (!inSpeech) {                        // 句子开始：先灌入预滚
                        inSpeech = true; silenceSec = 0.0; segSec = 0.0
                        preroll.forEach { seg.write(it) }
                    }
                    seg.write(bytes); silenceSec = 0.0; segSec += frameSec
                } else {                                    // —— 静音 ——
                    if (inSpeech) {
                        seg.write(bytes); segSec += frameSec; silenceSec += frameSec
                        if (silenceSec >= SILENCE_TAIL_SEC) {   // 一句话结束
                            finalizeSegment(seg, segSec - silenceSec)
                            seg.reset(); inSpeech = false; silenceSec = 0.0; segSec = 0.0
                        }
                    } else {                                // 维护预滚缓冲
                        preroll.addLast(bytes)
                        while (preroll.size > prerollFrames) preroll.removeFirst()
                    }
                }
                if (inSpeech && segSec >= MAX_SEG_SEC) {     // 太长强制切段
                    finalizeSegment(seg, segSec); seg.reset()
                    inSpeech = false; silenceSec = 0.0; segSec = 0.0
                }
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            if (inSpeech && segSec >= MIN_SPEECH_SEC) finalizeSegment(seg, segSec)
        }
    }

    /** 把一段语音写成 WAV 落到队列，并触发上传。 */
    private fun finalizeSegment(seg: ByteArrayOutputStream, speechSec: Double) {
        if (speechSec < MIN_SPEECH_SEC) return
        val pcm = seg.toByteArray()
        val name = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date()) + ".wav"
        val f = File(queueDir, name)
        try {
            WavWriter.write(f, pcm, SAMPLE_RATE)
            Log.i(TAG, "切出一段 ${"%.1f".format(speechSec)}s -> $name")
            scope.launch { Uploader.drainQueue(this@RecorderService, queueDir) }
        } catch (e: Exception) { Log.e(TAG, "写WAV失败", e) }
    }

    // ---------- 工具 ----------
    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return sqrt(sum / n)
    }
    private fun toBytes(buf: ShortArray, n: Int): ByteArray {
        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putShort(buf[i])
        return bb.array()
    }
    private fun broadcastAmp(amp: Float) {
        sendBroadcast(Intent(BROADCAST_AMP).setPackage(packageName)
            .putExtra(EXTRA_AMP, amp))
    }
    private fun broadcastState(s: String) {
        sendBroadcast(Intent(BROADCAST_AMP).setPackage(packageName)
            .putExtra(EXTRA_STATE, s))
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceKB:rec").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_ID, "录音服务", NotificationManager.IMPORTANCE_LOW)
        ch.description = "全天候语音记录"
        nm.createNotificationChannel(ch)
    }
    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("语音知识库")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        broadcastState("已停止")
        job?.cancel(); scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
