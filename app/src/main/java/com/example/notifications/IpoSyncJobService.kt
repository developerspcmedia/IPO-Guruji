package com.example.notifications

import android.app.job.JobParameters
import android.app.job.JobService
import com.example.data.local.IpoDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.IpoNewsRepository
import com.example.data.repository.IpoRepository
import com.example.widget.IpoLiveWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class IpoSyncJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        serviceScope.launch {
            var shouldReschedule = false
            try {
                val database = IpoDatabase.getInstance(applicationContext)
                val repository = IpoRepository(database.ipoDao())
                val newsRepository = IpoNewsRepository()
                val prefs = UserPreferencesRepository(applicationContext)
                val summary = repository.syncWithLiveGmp()
                IpoNotificationManager.processSyncNotifications(
                    context = applicationContext,
                    preferencesRepository = prefs,
                    previousIpos = summary.previousIpos,
                    updatedIpos = summary.updatedIpos,
                )
                IpoNotificationManager.processSmartIpoAlerts(
                    context = applicationContext,
                    preferencesRepository = prefs,
                    ipos = summary.updatedIpos,
                )
                IpoLiveWidgetProvider.requestWidgetUpdate(applicationContext)
                val newsResult = newsRepository.fetchLatestNews(summary.updatedIpos)
                IpoNotificationManager.processNewsSyncNotifications(
                    context = applicationContext,
                    preferencesRepository = prefs,
                    articles = newsResult.articles,
                )
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
