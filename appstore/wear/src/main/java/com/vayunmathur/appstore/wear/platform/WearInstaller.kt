package com.vayunmathur.appstore.wear.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.vayunmathur.appstore.wear.data.WearStore
import com.vayunmathur.appstore.wear.domain.WatchAppEvent
import com.vayunmathur.library.log.Log
import java.io.File

/**
 * Hands one verified APK to PackageInstaller — with the user action required.
 *
 * A regular Wear OS app cannot silently install anything: the session is
 * committed without USER_ACTION_NOT_REQUIRED, so the OS shows its own
 * confirmation to the wearer and [InstallResultReceiver] forwards that prompt
 * and reports the verdict. Nothing in this file may claim success before the
 * OS does.
 */
object WearInstaller {

    private const val TAG = "WearInstaller"

    /**
     * Commit [file] for [packageName]. Returns true when the session was
     * committed (the OS verdict still arrives via [InstallResultReceiver]).
     */
    fun install(context: Context, packageName: String, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val store = WearStore.get(context)
        return try {
            val installer = context.packageManager.packageInstaller
            installer.mySessions
                .filter { it.appPackageName == packageName }
                .forEach { runCatching { installer.abandonSession(it.sessionId) } }

            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setSize(file.length())
                setAppPackageName(packageName)
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                session.openWrite(file.name, 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java).apply {
                    action = InstallResultReceiver.ACTION_INSTALL_RESULT
                    putExtra(InstallResultReceiver.EXTRA_PACKAGE, packageName)
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
                session.close()
                store.applyEvent(packageName, WatchAppEvent.InstallTapped)
                true
            } catch (expected: Exception) {
                runCatching { session.abandon() }
                throw expected
            }
        } catch (expected: Exception) {
            Log.error(TAG, "install $packageName failed to start", expected)
            false
        }
    }
}
