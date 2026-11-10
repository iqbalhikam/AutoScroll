package id.iqbal.tiktokauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(it) }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.update_installed),
                    Toast.LENGTH_LONG
                ).show()
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: context.getString(R.string.update_install_unknown)
                Toast.makeText(
                    context,
                    context.getString(R.string.update_install_failed, message),
                    Toast.LENGTH_LONG
                ).show()
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                if (sessionId >= 0) {
                    runCatching {
                        context.packageManager.packageInstaller.abandonSession(sessionId)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
