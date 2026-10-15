package com.sokolovskiy.party.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.sokolovskiy.party.PartyHost
import java.io.File

/**
 * The on-screen part of self-update: a check on launch (quiet unless there is a new
 * version), a manual check from the host menu, the "allow installs" detour to
 * settings, and a download progress dialog.
 */
class UpdateUi(private val activity: Activity) {

    /** Newest release seen that is newer than what is installed. */
    @Volatile var available: Release? = null
        private set

    private var checking = false
    private var waitingForPermission: Release? = null
    private var progressDialog: AlertDialog? = null
    /** The downloaded APK, kept for the fallback install if the session gets aborted. */
    private var apk: File? = null
    @Volatile private var cancelled = false

    private val dialogTheme = android.R.style.Theme_DeviceDefault_Dialog_Alert

    fun start() {
        UpdateReceiver.clearNotification(activity)
        UpdateReceiver.listener = { r -> activity.runOnUiThread { onInstallResult(r) } }
        check(manual = false)
    }

    fun stop() {
        UpdateReceiver.listener = null
        progressDialog?.dismiss()
    }

    /** Called from onResume: continue after the user came back from the permission screen. */
    fun resume() {
        val r = waitingForPermission ?: return
        waitingForPermission = null
        if (activity.packageManager.canRequestPackageInstalls()) download(r)
        else toast("Без дозволу на встановлення оновитись не вийде")
    }

    fun check(manual: Boolean) {
        if (checking) return
        checking = true
        if (manual) toast("Перевіряю оновлення…")
        Thread {
            val result = runCatching { Updater.fetchLatest() }
            activity.runOnUiThread {
                checking = false
                if (activity.isFinishing) return@runOnUiThread
                val release = result.getOrNull()
                when {
                    result.isFailure -> if (manual) toast("Не вдалося перевірити: немає зв'язку з GitHub")
                    release != null && release.number > Updater.installedVersion(activity) -> {
                        available = release
                        offer(release)
                    }
                    else -> if (manual) toast("У тебе остання версія (v${Updater.installedName(activity)})")
                }
            }
        }.start()
    }

    private fun offer(r: Release) {
        val msg = buildString {
            append("Встановлена: v${Updater.installedName(activity)}\n")
            append("Нова: v${r.name}")
            if (r.size > 0) append(" · ${"%.1f".format(r.size / 1_048_576.0)} МБ")
            if (r.notes.isNotBlank()) append("\n\nЩо нового:\n${r.notes}")
            if (gameRunning()) append("\n\n⚠️ Поточна гра перерветься — гравці зможуть зайти знову.")
        }
        AlertDialog.Builder(activity, dialogTheme)
            .setTitle("Доступне оновлення")
            .setMessage(msg)
            .setPositiveButton("Оновити") { _, _ -> beginInstall(r) }
            .setNegativeButton("Пізніше", null)
            .show()
    }

    private fun beginInstall(r: Release) {
        if (activity.packageManager.canRequestPackageInstalls()) { download(r); return }
        AlertDialog.Builder(activity, dialogTheme)
            .setTitle("Потрібен дозвіл")
            .setMessage("Android дозволяє оновлюватись не з Play Store лише з дозволу.\n\n" +
                "На наступному екрані увімкни «Дозволити з цього джерела» і повернись назад. Це потрібно зробити один раз.")
            .setPositiveButton("Відкрити налаштування") { _, _ ->
                waitingForPermission = r
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                runCatching { activity.startActivity(intent) }.onFailure {
                    waitingForPermission = null
                    toast("Не вдалося відкрити налаштування")
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun download(r: Release) {
        cancelled = false
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val label = TextView(activity).apply { text = "Завантажую v${r.name}…"; setPadding(0, 0, 0, pad / 2) }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(label); addView(bar)
        }
        progressDialog = AlertDialog.Builder(activity, dialogTheme)
            .setTitle("Оновлення")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Скасувати") { _, _ -> cancelled = true }
            .show()

        Thread {
            val res = runCatching {
                val file = Updater.download(activity, r, { cancelled }) { p ->
                    activity.runOnUiThread {
                        if (p >= 0) { bar.isIndeterminate = false; bar.progress = (p * 100).toInt(); label.text = "Завантажую v${r.name}… ${(p * 100).toInt()}%" }
                    }
                }
                activity.runOnUiThread { apk = file; label.text = "Встановлюю… застосунок перезапуститься"; bar.isIndeterminate = true }
                Updater.install(activity, file)
            }
            activity.runOnUiThread {
                if (res.isFailure) {
                    progressDialog?.dismiss()
                    if (!cancelled) toast("Не вдалося оновити: ${res.exceptionOrNull()?.message ?: "помилка"}")
                }
                // On success the receiver reports back (confirmation screen or done).
            }
        }.start()
    }

    private fun onInstallResult(r: UpdateReceiver.Result) {
        when (r) {
            is UpdateReceiver.Result.NeedsConfirmation -> {
                progressDialog?.dismiss()
                runCatching { activity.startActivity(r.confirm) }.onFailure { offerSystemInstall(null) }
            }
            UpdateReceiver.Result.Success -> { /* the process is about to be replaced */ }
            is UpdateReceiver.Result.Cancelled -> { progressDialog?.dismiss(); offerSystemInstall(r.message) }
            is UpdateReceiver.Result.Failed -> { progressDialog?.dismiss(); toast(r.message) }
        }
    }

    /**
     * The session install was aborted. That is either the user's "Cancel" or a vendor
     * installer refusing session installs, so offer the plain system installer instead.
     */
    private fun offerSystemInstall(reason: String?) {
        val file = apk?.takeIf { it.exists() }
        if (file == null || activity.isFinishing) { toast("Оновлення скасовано"); return }
        val msg = "Встановлення не завершилось" +
            (reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            ".\n\nЯкщо ти не натискав «Скасувати», спробуй встановити через системний інсталятор."
        AlertDialog.Builder(activity, dialogTheme)
            .setTitle("Оновлення скасовано")
            .setMessage(msg)
            .setPositiveButton("Встановити") { _, _ ->
                runCatching { Updater.installViaSystemUi(activity, file) }
                    .onFailure { toast("Не вдалося відкрити інсталятор: ${it.message}") }
            }
            .setNegativeButton("Закрити", null)
            .show()
    }

    private fun gameRunning(): Boolean =
        // A hint only (read without the hub lock): anyone besides the host is connected.
        runCatching { PartyHost.server?.room?.players?.values?.count { it.connected } ?: 0 }.getOrDefault(0) >= 2

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()
}
