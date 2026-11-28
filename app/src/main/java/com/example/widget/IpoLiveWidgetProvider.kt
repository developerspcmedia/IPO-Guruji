package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.local.IpoDatabase
import com.example.data.local.IpoSeedData
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.data.model.IpoOverrideMerger
import com.example.data.model.IpoStatus
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.AdminOverrideRepository
import com.example.data.repository.IpoFilingVerifier
import com.example.data.repository.IpoRepository
import com.example.data.repository.LiveIpoCloudRepository
import com.example.notifications.IpoNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class IpoLiveWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val ipos = loadVerifiedWidgetIpos(context)
                appWidgetIds.forEach { widgetId ->
                    val views = buildRemoteViews(context, ipos)
                    appWidgetManager.updateAppWidget(widgetId, views)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, IpoLiveWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                onUpdate(context, manager, ids)
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.example.widget.ACTION_IPO_WIDGET_REFRESH"

        fun requestWidgetUpdate(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val component = ComponentName(context, IpoLiveWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids.isEmpty()) return
                val updateIntent = Intent(context, IpoLiveWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(updateIntent)
            }
        }

        internal suspend fun loadVerifiedWidgetIpos(context: Context): List<IpoEntity> {
            val appCtx = context.applicationContext ?: context
            val database = IpoDatabase.getInstance(appCtx)
            val adminOverrideRepository = AdminOverrideRepository(
                context = appCtx,
                overrideDao = database.adminOverrideDao(),
            )
            val repository = IpoRepository(
                ipoDao = database.ipoDao(),
                adminOverrideRepository = adminOverrideRepository,
                liveIpoCloudRepository = LiveIpoCloudRepository(appCtx),
                preferencesRepository = UserPreferencesRepository(appCtx),
            )
            runCatching { repository.syncWithLiveGmp(forceManual = false) }
            val dbIpos = runCatching {
                database.ipoDao().getAllSnapshot()
            }.getOrDefault(emptyList())
            val source = if (dbIpos.isNotEmpty()) dbIpos else IpoSeedData.createInitialCatalog()
            val cachedOverrides = runCatching {
                adminOverrideRepository.getCachedOverridesSnapshot()
            }.getOrDefault(emptyList())
            val merged = IpoOverrideMerger.applyOverridesToCatalog(
                baseCatalog = source,
                overrides = cachedOverrides,
            )
            return merged.filter { IpoFilingVerifier.isVerifiedFiledEntity(it) }
        }

        fun selectTopWidgetIpos(allIpos: List<IpoEntity>): List<IpoEntity> {
            val active = allIpos.filter { ipo ->
                !IpoDateUtils.isListingDateArrived(
                    listingDate = ipo.listingDate,
                    closeDate = ipo.closeDate,
                    status = ipo.status,
                )
            }
            val pool = active.ifEmpty { allIpos }
            return pool
                .sortedWith(
                    compareBy<IpoEntity> {
                        when (it.status) {
                            IpoStatus.LIVE -> 0
                            IpoStatus.PRE_APPLY -> 1
                            IpoStatus.UPCOMING -> 2
                            IpoStatus.CLOSED -> 3
                        }
                    }.thenByDescending { it.gmpPercent },
                )
                .take(3)
        }

        fun buildRemoteViews(context: Context, allIpos: List<IpoEntity>): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_ipo_live)
            val liveCount = allIpos.count { it.status == IpoStatus.LIVE }
            val topGmpIpo = allIpos.maxByOrNull { it.gmpPercent }
            val topGmpLabel = if (topGmpIpo != null && topGmpIpo.gmpRupees != 0) {
                val sign = if (topGmpIpo.gmpPercent >= 0) "+" else ""
                "Top GMP $sign${String.format(Locale.US, "%.0f%%", topGmpIpo.gmpPercent)}"
            } else {
                "Verified Filed IPOs"
            }

            views.setTextViewText(R.id.widget_title, "IPO Guruji • Live Radar")
            views.setTextViewText(R.id.widget_subtitle, "$liveCount Open Now • $topGmpLabel")

            // Open main app when tapping root header
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPending = PendingIntent.getActivity(
                context,
                8100,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppPending)

            // Manual Sync button
            val refreshIntent = Intent(context, IpoLiveWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_REFRESH
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                8101,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)

            val topIpos = selectTopWidgetIpos(allIpos)
            val rowBindings = listOf(
                Triple(R.id.widget_row_1, Triple(R.id.widget_ipo_name_1, R.id.widget_ipo_meta_1, R.id.widget_ipo_gmp_1), 8111),
                Triple(R.id.widget_row_2, Triple(R.id.widget_ipo_name_2, R.id.widget_ipo_meta_2, R.id.widget_ipo_gmp_2), 8112),
                Triple(R.id.widget_row_3, Triple(R.id.widget_ipo_name_3, R.id.widget_ipo_meta_3, R.id.widget_ipo_gmp_3), 8113),
            )

            rowBindings.forEachIndexed { index, (rowId, textIds, requestCode) ->
                val ipo = topIpos.getOrNull(index)
                if (ipo == null) {
                    views.setViewVisibility(rowId, View.GONE)
                } else {
                    views.setViewVisibility(rowId, View.VISIBLE)
                    views.setTextViewText(textIds.first, ipo.companyName)
                    val subPart = if (ipo.totalSubX > 0.0) {
                        "${String.format(Locale.US, "%.1f", ipo.totalSubX)}x Sub"
                    } else {
                        "Closes ${ipo.closeDate}"
                    }
                    views.setTextViewText(
                        textIds.second,
                        "${ipo.status.displayLabel} • ${ipo.boardType.displayLabel} • $subPart",
                    )
                    val sign = if (ipo.gmpRupees >= 0) "+" else "-"
                    val gmpText = "${sign}₹${abs(ipo.gmpRupees)} (${sign}${String.format(Locale.US, "%.0f%%", abs(ipo.gmpPercent))})"
                    views.setTextViewText(textIds.third, gmpText)
                    views.setTextColor(
                        textIds.third,
                        if (ipo.gmpRupees >= 0) Color.parseColor("#34D399") else Color.parseColor("#FB7185"),
                    )

                    val detailIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(IpoNotificationManager.EXTRA_IPO_ID, ipo.id)
                    }
                    val detailPending = PendingIntent.getActivity(
                        context,
                        requestCode,
                        detailIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    views.setOnClickPendingIntent(rowId, detailPending)
                }
            }

            return views
        }
    }
}
