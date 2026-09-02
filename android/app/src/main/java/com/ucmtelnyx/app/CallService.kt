package com.ucmtelnyx.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the single SipManager instance for the app's lifetime and keeps a
 * foreground notification up while a call is ringing or active, so Android
 * doesn't tear down the process (and the call's audio) when the app is
 * backgrounded.
 */
class CallService : Service() {

    companion object {
        const val ACTION_ANSWER = "com.ucmtelnyx.app.ANSWER"
        const val ACTION_DECLINE = "com.ucmtelnyx.app.DECLINE"
        const val ACTION_HANGUP = "com.ucmtelnyx.app.HANGUP"
        private const val CHANNEL_ID = "calls"
        private const val NOTIFICATION_ID = 1001
    }

    inner class LocalBinder : Binder() {
        fun sipManager(): SipManager = sip
    }

    private val binder = LocalBinder()
    private lateinit var sip: SipManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        sip = SipManager(applicationContext)
        createNotificationChannel()

        serviceScope.launch {
            sip.callState.collect { state ->
                when (state.phase) {
                    CallPhase.IDLE -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // Drop the "started" state from the call above; the service still lives
                        // on as long as MainActivity keeps it bound.
                        stopSelf()
                    }
                    CallPhase.INCOMING -> {
                        // Self-promote to a started service so a bind-only MainActivity going
                        // away mid-call (e.g. swiped from recents) doesn't kill this service -
                        // startForeground() below satisfies the "call it promptly" requirement
                        // since it runs right after, in this same callback.
                        ContextCompat.startForegroundService(this@CallService, callServiceIntent())
                        startForeground(NOTIFICATION_ID, incomingNotification(state.remoteAddress))
                    }
                    CallPhase.OUTGOING, CallPhase.ACTIVE -> {
                        ContextCompat.startForegroundService(this@CallService, callServiceIntent())
                        startForeground(NOTIFICATION_ID, ongoingNotification(state.remoteAddress))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> sip.answer()
            ACTION_DECLINE -> sip.decline()
            ACTION_HANGUP -> sip.hangup()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        sip.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.call_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun ongoingNotification(remote: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangupIntent = serviceActionIntent(ACTION_HANGUP)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle(getString(R.string.call_notification_ongoing))
            .setContentText(remote)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, "Hang up", hangupIntent)
            .build()
    }

    private fun incomingNotification(remote: String): Notification {
        val fullScreenIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val answerIntent = serviceActionIntent(ACTION_ANSWER)
        val declineIntent = serviceActionIntent(ACTION_DECLINE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(getString(R.string.call_notification_incoming))
            .setContentText(remote)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .addAction(0, "Answer", answerIntent)
            .addAction(0, "Decline", declineIntent)
            .build()
    }

    private fun serviceActionIntent(action: String): PendingIntent {
        val intent = Intent(this, CallService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Convenience for binding from an Activity/Composable. */
fun Context.callServiceIntent(): Intent = Intent(this, CallService::class.java)
