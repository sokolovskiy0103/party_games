package com.sokolovskiy.party.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases: check the latest release, download its APK and
 * hand it to [PackageInstaller]. Every build is signed with the same key, so this is
 * an in-place update that keeps the app's data.
 *
 * On Android 12+ the update goes through without a confirmation dialog once this app
 * is its own "installer of record" — i.e. from the second self-update on.
 */
object Updater {

    fun installedVersion(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun installedName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Blocking. The newest release, or null when GitHub has none with an APK. */
    @Throws(IOException::class)
    fun fetchLatest(): Release? {
        val c = open(Release.LATEST_URL)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw IOException("GitHub: HTTP ${c.responseCode}")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return Release.parse(JSONObject(text))
        } finally {
            c.disconnect()
        }
    }

    /** Blocking. Downloads the APK to the cache; [progress] gets 0..1 (or -1 if size unknown). */
    @Throws(IOException::class)
    fun download(context: Context, release: Release, cancelled: () -> Boolean, progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "PartyGames-${release.number}.apk")
        val c = open(release.apkUrl) // GitHub redirects to its CDN; HttpURLConnection follows https→https
        try {
            if (c.responseCode != 200) throw IOException("Завантаження: HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.size
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; progress(if (total > 0) done.toFloat() / total else -1f) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Файл завантажився не повністю")
            return file
        } catch (e: IOException) {
            file.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Hands the APK to the system installer; the result arrives in [UpdateReceiver]. */
    @Throws(IOException::class)
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_STATUS)
                // Must be mutable: the installer fills in the status extras.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw if (e is IOException) e else IOException(e.message ?: e.toString(), e)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PartyGames-Android")
        }
}
