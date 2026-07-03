@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.budget_planner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = {
            Text(
                if (editName != null) t(tr,"edit_category") else t(tr,"add_category"),
                color = th.fg, fontWeight = FontWeight.Bold
            )
        },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                ) { mode = it }
                OutlinedTextField(
                    value = valueStr, onValueChange = { valueStr = it },
                    label = { Text(t(tr,"category_value")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        Text(
                            if (mode == "percentage") "%" else sym,
                            color = th.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                )
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
                state.upsertCategory(editName, name.trim(), valueStr.toDoubleOrNull() ?: 0.0, mode, overdraft)
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
    onDismiss: () -> Unit
) {
    val unallocAlloc = computeUnallocated(state.categories, state.totalMoney)
    val unallocSpent = state.categories["__unallocated__"]?.spent ?: 0.0
    val options = remember {
        val named = state.orderedCategoryNames()
        if (unallocAlloc > 0 || named.isEmpty()) named + "__unallocated__" else named
    }
    var target    by remember { mutableStateOf(fixedTarget ?: options.first()) }
    var amountStr by remember { mutableStateOf("") }
    var desc      by remember { mutableStateOf("") }
    var dateStr   by remember { mutableStateOf("") }          // "" = today
    var expanded  by remember { mutableStateOf(false) }

    val maxAmt = if (target == "__unallocated__")
        (unallocAlloc - unallocSpent).coerceAtLeast(0.0)
    else
        state.categories[target]?.let { (it.allocated(state.totalMoney) - it.spent).coerceAtLeast(0.0) } ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = {
            Text(
                "${t(tr,"spend_from")}: ${catDisplay(target, tr)}",
                color = th.fg, fontWeight = FontWeight.Bold
            )
        },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                // Info chip showing the max available amount
                Box(
                    Modifier
                        .background(th.accent.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        "${t(tr,"max")}: ${maxAmt.money()} $sym",
                        fontSize = 12.sp,
                        color    = th.accent,
                        fontWeight = FontWeight.Medium
                    )
                }
                OutlinedTextField(
                    value = amountStr, onValueChange = { amountStr = it },
                    label = { Text(t(tr,"amount")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp)
                )
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text(t(tr,"edit_expense"), color = th.fg, fontWeight = FontWeight.Bold) },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
