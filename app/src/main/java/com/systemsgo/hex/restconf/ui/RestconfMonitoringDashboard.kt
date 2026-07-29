package com.systemsgo.hex.restconf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RestconfHistoryEntry
import com.systemsgo.hex.restconf.protocol.RestconfConnectionState
import com.systemsgo.hex.restconf.protocol.RestconfSessionStats
import com.systemsgo.hex.transfer.formatBytes
import com.systemsgo.hex.ui.theme.NovaPink
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.SolarFlare
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * RESTCONF FEATURE (Part 4/4): the Monitoring Dashboard — a dedicated,
 * always-live view over the exact same [RestconfSessionStats] StateFlow that
 * already drove the one-line footer in [com.systemsgo.hex.ui.screens.RestconfExplorerScreen],
 * plus the [RestconfHistoryEntry] history that already backed the API
 * Explorer's Recent tab. Nothing new is tracked here — this is purely a
 * richer *view* over data the client and the Room-backed history repository
 * were already recording: connection health (state/uptime/TLS/reconnects),
 * traffic + error-rate summary cards, a latency sparkline over the recent
 * request window, a 2xx/3xx/4xx/5xx status-code breakdown, and a
 * most-recent-first request timeline.
 */
@Composable
fun RestconfMonitoringDashboard(
    connectionState: RestconfConnectionState,
    stats: RestconfSessionStats,
    history: List<RestconfHistoryEntry>,
    modifier: Modifier = Modifier,
) {
    val recent = remember(history) { history.sortedByDescending { it.timestamp } }
    val errorRate = if (stats.requestCount > 0) (stats.errorCount * 100.0 / stats.requestCount) else 0.0

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ConnectionHealthCard(connectionState, stats) }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.restconf_stat_requests_label), stats.requestCount.toString(), PulsarCyan, Modifier.weight(1f))
                StatCard(
                    stringResource(R.string.restconf_stat_errors_label),
                    "${stats.errorCount} (${"%.1f".format(errorRate)}%)",
                    if (stats.errorCount > 0) NovaPink else PlasmaGreen,
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.restconf_avg_latency_label), "${stats.averageLatencyMillis} ms", SolarFlare, Modifier.weight(1f))
                StatCard(stringResource(R.string.restconf_last_latency_label), "${stats.lastLatencyMillis} ms", SolarFlare, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.restconf_sent_label), formatBytes(stats.bytesSent), PulsarCyan, Modifier.weight(1f))
                StatCard(stringResource(R.string.restconf_received_label), formatBytes(stats.bytesReceived), PulsarCyan, Modifier.weight(1f))
            }
        }

        if (recent.isNotEmpty()) {
            item { LatencySparklineCard(recent) }
            item { StatusCodeBreakdownCard(recent) }
        }

        item {
            Text(
                stringResource(R.string.restconf_recent_requests),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (recent.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.restconf_no_requests_this_session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(recent.take(50), key = { it.id }) { entry -> HistoryRow(entry) }
        }
    }
}

@Composable
private fun ConnectionHealthCard(state: RestconfConnectionState, stats: RestconfSessionStats) {
    val color = when (state) {
        RestconfConnectionState.CONNECTED -> PlasmaGreen
        RestconfConnectionState.CONNECTING, RestconfConnectionState.RECONNECTING -> SolarFlare
        RestconfConnectionState.ERROR -> NovaPink
        RestconfConnectionState.DISCONNECTED -> PulsarCyan
    }
    val label = when (state) {
        RestconfConnectionState.CONNECTED -> stringResource(R.string.restconf_conn_connected)
        RestconfConnectionState.CONNECTING -> stringResource(R.string.restconf_conn_connecting)
        RestconfConnectionState.RECONNECTING -> stringResource(R.string.restconf_conn_reconnecting)
        RestconfConnectionState.ERROR -> stringResource(R.string.restconf_conn_error)
        RestconfConnectionState.DISCONNECTED -> stringResource(R.string.restconf_conn_disconnected)
    }
    // Ticks once a second purely to re-render the uptime string below — the
    // underlying connectedSinceEpochMillis timestamp itself never changes
    // between reconnects, so this is the only thing that needs a clock.
    val nowMillis by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val uptime = stats.connectedSinceEpochMillis?.let { formatUptime(nowMillis - it) } ?: "—"

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.padding(start = 6.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, color = color)
            }
            Spacer(Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabeledValue(stringResource(R.string.restconf_uptime), uptime)
                LabeledValue(stringResource(R.string.restconf_tls_label), stats.lastTlsVersion ?: "—")
                LabeledValue(stringResource(R.string.restconf_protocol_label), stats.lastProtocol ?: "—")
                LabeledValue(stringResource(R.string.restconf_reconnects_label), stats.reconnectCount.toString())
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, color = accent)
        }
    }
}

