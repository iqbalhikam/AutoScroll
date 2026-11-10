package id.iqbal.tiktokauto

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String
)

class UpdateException(message: String) : Exception(message)

object UpdateManager {

    fun currentVersionCode(context: Context): Long {
        val info = packageInfo(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun currentVersionName(context: Context): String =
        packageInfo(context).versionName ?: "?"

    private fun packageInfo(context: Context): android.content.pm.PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }

    fun fetch(manifestUrl: String): UpdateInfo {
        val connection = open(manifestUrl)
        val body = try {
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw UpdateException("Format manifest tidak valid")
        }

        val code = json.optLong("versionCode", 0L)
        val name = json.optString("versionName").ifBlank { code.toString() }
        val apk = json.optString("apkUrl").trim()
        val sha = json.optString("sha256").trim().lowercase()

        if (code <= 0 || apk.isBlank() || sha.length != 64) {
            throw UpdateException("Manifest tidak lengkap")
        }

        return UpdateInfo(code, name, resolve(manifestUrl, apk), sha, json.optString("notes"))
    }

    fun download(info: UpdateInfo, dest: File, onProgress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        val connection = open(info.apkUrl)
        val total = connection.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")
        var lastPct = -1

        try {
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val pct = (written * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            dest.delete()
            throw UpdateException("Checksum APK tidak cocok")
        }
    }

    fun install(context: Context, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            throw UpdateException("Izin pemasangan belum diberikan")
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)

        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, InstallResultReceiver::class.java)
                    .putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw if (e is UpdateException) e else UpdateException(
                e.message ?: "Gagal memulai pemasangan"
            )
        }
    }

    private fun open(url: String): HttpURLConnection {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw UpdateException("URL harus HTTPS")
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AutoScroll-Updater")
            setRequestProperty("Accept", "application/json, application/octet-stream")
        }
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw UpdateException("Server membalas $code")
        }
        return connection
    }

    private fun resolve(base: String, ref: String): String {
        if (ref.startsWith("https://", ignoreCase = true)) return ref
        return try {
            URL(URL(base), ref).toString()
        } catch (e: Exception) {
            throw UpdateException("URL APK tidak valid")
        }
    }
}
