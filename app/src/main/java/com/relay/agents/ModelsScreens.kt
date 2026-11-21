package com.relay.agents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

private val PALETTE = listOf(
    Color(0xFFE5654B), Color(0xFF7CB342), Color(0xFF4285F4), Color(0xFFE58A3A),
    Color(0xFF8B5CF6), Color(0xFFD946A8), Color(0xFF14B8A6), Color(0xFF9CA3AF)
)

internal fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

private class Row3(val a: AgentConfig, val rel: Int, val speed: Int, val intel: Int, val score: Double, val colorIdx: Int)

@Composable
private fun MiniBar(label: String, value: Int, color: Color) {
    Column(Modifier.width(84.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, 6)
            Spacer(Modifier.width(4.dp))
            Text("$label $value", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
            Box(Modifier.fillMaxWidth((value / 100f).coerceIn(0.02f, 1f)).fillMaxHeight().background(color))
        }
    }
}

/** مسار آخر طلب: من جُرّب أولاً، من تخطّاه الموجّه وبسبب ماذا، ومن نجح وبكم، ومن بقي احتياطاً. */
@Composable
fun RouteTraceCard(vm: RelayViewModel, tick: Int = 0) {
    val trace = vm.pool.lastTrace
    val now = System.currentTimeMillis()
    val tried = trace.map { it.name }.toSet()
    val standby = vm.pool.router.snapshot()
        .filter { !it.disabled && it.cooldownUntil <= now && it.agent.enabled && it.agent.name !in tried }
        .take(3)
    SoftCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("طلب واحد، وأفضل نموذج مجاني يجيب"), style = MaterialTheme.typography.titleMedium)
            Text(tr("كل طلب يمرّ على سلسلة احتياط حيّة: الحدود والراحة والحصص تُعالَج قبل أن يرى تطبيقك أي خطأ."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (trace.isEmpty()) {
                Text(tr("لا يوجد طلب بعد. أرسل رسالة في المحادثة أو عبر البوابة لترى المسار هنا."), style = MaterialTheme.typography.bodySmall)
            }
            trace.forEachIndexed { i, t ->
                val c = if (t.ok) Brand.Green else Brand.Amber
                Card(
                    shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, c),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(22.dp))
                        Text(t.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text((if (t.ok) "✓ " else "") + t.detail, style = MaterialTheme.typography.labelMedium, color = c)
                    }
                }
            }
            standby.forEachIndexed { i, st ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${trace.size + i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(22.dp))
                    Text(st.agent.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(tr("في الانتظار"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** كتالوج النماذج: استهلاك 30 يوماً، استراتيجية التوجيه، ضغط الموجّه، وترتيب مُقيَّم بالموثوقية والسرعة والذكاء. */
@Composable
fun ModelsTab(vm: RelayViewModel, onOpenRouting: () -> Unit, onOpenKeys: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var visionOnly by remember { mutableStateOf(false) }
    var showAllUsage by remember { mutableStateOf(false) }
    var showPressure by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val usage = remember(vm.agents, vm.health) {
        Telemetry.stats(now - 30L * 24 * 3_600_000L).filter { it.tokens > 0 }.sortedByDescending { it.tokens }
    }
    val totalTokens = usage.sumOf { it.tokens }
    val live = vm.pool.router.stats()
    val w = Scoring.weights(vm.routeStrategy)
    val states = vm.states
    fun stateOf(a: AgentConfig) = states.firstOrNull { it.agent.id == a.id }

    val rows = vm.agents.mapIndexed { i, a ->
        val (lat, rel) = live[a.id] ?: (0.0 to 0.5)
        val sp = Scoring.speed(lat)
        val ig = Scoring.intelligence(a.model)
        Row3(a, (rel * 100).roundToInt(), sp, ig, Scoring.score(w, rel, sp, ig), i)
    }.filter { r ->
        (q.isBlank() || (r.a.name + " " + r.a.model + " " + r.a.provider.label).contains(q.trim(), ignoreCase = true)) &&
            (!visionOnly || r.a.provider.vision)
    }.let { l -> if (vm.routeStrategy == AgentRouter.Strategy.PRIORITY) l else l.sortedByDescending { it.score } }

    val pressured = states.filter { it.disabled || it.cooldownUntil > now }
    val readyCount = vm.agents.count { a ->
        val st = stateOf(a)
        a.enabled && (a.apiKey.isNotBlank() || a.provider.manual || a.provider.custom) && !(st?.disabled ?: false) && (st?.cooldownUntil ?: 0L) <= now
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(trf("%s جاهز • %s إجمالاً", readyCount, vm.agents.size), style = MaterialTheme.typography.bodyMedium) }

        // استهلاك 30 يوماً
        item {
            SoftCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("الاستهلاك خلال 30 يوماً"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(trf("%s توكن تقديرياً", fmtTokens(totalTokens)), style = MaterialTheme.typography.labelMedium)
                    }
                    if (usage.isEmpty()) {
                        Text(tr("لا استهلاك بعد. الأرقام تقديرية من حجم النص، وتُسجَّل على هذا الجهاز فقط."), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                            usage.forEachIndexed { i, u ->
                                Box(Modifier.weight(u.tokens.toFloat()).fillMaxHeight().background(PALETTE[i % PALETTE.size]))
                            }
                        }
                        (if (showAllUsage) usage else usage.take(6)).forEachIndexed { i, u ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusDot(PALETTE[i % PALETTE.size], 8)
                                Text(u.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text(fmtTokens(u.tokens), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (usage.size > 6) TextButton(onClick = { showAllUsage = !showAllUsage }) {
                            Text(if (showAllUsage) tr("إخفاء") else trf("عرض كل %s نموذج", usage.size))
                        }
                    }
                }
            }
        }

        // استراتيجية التوجيه
        item {
            SoftCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("استراتيجية التوجيه"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            trf("موثوقية %s٪ · سرعة %s٪ · ذكاء %s٪", (w.reliability * 100).roundToInt(), (w.speed * 100).roundToInt(), (w.intelligence * 100).roundToInt()),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AgentRouter.Strategy.values().forEach { st ->
                            FilterChip(selected = vm.routeStrategy == st, onClick = { vm.selectStrategy(st) }, label = { Text(strategyLabel(st)) })
                        }
                    }
                    Text(tr("الترتيب أدناه حسب النتيجة بأوزان الاستراتيجية المختارة، وتتحدّث الموثوقية والسرعة من الطلبات الحيّة."), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenRouting) { Text(tr("التحليلات وترتيب الأولوية ›")) }
                }
            }
        }

        // ضغط الموجّه
        item {
            SoftCard(Modifier.clickable { showPressure = !showPressure }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (pressured.isEmpty()) Brand.Green else Brand.Amber)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr("ضغط الموجّه"), style = MaterialTheme.typography.titleSmall)
                            Text(tr("وكلاء في راحة أو معطّلون الآن"), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(trf("%s نشط", pressured.size), style = MaterialTheme.typography.labelMedium)
                    }
                    if (showPressure) {
                        if (pressured.isEmpty()) Text(tr("كل الوكلاء سليمون."), style = MaterialTheme.typography.bodySmall)
                        pressured.forEach { st ->
                            Text(
                                st.agent.name + " — " + if (st.disabled) tr("معطّل") else trf("راحة %s ث", (st.cooldownUntil - now) / 1000) +
                                    if (st.note.isNotEmpty()) " • ${st.note.take(60)}" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(q, { q = it }, placeholder = { Text(tr("ابحث في النماذج…")) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = visionOnly, onClick = { visionOnly = !visionOnly }, label = { Text(tr("رؤية")) })
            }
        }

        if (vm.agents.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("لا توجد نماذج بعد"), style = MaterialTheme.typography.titleMedium)
                    Text(tr("أضف مفاتيح مجانية من المزوّدين لفتح نماذج وحصص أكثر."), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onOpenKeys) { Text(tr("إضافة مفتاح")) }
                }
            }
        }

        itemsIndexed(rows, key = { _, r -> "m" + r.a.id }) { rank, r ->
            val a = r.a
            val st = stateOf(a)
            val (label, color) = when {
                !a.enabled -> tr("متوقف") to MaterialTheme.colorScheme.outline
                st?.disabled == true -> tr("معطّل") to Brand.Red
                (st?.cooldownUntil ?: 0L) > now -> tr("راحة") to Brand.Amber
                a.apiKey.isBlank() && !a.provider.manual && !a.provider.custom -> tr("بلا مفتاح") to Brand.Amber
                else -> tr("جاهز") to Brand.Green
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${rank + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(18.dp))
                    ProviderBadge(a.provider.label, 36)
                    Column(Modifier.weight(1f)) {
                        Text((if (a.id == vm.leadId) "⭐ " else "") + a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            listOfNotNull(a.provider.label, a.model.take(26), if (a.provider.vision) tr("رؤية") else null, if (a.provider.manual) tr("يدوي") else null)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(String.format(Locale.US, "%.3f", r.score), style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            StatusDot(color, 7)
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Switch(checked = a.enabled, onCheckedChange = { vm.toggleAgent(a.id) })
                }
                Row(Modifier.padding(start = 28.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniBar(tr("موثوقية"), r.rel, Color(0xFF22C55E))
                    MiniBar(tr("سرعة"), r.speed, Color(0xFF3B82F6))
                    MiniBar(tr("ذكاء"), r.intel, Color(0xFF8B5CF6))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
