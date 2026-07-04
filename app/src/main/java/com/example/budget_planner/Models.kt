package com.example.budget_planner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ── Data models ───────────────────────────────────────────────────────────────
// Immutable data classes: every change replaces the object, which lets Compose
// skip recomposition for anything that didn't actually change.

data class Category(
    val name: String,
    val value: Double,
    val mode: String, // "percentage" | "monthly" | "annual"
    val overdraft: Boolean,
    val spent: Double = 0.0
) {
    fun allocated(totalMoney: Double): Double = when (mode) {
        "percentage" -> totalMoney * value / 100.0
        "monthly"    -> value
        "annual"     -> value / 12.0
        else          -> 0.0
    }

    fun toJsonObject() = JSONObject().apply {
        put("value", value); put("mode", mode)
        put("overdraft", overdraft); put("spent", spent)
    }
    companion object {
        fun fromJsonObject(name: String, json: JSONObject) = Category(
            name      = name,
            value     = json.optDouble("value", 0.0),
            mode      = json.optString("mode", "percentage"),
            overdraft = json.optBoolean("overdraft", false),
            spent     = json.optDouble("spent", 0.0)
        )
    }
}

data class Expense(
    val category: String,
    val amount: Double,
    val description: String,
    val date: String
) {
    fun toJsonObject() = JSONObject().apply {
        put("category", category); put("amount", amount)
        put("description", description); put("date", date)
    }
    companion object {
        fun fromJsonObject(json: JSONObject) = Expense(
            category    = json.optString("category", ""),
            amount      = json.optDouble("amount", 0.0),
            description = json.optString("description", ""),
            date        = json.optString("date", "")
        )
    }
}

data class Income(
    val source: String,
    val amount: Double,
    val date: String
) {
    fun toJsonObject() = JSONObject().apply {
        put("source", source); put("amount", amount); put("date", date)
    }
    companion object {
        fun fromJsonObject(json: JSONObject) = Income(
            source = json.optString("source", "N/A"),
            amount = json.optDouble("amount", 0.0),
            date   = json.optString("date", "")
        )
    }
}

// ── App state ─────────────────────────────────────────────────────────────────
// Single source of truth. Primitive-typed state (Double/Int overloads) avoids
// autoboxing on every read — a small but free optimization.

