package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BoardType
import com.example.data.model.IpoDateUtils
import com.example.data.model.IpoEntity
import com.example.ui.components.glassBorderStroke
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.viewmodel.BoardFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CalendarEventType(val label: String, val sortRank: Int) {
    OPEN("OPEN", 0),
    CLOSING("CLOSING", 1),
    ALLOTMENT("ALLOTMENT", 2),
    LISTING("LISTING", 3),
}

data class IpoCalendarEvent(
    val key: String,
    val ipoId: String,
    val companyName: String,
    val boardType: BoardType,
    val boardBadgeText: String,
    val eventType: CalendarEventType,
    val dateMs: Long,
)

data class MarketHolidaySpec(
    val rawDate: String,
    val title: String,
)

sealed interface CalendarTimelineItem {
    val sortDateMs: Long
    val key: String

    data class DayGroup(
        override val sortDateMs: Long,
        val formattedDate: String,
        val isToday: Boolean,
        val events: List<IpoCalendarEvent>,
    ) : CalendarTimelineItem {
        override val key: String = "day_$sortDateMs"
    }

    data class MarketHoliday(
        override val sortDateMs: Long,
        val formattedDate: String,
        val title: String,
    ) : CalendarTimelineItem {
        override val key: String = "holiday_$sortDateMs"
    }
}

data class CalendarBoardCounts(
    val allCount: Int,
    val mainboardCount: Int,
    val smeCount: Int,
)

object IpoEventCalendarBuilder {
    // Official Indian NSE/BSE equity market holidays (2025-2027)
    private val INDIAN_MARKET_HOLIDAYS = listOf(
        MarketHolidaySpec("26 Jan 2025", "Republic Day"),
        MarketHolidaySpec("26 Feb 2025", "Mahashivratri"),
        MarketHolidaySpec("14 Mar 2025", "Holi"),
        MarketHolidaySpec("31 Mar 2025", "Id-Ul-Fitr (Ramzan Eid)"),
        MarketHolidaySpec("10 Apr 2025", "Shri Mahavir Jayanti"),
        MarketHolidaySpec("14 Apr 2025", "Dr. Baba Saheb Ambedkar Jayanti"),
        MarketHolidaySpec("18 Apr 2025", "Good Friday"),
        MarketHolidaySpec("01 May 2025", "Maharashtra Day"),
        MarketHolidaySpec("15 Aug 2025", "Independence Day"),
        MarketHolidaySpec("27 Aug 2025", "Ganesh Chaturthi"),
        MarketHolidaySpec("02 Oct 2025", "Mahatma Gandhi Jayanti / Dussehra"),
        MarketHolidaySpec("21 Oct 2025", "Diwali Laxmi Pujan"),
        MarketHolidaySpec("22 Oct 2025", "Diwali Balipratipada"),
        MarketHolidaySpec("05 Nov 2025", "Prakash Gurpurb"),
        MarketHolidaySpec("25 Dec 2025", "Christmas"),
        MarketHolidaySpec("26 Jan 2026", "Republic Day"),
        MarketHolidaySpec("03 Mar 2026", "Holi"),
        MarketHolidaySpec("26 Mar 2026", "Shri Ram Navami"),
        MarketHolidaySpec("31 Mar 2026", "Shri Mahavir Jayanti"),
        MarketHolidaySpec("03 Apr 2026", "Good Friday"),
        MarketHolidaySpec("14 Apr 2026", "Dr. Baba Saheb Ambedkar Jayanti"),
        MarketHolidaySpec("01 May 2026", "Maharashtra Day"),
        MarketHolidaySpec("28 May 2026", "Bakri Id"),
        MarketHolidaySpec("26 Jun 2026", "Moharram"),
        MarketHolidaySpec("14 Sep 2026", "Ganesh Chaturthi"),
        MarketHolidaySpec("02 Oct 2026", "Mahatma Gandhi Jayanti"),
        MarketHolidaySpec("20 Oct 2026", "Dussehra"),
        MarketHolidaySpec("10 Nov 2026", "Diwali Balipratipada"),
        MarketHolidaySpec("24 Nov 2026", "Prakash Gurpurb"),
        MarketHolidaySpec("25 Dec 2026", "Christmas"),
        MarketHolidaySpec("26 Jan 2027", "Republic Day"),
        MarketHolidaySpec("22 Mar 2027", "Holi"),
        MarketHolidaySpec("26 Mar 2027", "Good Friday"),
        MarketHolidaySpec("14 Apr 2027", "Dr. Baba Saheb Ambedkar Jayanti"),
        MarketHolidaySpec("15 Aug 2027", "Independence Day"),
        MarketHolidaySpec("02 Oct 2027", "Mahatma Gandhi Jayanti"),
        MarketHolidaySpec("29 Oct 2027", "Diwali Balipratipada"),
        MarketHolidaySpec("14 Nov 2027", "Prakash Gurpurb"),
        MarketHolidaySpec("25 Dec 2027", "Christmas"),
    )

