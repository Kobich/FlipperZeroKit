package dev.flipper.ui.internal.pages.files

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.flipper.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Only active file operations use a connectedDevice foreground service. No idle keep-alive. */
internal class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: TransferManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var updates: Job? = null
    private var lease: Job? = null
    private var detachedResult = false

    companion object {
        private const val CHANNEL = "flipper_file_transfer"
        private const val NOTIFICATION = 16492
        private const val CANCEL = "dev.flipper.action.CANCEL_TRANSFER"
        private var pending: TransferManager? = null
        private var service: TransferService? = null

        fun start(context: Context, manager: TransferManager) {
            check(pending == null || pending === manager) { "Another transfer is already in progress" }
            pending = manager
            try {
                ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
            } catch (error: Exception) {
                pending = null
                throw error
            }
        }
        fun finish(manager: TransferManager) {
            if (pending !== manager) return
            pending = null
            service?.endOperation(manager)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { manager?.cancel(); return START_NOT_STICKY }
        val operation = pending ?: run { stopSelf(); return START_NOT_STICKY }
        if (manager === operation) return START_NOT_STICKY
        manager = operation
        detachedResult = false
        service = this
        val notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Flipper file transfers", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(operation.state.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            // A bounded lease is renewed only while a user-started operation remains active.
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flipper:Transfer").apply { setReferenceCounted(false); acquire(600_000L) }
            lease = scope.launch {
                while (pending === operation) { delay(300_000L); if (pending === operation) wakeLock?.acquire(600_000L) }
            }
            operation.onServiceReady()
            updates = scope.launch {
                var lastUpdate = 0L
                operation.state.collect { state ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (state?.active != false && now - lastUpdate >= 500) {
                        lastUpdate = now
                        val allowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                            this@TransferService, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        if (allowed) try { notifications.notify(NOTIFICATION, notification(state)) } catch (_: SecurityException) { }
                    }
                }
            }
        } catch (error: Exception) { operation.onServiceFailure(error); stopSelf() }
        return START_NOT_STICKY
    }

    private fun notification(state: TransferUi?): android.app.Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val total = state?.total
        val percent = if (total != null && total > 0) ((state.bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.flipper_ic_transfer)
            .setContentTitle(state?.title ?: "Flipper file transfer")
            .setContentText(state?.let { it.bytes.toString()+" / "+(it.total?.toString() ?: "?")+" bytes · "+it.message } ?: "Preparing…")
            .setStyle(NotificationCompat.BigTextStyle().bigText(state?.message ?: "Preparing…"))
            .setOnlyAlertOnce(true).setContentIntent(launch)
        if (state?.active != false) builder.setOngoing(true).setProgress(100, percent, total == null || total == 0L)
        else builder.setOngoing(false).setAutoCancel(true)
        if (state?.canCancel == true) {
            val cancel = PendingIntent.getService(this, 0, Intent(this, TransferService::class.java).setAction(CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Cancel", cancel)
        }
        return builder.build()
    }

    private fun endOperation(operation: TransferManager) {
        if (manager !== operation) return
        updates?.cancel(); lease?.cancel()
        manager = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        val allowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val result = operation.state.value
        if (allowed && result != null && !result.successful) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(result))
                detachedResult = true
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            } catch (_: SecurityException) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        } else ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.launch { delay(500); if (pending == null) stopSelf() }
    }

    override fun onDestroy() {
        manager?.onServiceDestroyed()
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        if (service === this) service = null
        if (pending === manager) pending = null
        if (!detachedResult) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
