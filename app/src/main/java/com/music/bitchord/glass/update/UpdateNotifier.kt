package com.music.bitchord.glass.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateChecker
import java.util.concurrent.TimeUnit

/**
 * BitChord Glass: a background look at the fork's release, so a new build is
 * announced by a notification even while the app is closed. The check, the
 * download, the checksum and the install are BitChord's own
 * [AppUpdateChecker]; this only schedules it and posts the notice. Tapping it
 * opens the app, whose launch check shows the usual update sheet.
 */
object UpdateNotifier {
    private const val WORK = "glass-update-check"
    private const val CHANNEL = "glass_updates"
    private const val PREFS = "glass_update_notifier"
    private const val KEY_LAST = "last_notified"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            AppUpdateChecker.check()
            val update = AppUpdateChecker.available.value ?: return Result.success()
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_LAST, null) == update.version) return Result.success()
            if (notify(applicationContext, update.version)) {
                prefs.edit().putString(KEY_LAST, update.version).apply()
            }
            return Result.success()
        }
    }

    private fun notify(context: Context, version: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.glass_update_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(context.getString(R.string.glass_update_title))
            .setContentText(context.getString(R.string.glass_update_text, version))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(0x61A55, notification) }.isSuccess
    }
}