    fun extractUpcomingEvents(
        allIpos: List<IpoEntity>,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): List<IpoCalendarEvent> {
        val todayMs = IpoDateUtils.startOfDayMs(referenceTimeMs)
        val allExtracted = mutableListOf<IpoCalendarEvent>()

        for (ipo in allIpos) {
            val badgeText = when {
                ipo.boardType == BoardType.MAINBOARD -> "MAINBOARD"
                ipo.exchange.contains("NSE", ignoreCase = true) -> "NSE SME"
                ipo.exchange.contains("BSE", ignoreCase = true) -> "BSE SME"
                else -> "SME"
            }
            val datePairs = listOf(
                CalendarEventType.OPEN to ipo.openDate,
                CalendarEventType.CLOSING to ipo.closeDate,
                CalendarEventType.ALLOTMENT to ipo.allotmentDate,
                CalendarEventType.LISTING to ipo.listingDate,
            )
            for ((eventType, rawDate) in datePairs) {
                val parsedMs = IpoDateUtils.parseFlexibleDateMs(rawDate, referenceTimeMs) ?: continue
                allExtracted += IpoCalendarEvent(
                    key = "${ipo.id}_${eventType.name}_$parsedMs",
                    ipoId = ipo.id,
                    companyName = ipo.companyName,
                    boardType = ipo.boardType,
                    boardBadgeText = badgeText,
                    eventType = eventType,
                    dateMs = parsedMs,
                )
            }
        }

        val upcoming = allExtracted.filter { it.dateMs >= todayMs }
        return if (upcoming.isNotEmpty()) upcoming else allExtracted.sortedByDescending { it.dateMs }.take(20)
    }

    fun computeCounts(events: List<IpoCalendarEvent>): CalendarBoardCounts {
        val mainboard = events.count { it.boardType == BoardType.MAINBOARD }
        val sme = events.count { it.boardType == BoardType.SME }
        return CalendarBoardCounts(
            allCount = events.size,
            mainboardCount = mainboard,
            smeCount = sme,
        )
    }

    fun buildTimeline(
        events: List<IpoCalendarEvent>,
        boardFilter: BoardFilter,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): List<CalendarTimelineItem> {
        val todayMs = IpoDateUtils.startOfDayMs(referenceTimeMs)
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)

        val filteredEvents = when (boardFilter) {
            BoardFilter.ALL -> events
            BoardFilter.MAINBOARD -> events.filter { it.boardType == BoardType.MAINBOARD }
            BoardFilter.SME -> events.filter { it.boardType == BoardType.SME }
        }

        val groupedDays = filteredEvents
            .groupBy { it.dateMs }
            .entries
            .sortedBy { it.key }
            .map { (dateMs, dayEvents) ->
                CalendarTimelineItem.DayGroup(
                    sortDateMs = dateMs,
                    formattedDate = formatter.format(Date(dateMs)),
                    isToday = dateMs == todayMs,
                    events = dayEvents.sortedWith(
                        compareBy<IpoCalendarEvent> { it.eventType.sortRank }
                            .thenBy { it.boardType.ordinal }
                            .thenBy { it.companyName },
                    ),
                )
            }

        val maxEventDateMs = groupedDays.lastOrNull()?.sortDateMs ?: (todayMs + 30L * 86_400_000L)
        val minWindowMs = groupedDays.firstOrNull()?.sortDateMs?.coerceAtMost(todayMs) ?: todayMs
        val maxWindowMs = maxOf(maxEventDateMs, todayMs + 21L * 86_400_000L)

        val holidays = INDIAN_MARKET_HOLIDAYS.mapNotNull { spec ->
            val holidayMs = IpoDateUtils.parseFlexibleDateMs(spec.rawDate, referenceTimeMs) ?: return@mapNotNull null
            if (holidayMs in minWindowMs..maxWindowMs) {
                CalendarTimelineItem.MarketHoliday(
                    sortDateMs = holidayMs,
                    formattedDate = formatter.format(Date(holidayMs)),
                    title = spec.title,
                )
            } else {
                null
            }
        }

