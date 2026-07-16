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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

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
// Progress bar fills with a spring animation. Colors (v0.9): red only when the
// budget is truly exceeded, GREEN when it is spent exactly to the cent, accent
// otherwise. All remaining-math is rounded to cents first, so float dust can
// no longer produce "-0.01" on an exactly-spent category.

@Composable
fun CategoryCard(
    name: String,
    modeStr: String,
    alloc: Double,
    spent: Double,
    sym: String,
    th: AppThemeData,
    tr: Map<String, String>,
    onSpend: () -> Unit,
    isDragging: Boolean = false,
    modifier: Modifier = Modifier
) {
    val rem        = roundCents(alloc - spent)
    val overBudget = rem < 0.0
    val exactSpent = !overBudget && rem == 0.0 && spent > 0 && alloc > 0
    val target     = if (alloc > 0) (spent / alloc).toFloat().coerceIn(0f, 1f) else 0f
    // Kept as State objects: their .value is read inside drawBehind only, so the
    // bar animates at the draw phase without recomposing the card every frame.
    val progressState = animateFloatAsState(
        target, spring(stiffness = Spring.StiffnessLow), label = "catProgress"
    )
    val stateColor = when {
        overBudget -> kBad
        exactSpent -> kGood
        else       -> th.accent
    }
    val barColorState = animateColorAsState(stateColor, tween(300), label = "catBarColor")
    val cardElev by animateFloatAsState(if (isDragging) 12f else 0f, tween(180), label = "catElev")

    Card(
        modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = th.cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElev.dp)
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
                SmallActionBox(bg = th.accent, onClick = onSpend) {
                    Icon(Icons.Default.Add, null, tint = th.bg, modifier = Modifier.size(18.dp))
                }
            }

            // Animated progress bar (fill + color read only at draw time)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(th.fg.copy(alpha = 0.08f))
                    .drawBehind {
                        val w = size.width * progressState.value
                        if (w > 0f) drawRoundRect(
                            color        = barColorState.value,
                            size         = Size(w, size.height),
                            cornerRadius = CornerRadius(size.height / 2f)
                        )
                    }
            )

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
                    color      = stateColor
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

// Square selection row (v0.9 redesign): replaces the old round radio buttons in
// Settings and pickers with a compact square check box that fits the card style.
@Composable
fun SquareOptionRow(
    label: String,
    selected: Boolean,
    th: AppThemeData,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val boxBg by animateColorAsState(
        if (selected) th.accent else Color.Transparent, tween(180), label = "sqBg"
    )
    val boxBorder by animateColorAsState(
        if (selected) th.accent else th.fg.copy(alpha = 0.35f), tween(180), label = "sqBorder"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(boxBg)
                .border(1.5.dp, boxBorder, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(Icons.Default.Check, null, tint = th.bg, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = th.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

// Square segmented selector (v0.9 redesign): used for percentage/monthly/annual
// when creating a category, and for the AI provider choice in Settings.
@Composable
fun SquareSegmented(
    options: List<Pair<String, String>>,
    selectedKey: String,
    th: AppThemeData,
    onSelect: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            val sel = key == selectedKey
            val bg by animateColorAsState(
                if (sel) th.accent else Color.Transparent, tween(180), label = "segSqBg"
            )
            val bd by animateColorAsState(
                if (sel) th.accent else th.fg.copy(alpha = 0.25f), tween(180), label = "segSqBd"
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .border(1.dp, bd, RoundedCornerShape(10.dp))
                    .clickable { onSelect(key) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize   = 12.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color      = if (sel) th.bg else th.fg.copy(alpha = 0.75f),
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
            }
        }
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

// ── Swipe-to-reveal (v0.9) ────────────────────────────────────────────────────
// Telegram-style: dragging the card to the left reveals action buttons behind
// it. The card snaps open/closed with a spring; only one card is open at a time
// (the parent keeps the open key and closes the others).

@Composable
fun SwipeRevealBox(
    isOpen: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    actionWidth: Dp = 132.dp,
    content: @Composable () -> Unit
) {
    val revealPx = with(LocalDensity.current) { actionWidth.toPx() }
    val offsetX  = remember { Animatable(0f) }
    val scope    = rememberCoroutineScope()

    // Follow external open/close state (e.g. another card was opened)
    LaunchedEffect(isOpen, enabled) {
        val target = if (isOpen && enabled) -revealPx else 0f
        if (offsetX.targetValue != target) {
            offsetX.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }

    Box(modifier.fillMaxWidth()) {
        // Action buttons behind the card. The reveal fraction is computed inside
        // graphicsLayer, so swiping animates at the draw phase only.
        Row(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val progress = (-offsetX.value / revealPx).coerceIn(0f, 1f)
                    alpha  = progress
                    scaleX = 0.85f + 0.15f * progress
                    scaleY = 0.85f + 0.15f * progress
                },
            horizontalArrangement = Arrangement.End,
            verticalAlignment     = Alignment.CenterVertically
        ) { actions() }

        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .then(
                    if (enabled) Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (offsetX.value < -revealPx / 2) {
                                        offsetX.animateTo(-revealPx, spring(stiffness = Spring.StiffnessMediumLow))
                                        onOpen()
                                    } else {
                                        offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                        onClose()
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                    onClose()
                                }
                            },
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                val next = (offsetX.value + dx).coerceIn(-revealPx * 1.12f, 0f)
                                scope.launch { offsetX.snapTo(next) }
                            }
                        )
                    } else Modifier
                )
                .then(
                    if (enabled && isOpen) Modifier.pointerInput(isOpen) {
                        detectTapGestures { onClose() }   // tap the card to close it
                    } else Modifier
                )
        ) { content() }
    }
}

