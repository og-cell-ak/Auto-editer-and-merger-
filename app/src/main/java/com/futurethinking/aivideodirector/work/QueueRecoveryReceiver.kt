package com.futurethinking.aivideodirector.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.futurethinking.aivideodirector.data.Project
import com.futurethinking.aivideodirector.data.ProjectStore
import java.util.concurrent.TimeUnit

object QueueRecovery {
    private const val QUEUE_NAME = "editor-and-merger-media-queue"

    fun reconcile(context: Context) {
        val app = context.applicationContext
        val store = ProjectStore(app)
        val wm = WorkManager.getInstance(app)
        val projects = store.list()
            .filter { it.state == "QUEUED" || it.state == "ANALYZING" || it.state == "RENDERING" || it.state == "MERGING" }
            .sortedWith(compareBy<Project> { it.createdAt }.thenBy { it.updatedAt })

        val active = runCatching {
            wm.getWorkInfosForUniqueWork(QUEUE_NAME).get().any { !it.state.isFinished }
        }.getOrDefault(false)

        if (active) return

        projects.forEach { project ->
            val request = if (project.isMerged) {
                OneTimeWorkRequestBuilder<MergeWorker>()
                    .setInputData(workDataOf(MergeWorker.KEY_PROJECT_ID to project.id))
                    .addTag("editor-merge")
                    .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build()
            } else {
                OneTimeWorkRequestBuilder<GenerationWorker>()
                    .setInputData(workDataOf(GenerationWorker.KEY_PROJECT_ID to project.id))
                    .addTag("editor-generation")
                    .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build()
            }
            wm.beginUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue()
        }
    }
}

class QueueRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Never let boot/package-replacement recovery crash the application process.
                // Run it off the receiver thread and finish even if WorkManager is temporarily unavailable.
                val pending = goAsync()
                Thread {
                    try {
                        runCatching { QueueRecovery.reconcile(context.applicationContext) }
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
