package com.example.notifications

import android.app.job.JobParameters
import android.app.job.JobService
import com.example.data.local.IpoDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.AllotmentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Private on-device background JobService that checks pending IPO allotment watches
 * directly against official registrars and fires instant local notifications.
 */
class AllotmentMonitorJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        serviceScope.launch {
            var shouldReschedule = false
            try {
                val prefs = UserPreferencesRepository(applicationContext)
                val snapshot = prefs.getNotificationSnapshot()
                if (!snapshot.notifyAllotmentEnabled) {
                    IpoNotificationManager.scheduleAllotmentMonitor(applicationContext, enabled = false)
                    return@launch
                }

                val database = IpoDatabase.getInstance(applicationContext)
                val repository = AllotmentRepository(database.allotmentDao())
                val result = repository.checkAllPendingWatches()
                result.events.forEach { event ->
                    IpoNotificationManager.postAllotmentEventNotification(applicationContext, event)
                }

                val stillPending = repository.hasPendingWatches()
                if (!stillPending) {
                    IpoNotificationManager.scheduleAllotmentMonitor(applicationContext, enabled = false)
                }
            } catch (_: Throwable) {
                shouldReschedule = true
            } finally {
                jobFinished(params, shouldReschedule)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        serviceScope.cancel()
        return true
    }
}
