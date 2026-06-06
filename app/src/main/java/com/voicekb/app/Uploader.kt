package com.voicekb.app

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.voicekb.app.Prefs.serverUrl
import com.voicekb.app.Prefs.token
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 负责把录音队列里的 WAV 文件传回电脑。
 * 上传成功删除文件；失败保留，等下次联网重试。
 */
object Uploader {
    private const val TAG = "VoiceKB/Uploader"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)   // 识别可能较慢
        .build()

    fun isOnline(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 把队列目录里所有 wav 依次上传。返回成功上传的数量。 */
    fun drainQueue(c: Context, queueDir: File): Int {
        if (!isOnline(c)) return 0
        val base = c.serverUrl
        if (base.isEmpty()) return 0
        val files = queueDir.listFiles { f -> f.name.endsWith(".wav") }
            ?.sortedBy { it.name } ?: return 0
        var ok = 0
        for (f in files) {
            if (uploadIngest(c, base, f)) { f.delete(); ok++ }
            else break   // 一个失败（多半断网/服务器没开），停下等下次
        }
        return ok
    }

    private fun uploadIngest(c: Context, base: String, f: File): Boolean {
        return try {
            // 文件名里编码了录制时间：rec_yyyyMMdd_HHmmss.wav
            val recordedAt = fileNameToIso(f.name)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("audio", f.name,
                    f.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("recorded_at", recordedAt)
                .build()
            val req = Request.Builder().url("$base/ingest")
                .apply { if (c.token.isNotEmpty()) addHeader("X-Token", c.token) }
                .post(body).build()
            client.newCall(req).execute().use { resp ->
                Log.i(TAG, "ingest ${f.name} -> ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload failed ${f.name}: ${e.message}")
            false
        }
    }

    private fun fileNameToIso(name: String): String {
        // rec_20260606_091500.wav -> 2026-06-06T09:15:00
        return try {
            val s = name.removePrefix("rec_").removeSuffix(".wav")
            val (d, t) = s.split("_")
            "${d.substring(0,4)}-${d.substring(4,6)}-${d.substring(6,8)}" +
                "T${t.substring(0,2)}:${t.substring(2,4)}:${t.substring(4,6)}"
        } catch (e: Exception) { "" }
    }
}
