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
    val spent: Double = 0.0,
    val kind: String = "spending" // v0.9.2: "spending" | "saving" (funds, investments)
) {
    /**
     * v0.9.3: allocations follow the period filter. [pctBase] is the income of
     * the chosen period minus the fixed categories for that period (NI = I - R),
     * so percentage categories keep their ratio and never exceed the income.
     */
    fun allocatedFor(period: String, pctBase: Double): Double = when (mode) {
        "percentage" -> pctBase * value / 100.0
        else          -> fixedFor(period)
    }

    /** What a fixed category costs within the given period ("all"|"month"|"year"). */
    fun fixedFor(period: String): Double = when (mode) {
        "monthly" -> if (period == "year") value * 12.0 else value
        "annual"  -> if (period == "year") value else value / 12.0
        else       -> 0.0
    }

    fun toJsonObject() = JSONObject().apply {
        put("value", value); put("mode", mode)
        put("overdraft", overdraft); put("spent", spent)
        put("kind", kind)
    }
    companion object {
        fun fromJsonObject(name: String, json: JSONObject) = Category(
            name      = name,
            value     = json.optDouble("value", 0.0),
            mode      = json.optString("mode", "percentage"),
            overdraft = json.optBoolean("overdraft", false),
            spent     = json.optDouble("spent", 0.0),
            // Old saves have no "kind": recognize funds/investments by name once,
            // so v0.9.1 data upgrades seamlessly.
            kind      = json.optString("kind", "").ifBlank { guessKind(name) }
        )
    }
}

// Name fragments (all app languages) that mark a category as savings-like.
private val kSavingHints = listOf(
    "fund", "invest", "saving", "emerg", "deposit",
    "фонд", "інвест", "заощад", "накопич", "депозит", "резерв",
    "spar", "rücklage", "notgroschen", "anlage",
    "ادخار", "استثمار", "طوارئ", "صندوق", "وديعة",
    "储蓄", "投资", "应急", "基金", "存款"
)

fun guessKind(name: String): String =
    if (kSavingHints.any { name.lowercase().contains(it) }) "saving" else "spending"

data class Expense(
    val category: String,
    val amount: Double,
    val description: String,
    val date: String,
    val splitId: String = ""   // v0.9.3: parts of one split expense share an id
) {
    fun toJsonObject() = JSONObject().apply {
        put("category", category); put("amount", amount)
        put("description", description); put("date", date)
        if (splitId.isNotBlank()) put("split_id", splitId)
    }
    companion object {
        fun fromJsonObject(json: JSONObject) = Expense(
            category    = json.optString("category", ""),
            amount      = json.optDouble("amount", 0.0),
            description = json.optString("description", ""),
            date        = json.optString("date", ""),
            splitId     = json.optString("split_id", "")
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
    themeName: String = "matcha",   // v0.9.3: white-green is the main theme
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
                themeName  = j.optString("theme", "matcha")
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

fun AppState.recordExpense(
    target: String, amount: Double, description: String, date: String,
    splitId: String = ""
) {
    if (amount <= 0) return
    val prev = categories[target]?.spent ?: 0.0
    categories[target] = (categories[target]
        ?: Category(target, 0.0, "unallocated", false, 0.0))
        .copy(spent = prev + amount)
    expenses.add(Expense(target, amount, description, normalizeDate(date) ?: todayStr(), splitId))
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

/**
 * v0.9.6: money left in [target] for a period — allocation minus spent.
 * Can be negative when the category is already over budget.
 */
fun AppState.remainingFor(target: String, period: String = "all"): Double {
    val income = if (period == "all") totalMoney
        else incomes.filter { filterByPeriod(it.date, period, monthResetDay) }.sumOf { it.amount }
    val base = percentBaseFor(categories, income, period)
    val alloc = if (target == "__unallocated__") computeUnallocatedFor(categories, income, period)
        else categories[target]?.allocatedFor(period, base) ?: 0.0
    val spent = if (period == "all") categories[target]?.spent ?: 0.0
        else expenses.filter { it.category == target && filterByPeriod(it.date, period, monthResetDay) }
            .sumOf { it.amount }
    return alloc - spent
}

/**
 * v0.9.6: how an entered amount relates to what's left.
 * 0 = fits; 1 = exceeds but overdraft is ON (warn, allowed);
 * 2 = exceeds and overdraft is OFF (error, blocked).
 */
fun spendStatus(remaining: Double, amount: Double, overdraft: Boolean): Int = when {
    amount <= remaining + 1e-9 -> 0
    overdraft                  -> 1
    else                       -> 2
}

/** Sum of all percentage-category values, optionally excluding one (when editing it). */
fun AppState.percentSumExcluding(exclude: String?): Double =
    categories.values
        .filter { it.mode == "percentage" && it.name != "__unallocated__" && it.name != exclude }
        .sumOf { it.value }

/** Create a new category, or update/rename an existing one when [oldName] is given. */
fun AppState.upsertCategory(
    oldName: String?, name: String, value: Double, mode: String,
    overdraft: Boolean, kind: String = "spending"
) {
    if (name.isBlank() || value <= 0) return
    if (oldName == null && categories.containsKey(name)) return   // no duplicates
    // v0.9.5: percentage categories can never exceed 100% in total
    if (mode == "percentage" && percentSumExcluding(oldName) + value > 100.0 + 1e-6) return
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
    categories[name] = Category(name, value, mode, overdraft, keepSpent, kind)
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

/**
 * v0.9.3 math, exactly the user's formula: x+y+z = 100%, I = income of the
 * selected period (this month / this year / all time), R = fixed categories,
 * NI = I - R, and every percentage category gets NI * x/100 — ratio preserved,
 * and R + all percentage allocations = I when percentages sum to 100.
 * Example: I=1000, R=600 rent, 20/30/50 → 80/120/200.
 */
fun percentBaseFor(categories: Map<String, Category>, income: Double, period: String): Double {
    val fixed = categories.values
        .filter { it.name != "__unallocated__" && it.mode != "percentage" }
        .sumOf { it.fixedFor(period) }
    return (income - fixed).coerceAtLeast(0.0)
}

fun computeUnallocatedFor(categories: Map<String, Category>, income: Double, period: String): Double {
    val base = percentBaseFor(categories, income, period)
    val used = categories.filter { it.key != "__unallocated__" }.values
        .sumOf { it.allocatedFor(period, base) }
    return (income - used).coerceAtLeast(0.0)
}
