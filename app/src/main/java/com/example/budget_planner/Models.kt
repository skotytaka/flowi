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
    monthResetDay: Int = 1
) {
    var totalMoney by mutableDoubleStateOf(totalMoney)
    val categories = mutableStateMapOf<String, Category>().apply { putAll(categories) }
    val expenses   = mutableStateListOf<Expense>().apply { addAll(expenses) }
    val incomes    = mutableStateListOf<Income>().apply { addAll(incomes) }
    var language      by mutableStateOf(language)
    var themeName     by mutableStateOf(themeName)
    var currency      by mutableStateOf(currency)
    var monthResetDay by mutableIntStateOf(monthResetDay)

    fun toJson(): String {
        val root = JSONObject()
        root.put("total_money", totalMoney)
        root.put("language", language)
        root.put("theme", themeName)
        root.put("currency", currency)
        root.put("month_reset_day", monthResetDay)
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
            return AppState(
                totalMoney = j.optDouble("total_money", 0.0),
                categories = cats, expenses = exps, incomes = incs,
                language   = j.optString("language", "en"),
                themeName  = j.optString("theme", "black"),
                currency   = j.optString("currency", "Dollar"),
                monthResetDay = j.optInt("month_reset_day", 1).coerceIn(1, 31)
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
    incomes.add(Income(source.ifBlank { "N/A" }, amount, date))
}

fun AppState.recordExpense(target: String, amount: Double, description: String, date: String) {
    if (amount <= 0) return
    val prev = categories[target]?.spent ?: 0.0
    categories[target] = (categories[target]
        ?: Category(target, 0.0, "unallocated", false, 0.0))
        .copy(spent = prev + amount)
    expenses.add(Expense(target, amount, description, date))
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
    expenses[idx] = old.copy(category = newCat, amount = newAmt, description = newDesc, date = newDate)
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
    incomes[idx] = old.copy(source = newSrc.ifBlank { "N/A" }, amount = newAmt, date = newDate)
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
    }
    categories[name] = Category(name, value, mode, overdraft, keepSpent)
}

fun AppState.resetAll() {
    totalMoney = 0.0
    categories.clear()
    expenses.clear()
    incomes.clear()
}

// ── Date & money helpers ──────────────────────────────────────────────────────

val kDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
fun todayStr(): String = kDateFmt.format(Date())
fun parseDate(s: String): Date? = runCatching { kDateFmt.parse(s) }.getOrNull()
fun Double.money(): String = "%.2f".format(this)

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
    "year"  -> date.startsWith("%04d".format(Calendar.getInstance().get(Calendar.YEAR)))
    else    -> true
}

fun computeUnallocated(categories: Map<String, Category>, totalMoney: Double): Double {
    val used = categories.filter { it.key != "__unallocated__" }.values.sumOf { it.allocated(totalMoney) }
    return (totalMoney - used).coerceAtLeast(0.0)
}
