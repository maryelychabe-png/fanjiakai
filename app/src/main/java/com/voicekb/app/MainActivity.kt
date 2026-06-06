package com.voicekb.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.voicekb.app.Prefs.recording
import com.voicekb.app.Prefs.sensitivity
import com.voicekb.app.Prefs.serverUrl
import com.voicekb.app.Prefs.token
import com.voicekb.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val http = OkHttpClient.Builder()
        .readTimeout(180, TimeUnit.SECONDS).build()
    private var asking = false
    private var voiceRec: MediaRecorder? = null
    private var voiceFile: File? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) startRecording()
        else toast("需要录音权限才能聆听")
    }

    private val ampReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.getStringExtra(RecorderService.EXTRA_STATE)?.let { b.recStatus.text = it }
            if (i?.hasExtra(RecorderService.EXTRA_AMP) == true)
                b.wave.push(i.getFloatExtra(RecorderService.EXTRA_AMP, 0f))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 回填已存配置
        b.server.setText(serverUrl)
        b.tokenInput.setText(token)
        b.gain.value = sensitivity.coerceIn(0.2f, 3f)
        b.gainVal.text = "%.1f".format(sensitivity)
        refreshRecUi()

        b.startBtn.setOnClickListener { ensurePermsThenStart() }
        b.stopBtn.setOnClickListener { stopRecording() }
        b.sendBtn.setOnClickListener { askText() }
        b.askVoiceBtn.setOnClickListener { toggleVoiceAsk() }

        b.gain.addOnChangeListener { _, v, _ ->
            b.gainVal.text = "%.1f".format(v); sensitivity = v
        }
        // 失焦即保存服务器地址 / token
        b.server.setOnFocusChangeListener { _, f -> if (!f) serverUrl = b.server.text.toString() }
        b.tokenInput.setOnFocusChangeListener { _, f -> if (!f) token = b.tokenInput.text.toString() }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, ampReceiver,
            IntentFilter(RecorderService.BROADCAST_AMP),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(ampReceiver) } catch (_: Exception) {}
        serverUrl = b.server.text.toString(); token = b.tokenInput.text.toString()
    }

    // ---------- 录音服务开关 ----------
    private fun ensurePermsThenStart() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) startRecording() else permLauncher.launch(need.toTypedArray())
    }
    private fun startRecording() {
        if (serverUrl.isEmpty()) { toast("请先填写服务器地址"); return }
        serverUrl = b.server.text.toString()
        val i = Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
        recording = true; refreshRecUi()
    }
    private fun stopRecording() {
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        recording = false; refreshRecUi(); b.wave.clear()
    }
    private fun refreshRecUi() {
        b.recStatus.text = if (recording) "聆听中…" else "待机"
        b.startBtn.isEnabled = !recording
        b.stopBtn.isEnabled = recording
    }

    // ---------- 提问 ----------
    private fun askText() {
        val q = b.q.text.toString().trim()
        if (q.isEmpty()) { toast("先输入问题"); return }
        sendAsk(textQuery = q, audio = null)
    }
    private fun toggleVoiceAsk() {
        if (voiceRec == null) startVoiceAsk() else stopVoiceAskAndSend()
    }
    private fun startVoiceAsk() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)); return
        }
        val f = File(cacheDir, "ask.m4a")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setOutputFile(f.absolutePath)
        rec.prepare(); rec.start()
        voiceRec = rec; voiceFile = f
        b.askVoiceBtn.text = "● 松手发送"
        b.recStatus.text = "请说出问题…"
    }
    private fun stopVoiceAskAndSend() {
        try { voiceRec?.stop() } catch (_: Exception) {}
        voiceRec?.release(); voiceRec = null
        b.askVoiceBtn.text = "🎤 语音"
        b.recStatus.text = if (recording) "聆听中…" else "待机"
        voiceFile?.let { if (it.exists()) sendAsk(textQuery = null, audio = it) }
    }

    private fun sendAsk(textQuery: String?, audio: File?) {
        if (asking) return
        val base = serverUrl
        if (base.isEmpty()) { toast("请先填写服务器地址"); return }
        asking = true
        b.answer.text = "查询中…"; b.answerCard.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val bodyB = MultipartBody.Builder().setType(MultipartBody.FORM)
                    if (textQuery != null) bodyB.addFormDataPart("query", textQuery)
                    if (audio != null) bodyB.addFormDataPart("audio", "q.m4a",
                        audio.asRequestBody("audio/mp4".toMediaType()))
                    bodyB.addFormDataPart("top_k", "5")
                    val req = Request.Builder().url("$base/ask")
                        .apply { if (token.isNotEmpty()) addHeader("X-Token", token) }
                        .post(bodyB.build()).build()
                    http.newCall(req).execute().use { it.body?.string() ?: "" }
                } catch (e: Exception) { "ERR:${e.message}" }
            }
            asking = false
            renderAnswer(result)
        }
    }

    private fun renderAnswer(json: String) {
        if (json.startsWith("ERR:")) { b.answer.text = "请求失败：" + json.drop(4); return }
        try {
            val o = JSONObject(json)
            b.q.setText(o.optString("query", b.q.text.toString()))
            val sb = StringBuilder(o.optString("answer", ""))
            val hits = o.optJSONArray("hits")
            if (hits != null) for (i in 0 until hits.length()) {
                val h = hits.getJSONObject(i)
                sb.append("\n\n[").append(h.optDouble("score")).append("] ")
                    .append(h.optString("title").ifEmpty { h.optString("source") })
                    .append("\n").append(h.optString("text").take(140))
            }
            b.answer.text = sb.toString()
        } catch (e: Exception) { b.answer.text = json }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