// One revealed action: a colored square with an icon and a tiny label.
@Composable
fun SwipeActionButton(
    icon: ImageVector,
    label: String,
    bg: Color,
    fg: Color,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .width(58.dp)
            .fillMaxHeight()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = fg, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Drag & drop reorder (v0.9) ───────────────────────────────────────────────
// Long-press a category card, then drag it up/down; the other cards animate out
// of the way (they keep Modifier.animateItem, the dragged one is translated by
// hand). Only keys accepted by [isDraggableKey] participate, so the header
// items of the LazyColumn are never swap targets.

class DragDropState(
    private val listState: LazyListState,
    private val isDraggableKey: (Any?) -> Boolean,
    private val onMove: (fromKey: String, toKey: String) -> Unit
) {
    var draggingKey by mutableStateOf<String?>(null)
        private set
    private var draggedDelta  by mutableFloatStateOf(0f)
    private var initialOffset by mutableIntStateOf(0)

    /** Set while dragging near the top/bottom edge; the screen scrolls the list. */
    var scrollSpeed by mutableFloatStateOf(0f)
        private set

    private val draggingInfo: LazyListItemInfo?
        get() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey }

    /** Visual translation of the dragged item (self-corrects after each swap). */
    val draggingOffset: Float
        get() = draggingInfo?.let { initialOffset + draggedDelta - it.offset } ?: 0f

    fun onDragStart(key: String) {
        draggingKey   = key
        draggedDelta  = 0f
        initialOffset = draggingInfo?.offset ?: 0
    }

    fun onDragStop() {
        draggingKey = null
        draggedDelta = 0f
        scrollSpeed = 0f
    }

    fun onDrag(dy: Float) {
        draggedDelta += dy
        val item   = draggingInfo ?: return
        val top    = item.offset + draggingOffset
        val bottom = top + item.size
        val middle = (top + bottom) / 2f

        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            other.key != draggingKey && isDraggableKey(other.key) &&
                middle >= other.offset && middle <= other.offset + other.size
        }
        if (target != null) {
            onMove(
                (draggingKey as String).removePrefix(kCatKeyPrefix),
                (target.key as String).removePrefix(kCatKeyPrefix)
            )
        }

        val vpTop    = listState.layoutInfo.viewportStartOffset.toFloat()
        val vpBottom = listState.layoutInfo.viewportEndOffset.toFloat()
        scrollSpeed = when {
            bottom > vpBottom - 56f && draggedDelta > 0 ->  16f
            top    < vpTop    + 56f && draggedDelta < 0 -> -16f
            else                                        ->  0f
        }
    }
}

/** LazyColumn item keys for categories are prefixed to avoid clashing with header keys. */
const val kCatKeyPrefix = "cat::"

// ── "No-no" shake (v0.9.6) ───────────────────────────────────────────────────
// Bump [trigger] to make the content wobble left-right like a head shake —
// used by dialogs when OK is pressed while the input is invalid.

@Composable
fun Modifier.shakeOnTrigger(trigger: Int): Modifier {
    val shift = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            listOf(-16f, 14f, -10f, 7f, -4f, 0f).forEach { target ->
                shift.animateTo(target, tween(45))
            }
        }
    }
    return this.offset { IntOffset(shift.value.roundToInt(), 0) }
}

