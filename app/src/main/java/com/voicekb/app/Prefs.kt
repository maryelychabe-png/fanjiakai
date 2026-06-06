package com.voicekb.app

import android.content.Context

/** 简单的本地配置：服务器地址、鉴权 token、麦克风灵敏度。 */
object Prefs {
    private const val FILE = "voicekb"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var Context.serverUrl: String
        get() = sp(this).getString("server", "")?.trim().orEmpty()
        set(v) {
            var s = v.trim().trimEnd('/')
            // 没写协议就补 http://，避免被当成 https:443
            if (s.isNotEmpty() && !s.startsWith("http://") && !s.startsWith("https://"))
                s = "http://$s"
            sp(this).edit().putString("server", s).apply()
        }

    var Context.token: String
        get() = sp(this).getString("token", "").orEmpty()
        set(v) { sp(this).edit().putString("token", v).apply() }

    /** 灵敏度：0.2~3.0，越大越容易触发录音（增益 + 降低阈值）。 */
    var Context.sensitivity: Float
        get() = sp(this).getFloat("sens", 1.0f)
        set(v) { sp(this).edit().putFloat("sens", v).apply() }

    var Context.recording: Boolean
        get() = sp(this).getBoolean("recording", false)
        set(v) { sp(this).edit().putBoolean("recording", v).apply() }
}
