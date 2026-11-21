package com.ketabkhoon.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the notification buttons (play, pause, next, previous). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlaybackService.listener?.invoke(intent.getStringExtra("cmd") ?: "toggle")
    }
}
