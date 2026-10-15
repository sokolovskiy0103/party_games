package com.sokolovskiy.party.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.sokolovskiy.party.MainActivity

/**
 * Install status from [Updater.install], and MY_PACKAGE_REPLACED after a successful
 * update (the old process is killed by the install, so a notification invites the
 * user back into the new version).
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STATUS -> onStatus(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> notifyUpdated(context)
        }
    }

    private fun onStatus(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) { listener?.invoke(Result.Failed("Система не показала вікно встановлення")); return }
                // Prefer launching from the on-screen activity (same task): a confirmation
                // started as a separate task from a receiver gets aborted on some phones.
                listener?.let { it(Result.NeedsConfirmation(confirm)); return }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> listener?.invoke(Result.Success)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                listener?.invoke(Result.Cancelled(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                listener?.invoke(Result.Failed(describe(status, msg)))
            }
        }
    }

    private fun describe(status: Int, msg: String?): String = (when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Конфлікт із встановленою версією (інший підпис?)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Версія несумісна з цим телефоном"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Пошкоджений файл оновлення"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Недостатньо місця"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Встановлення заблоковане системою"
        else -> "Помилка встановлення"
    }) + (msg?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")

    private fun notifyUpdated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Оновлення", NotificationManager.IMPORTANCE_DEFAULT))
        val name = runCatching { Updater.installedName(context) }.getOrDefault("")
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Ігри оновлено до v$name")
            .setContentText("Натисни, щоб відкрити")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    sealed interface Result {
        data class NeedsConfirmation(val confirm: Intent) : Result
        data object Success : Result
        /** Aborted — by the user, or by the system/vendor installer ([message] may say which). */
        data class Cancelled(val message: String?) : Result
        data class Failed(val message: String) : Result
    }

    companion object {
        const val ACTION_STATUS = "com.sokolovskiy.party.UPDATE_STATUS"
        const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 7

        /** Set by the on-screen updater; called on the main thread. */
        @Volatile var listener: ((Result) -> Unit)? = null

        fun clearNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