class AppState(
    totalMoney: Double = 0.0,
    categories: Map<String, Category> = emptyMap(),
    expenses: List<Expense> = emptyList(),
    incomes: List<Income> = emptyList(),
    language: String = "en",
    themeName: String = "black",
    currency: String = "Dollar",
    monthResetDay: Int = 1,
    categoryOrder: List<String> = emptyList(),
    aiProvider: String = "gemini",
    aiApiKey: String = ""
) {
    var totalMoney by mutableDoubleStateOf(totalMoney)
    val categories = mutableStateMapOf<String, Category>().apply { putAll(categories) }
    val expenses   = mutableStateListOf<Expense>().apply { addAll(expenses) }
    val incomes    = mutableStateListOf<Income>().apply { addAll(incomes) }
    var language      by mutableStateOf(language)
    var themeName     by mutableStateOf(themeName)
    var currency      by mutableStateOf(currency)
    var monthResetDay by mutableIntStateOf(monthResetDay)
    /** User-chosen display order of categories on the Budget screen (v0.9). */
    val categoryOrder = mutableStateListOf<String>().apply { addAll(categoryOrder) }
    var aiProvider by mutableStateOf(aiProvider)
    var aiApiKey   by mutableStateOf(aiApiKey)

    fun toJson(): String {
        val root = JSONObject()
        root.put("total_money", totalMoney)
        root.put("language", language)
        root.put("theme", themeName)
        root.put("currency", currency)
        root.put("month_reset_day", monthResetDay)
        root.put("category_order", JSONArray().also { a -> categoryOrder.forEach { a.put(it) } })
        root.put("ai_provider", aiProvider)
        root.put("ai_api_key", aiApiKey)
        root.put("categories", JSONObject().also { o -> categories.forEach { (k, v) -> o.put(k, v.toJsonObject()) } })
        root.put("expenses", JSONArray().also { a -> expenses.forEach { a.put(it.toJsonObject()) } })
        root.put("incomes",  JSONArray().also { a -> incomes.forEach  { a.put(it.toJsonObject()) } })
        return root.toString()
    }

    companion object {
        fun fromJson(s: String): AppState {
            val j = JSONObject(s)
            val cats = mutableMapOf<String, Category>()
            j.optJSONObject("categories")?.keys()?.forEach { k ->
                cats[k] = Category.fromJsonObject(k, j.getJSONObject("categories").getJSONObject(k))
            }
            val exps = mutableListOf<Expense>()
            j.optJSONArray("expenses")?.let { arr -> for (i in 0 until arr.length()) exps.add(Expense.fromJsonObject(arr.getJSONObject(i))) }
            val incs = mutableListOf<Income>()
            j.optJSONArray("incomes")?.let  { arr -> for (i in 0 until arr.length()) incs.add(Income.fromJsonObject(arr.getJSONObject(i))) }
            val order = mutableListOf<String>()
            j.optJSONArray("category_order")?.let { arr -> for (i in 0 until arr.length()) order.add(arr.optString(i)) }
            return AppState(
                totalMoney = j.optDouble("total_money", 0.0),
                // v0.9 migration: normalize any hand-typed dates ("2026-7-3",
                // "03.07.2026", …) to strict yyyy-MM-dd so period filters and
                // sorting work reliably. This is what fixes incomes that never
                // showed up in "This month".
                categories = cats,
                expenses   = exps.map { e -> e.copy(date = normalizeDate(e.date) ?: e.date) },
                incomes    = incs.map { i -> i.copy(date = normalizeDate(i.date) ?: i.date) },
                language   = j.optString("language", "en"),
                // v0.9.1: "white"/"hacker" themes were removed — migrate to matcha
                themeName  = j.optString("theme", "black")
                    .let { if (it == "white" || it == "hacker") "matcha" else it },
                currency   = j.optString("currency", "Dollar"),
                monthResetDay = j.optInt("month_reset_day", 1).coerceIn(1, 31),
                categoryOrder = order,
                aiProvider = j.optString("ai_provider", "gemini"),
                aiApiKey   = j.optString("ai_api_key", "")
            )
        }
    }
}

// ── State mutations ───────────────────────────────────────────────────────────
// All writes to AppState go through these functions, so every screen and dialog
// mutates data through exactly one, well-tested path.

fun AppState.recordIncome(source: String, amount: Double, date: String) {
    if (amount <= 0) return
    totalMoney += amount
    incomes.add(Income(source.ifBlank { "N/A" }, amount, normalizeDate(date) ?: todayStr()))
}

fun AppState.recordExpense(target: String, amount: Double, description: String, date: String) {
    if (amount <= 0) return
    val prev = categories[target]?.spent ?: 0.0
    categories[target] = (categories[target]
        ?: Category(target, 0.0, "unallocated", false, 0.0))
        .copy(spent = prev + amount)
    expenses.add(Expense(target, amount, description, normalizeDate(date) ?: todayStr()))
}

fun AppState.updateExpense(idx: Int, newCat: String, newAmt: Double, newDesc: String, newDate: String) {
    val old = expenses.getOrNull(idx) ?: return
    if (newAmt <= 0) return
    if (old.category == newCat) {
        categories[newCat]?.let {
            categories[newCat] = it.copy(spent = (it.spent + newAmt - old.amount).coerceAtLeast(0.0))
        }
    } else {
        categories[old.category]?.let {
            categories[old.category] = it.copy(spent = (it.spent - old.amount).coerceAtLeast(0.0))
        }
        categories[newCat]?.let {
            categories[newCat] = it.copy(spent = it.spent + newAmt)
        }
    }
    expenses[idx] = old.copy(
        category = newCat, amount = newAmt, description = newDesc,
        date = normalizeDate(newDate) ?: old.date
    )
}

