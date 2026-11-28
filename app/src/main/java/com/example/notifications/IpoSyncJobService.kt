package com.example.notifications

import android.app.job.JobParameters
import android.app.job.JobService
import com.example.data.local.IpoDatabase
import com.example.data.model.IpoOverrideMerger
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.AdminOverrideRepository
import com.example.data.repository.IpoNewsRepository
import com.example.data.repository.IpoRepository
import com.example.widget.IpoLiveWidgetProvider
import kotlinx.coroutines.CancellationException
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
                val adminOverrideRepository = AdminOverrideRepository(
                    context = applicationContext,
                    overrideDao = database.adminOverrideDao(),
                )
                val repository = IpoRepository(
                    ipoDao = database.ipoDao(),
                    adminOverrideRepository = adminOverrideRepository,
                )
                val newsRepository = IpoNewsRepository()
                val prefs = UserPreferencesRepository(applicationContext)
                val summary = repository.syncWithLiveGmp()
                val cachedOverrides = adminOverrideRepository.getCachedOverridesSnapshot()
                val effectivePreviousIpos = IpoOverrideMerger.applyOverridesToCatalog(
                    baseCatalog = summary.previousIpos,
                    overrides = cachedOverrides,
                )
                val effectiveUpdatedIpos = IpoOverrideMerger.applyOverridesToCatalog(
                    baseCatalog = summary.updatedIpos,
                    overrides = cachedOverrides,
                )
                IpoNotificationManager.processSyncNotifications(
                    context = applicationContext,
                    preferencesRepository = prefs,
                    previousIpos = effectivePreviousIpos,
                    updatedIpos = effectiveUpdatedIpos,
                )
                IpoNotificationManager.processSmartIpoAlerts(
                    context = applicationContext,
                    preferencesRepository = prefs,
                    ipos = effectiveUpdatedIpos,
                )
                IpoLiveWidgetProvider.requestWidgetUpdate(applicationContext)
                val newsResult = newsRepository.fetchLatestNews(effectiveUpdatedIpos)
                IpoNotificationManager.processNewsSyncNotifications(
                    context = applicationContext,
                    preferencesRepository = prefs,
                    articles = newsResult.articles,
                )
            } catch (e: CancellationException) {
                throw e
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
