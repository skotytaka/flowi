@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.budget_planner

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Home (Budget) ─────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(state: AppState, th: AppThemeData, tr: Map<String, String>, sym: String) {
    var showIncome         by remember { mutableStateOf(false) }
    var showCategory       by remember { mutableStateOf(false) }
    var spendTarget        by remember { mutableStateOf<String?>(null) }
    var editCategoryName   by remember { mutableStateOf<String?>(null) }
    var deleteCategoryName by remember { mutableStateOf<String?>(null) }
    var period             by remember { mutableStateOf("all") }

    // derivedStateOf: these recompute only when the underlying data changes,
    // not on every recomposition
    val pExp by remember { derivedStateOf {
        state.expenses.filter { filterByPeriod(it.date, period, state.monthResetDay) }
    } }
    val pInc by remember { derivedStateOf {
        state.incomes.filter { filterByPeriod(it.date, period, state.monthResetDay) }
    } }
    val catNames by remember { derivedStateOf {
        state.categories.keys.filter { it != "__unallocated__" }.sorted()
    } }

    val pExpSum      = pExp.sumOf { it.amount }
    val pIncSum      = pInc.sumOf { it.amount }
    val unallocAlloc = computeUnallocated(state.categories, state.totalMoney)
    val unallocSpent = state.categories["__unallocated__"]?.spent ?: 0.0
    val shownTotal   = animateMoney(state.totalMoney)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "chips") {
            PeriodChips(
                listOf(
                    "all"   to t(tr,"period_all"),
                    "month" to t(tr,"period_month"),
                    "year"  to t(tr,"period_year")
                ),
                period, th
            ) { period = it }
        }

        // Gradient balance header with an animated counter
        item(key = "balance") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(th.accent, th.accent.copy(alpha = 0.72f))))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    t(tr,"total_money"),
                    color = th.bg.copy(alpha = 0.75f),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
                Text(
                    "${shownTotal.money()} $sym",
                    color = th.bg, fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatMini("↑ ${pIncSum.money()} $sym", t(tr,"income_lbl"), th.bg)
                    StatMini("↓ ${pExpSum.money()} $sym", t(tr,"expenses_lbl"), th.bg)
                    StatMini("= ${(pIncSum - pExpSum).money()} $sym", t(tr,"balance_lbl"), th.bg)
                }
            }
        }

        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick   = { showIncome = true },
                    modifier  = Modifier.weight(1f).height(46.dp),
                    colors    = ButtonDefaults.buttonColors(containerColor = th.cardBg, contentColor = th.fg),
                    shape     = RoundedCornerShape(14.dp),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = kGood)
                    Spacer(Modifier.width(6.dp))
                    Text(t(tr,"add_income"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Button(
                    onClick   = { showCategory = true },
                    modifier  = Modifier.weight(1f).height(46.dp),
                    colors    = ButtonDefaults.buttonColors(containerColor = th.accent, contentColor = th.bg),
                    shape     = RoundedCornerShape(14.dp),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = th.bg)
                    Spacer(Modifier.width(6.dp))
                    Text(t(tr,"add_category"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        items(catNames, key = { it }) { name ->
            val cat = state.categories[name] ?: return@items
            val dispSpent = if (period == "all") cat.spent
                            else pExp.filter { it.category == name }.sumOf { it.amount }
            val modeStr = when (cat.mode) {
                "percentage" -> "${cat.value.toInt()}%"
                "monthly"    -> "${"%.0f".format(cat.value)}$sym${t(tr,"per_month")}"
                "annual"     -> "${"%.0f".format(cat.value)}$sym${t(tr,"per_year")}"
                else          -> cat.value.toString()
            }
            CategoryCard(
                name        = name,
                modeStr     = modeStr,
                alloc       = cat.allocated(state.totalMoney),
                spent       = dispSpent,
                sym         = sym,
                th          = th,
                tr          = tr,
                isDeletable = true,
                onSpend     = { spendTarget = name },
                onEdit      = { editCategoryName = name },
                onDelete    = { deleteCategoryName = name },
                modifier    = Modifier.animateItem()
            )
        }

        if (unallocAlloc > 0) {
            item(key = "__unallocated__") {
                val dispSpent = if (period == "all") unallocSpent
                                else pExp.filter { it.category == "__unallocated__" }.sumOf { it.amount }
                CategoryCard(
                    name        = t(tr,"unallocated"),
                    modeStr     = "~",
                    alloc       = unallocAlloc,
                    spent       = dispSpent,
                    sym         = sym,
                    th          = th,
                    tr          = tr,
                    isDeletable = false,
                    onSpend     = { spendTarget = "__unallocated__" },
                    onEdit      = {},
                    onDelete    = {},
                    modifier    = Modifier.animateItem()
                )
            }
        }
    }

    // ── Dialogs ──
    if (showIncome)   IncomeDialog(state, tr, th) { showIncome = false }
    if (showCategory) CategoryDialog(null, state, tr, th) { showCategory = false }
    editCategoryName?.let { name ->
        CategoryDialog(name, state, tr, th) { editCategoryName = null }
    }
    deleteCategoryName?.let { name ->
        ConfirmDialog(t(tr,"confirm_delete_category"), tr, th) { ok ->
            if (ok) state.categories.remove(name)  // expenses stay in history
            deleteCategoryName = null
        }
    }
    spendTarget?.let { target ->
        SpendDialog(target, state, tr, th, sym) { spendTarget = null }
    }
}

// ── Stats ─────────────────────────────────────────────────────────────────────

@Composable
fun StatsScreen(state: AppState, th: AppThemeData, tr: Map<String, String>, sym: String) {
    var period    by remember { mutableStateOf("month") }
    var chartMode by remember { mutableStateOf("pie") }

    val filtered by remember { derivedStateOf {
        state.expenses.filter { filterByPeriod(it.date, period, state.monthResetDay) }
    } }
    val catSums = remember(filtered) {
        filtered.groupBy { it.category }
            .map { (cat, list) -> cat to list.sumOf { it.amount } }
            .sortedByDescending { it.second }
    }
    val total = catSums.sumOf { it.second }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(top = 6.dp, bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PeriodChips(
            listOf(
                "month" to t(tr,"period_month"),
                "year"  to t(tr,"period_year"),
                "all"   to t(tr,"period_all")
            ),
            period, th
        ) { period = it }

        if (filtered.isEmpty()) {
            EmptyState(
                t(tr,"no_expenses_period"), th,
                Modifier.fillMaxWidth().padding(top = 90.dp)
            )
        } else {
            SegmentedToggle(
                options = listOf(
                    t(tr,"expenses_by_category"),
                    if (period == "year") t(tr,"by_month") else t(tr,"by_day")
                ),
                selectedIndex = if (chartMode == "pie") 0 else 1,
                th = th
            ) { chartMode = if (it == 0) "pie" else "bar" }

            Crossfade(targetState = chartMode, label = "chartMode") { mode ->
                if (mode == "pie") {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        AnimatedDonutChart(
                            entries     = catSums.map { (c, v) -> catDisplay(c, tr) to v },
                            centerLabel = t(tr,"expenses_lbl"),
                            centerValue = "${total.money()} $sym",
                            th          = th
                        )
                        Column(Modifier.fillMaxWidth()) {
                            catSums.forEachIndexed { i, (cat, amt) ->
                                LegendRow(
                                    color = kChartColors[i % kChartColors.size],
                                    label = catDisplay(cat, tr),
                                    value = "${amt.money()} $sym",
                                    share = "${if (total > 0) (amt / total * 100).toInt() else 0}%",
                                    th    = th
                                )
                            }
                        }
                    }
                } else {
                    val barData: List<Pair<String, Double>> = if (period == "year") {
                        val map = (1..12).associate { "%02d".format(it) to 0.0 }.toMutableMap()
                        filtered.forEach { e ->
                            val m = e.date.drop(5).take(2)
                            map[m] = (map[m] ?: 0.0) + e.amount
                        }
                        map.toSortedMap().toList()
                    } else {
                        val map = mutableMapOf<String, Double>()
                        filtered.forEach { e ->
                            val d = e.date.takeLast(2)
                            map[d] = (map[d] ?: 0.0) + e.amount
                        }
                        map.toSortedMap().toList()
                    }
                    AnimatedBarChart(barData, th)
                }
            }
        }
    }
}

// ── History ───────────────────────────────────────────────────────────────────

@Composable
fun HistoryScreen(state: AppState, th: AppThemeData, tr: Map<String, String>, sym: String) {
    var seg        by remember { mutableIntStateOf(0) }   // 0 = expenses, 1 = income
    var filterKey  by remember { mutableStateOf<String?>(null) }
    var editExpIdx by remember { mutableStateOf<Int?>(null) }
    var delExpIdx  by remember { mutableStateOf<Int?>(null) }
    var editIncIdx by remember { mutableStateOf<Int?>(null) }
    var delIncIdx  by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(seg) { filterKey = null }

    // Newest first: date descending, same-date entries by insertion order descending
    val sortedExp by remember { derivedStateOf {
        state.expenses.mapIndexed { i, e -> i to e }
            .sortedWith(compareByDescending<Pair<Int, Expense>> { it.second.date }.thenByDescending { it.first })
    } }
    val sortedInc by remember { derivedStateOf {
        state.incomes.mapIndexed { i, e -> i to e }
            .sortedWith(compareByDescending<Pair<Int, Income>> { it.second.date }.thenByDescending { it.first })
    } }
    val expCats by remember { derivedStateOf { state.expenses.map { it.category }.distinct() } }
    val incSrcs by remember { derivedStateOf { state.incomes.map { it.source }.distinct() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(6.dp))
        SegmentedToggle(
            options       = listOf(t(tr,"expenses_lbl"), t(tr,"income_lbl")),
            selectedIndex = seg,
            th            = th
        ) { seg = it }

        // Filter chips (only shown when there is something to filter)
        val filterKeys = if (seg == 0) expCats else incSrcs
        if (filterKeys.size > 1) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = filterKey == null,
                    onClick  = { filterKey = null },
                    label    = { Text(t(tr,"all_sources"), fontSize = 12.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = th.accent,
                        selectedLabelColor     = th.bg,
                        labelColor             = th.fg.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(50)
                )
                filterKeys.forEach { key ->
                    FilterChip(
                        selected = filterKey == key,
                        onClick  = { filterKey = if (filterKey == key) null else key },
                        label    = { Text(catDisplay(key, tr), fontSize = 12.sp) },
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

        val shownExp = if (filterKey == null) sortedExp else sortedExp.filter { it.second.category == filterKey }
        val shownInc = if (filterKey == null) sortedInc else sortedInc.filter { it.second.source == filterKey }
        val isEmpty  = if (seg == 0) shownExp.isEmpty() else shownInc.isEmpty()

        if (isEmpty) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(t(tr,"no_items"), th)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding      = PaddingValues(top = 10.dp, bottom = 96.dp)
            ) {
                if (seg == 0) {
                    items(shownExp, key = { "e${it.first}" }) { (idx, exp) ->
                        HistoryRow(
                            title       = catDisplay(exp.category, tr),
                            subtitle    = "${exp.description.ifBlank { "—" }} · ${exp.date}",
                            amountText  = "−${exp.amount.money()} $sym",
                            amountColor = kBad,
                            th          = th,
                            onEdit      = { editExpIdx = idx },
                            onDelete    = { delExpIdx  = idx },
                            modifier    = Modifier.animateItem()
                        )
                    }
                } else {
                    items(shownInc, key = { "i${it.first}" }) { (idx, inc) ->
                        HistoryRow(
                            title       = inc.source,
                            subtitle    = inc.date,
                            amountText  = "+${inc.amount.money()} $sym",
                            amountColor = kGood,
                            th          = th,
                            onEdit      = { editIncIdx = idx },
                            onDelete    = { delIncIdx  = idx },
                            modifier    = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    // ── Dialogs ──
    editExpIdx?.let { idx -> EditExpenseDialog(idx, state, tr, th) { editExpIdx = null } }
    delExpIdx?.let { idx ->
        ConfirmDialog(t(tr,"confirm_delete_expense"), tr, th) { ok ->
            if (ok) state.removeExpense(idx)
            delExpIdx = null
        }
    }
    editIncIdx?.let { idx -> EditIncomeDialog(idx, state, tr, th) { editIncIdx = null } }
    delIncIdx?.let { idx ->
        ConfirmDialog(t(tr,"confirm_delete_income"), tr, th) { ok ->
            if (ok) state.removeIncome(idx)
            delIncIdx = null
        }
    }
}

// ── Settings ──────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(state: AppState, th: AppThemeData, tr: Map<String, String>) {
    var showReset        by remember { mutableStateOf(false) }
    var langExpanded     by remember { mutableStateOf(false) }
    var themeExpanded    by remember { mutableStateOf(false) }
    var currExpanded     by remember { mutableStateOf(false) }
    var resetDayExpanded by remember { mutableStateOf(false) }
    var aboutExpanded    by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)
    ) {

        item(key = "language") {
            AccordionSection(
                title    = t(tr, "language"),
                subtitle = kLanguageNames[state.language] ?: state.language,
                expanded = langExpanded,
                th       = th,
                onToggle = { langExpanded = !langExpanded }
            ) {
                kLanguageNames.forEach { (code, name) ->
                    RadioRow(label = name, selected = state.language == code, th = th) {
                        state.language = code
                    }
                }
            }
        }

        item(key = "theme") {
            AccordionSection(
                title    = t(tr, "theme"),
                subtitle = t(tr, "theme_${state.themeName}", state.themeName),
                expanded = themeExpanded,
                th       = th,
                onToggle = { themeExpanded = !themeExpanded }
            ) {
                kThemeKeys.forEach { key ->
                    val preview = kThemes.getValue(key)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { state.themeName = key }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.themeName == key,
                            onClick  = { state.themeName = key },
                            colors   = RadioButtonDefaults.colors(selectedColor = th.accent)
                        )
                        Text(t(tr, "theme_$key", key), color = th.fg, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier.size(16.dp)
                                    .background(preview.bg, RoundedCornerShape(4.dp))
                                    .border(1.dp, th.fg.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            )
                            Box(Modifier.size(16.dp).background(preview.accent, RoundedCornerShape(4.dp)))
                            Box(
                                Modifier.size(16.dp)
                                    .background(preview.cardBg, RoundedCornerShape(4.dp))
                                    .border(1.dp, th.fg.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }
            }
        }

        item(key = "currency") {
            AccordionSection(
                title    = t(tr, "currency"),
                subtitle = "${state.currency} (${kCurrencies[state.currency] ?: "?"})",
                expanded = currExpanded,
                th       = th,
                onToggle = { currExpanded = !currExpanded }
            ) {
                kCurrencies.forEach { (name, sym) ->
                    RadioRow(label = "$name  $sym", selected = state.currency == name, th = th) {
                        state.currency = name
                    }
                }
            }
        }

        item(key = "resetDay") {
            AccordionSection(
                title    = t(tr, "month_reset_day"),
                subtitle = "${t(tr, "day")} ${state.monthResetDay}",
                expanded = resetDayExpanded,
                th       = th,
                onToggle = { resetDayExpanded = !resetDayExpanded }
            ) {
                Text(
                    t(tr, "month_reset_desc"),
                    fontSize = 12.sp,
                    color    = th.fg.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                // 1..31 laid out as a calendar-like grid, 7 per row
                (1..31).chunked(7).forEach { week ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        week.forEach { day ->
                            val selected = state.monthResetDay == day
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) th.accent else th.fg.copy(alpha = 0.06f))
                                    .clickable { state.monthResetDay = day },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "$day",
                                    fontSize   = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color      = if (selected) th.bg else th.fg
                                )
                            }
                        }
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        item(key = "about") {
            AccordionSection(
                title    = t(tr, "about"),
                subtitle = "Flowi v0.8",
                expanded = aboutExpanded,
                th       = th,
                onToggle = { aboutExpanded = !aboutExpanded }
            ) {
                AboutContent(th, tr)
            }
        }

        item(key = "reset") {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick   = { showReset = true },
                modifier  = Modifier.fillMaxWidth().height(52.dp),
                colors    = ButtonDefaults.buttonColors(containerColor = kBad.copy(alpha = 0.15f)),
                shape     = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) {
                Icon(Icons.Default.Delete, null, tint = kBad, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t(tr,"reset"), color = kBad, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title            = { Text(t(tr,"confirm_reset_title"), color = th.fg, fontWeight = FontWeight.Bold) },
            text             = { Text(t(tr,"confirm_reset"), color = th.fg) },
            confirmButton    = {
                TextButton(onClick = {
                    state.resetAll()
                    showReset = false
                }) { Text(t(tr,"yes"), color = kBad, fontWeight = FontWeight.Bold) }
            },
            dismissButton    = {
                TextButton(onClick = { showReset = false }) { Text(t(tr,"no"), color = th.accent) }
            },
            containerColor   = th.cardBg,
            shape            = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun AboutContent(th: AppThemeData, tr: Map<String, String>) {
    val uriHandler = LocalUriHandler.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(th.accent.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).background(th.accent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("F", color = th.bg, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            }
            Column {
                Text("Flowi", fontWeight = FontWeight.Bold, color = th.fg, fontSize = 17.sp)
                Text("${t(tr,"version")} 0.8", fontSize = 12.sp, color = th.fg.copy(alpha = 0.5f))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(t(tr, "author"), color = th.fg.copy(alpha = 0.6f), fontSize = 13.sp)
            Text("skotytaka", color = th.fg, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { uriHandler.openUri("https://github.com/skotytaka") }
                .background(th.fg.copy(alpha = 0.04f))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(t(tr, "open_source"), color = th.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Icon(
                Icons.Default.KeyboardArrowRight, null,
                tint = th.accent, modifier = Modifier.size(20.dp)
            )
        }
    }
}