fun AppState.removeExpense(idx: Int) {
    val e = expenses.getOrNull(idx) ?: return
    categories[e.category]?.let {
        categories[e.category] = it.copy(spent = (it.spent - e.amount).coerceAtLeast(0.0))
    }
    expenses.removeAt(idx)
}

fun AppState.updateIncome(idx: Int, newSrc: String, newAmt: Double, newDate: String) {
    val old = incomes.getOrNull(idx) ?: return
    if (newAmt <= 0) return
    totalMoney += (newAmt - old.amount)
    incomes[idx] = old.copy(
        source = newSrc.ifBlank { "N/A" }, amount = newAmt,
        date = normalizeDate(newDate) ?: old.date
    )
}

fun AppState.removeIncome(idx: Int) {
    val old = incomes.getOrNull(idx) ?: return
    totalMoney -= old.amount
    incomes.removeAt(idx)
}

/** Create a new category, or update/rename an existing one when [oldName] is given. */
fun AppState.upsertCategory(oldName: String?, name: String, value: Double, mode: String, overdraft: Boolean) {
    if (name.isBlank() || value <= 0) return
    if (oldName == null && categories.containsKey(name)) return   // no duplicates
    val keepSpent = oldName?.let { categories[it]?.spent } ?: 0.0
    if (oldName != null && oldName != name) {
        // Rename: move every expense recorded under the old name
        for (i in expenses.indices) {
            if (expenses[i].category == oldName) expenses[i] = expenses[i].copy(category = name)
        }
        categories.remove(oldName)
        val at = categoryOrder.indexOf(oldName)
        if (at >= 0) categoryOrder[at] = name else categoryOrder.add(name)
    } else if (oldName == null && name !in categoryOrder) {
        categoryOrder.add(name)                 // new categories go to the end
    }
    categories[name] = Category(name, value, mode, overdraft, keepSpent)
}

fun AppState.removeCategory(name: String) {
    categories.remove(name)
    categoryOrder.remove(name)
}

fun AppState.resetAll() {
    totalMoney = 0.0
    categories.clear()
    expenses.clear()
    incomes.clear()
    categoryOrder.clear()
}

// ── Category ordering (v0.9) ──────────────────────────────────────────────────
// The Budget screen shows categories in the user's chosen order. Categories
// created before ordering existed (or restored from old backups) are appended
// alphabetically.

fun AppState.orderedCategoryNames(): List<String> {
    val existing = categories.keys.filter { it != "__unallocated__" }
    val inOrder  = categoryOrder.filter { it in existing }
    val rest     = existing.filter { it !in inOrder }.sorted()
    return inOrder + rest
}

/** Move [fromKey] so it takes the position of [toKey] (drag & drop reorder). */
fun AppState.moveCategory(fromKey: String, toKey: String) {
    val current = orderedCategoryNames()
    if (categoryOrder.toList() != current) {
        categoryOrder.clear(); categoryOrder.addAll(current)
    }
    val from = categoryOrder.indexOf(fromKey)
    val to   = categoryOrder.indexOf(toKey)
    if (from < 0 || to < 0 || from == to) return
    categoryOrder.add(to, categoryOrder.removeAt(from))
}

// ── Backup: export / import (v0.9.1) ─────────────────────────────────────────

/** Replace everything in this state with the contents of an imported backup. */
fun AppState.replaceFrom(o: AppState) {
    totalMoney = o.totalMoney
    categories.clear();    categories.putAll(o.categories)
    expenses.clear();      expenses.addAll(o.expenses)
    incomes.clear();       incomes.addAll(o.incomes)
    categoryOrder.clear(); categoryOrder.addAll(o.categoryOrder)
    language      = o.language
    themeName     = o.themeName
    currency      = o.currency
    monthResetDay = o.monthResetDay
    aiProvider    = o.aiProvider
    if (o.aiApiKey.isNotBlank()) aiApiKey = o.aiApiKey   // keep current key if backup has none
}

