@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.budget_planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Dialogs ───────────────────────────────────────────────────────────────────
// Every dialog owns its inputs and commits changes through the AppState
// mutation functions in Models.kt. v0.9: dates are picked with the calendar
// (DateField), never typed; the category mode selector is the new square
// segmented control; the quick-add expense dialog lists categories in the
// user's own order and preselects the top one.

@Composable
fun IncomeDialog(state: AppState, tr: Map<String, String>, th: AppThemeData, onDismiss: () -> Unit) {
    var source    by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var dateStr   by remember { mutableStateOf("") }          // "" = today
    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text(t(tr,"add_income"), color = th.fg, fontWeight = FontWeight.Bold) },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amountStr, onValueChange = { amountStr = it },
                    label  = { Text(t(tr,"income_amount")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = source, onValueChange = { source = it },
                    label = { Text(t(tr,"income_source")) },
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp)
                )
                DateField(
                    value = dateStr, label = t(tr,"date"),
                    langCode = state.language, th = th, tr = tr
                ) { dateStr = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amt = amountStr.toDoubleOrNull() ?: 0.0
                state.recordIncome(source, amt, dateStr)   // recordIncome normalizes "" -> today
                onDismiss()
            }) { Text(t(tr,"ok"), color = th.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton  = {
            TextButton(onClick = onDismiss) { Text(t(tr,"cancel"), color = th.fg.copy(alpha = 0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}

/** Create a category, or edit an existing one when [editName] is given. */
@Composable
fun CategoryDialog(
    editName: String?,
    state: AppState,
    tr: Map<String, String>,
    th: AppThemeData,
    sym: String,
    onDismiss: () -> Unit
) {
    val existing = editName?.let { state.categories[it] }
    var name      by remember { mutableStateOf(existing?.name ?: "") }
    var valueStr  by remember { mutableStateOf(existing?.value?.toString() ?: "") }
    var mode      by remember { mutableStateOf(existing?.mode ?: "percentage") }
    var overdraft by remember { mutableStateOf(existing?.overdraft ?: false) }
    var kind      by remember { mutableStateOf(existing?.kind ?: "spending") }   // v0.9.2
    var pctError  by remember { mutableStateOf(false) }                           // v0.9.5
    var shakeTick by remember { mutableIntStateOf(0) }                             // v0.9.6
    val haptic = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = {
            Text(
                if (editName != null) t(tr,"edit_category") else t(tr,"add_category"),
                color = th.fg, fontWeight = FontWeight.Bold
            )
        },
        text   = {
            Column(
                Modifier.shakeOnTrigger(shakeTick),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(t(tr,"category_name")) },
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp)
                )
                Text(t(tr,"mode"), fontSize = 12.sp, color = th.fg.copy(alpha = 0.6f))
                SquareSegmented(
                    options = listOf(
                        "percentage" to t(tr,"percentage"),
                        "monthly"    to t(tr,"monthly"),
                        "annual"     to t(tr,"annual")
                    ),
                    selectedKey = mode,
                    th = th
                ) { mode = it; pctError = false }
                if (mode == "percentage") {
                    Text(t(tr,"pct_hint"), fontSize = 11.sp, color = th.fg.copy(alpha = 0.45f))
                }
                // v0.9.2: what this category IS — normal spending or set-aside savings
                Text(t(tr,"category_kind"), fontSize = 12.sp, color = th.fg.copy(alpha = 0.6f))
                SquareSegmented(
                    options = listOf(
                        "spending" to t(tr,"kind_spending"),
                        "saving"   to t(tr,"kind_saving")
                    ),
                    selectedKey = kind,
                    th = th
                ) { kind = it }
                if (kind == "saving") {
                    Text(t(tr,"kind_hint"), fontSize = 11.sp, color = th.fg.copy(alpha = 0.45f))
                }
                OutlinedTextField(
                    value = valueStr, onValueChange = { valueStr = it; pctError = false },
                    label = { Text(t(tr,"category_value")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp),
                    isError = pctError,
                    trailingIcon = {
                        Text(
                            if (mode == "percentage") "%" else sym,
                            color = if (pctError) kBad else th.accent,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                )
                if (pctError) {
                    val left = (100.0 - state.percentSumExcluding(editName)).coerceAtLeast(0.0)
                    Text(
                        t(tr,"pct_limit_error").replace("{left}", left.money()),
                        fontSize = 11.sp, color = kBad
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked         = overdraft,
                        onCheckedChange = { overdraft = it },
                        colors          = CheckboxDefaults.colors(checkedColor = th.accent)
                    )
                    Text(t(tr,"allow_overdraft"), color = th.fg, fontSize = 13.sp)
                }
            }
        },
        confirmButton  = {
            TextButton(onClick = {
                val v = valueStr.toDoubleOrNull() ?: 0.0
                // v0.9.5: hard 100% ceiling for percentage categories
                if (mode == "percentage" && v + state.percentSumExcluding(editName) > 100.0 + 1e-6) {
                    pctError = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    shakeTick++          // no-no
                    return@TextButton
                }
                state.upsertCategory(editName, name.trim(), v, mode, overdraft, kind)
                onDismiss()
            }) { Text(t(tr,"ok"), color = th.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton  = {
            TextButton(onClick = onDismiss) { Text(t(tr,"cancel"), color = th.fg.copy(alpha = 0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}

/**
 * Record an expense. With [fixedTarget] the category is locked (spend button on
 * a category card); with null the user picks a category (quick-add FAB).
 * The preselected category is the TOP one of the user's own list order.
 */
@Composable
fun SpendDialog(
    fixedTarget: String?,
    state: AppState,
    tr: Map<String, String>,
    th: AppThemeData,
    sym: String,
    period: String = "all",      // v0.9.3: limits follow the Budget screen filter
    onDismiss: () -> Unit
) {
    val options = remember {
        val named = state.orderedCategoryNames()
        val unallocAll = computeUnallocatedFor(state.categories, state.totalMoney, "all")
        if (unallocAll > 0 || named.isEmpty()) named + "__unallocated__" else named
    }
    var target    by remember { mutableStateOf(fixedTarget ?: options.first()) }
    var amountStr by remember { mutableStateOf("") }
    var desc      by remember { mutableStateOf("") }
    var dateStr   by remember { mutableStateOf("") }          // "" = today
    var expanded  by remember { mutableStateOf(false) }
    var shakeTick by remember { mutableIntStateOf(0) }        // v0.9.6
    val haptic = LocalHapticFeedback.current

    // v0.9.6: live remaining + overdraft status for the chosen category
    val remaining = state.remainingFor(target, period)
    val maxAmt    = remaining.coerceAtLeast(0.0)
    val amtVal    = amountStr.toDoubleOrNull() ?: 0.0
    val odOn      = if (target == "__unallocated__") false
                    else state.categories[target]?.overdraft ?: false
    val status    = spendStatus(remaining, amtVal, odOn)

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = {
            Text(
                "${t(tr,"spend_from")}: ${catDisplay(target, tr)}",
                color = th.fg, fontWeight = FontWeight.Bold
            )
        },
        text   = {
            Column(
                Modifier.shakeOnTrigger(shakeTick),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Category picker — only for quick-add
                if (fixedTarget == null) {
                    Box {
                        OutlinedTextField(
                            value = catDisplay(target, tr), onValueChange = {},
                            label = { Text(t(tr,"category")) },
                            readOnly = true, singleLine = true, colors = otfColors(th),
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                Icon(Icons.Default.ArrowDropDown, null, tint = th.fg,
                                    modifier = Modifier.clickable { expanded = true })
                            }
                        )
                        DropdownMenu(
                            expanded          = expanded,
                            onDismissRequest  = { expanded = false },
                            modifier          = Modifier.background(th.cardBg)
                        ) {
                            options.forEach { cat ->
                                DropdownMenuItem(
                                    text    = { Text(catDisplay(cat, tr), color = th.fg) },
                                    onClick = { target = cat; expanded = false }
                                )
                            }
                        }
                    }
                }
                // Info chip: remaining money, colored by overdraft status
                Box(
                    Modifier
                        .background(spendStatusColor(status, th).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        "${t(tr,"remaining")}: ${remaining.money()} $sym",
                        fontSize = 12.sp,
                        color    = spendStatusColor(status, th),
                        fontWeight = FontWeight.Medium
                    )
                }
                OutlinedTextField(
                    value = amountStr, onValueChange = { amountStr = it },
                    label = { Text(t(tr,"amount")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp),
                    isError = status == 2
                )
                when (status) {
                    2 -> Text(t(tr,"over_error"), fontSize = 11.sp, color = kBad)
                    1 -> Text(t(tr,"over_warn"), fontSize = 11.sp, color = kWarn)
                }
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text(t(tr,"description")) },
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp)
                )
                DateField(
                    value = dateStr, label = t(tr,"date"),
                    langCode = state.language, th = th, tr = tr
                ) { dateStr = it }
            }
        },
        confirmButton  = {
            TextButton(onClick = {
                if (status == 2) {                   // overdraft off — not enough left
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    shakeTick++          // no-no
                    return@TextButton
                }
                state.recordExpense(target, amountStr.toDoubleOrNull() ?: 0.0, desc, dateStr)
                onDismiss()
            }) { Text(t(tr,"ok"), color = th.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton  = {
            TextButton(onClick = onDismiss) { Text(t(tr,"cancel"), color = th.fg.copy(alpha = 0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}

@Composable
fun EditExpenseDialog(
    idx: Int,
    state: AppState,
    tr: Map<String, String>,
    th: AppThemeData,
    sym: String,
    onDismiss: () -> Unit
) {
    val expense = state.expenses.getOrNull(idx)
    if (expense == null) {           // index no longer valid — close safely
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val cats = remember {
        (state.orderedCategoryNames() + expense.category).distinct()
    }
    var category  by remember { mutableStateOf(expense.category) }
    var amountStr by remember { mutableStateOf(expense.amount.toString()) }
    var desc      by remember { mutableStateOf(expense.description) }
    var date      by remember { mutableStateOf(expense.date) }
    var expanded  by remember { mutableStateOf(false) }
    var shakeTick by remember { mutableIntStateOf(0) }        // v0.9.6
    val haptic = LocalHapticFeedback.current

    // v0.9.6: overdraft check — the original amount returns to the pool first
    val remaining = state.remainingFor(category, "all") +
        (if (category == expense.category) expense.amount else 0.0)
    val amtVal = amountStr.toDoubleOrNull() ?: 0.0
    val odOn   = if (category == "__unallocated__") false
                 else state.categories[category]?.overdraft ?: false
    val status = spendStatus(remaining, amtVal, odOn)

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text(t(tr,"edit_expense"), color = th.fg, fontWeight = FontWeight.Bold) },
        text   = {
            Column(
                Modifier.shakeOnTrigger(shakeTick),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box {
                    OutlinedTextField(
                        value = catDisplay(category, tr), onValueChange = {},
                        label = { Text(t(tr,"category")) },
                        readOnly = true, singleLine = true, colors = otfColors(th),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            Icon(Icons.Default.ArrowDropDown, null, tint = th.fg,
                                modifier = Modifier.clickable { expanded = true })
                        }
                    )
                    DropdownMenu(
                        expanded          = expanded,
                        onDismissRequest  = { expanded = false },
                        modifier          = Modifier.background(th.cardBg)
                    ) {
                        cats.forEach { cat ->
                            DropdownMenuItem(
                                text    = { Text(catDisplay(cat, tr), color = th.fg) },
                                onClick = { category = cat; expanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = amountStr, onValueChange = { amountStr = it }, label = { Text(t(tr,"amount")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, colors = otfColors(th), shape = RoundedCornerShape(12.dp)
                )
                Text(
                    "${t(tr,"remaining")}: ${remaining.money()} $sym",
                    fontSize = 11.sp,
                    color = if (status == 0) th.fg.copy(alpha = 0.45f) else spendStatusColor(status, th)
                )
                when (status) {
                    2 -> Text(t(tr,"over_error"), fontSize = 11.sp, color = kBad)
                    1 -> Text(t(tr,"over_warn"), fontSize = 11.sp, color = kWarn)
                }
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it }, label = { Text(t(tr,"description")) },
                    singleLine = true, colors = otfColors(th), shape = RoundedCornerShape(12.dp)
                )
                DateField(
                    value = date, label = t(tr,"date"),
                    langCode = state.language, th = th, tr = tr
                ) { date = it }
            }
        },
        confirmButton  = {
            TextButton(onClick = {
                if (status == 2) {                   // overdraft off — not enough left
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    shakeTick++          // no-no
                    return@TextButton
                }
                state.updateExpense(
                    idx, category,
                    amountStr.toDoubleOrNull() ?: expense.amount,
                    desc, date.ifBlank { expense.date }
                )
                onDismiss()
            }) { Text(t(tr,"ok"), color = th.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton  = {
            TextButton(onClick = onDismiss) { Text(t(tr,"cancel"), color = th.fg.copy(alpha = 0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}

@Composable
fun EditIncomeDialog(
    idx: Int,
    state: AppState,
    tr: Map<String, String>,
    th: AppThemeData,
    onDismiss: () -> Unit
) {
    val income = state.incomes.getOrNull(idx)
    if (income == null) {            // index no longer valid — close safely
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var source    by remember { mutableStateOf(income.source) }
    var amountStr by remember { mutableStateOf(income.amount.toString()) }
    var date      by remember { mutableStateOf(income.date) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text(t(tr,"edit_income"), color = th.fg, fontWeight = FontWeight.Bold) },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = source, onValueChange = { source = it }, label = { Text(t(tr,"source")) },
                    singleLine = true, colors = otfColors(th), shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = amountStr, onValueChange = { amountStr = it }, label = { Text(t(tr,"amount")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, colors = otfColors(th), shape = RoundedCornerShape(12.dp)
                )
                DateField(
                    value = date, label = t(tr,"date"),
                    langCode = state.language, th = th, tr = tr
                ) { date = it }
            }
        },
        confirmButton  = {
            TextButton(onClick = {
                state.updateIncome(
                    idx, source,
                    amountStr.toDoubleOrNull() ?: income.amount,
                    date.ifBlank { income.date }
                )
                onDismiss()
            }) { Text(t(tr,"ok"), color = th.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton  = {
            TextButton(onClick = onDismiss) { Text(t(tr,"cancel"), color = th.fg.copy(alpha = 0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}

@Composable
fun ConfirmDialog(message: String, tr: Map<String, String>, th: AppThemeData, onResult: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        text             = { Text(message, color = th.fg) },
        confirmButton    = {
            TextButton(onClick = { onResult(true) }) {
                Text(t(tr,"yes"), color = kBad, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton    = {
            TextButton(onClick = { onResult(false) }) { Text(t(tr,"no"), color = th.accent) }
        },
        containerColor   = th.cardBg,
        shape            = RoundedCornerShape(24.dp)
    )
}

/**
 * v0.9.3: split one purchase across several categories. Each part has its own
 * category + amount; description and date are shared; the total is computed
 * live so the user always sees what the parts add up to. Every part is stored
 * as a normal expense carrying a common splitId (marked ⇄ in History).
 */
@Composable
fun SplitExpenseDialog(
    state: AppState,
    tr: Map<String, String>,
    th: AppThemeData,
    sym: String,
    onDismiss: () -> Unit
) {
    val unallocAlloc = computeUnallocatedFor(state.categories, state.totalMoney, "all")
    val options = remember {
        val named = state.orderedCategoryNames()
        if (unallocAlloc > 0 || named.isEmpty()) named + "__unallocated__" else named
    }
    // part = category to amount-text
    val parts = remember {
        mutableStateListOf(
            options.first() to "",
            options.getOrElse(1) { options.first() } to ""
        )
    }
    var desc    by remember { mutableStateOf("") }
    var dateStr by remember { mutableStateOf("") }            // "" = today
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var shakeTick by remember { mutableIntStateOf(0) }        // v0.9.6
    val haptic = LocalHapticFeedback.current

    val amounts = parts.map { it.second.toDoubleOrNull() ?: 0.0 }
    val total   = amounts.sum()

    // v0.9.6: remaining per category; parts hitting the SAME category are summed
    val remainingByCat = parts.map { it.first }.distinct()
        .associateWith { state.remainingFor(it, "all") }
    val enteredByCat = parts.groupBy { it.first }
        .mapValues { (_, l) -> l.sumOf { it.second.toDoubleOrNull() ?: 0.0 } }
    fun statusOf(cat: String): Int {
        val od = if (cat == "__unallocated__") false else state.categories[cat]?.overdraft ?: false
        return spendStatus(remainingByCat[cat] ?: 0.0, enteredByCat[cat] ?: 0.0, od)
    }
    val anyBlocked = parts.any { statusOf(it.first) == 2 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text(t(tr,"split_expense"), color = th.fg, fontWeight = FontWeight.Bold) },
        text   = {
            Column(
                Modifier
                    .shakeOnTrigger(shakeTick)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                parts.forEachIndexed { i, part ->
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                        Box {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, th.fg.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                    .clickable { menuFor = i }
                                    .padding(horizontal = 10.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Text(
                                    catDisplay(part.first, tr),
                                    color = th.fg, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(Icons.Default.ArrowDropDown, null, tint = th.fg, modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(
                                expanded         = menuFor == i,
                                onDismissRequest = { menuFor = null },
                                modifier         = Modifier.background(th.cardBg)
                            ) {
                                options.forEach { cat ->
                                    DropdownMenuItem(
                                        text    = { Text(catDisplay(cat, tr), color = th.fg) },
                                        onClick = { parts[i] = cat to parts[i].second; menuFor = null }
                                    )
                                }
                            }
                        }
                        Text(
                            "${t(tr,"remaining")}: ${(remainingByCat[part.first] ?: 0.0).money()} $sym",
                            fontSize = 10.sp,
                            color = if (statusOf(part.first) == 0) th.fg.copy(alpha = 0.45f)
                                    else spendStatusColor(statusOf(part.first), th),
                            modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                        )
                        }
                        OutlinedTextField(
                            value = part.second,
                            onValueChange = { parts[i] = parts[i].first to it },
                            label = { Text(t(tr,"amount"), fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true, colors = otfColors(th),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.width(104.dp),
                            isError = statusOf(part.first) == 2
                        )
                        if (parts.size > 2) {
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(kBad.copy(alpha = 0.12f))
                                    .clickable { parts.removeAt(i) },
                                contentAlignment = Alignment.Center
                            ) { Text("×", color = kBad, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    when (statusOf(part.first)) {
                        2 -> Text(t(tr,"over_error"), fontSize = 10.sp, color = kBad)
                        1 -> Text(t(tr,"over_warn"), fontSize = 10.sp, color = kWarn)
                    }
                }

                if (parts.size < 6) {
                    TextButton(onClick = { parts.add(options.first() to "") }) {
                        Text("+ ${t(tr,"category")}", color = th.accent, fontSize = 13.sp)
                    }
                }

                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text(t(tr,"description")) },
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                DateField(
                    value = dateStr, label = t(tr,"date"),
                    langCode = state.language, th = th, tr = tr
                ) { dateStr = it }

                // Live total: "12.00 + 5.50 = 17.50 $"
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(th.accent.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        "${t(tr,"split_total")}: " +
                            amounts.joinToString(" + ") { it.money() } +
                            " = ${total.money()} $sym",
                        fontSize   = 12.sp,
                        color      = th.accent,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (amounts.any { it <= 0.0 } || anyBlocked) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    shakeTick++          // no-no
                    return@TextButton
                }
                val splitId = "s${System.currentTimeMillis()}"
                parts.forEach { (cat, amtStr) ->
                    state.recordExpense(cat, amtStr.toDoubleOrNull() ?: 0.0, desc, dateStr, splitId)
                }
                onDismiss()
            }) { Text(t(tr,"ok"), color = th.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t(tr,"cancel"), color = th.fg.copy(alpha = 0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}
