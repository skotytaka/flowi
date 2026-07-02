@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.budget_planner

import android.graphics.Paint as NativePaint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Animated number ───────────────────────────────────────────────────────────
// Smoothly counts from the previous value to the new one. Works on Double
// directly (no float precision loss) and always lands exactly on the target.

@Composable
fun animateMoney(target: Double): Double {
    var displayed by remember { mutableDoubleStateOf(target) }
    LaunchedEffect(target) {
        val start = displayed
        if (start == target) return@LaunchedEffect
        val anim = Animatable(0f)
        anim.animateTo(1f, tween(550, easing = EaseOutCubic)) {
            displayed = start + (target - start) * value.toDouble()
        }
        displayed = target
    }
    return displayed
}

// ── Small building blocks ─────────────────────────────────────────────────────

@Composable
fun StatMini(value: String, label: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = tint.copy(alpha = 0.65f), fontSize = 10.sp)
    }
}

@Composable
fun PeriodChips(
    options: List<Pair<String, String>>,
    selected: String,
    th: AppThemeData,
    onSelect: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            FilterChip(
                selected = selected == key,
                onClick  = { onSelect(key) },
                label    = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = th.accent,
                    selectedLabelColor     = th.bg,
                    labelColor             = th.fg.copy(alpha = 0.55f)
                ),
                shape = RoundedCornerShape(50)
            )
        }
    }
}

// Two-or-more segment pill switch with an animated highlight
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    th: AppThemeData,
    onSelect: (Int) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(th.cardBg, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val bg by animateColorAsState(
                if (selected) th.accent else Color.Transparent,
                tween(220), label = "segBg"
            )
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize   = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color      = if (selected) th.bg else th.fg.copy(alpha = 0.55f),
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun EmptyState(text: String, th: AppThemeData, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(450), label = "emptyFade")
    Column(
        modifier.alpha(a),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.Info, null, Modifier.size(38.dp), tint = th.fg.copy(alpha = 0.18f))
        Text(text, color = th.fg.copy(alpha = 0.4f), fontSize = 14.sp)
    }
}

// ── Category card ─────────────────────────────────────────────────────────────
// Progress bar fills with a spring animation; turns red when over budget.

@Composable
fun CategoryCard(
    name: String,
    modeStr: String,
    alloc: Double,
    spent: Double,
    sym: String,
    th: AppThemeData,
    tr: Map<String, String>,
    isDeletable: Boolean,
    onSpend: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rem        = alloc - spent
    val overBudget = rem < 0
    val target     = if (alloc > 0) (spent / alloc).toFloat().coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(
        target, spring(stiffness = Spring.StiffnessLow), label = "catProgress"
    )
    val barColor by animateColorAsState(
        if (overBudget) kBad else th.accent, tween(300), label = "catBarColor"
    )

    Card(
        modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = th.cardBg),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        fontWeight = FontWeight.SemiBold,
                        color      = th.fg,
                        fontSize   = 15.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Text(
                        "[$modeStr] · ${t(tr,"allocated")}: ${alloc.money()} $sym",
                        fontSize = 11.sp,
                        color    = th.fg.copy(alpha = 0.45f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isDeletable) {
                        SmallActionBox(bg = th.fg.copy(alpha = 0.07f), onClick = onEdit) {
                            Icon(Icons.Default.Edit, null, tint = th.fg.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
                        }
                        SmallActionBox(bg = kBad.copy(alpha = 0.12f), onClick = onDelete) {
                            Icon(Icons.Default.Delete, null, tint = kBad, modifier = Modifier.size(15.dp))
                        }
                    }
                    SmallActionBox(bg = th.accent, onClick = onSpend) {
                        Icon(Icons.Default.Add, null, tint = th.bg, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Animated progress bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(th.fg.copy(alpha = 0.08f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(barColor)
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${t(tr,"spent")}: ${spent.money()} $sym",
                    fontSize = 12.sp,
                    color    = th.fg.copy(alpha = 0.5f)
                )
                Text(
                    "${t(tr,"remaining")}: ${rem.money()} $sym",
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (overBudget) kBad else th.accent
                )
            }
        }
    }
}

// 32dp tappable icon box (IconButton forces a 48dp touch target that overlaps)
@Composable
fun SmallActionBox(bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

// ── History row ───────────────────────────────────────────────────────────────

@Composable
fun HistoryRow(
    title: String,
    subtitle: String,
    amountText: String,
    amountColor: Color,
    th: AppThemeData,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = th.cardBg),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Leading avatar with the first letter of the category / source
            Box(
                Modifier.size(38.dp).background(th.accent.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title.firstOrNull()?.uppercase() ?: "?",
                    color = th.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title, fontWeight = FontWeight.SemiBold, color = th.fg,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle, color = th.fg.copy(alpha = 0.45f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text(amountText, color = amountColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallActionBox(bg = th.fg.copy(alpha = 0.07f), onClick = onEdit) {
                    Icon(Icons.Default.Edit, null, tint = th.fg.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
                }
                SmallActionBox(bg = kBad.copy(alpha = 0.12f), onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, tint = kBad, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

// ── Accordion section (Settings) ──────────────────────────────────────────────

@Composable
fun AccordionSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    th: AppThemeData,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = th.cardBg),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold, color = th.fg, fontSize = 15.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 13.sp, color = th.accent)
                }
                val rot by animateFloatAsState(if (expanded) 180f else 0f, tween(250), label = "arrowRot")
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint     = th.fg.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp).rotate(rot)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically(tween(240)) + fadeIn(tween(240)),
                exit    = shrinkVertically(tween(200)) + fadeOut(tween(120))
            ) {
                Column {
                    HorizontalDivider(color = th.fg.copy(alpha = 0.06f))
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        content = content
                    )
                }
            }
        }
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, th: AppThemeData, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick  = onClick,
            colors   = RadioButtonDefaults.colors(selectedColor = th.accent)
        )
        Text(label, color = th.fg, fontSize = 14.sp)
    }
}