// ── Date picking (v0.9) ──────────────────────────────────────────────────────
// Replaces the hand-typed "YYYY-MM-DD" fields. The grid intentionally mirrors
// the "Month Reset Day" grid in Settings that users already know.

@Composable
fun DateField(
    value: String,                 // "" means "today"
    label: String,
    langCode: String,
    th: AppThemeData,
    tr: Map<String, String>,
    onChange: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val display = if (value.isBlank()) "${t(tr, "today")} · ${todayStr()}" else value

    Column {
        Text(label, fontSize = 12.sp, color = th.fg.copy(alpha = 0.6f))
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, th.fg.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .clickable { showPicker = true }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(display, color = th.fg, fontSize = 14.sp)
            Icon(Icons.Default.DateRange, null, tint = th.accent, modifier = Modifier.size(18.dp))
        }
    }

    if (showPicker) {
        CalendarPickerDialog(
            initial   = value.ifBlank { todayStr() },
            langCode  = langCode,
            th = th, tr = tr,
            onDismiss = { showPicker = false },
            onPick    = { onChange(it); showPicker = false }
        )
    }
}

@Composable
fun CalendarPickerDialog(
    initial: String,
    langCode: String,
    th: AppThemeData,
    tr: Map<String, String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val locale = remember(langCode) { Locale(langCode) }
    var selected by remember { mutableStateOf(normalizeDate(initial) ?: todayStr()) }
    var shownMonth by remember {
        mutableStateOf(
            Calendar.getInstance().apply {
                parseDate(normalizeDate(initial) ?: todayStr())?.let { time = it }
                set(Calendar.DAY_OF_MONTH, 1)
            }
        )
    }
    val monthTitle = remember(shownMonth.timeInMillis, locale) {
        SimpleDateFormat("LLLL yyyy", locale).format(shownMonth.time)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
    val weekdayInitials = remember(locale) {
        val fmt = SimpleDateFormat("EEEEE", locale)
        val cal = Calendar.getInstance()
        (0..6).map { i ->
            cal.set(2024, Calendar.JANUARY, 1 + i)   // 2024-01-01 was a Monday
            fmt.format(cal.time)
        }
    }
    val today = todayStr()

    fun shiftMonth(by: Int) {
        shownMonth = (shownMonth.clone() as Calendar).apply { add(Calendar.MONTH, by) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(tr, "pick_date"), color = th.fg, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                // ‹  July 2026  ›
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                            .background(th.fg.copy(alpha = 0.06f))
                            .clickable { shiftMonth(-1) },
                        contentAlignment = Alignment.Center
                    ) { Text("‹", color = th.fg, fontSize = 20.sp) }
                    Text(
                        monthTitle,
                        modifier   = Modifier.weight(1f),
                        color      = th.fg,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 15.sp,
                        textAlign  = TextAlign.Center
                    )
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                            .background(th.fg.copy(alpha = 0.06f))
                            .clickable { shiftMonth(1) },
                        contentAlignment = Alignment.Center
                    ) { Text("›", color = th.fg, fontSize = 20.sp) }
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    weekdayInitials.forEach { wd ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(wd, fontSize = 11.sp, color = th.fg.copy(alpha = 0.4f))
                        }
                    }
                }

                val year  = shownMonth.get(Calendar.YEAR)
                val month = shownMonth.get(Calendar.MONTH)
                val daysInMonth = shownMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
                val firstDow = ((shownMonth.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY) + 7) % 7
                val cells: List<Int?> = List(firstDow) { null } + (1..daysInMonth).toList()

                cells.chunked(7).forEach { week ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        week.forEach { day ->
                            if (day == null) {
                                Spacer(Modifier.weight(1f).aspectRatio(1f))
                            } else {
                                val dateStr = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                                val isSel   = dateStr == selected
                                val isToday = dateStr == today
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSel) th.accent else th.fg.copy(alpha = 0.06f))
                                        .then(
                                            if (isToday && !isSel)
                                                Modifier.border(1.dp, th.accent, RoundedCornerShape(10.dp))
                                            else Modifier
                                        )
                                        .clickable { selected = dateStr },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "$day",
                                        fontSize   = 13.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color      = if (isSel) th.bg else th.fg
                                    )
                                }
                            }
                        }
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f).aspectRatio(1f)) }
                    }
                }

                TextButton(onClick = {
                    selected = today
                    shownMonth = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
                }) { Text(t(tr, "today"), color = th.accent, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) {
                Text(t(tr, "ok"), color = th.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t(tr, "cancel"), color = th.fg.copy(alpha = 0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}
