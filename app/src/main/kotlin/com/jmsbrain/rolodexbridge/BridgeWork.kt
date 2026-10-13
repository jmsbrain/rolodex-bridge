package com.jmsbrain.rolodexbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Nothing runs in the background. Android starts a short job when the phone's contacts change
 * (new LightOS contacts go to mailbox.org), and once an hour (new mailbox.org contacts come to LightOS).
 */
class BridgeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val full = inputData.getBoolean(FULL, false)
        Bridge.run(applicationContext, full)
        // A contacts-change job fires once; set up the next one.
        if (!full) BridgeWork.watchContacts(applicationContext, fromJob = true)
        return Result.success()
    }

    companion object {
        const val FULL = "full"
    }
}

object BridgeWork {
    private const val CHANGED = "contacts-changed"
    private const val HOURLY = "hourly"

    fun start(ctx: Context) {
        watchContacts(ctx, fromJob = false)
        val hourly = PeriodicWorkRequestBuilder<BridgeWorker>(1, TimeUnit.HOURS)
            .setInputData(workDataOf(BridgeWorker.FULL to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(HOURLY, ExistingPeriodicWorkPolicy.KEEP, hourly)
    }

    fun stop(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(CHANGED)
        WorkManager.getInstance(ctx).cancelUniqueWork(HOURLY)
    }

    fun watchContacts(ctx: Context, fromJob: Boolean) {
        val req = OneTimeWorkRequestBuilder<BridgeWorker>()
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(ContactsContract.RawContacts.CONTENT_URI, true)
                    // Let a burst of changes (a sync, a save) settle first.
                    .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
                    .build(),
            )
            .build()
        // From the job itself: queue behind it. Otherwise keep one that's already waiting.
        val policy = if (fromJob) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(ctx).enqueueUniqueWork(CHANGED, policy, req)
    }
}

/** Lets LightOS list this as a tool. */
class LightMarkerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = Unit
}