        return (groupedDays + holidays).sortedWith(
            compareBy<CalendarTimelineItem> { it.sortDateMs }
                .thenBy { if (it is CalendarTimelineItem.MarketHoliday) 0 else 1 },
        )
    }
}

@Composable
fun IpoEventCalendarScreen(
    allIpos: List<IpoEntity>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onIpoClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    var selectedFilter by remember { mutableStateOf(BoardFilter.ALL) }

    val extractedEvents = remember(allIpos) {
        IpoEventCalendarBuilder.extractUpcomingEvents(allIpos)
    }
    val counts = remember(extractedEvents) {
        IpoEventCalendarBuilder.computeCounts(extractedEvents)
    }
    val timelineItems = remember(extractedEvents, selectedFilter) {
        IpoEventCalendarBuilder.buildTimeline(
            events = extractedEvents,
            boardFilter = selectedFilter,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("ipo_calendar_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
        ) {
            // Top Bar matching reference screenshot: Back | IPO Event Calendar | Refresh
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("calendar_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "IPO Event Calendar",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("calendar_refresh_button"),
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh IPO calendar",
                        )
                    }
                }
            }

            // Segmented Capsule Filter Bar: All (N) | Mainboard (N) | SME (N)
            CalendarBoardSegmentedBar(
                selectedFilter = selectedFilter,
                counts = counts,
                onSelectFilter = { selectedFilter = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (timelineItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CalendarMonth,
                            contentDescription = null,
                            tint = marketColors.mutedText,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "No upcoming IPO events",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Tap Refresh or switch board filter to view scheduled IPO open, close, allotment, and listing dates.",
                            style = MaterialTheme.typography.bodySmall,
                            color = marketColors.mutedText,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("calendar_timeline_list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items = timelineItems, key = { it.key }) { item ->
                        when (item) {
                            is CalendarTimelineItem.DayGroup -> {
                                CalendarDayGroupSection(
                                    dayGroup = item,
                                    onIpoClick = onIpoClick,
                                )
                            }

                            is CalendarTimelineItem.MarketHoliday -> {
                                MarketHolidayCard(holiday = item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarBoardSegmentedBar(
    selectedFilter: BoardFilter,
    counts: CalendarBoardCounts,
    onSelectFilter: (BoardFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassStyle.current
    val options = listOf(
        Triple(BoardFilter.ALL, "All", counts.allCount),
        Triple(BoardFilter.MAINBOARD, "Mainboard", counts.mainboardCount),
        Triple(BoardFilter.SME, "SME", counts.smeCount),
    )

    Surface(
        modifier = modifier.testTag("calendar_filter_bar"),
        shape = RoundedCornerShape(99.dp),
        color = glass.pillFill,
        border = glassBorderStroke(glass = glass, width = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (filter, label, count) ->
                val selected = selectedFilter == filter
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(99.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        )
                        .minimumInteractiveComponentSize()
                        .clickable { onSelectFilter(filter) }
                        .padding(horizontal = 8.dp, vertical = 9.dp)
                        .testTag("calendar_filter_${filter.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                    },
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                fontWeight = FontWeight.Bold,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayGroupSection(
    dayGroup: CalendarTimelineItem.DayGroup,
    onIpoClick: (String) -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    val accentBarColor = if (dayGroup.isToday) {
        marketColors.ctaGold
    } else {
        MaterialTheme.colorScheme.primary
    }
    val eventCountText = if (dayGroup.events.size == 1) "1 event" else "${dayGroup.events.size} events"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Date Header Strip with Left Accent Bar, Calendar Icon, Date, TODAY badge & Event Count
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = glass.pillFill,
            border = glassBorderStroke(
                glass = glass,
                width = 0.9.dp,
                accentTint = if (dayGroup.isToday) MaterialTheme.colorScheme.primary else null,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(if (dayGroup.isToday) "calendar_today_header" else "calendar_day_header_${dayGroup.sortDateMs}"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(accentBarColor),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (dayGroup.isToday) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CalendarMonth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        Text(
                            text = dayGroup.formattedDate,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        if (dayGroup.isToday) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "TODAY",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.6.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = eventCountText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = marketColors.mutedText,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Event Cards for this Date
        dayGroup.events.forEach { event ->
            CalendarIpoEventCard(
                event = event,
                formattedDate = dayGroup.formattedDate,
                onClick = { onIpoClick(event.ipoId) },
            )
        }
    }
}

@Composable
private fun CalendarIpoEventCard(
    event: IpoCalendarEvent,
    formattedDate: String,
    onClick: () -> Unit,
) {
    val marketColors = LocalMarketColors.current
    val (boardBg, boardFg) = if (event.boardType == BoardType.MAINBOARD) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f) to MaterialTheme.colorScheme.primary
    } else {
        marketColors.smeContainer to marketColors.smeBadge
    }

    val (eventBg, eventDotColor, eventTextColor) = when (event.eventType) {
        CalendarEventType.OPEN -> Triple(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary,
        )

        CalendarEventType.CLOSING -> Triple(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            marketColors.mutedText,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CalendarEventType.ALLOTMENT -> Triple(
            marketColors.bullishContainer,
            marketColors.bullish,
            marketColors.bullish,
        )

        CalendarEventType.LISTING -> Triple(
            marketColors.smeContainer.copy(alpha = 0.85f),
            marketColors.smeBadge,
            marketColors.smeBadge,
        )
    }

    val glass = LocalGlassStyle.current
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = glass.cardFill,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = glassBorderStroke(glass = glass, width = 0.9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("calendar_event_card_${event.key}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = event.companyName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // Board Pill: MAINBOARD / NSE SME / BSE SME
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(boardBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = event.boardBadgeText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp,
                    ),
                    color = boardFg,
                    maxLines = 1,
                )
            }

            // Event Type Pill with Dot: ● OPEN / ● CLOSING / ● ALLOTMENT / ● LISTING
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(eventBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(eventDotColor),
                )
                Text(
                    text = event.eventType.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp,
                    ),
                    color = eventTextColor,
                    maxLines = 1,
                )
            }

            // One-Tap Zero-Permission Export to Phone Calendar
            IconButton(
                onClick = {
                    val eventHour = when (event.eventType) {
                        CalendarEventType.CLOSING -> 14
                        else -> 10
                    }
                    launchAddIpoEventToCalendar(
                        context = context,
                        title = "${event.companyName} IPO — ${event.eventType.label}",
                        dateText = formattedDate,
                        description = "${event.boardBadgeText} IPO ${event.eventType.label} date tracked in IPO Guruji.",
                        hourOfDay = eventHour,
                    )
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("calendar_export_${event.key}"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.EventAvailable,
                    contentDescription = "Add ${event.companyName} ${event.eventType.label} to phone calendar",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun MarketHolidayCard(
    holiday: CalendarTimelineItem.MarketHoliday,
) {
    val marketColors = LocalMarketColors.current
    val backdropGradient = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF172338),
            Color(0xFF1E293B),
            Color(0xFF162032),
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(backdropGradient)
            .testTag("calendar_holiday_${holiday.sortDateMs}"),
    ) {
        // Subtle atmospheric sparkle particles mirroring reference holiday banner
        Canvas(modifier = Modifier.matchParentSize()) {
            val dots = listOf(
                Triple(0.14f, 0.25f, 2.2f),
                Triple(0.24f, 0.42f, 3.0f),
                Triple(0.32f, 0.78f, 2.0f),
                Triple(0.45f, 0.18f, 2.8f),
                Triple(0.51f, 0.62f, 2.2f),
                Triple(0.62f, 0.30f, 1.8f),
                Triple(0.76f, 0.74f, 2.5f),
                Triple(0.88f, 0.22f, 2.0f),
            )
            dots.forEach { (fx, fy, r) ->
                drawCircle(
                    color = Color.White.copy(alpha = 0.28f),
                    radius = r.dp.toPx(),
                    center = Offset(size.width * fx, size.height * fy),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, Color(0xFF334866)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF28426B)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CalendarMonth,
                                contentDescription = null,
                                tint = Color(0xFF93C5FD),
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Text(
                            text = holiday.formattedDate,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFCBD5E1),
                        )
                    }
                    Text(
                        text = holiday.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(Color(0xFF263B59))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "MARKET HOLIDAY",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp,
                            ),
                            color = Color(0xFF93C5FD),
                            maxLines = 1,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(marketColors.ctaGold.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BeachAccess,
                            contentDescription = "Market Holiday",
                            tint = marketColors.ctaGold,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
    }
}
