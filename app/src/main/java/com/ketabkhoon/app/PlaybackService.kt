package com.ketabkhoon.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the app alive while a book is being read, and shows the
 * play / pause / next / previous controls on the lock screen.
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL = "playback"
        const val NOTIF_ID = 41
        const val ACTION_CMD = "com.ketabkhoon.app.CMD"

        @Volatile
        var listener: ((String) -> Unit)? = null
    }

    private var session: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "پخش کتاب", NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSession(this, "ketabkhoon").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    listener?.invoke("play")
                }

                override fun onPause() {
                    listener?.invoke("pause")
                }

                override fun onSkipToNext() {
                    listener?.invoke("next")
                }

                override fun onSkipToPrevious() {
                    listener?.invoke("prev")
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playing = intent?.getBooleanExtra("playing", false) ?: false
        val title = intent?.getStringExtra("title") ?: "کتابخون"
        val sub = intent?.getStringExtra("sub") ?: ""
        try {
            show(playing, title, sub)
        } catch (t: Throwable) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun action(cmd: String, label: String, icon: Int, code: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            this, code,
            Intent(this, ActionReceiver::class.java).setAction(ACTION_CMD).putExtra("cmd", cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun show(playing: Boolean, title: String, sub: String) {
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
                .build()
        )
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f
                )
                .build()
        )

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.sessionToken?.let { style.setMediaSession(it) }

        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(title)
            .setContentText(sub)
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action("prev", "قبلی", R.drawable.ic_pb_prev, 1))
            .addAction(
                if (playing) action("pause", "توقف", R.drawable.ic_pb_pause, 2)
                else action("play", "پخش", R.drawable.ic_pb_play, 2)
            )
            .addAction(action("next", "بعدی", R.drawable.ic_pb_next, 3))
            .setStyle(style)
            .build()

        if (playing) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            if (wake == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ketabkhoon:playback")
            }
            wake?.let { if (!it.isHeld) it.acquire(6 * 60 * 60 * 1000L) }
        } else {
            stopForeground(Service.STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
            wake?.let { if (it.isHeld) it.release() }
        }
    }

    override fun onDestroy() {
        try {
            wake?.let { if (it.isHeld) it.release() }
            session?.release()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        } catch (t: Throwable) {
        }
        super.onDestroy()
    }
}
