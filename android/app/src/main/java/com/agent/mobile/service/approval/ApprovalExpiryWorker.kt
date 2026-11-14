package com.agent.mobile.service.approval

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.agent.mobile.AgentRuntime
import java.util.concurrent.TimeUnit

/**
 * A parked approval must not hold its task forever: after [REMIND_AFTER_MIN] the question is
 * shown again, after [EXPIRE_AFTER_MIN] in total it expires and the task continues without the
 * action (and says so in its summary).
 */
class ApprovalExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val queue = AgentRuntime.get(applicationContext).approvalQueue
        if (inputData.getBoolean(KEY_REMIND, false)) {
            queue.remind(id)
            enqueue(applicationContext, id, remind = false, delayMinutes = EXPIRE_AFTER_MIN - REMIND_AFTER_MIN)
        } else {
            queue.expire(id)
        }
        return Result.success()
    }

    companion object {
        const val REMIND_AFTER_MIN = 2L * 60
        const val EXPIRE_AFTER_MIN = 12L * 60
        private const val KEY_ID = "approval_id"
        private const val KEY_REMIND = "remind"

        /** Called when an approval is parked: reminder first, expiry afterwards. */
        fun schedule(context: Context, approvalId: String) {
            try {
                enqueue(context, approvalId, remind = true, delayMinutes = REMIND_AFTER_MIN)
            } catch (e: Exception) {
                // WorkManager missing (unit tests) or not initialised: the approval simply has no expiry.
                android.util.Log.w("ApprovalExpiry", "Could not schedule the expiry of $approvalId: ${e.message}")
            }
        }

        private fun enqueue(context: Context, approvalId: String, remind: Boolean, delayMinutes: Long) {
            val request = OneTimeWorkRequestBuilder<ApprovalExpiryWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_ID to approvalId, KEY_REMIND to remind))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("amc_approval_${if (remind) "remind" else "expire"}_$approvalId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
