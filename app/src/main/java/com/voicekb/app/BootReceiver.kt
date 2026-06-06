package com.voicekb.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.voicekb.app.Prefs.recording

/** 开机后，如果上次是录音状态，则自动把录音服务拉起来。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && context.recording) {
            val i = Intent(context, RecorderService::class.java)
                .setAction(RecorderService.ACTION_START)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
