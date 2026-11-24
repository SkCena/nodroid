package dev.nodroid.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

/** WorkManager worker that runs a scheduled workflow and supports checkpoint resume after a crash or delay. */
class ScheduleWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(KEY_WORKFLOW_ID) ?: return Result.failure()
        return try {
            val ok = RuntimeHolder.runWorkflow(workflowId, "sched-" + UUID.randomUUID())
            if (ok) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_WORKFLOW_ID = "workflow_id"

        /** Schedules a workflow periodically. Connectivity triggers use a network constraint instead of a timer. */
        fun schedule(context: Context, workflowId: String, intervalMinutes: Long, requireNetwork: Boolean = false) {
            val builder = PeriodicWorkRequestBuilder<ScheduleWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_WORKFLOW_ID to workflowId))
            if (requireNetwork) builder.setConstraints(
                androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "nodroid-" + workflowId, ExistingPeriodicWorkPolicy.UPDATE, builder.build(),
            )
        }

        fun cancel(context: Context, workflowId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("nodroid-" + workflowId)
        }
    }
}