// ── Charts ────────────────────────────────────────────────────────────────────
// Both charts animate in whenever their data changes: the donut sweeps
// clockwise, the bars grow from the baseline.

@Composable
fun AnimatedDonutChart(
    entries: List<Pair<String, Double>>,
    centerLabel: String,
    centerValue: String,
    th: AppThemeData
) {
    val anim = remember(entries) { Animatable(0f) }
    LaunchedEffect(entries) { anim.animateTo(1f, tween(900, easing = EaseOutCubic)) }

    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(195.dp)) {
            val total = entries.sumOf { it.second }
            if (total <= 0) return@Canvas
            val stroke  = 30.dp.toPx()
            val inset   = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            var start   = -90f
            entries.forEachIndexed { i, (_, v) ->
                val sweep = (v / total * 360.0 * anim.value).toFloat()
                drawArc(
                    color      = kChartColors[i % kChartColors.size],
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter  = false,
                    topLeft    = Offset(inset, inset),
                    size       = arcSize,
                    style      = Stroke(width = stroke)
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerLabel, fontSize = 11.sp, color = th.fg.copy(alpha = 0.45f))
            Text(centerValue, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = th.fg)
        }
    }
}

@Composable
fun AnimatedBarChart(entries: List<Pair<String, Double>>, th: AppThemeData) {
    if (entries.isEmpty()) return
    val anim = remember(entries) { Animatable(0f) }
    LaunchedEffect(entries) { anim.animateTo(1f, tween(750, easing = EaseOutCubic)) }

    val maxVal = entries.maxOf { it.second }.coerceAtLeast(0.01)

    Canvas(Modifier.fillMaxWidth().height(210.dp).padding(bottom = 4.dp)) {
        val labelH = 22.dp.toPx()
        val chartH = size.height - labelH
        val total  = entries.size.coerceAtLeast(1)
        val barW   = (size.width / (total * 1.4f)).coerceAtMost(32.dp.toPx())
        val gap    = (size.width - barW * total) / (total + 1)
        val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        val paint  = NativePaint().apply {
            color       = th.fg.copy(alpha = 0.55f).toArgb()
            textSize    = 8.5.sp.toPx()
            textAlign   = NativePaint.Align.CENTER
            isAntiAlias = true
        }
        entries.forEachIndexed { i, (label, value) ->
            val barH = (value / maxVal * chartH * anim.value).toFloat().coerceAtLeast(2f)
            val x    = gap + i * (barW + gap)
            drawRoundRect(
                color        = kChartColors[i % kChartColors.size],
                topLeft      = Offset(x, chartH - barH),
                size         = Size(barW, barH),
                cornerRadius = corner
            )
            val step = when { total > 20 -> 5; total > 10 -> 3; else -> 1 }
            if (i % step == 0) {
                drawContext.canvas.nativeCanvas.drawText(label, x + barW / 2f, size.height - 2.dp.toPx(), paint)
            }
        }
    }
}

@Composable
fun LegendRow(color: Color, label: String, value: String, share: String, th: AppThemeData) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), color = th.fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontWeight = FontWeight.SemiBold, color = th.fg, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(share, color = th.fg.copy(alpha = 0.4f), fontSize = 11.sp)
    }
}