/** Draws the last (up to) 40 requests' [RestconfHistoryEntry.elapsedMillis] oldest→newest, one dot/line-segment per request, colored red for non-2xx. Same "min/max scaled to the available height" approach as any sparkline — no axis labels, this is a trend-at-a-glance, not a chart to read precise values off of. */
@Composable
private fun LatencySparklineCard(recentDescending: List<RestconfHistoryEntry>) {
    val points = remember(recentDescending) { recentDescending.take(40).reversed() }
    val maxLatency = remember(points) { (points.maxOfOrNull { it.elapsedMillis } ?: 1L).coerceAtLeast(1L) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.restconf_latency_trend), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.restconf_max_latency, maxLatency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.padding(top = 8.dp))
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                if (points.size < 2) return@Canvas
                val stepX = size.width / (points.size - 1)
                val prevPoints = points.zipWithNext()
                prevPoints.forEachIndexed { index, (from, to) ->
                    val x1 = index * stepX
                    val x2 = (index + 1) * stepX
                    val y1 = size.height - (from.elapsedMillis.toFloat() / maxLatency) * size.height
                    val y2 = size.height - (to.elapsedMillis.toFloat() / maxLatency) * size.height
                    drawLine(
                        color = if (to.statusCode in 200..299) PulsarCyanStatic else NovaPinkStatic,
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCodeBreakdownCard(recentDescending: List<RestconfHistoryEntry>) {
    val buckets = remember(recentDescending) {
        listOf(
            "2xx" to recentDescending.count { it.statusCode in 200..299 },
            "3xx" to recentDescending.count { it.statusCode in 300..399 },
            "4xx" to recentDescending.count { it.statusCode in 400..499 },
            "5xx" to recentDescending.count { it.statusCode in 500..599 },
        )
    }
    val total = buckets.sumOf { it.second }.coerceAtLeast(1)
    val colors = listOf(PlasmaGreen, PulsarCyan, SolarFlare, NovaPink)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.restconf_status_codes), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
            ) {
                buckets.forEachIndexed { i, (_, count) ->
                    if (count > 0) {
                        Box(
                            Modifier
                                .weight(count.toFloat() / total)
                                .fillMaxSize()
                                .background(colors[i]),
                        )
                    }
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                buckets.forEachIndexed { i, (label, count) ->
                    Text(
                        "$label: $count",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors[i],
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: RestconfHistoryEntry) {
    val statusColor = when (entry.statusCode) {
        in 200..299 -> PlasmaGreen
        in 300..399 -> PulsarCyan
        in 400..499 -> SolarFlare
        else -> NovaPink
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.method,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.padding(start = 8.dp))
            Text(entry.path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
            Text(entry.statusCode.toString(), style = MaterialTheme.typography.labelMedium, color = statusColor)
            Spacer(Modifier.padding(start = 8.dp))
            Text("${entry.elapsedMillis} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(start = 8.dp))
            Text(relativeTimeLabel(entry.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
    }
}

@Composable
private fun formatUptime(ms: Long): String {
    if (ms <= 0L) return stringResource(R.string.restconf_uptime_s, 0)
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return when {
        hours > 0 -> stringResource(R.string.restconf_uptime_hm, hours, minutes)
        minutes > 0 -> stringResource(R.string.restconf_uptime_ms, minutes, seconds)
        else -> stringResource(R.string.restconf_uptime_s, seconds)
    }
}

@Composable
private fun relativeTimeLabel(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> stringResource(R.string.restconf_just_now)
        diff < 3_600_000 -> stringResource(R.string.restconf_ms_ago, TimeUnit.MILLISECONDS.toMinutes(diff))
        diff < 86_400_000 -> stringResource(R.string.restconf_hr_ago, TimeUnit.MILLISECONDS.toHours(diff))
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

// Fixed (non-theme-reactive) colors for use inside Canvas.drawLine, which
// runs in a DrawScope lambda where the @Composable theme-color getters
// above ([PulsarCyan]/[NovaPink]) cannot be called.
private val PulsarCyanStatic = Color(0xFF00D9FF)
private val NovaPinkStatic = Color(0xFFFF2D78)