// ── Date & money helpers ──────────────────────────────────────────────────────
// Locale.US everywhere: dates are stored/compared as plain "yyyy-MM-dd" strings,
// so they must always use ASCII digits regardless of the phone's locale.

val kDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

// Formats accepted when normalizing hand-typed or legacy dates.
private val kDateInputFmts = listOf("yyyy-MM-dd", "dd.MM.yyyy", "dd/MM/yyyy", "dd-MM-yyyy")
    .map { SimpleDateFormat(it, Locale.US).apply { isLenient = false } }

fun todayStr(): String = kDateFmt.format(Date())
fun parseDate(s: String): Date? = runCatching { kDateFmt.parse(s) }.getOrNull()

/**
 * Bug fix (v0.9): entries whose date was typed by hand ("2026-7-3", "03.07.2026")
 * were stored verbatim, so the string comparison inside the "This month" filter
 * never matched them — incomes silently dropped out of the period totals.
 * All dates now pass through here and come out as strict zero-padded yyyy-MM-dd.
 */
fun normalizeDate(raw: String): String? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    for (f in kDateInputFmts) {
        val d = runCatching { f.parse(s) }.getOrNull()
        if (d != null) return kDateFmt.format(d)
    }
    return null
}

/** Round to whole cents — kills float dust like 290.97000000000003. */
fun roundCents(v: Double): Double = Math.round(v * 100.0) / 100.0

fun Double.money(): String {
    val v = roundCents(this)
    val safe = if (v == 0.0) 0.0 else v    // never print "-0.00"
    return "%.2f".format(safe)
}

// Returns the current "budget month" window [start, end) as yyyy-MM-dd strings.
// The budget month begins on `resetDay` of each calendar month (clamped for
// short months, e.g. day 31 in February becomes Feb 28/29).
fun budgetMonthRange(resetDay: Int): Pair<String, String> {
    val start = Calendar.getInstance()
    val today = start.get(Calendar.DAY_OF_MONTH)
    val clampedNow = resetDay.coerceAtMost(start.getActualMaximum(Calendar.DAY_OF_MONTH))
    if (today >= clampedNow) {
        start.set(Calendar.DAY_OF_MONTH, clampedNow)
    } else {
        start.add(Calendar.MONTH, -1)
        start.set(Calendar.DAY_OF_MONTH, resetDay.coerceAtMost(start.getActualMaximum(Calendar.DAY_OF_MONTH)))
    }
    val end = start.clone() as Calendar
    end.set(Calendar.DAY_OF_MONTH, 1)          // avoid overflow when adding a month
    end.add(Calendar.MONTH, 1)
    end.set(Calendar.DAY_OF_MONTH, resetDay.coerceAtMost(end.getActualMaximum(Calendar.DAY_OF_MONTH)))
    return kDateFmt.format(start.time) to kDateFmt.format(end.time)
}

fun filterByPeriod(date: String, period: String, monthResetDay: Int = 1): Boolean = when (period) {
    "month" -> {
        // yyyy-MM-dd strings compare correctly as plain strings
        val (s, e) = budgetMonthRange(monthResetDay.coerceIn(1, 31))
        date >= s && date < e
    }
    "year"  -> date.startsWith(Calendar.getInstance().get(Calendar.YEAR).toString())
    else    -> true
}

fun computeUnallocated(categories: Map<String, Category>, totalMoney: Double): Double {
    val used = categories.filter { it.key != "__unallocated__" }.values.sumOf { it.allocated(totalMoney) }
    return (totalMoney - used).coerceAtLeast(0.0)
}
