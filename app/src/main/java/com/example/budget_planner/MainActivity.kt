@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.budget_planner

import android.content.Context
import android.graphics.Paint as NativePaint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ── Data Models ──────────────────────────────────────────────────────────────

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
    fun remaining(totalMoney: Double) = allocated(totalMoney) - spent

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
    var category: String,
    var amount: Double,
    var description: String,
    var date: String
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
    var source: String,
    var amount: Double,
    var date: String
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

// ── App State ─────────────────────────────────────────────────────────────────
// All app data lives here. Compose watches these fields and redraws only what changed.

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
    var totalMoney by mutableStateOf(totalMoney)
    val categories = mutableStateMapOf<String, Category>().apply { putAll(categories) }
    val expenses   = mutableStateListOf<Expense>().apply { addAll(expenses) }
    val incomes    = mutableStateListOf<Income>().apply { addAll(incomes) }
    var language   by mutableStateOf(language)
    var themeName  by mutableStateOf(themeName)
    var currency   by mutableStateOf(currency)
    var monthResetDay by mutableStateOf(monthResetDay)

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

// ── Constants ─────────────────────────────────────────────────────────────────

val kLanguageNames = linkedMapOf(
    "en" to "English",
    "uk" to "Українська",
    "de" to "Deutsch",
    "ar" to "العربية",
    "zh" to "中文"
)
val kCurrencies    = linkedMapOf("Dollar" to "$", "Euro" to "€", "Hryvnia" to "₴", "Pound" to "£", "Zloty" to "zł", "Koruna" to "Kč", "Rupiah" to "Rp")
val kThemeKeys     = listOf("black", "white", "sakura", "cruel_angel", "hacker", "mandarin")
val kChartColors   = listOf(
    Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFFE15759), Color(0xFF76B7B2),
    Color(0xFF59A14F), Color(0xFFEDC948), Color(0xFFB07AA1), Color(0xFFFF9DA7),
    Color(0xFF9C755F), Color(0xFFBAB0AC)
)

// AppThemeData bundles all colors for the active theme in one place
data class AppThemeData(val bg: Color, val fg: Color, val btnBg: Color, val cardBg: Color, val accent: Color)

val kThemes = mapOf(
    "black"       to AppThemeData(Color(0xFF0A0A0F), Color(0xFFEAEAEA), Color(0xFF16161E), Color(0xFF1C1C26), Color(0xFF7B8FF7)),
    "white"       to AppThemeData(Color(0xFFF2F2F7), Color(0xFF1C1C1E), Color(0xFFE5E5EA), Color(0xFFFFFFFF), Color(0xFF0A84FF)),
    "sakura"      to AppThemeData(Color(0xFFFFF0F3), Color(0xFFAD1457), Color(0xFFFFCDD9), Color(0xFFFFFFFF), Color(0xFFE91E8C)),
    "cruel_angel" to AppThemeData(Color(0xFF080812), Color(0xFFFF4444), Color(0xFF14001A), Color(0xFF1A0020), Color(0xFFFF2255)),
    "hacker"      to AppThemeData(Color(0xFF060F06), Color(0xFF33FF33), Color(0xFF001800), Color(0xFF0A160A), Color(0xFF00E676)),
    "mandarin"    to AppThemeData(Color(0xFFFFF8F0), Color(0xFFBF5700), Color(0xFFFFE0B2), Color(0xFFFFFFFF), Color(0xFFFF6D00))
)

// ── Translations ──────────────────────────────────────────────────────────────

val kT = mapOf(
    "en" to mapOf(
        "app_name"                  to "Flowi",
        "total_money"               to "Total Money",
        "add_income"                to "Add Income",
        "add_category"              to "Add Category",
        "edit_category"             to "Edit Category",
        "budget"                    to "Budget",
        "daily"                     to "Expenses",
        "expense_history"           to "Exp. History",
        "income_history"            to "Inc. History",
        "settings"                  to "Settings",
        "category"                  to "Category",
        "amount"                    to "Amount",
        "description"               to "Description",
        "date"                      to "Date",
        "source"                    to "Source",
        "mode"                      to "Mode",
        "overdraft"                 to "Overdraft",
        "spent"                     to "Spent",
        "allocated"                 to "Alloc",
        "remaining"                 to "Remaining",
        "total_remaining"           to "Total Remaining",
        "percentage"                to "Percent",
        "monthly"                   to "Monthly",
        "annual"                    to "Annual",
        "allow_overdraft"           to "Allow Overdraft",
        "yes"                       to "Yes",
        "no"                        to "No",
        "ok"                        to "OK",
        "cancel"                    to "Cancel",
        "save"                      to "Save",
        "delete"                    to "Delete",
        "edit"                      to "Edit",
        "reset"                     to "Reset All Data",
        "confirm_reset_title"       to "Confirm Reset",
        "confirm_reset"             to "Delete ALL data? This cannot be undone.",
        "confirm_delete_category"   to "Delete this category? Related expenses will stay in history.",
        "category_name"             to "Category name",
        "category_value"            to "Value",
        "income_amount"             to "Amount",
        "income_source"             to "Source",
        "expense_description"       to "Description",
        "enter_date"                to "Date YYYY-MM-DD (blank = today)",
        "invalid_amount"            to "Invalid amount (must be > 0)",
        "invalid_date"              to "Invalid date format",
        "not_enough_funds"          to "Not enough funds",
        "category_exists"           to "Category already exists",
        "no_items"                  to "No items yet",
        "unallocated"               to "Unallocated",
        "language"                  to "Language",
        "theme"                     to "Theme",
        "currency"                  to "Currency",
        "confirm_delete_expense"    to "Delete this expense?",
        "confirm_delete_income"     to "Delete this income?",
        "period_all"                to "All time",
        "period_month"              to "This month",
        "period_year"               to "This year",
        "income_lbl"                to "Income",
        "expenses_lbl"              to "Expenses",
        "balance_lbl"               to "Balance",
        "no_expenses_period"        to "No expenses in this period",
        "expenses_by_category"      to "By Category",
        "by_day"                    to "By Day",
        "by_month"                  to "By Month",
        "spend_from"                to "Spend from",
        "max"                       to "max",
        "all_sources"               to "All",
        "edit_expense"              to "Edit Expense",
        "edit_income"               to "Edit Income",
        "per_month"                 to "/mo",
        "per_year"                  to "/yr",
        "theme_black"               to "Midnight",
        "theme_white"               to "Cloud",
        "theme_sakura"              to "Sakura",
        "theme_cruel_angel"         to "Cruel Angel",
        "theme_hacker"              to "Hacker",
        "theme_mandarin"            to "Mandarin",
        "about"                     to "About",
        "author"                    to "Author",
        "version"                   to "Version",
        "open_source"               to "Open Source on GitHub",
        "month_reset_day"           to "Month Reset Day",
        "month_reset_desc"          to "The \"This month\" filter starts on this day of each month",
        "day"                       to "Day"
    ),
    "uk" to mapOf(
        "app_name"                  to "Flowi",
        "total_money"               to "Загальні кошти",
        "add_income"                to "Додати дохід",
        "add_category"              to "Додати категорію",
        "edit_category"             to "Редагувати категорію",
        "budget"                    to "Бюджет",
        "daily"                     to "Витрати",
        "expense_history"           to "Іст. витрат",
        "income_history"            to "Іст. доходів",
        "settings"                  to "Налаштування",
        "category"                  to "Категорія",
        "amount"                    to "Сума",
        "description"               to "Опис",
        "date"                      to "Дата",
        "source"                    to "Джерело",
        "mode"                      to "Режим",
        "overdraft"                 to "Перевитрата",
        "spent"                     to "Витрачено",
        "allocated"                 to "Виділено",
        "remaining"                 to "Залишок",
        "total_remaining"           to "Заг. залишок",
        "percentage"                to "Відсоток",
        "monthly"                   to "Щомісяця",
        "annual"                    to "Щороку",
        "allow_overdraft"           to "Дозволити перевитрату",
        "yes"                       to "Так",
        "no"                        to "Ні",
        "ok"                        to "OK",
        "cancel"                    to "Скасувати",
        "save"                      to "Зберегти",
        "delete"                    to "Видалити",
        "edit"                      to "Редагувати",
        "reset"                     to "Скинути всі дані",
        "confirm_reset_title"       to "Підтвердження",
        "confirm_reset"             to "Видалити ВСІ дані? Це незворотньо.",
        "confirm_delete_category"   to "Видалити категорію? Витрати залишаться в історії.",
        "category_name"             to "Назва категорії",
        "category_value"            to "Значення",
        "income_amount"             to "Сума",
        "income_source"             to "Джерело",
        "expense_description"       to "Опис",
        "enter_date"                to "Дата РРРР-ММ-ДД (порожнє = сьогодні)",
        "invalid_amount"            to "Невірна сума (> 0)",
        "invalid_date"              to "Невірний формат дати",
        "not_enough_funds"          to "Недостатньо коштів",
        "category_exists"           to "Категорія вже існує",
        "no_items"                  to "Елементів ще немає",
        "unallocated"               to "Нерозподілено",
        "language"                  to "Мова",
        "theme"                     to "Тема",
        "currency"                  to "Валюта",
        "confirm_delete_expense"    to "Видалити витрату?",
        "confirm_delete_income"     to "Видалити дохід?",
        "period_all"                to "Весь час",
        "period_month"              to "Цей місяць",
        "period_year"               to "Цей рік",
        "income_lbl"                to "Доходи",
        "expenses_lbl"              to "Витрати",
        "balance_lbl"               to "Баланс",
        "no_expenses_period"        to "Немає витрат за цей період",
        "expenses_by_category"      to "За категоріями",
        "by_day"                    to "По днях",
        "by_month"                  to "По місяцях",
        "spend_from"                to "Витратити з",
        "max"                       to "макс",
        "all_sources"               to "Всі",
        "edit_expense"              to "Редагувати витрату",
        "edit_income"               to "Редагувати дохід",
        "per_month"                 to "/міс",
        "per_year"                  to "/рік",
        "theme_black"               to "Північ",
        "theme_white"               to "Хмара",
        "theme_sakura"              to "Сакура",
        "theme_cruel_angel"         to "Жорстокий ангел",
        "theme_hacker"              to "Хакер",
        "theme_mandarin"            to "Мандарин",
        "about"                     to "Про програму",
        "author"                    to "Автор",
        "version"                   to "Версія",
        "open_source"               to "Відкритий код на GitHub",
        "month_reset_day"           to "День скидання місяця",
        "month_reset_desc"          to "Фільтр «Цей місяць» починається з цього дня кожного місяця",
        "day"                       to "День"
    ),
    "de" to mapOf(
        "app_name"                  to "Flowi",
        "total_money"               to "Gesamtgeld",
        "add_income"                to "Einnahme hinzufügen",
        "add_category"              to "Kategorie hinzufügen",
        "edit_category"             to "Kategorie bearbeiten",
        "budget"                    to "Budget",
        "daily"                     to "Ausgaben",
        "expense_history"           to "Ausg. Verlauf",
        "income_history"            to "Einnahm. Verlauf",
        "settings"                  to "Einstellungen",
        "category"                  to "Kategorie",
        "amount"                    to "Betrag",
        "description"               to "Beschreibung",
        "date"                      to "Datum",
        "source"                    to "Quelle",
        "mode"                      to "Modus",
        "overdraft"                 to "Überziehung",
        "spent"                     to "Ausgegeben",
        "allocated"                 to "Zugewiesen",
        "remaining"                 to "Verbleibend",
        "total_remaining"           to "Ges. verbleibend",
        "percentage"                to "Prozent",
        "monthly"                   to "Monatlich",
        "annual"                    to "Jährlich",
        "allow_overdraft"           to "Überziehung erlauben",
        "yes"                       to "Ja",
        "no"                        to "Nein",
        "ok"                        to "OK",
        "cancel"                    to "Abbrechen",
        "save"                      to "Speichern",
        "delete"                    to "Löschen",
        "edit"                      to "Bearbeiten",
        "reset"                     to "Alle Daten löschen",
        "confirm_reset_title"       to "Bestätigen",
        "confirm_reset"             to "ALLE Daten löschen? Das kann nicht rückgängig gemacht werden.",
        "confirm_delete_category"   to "Kategorie löschen? Ausgaben bleiben im Verlauf.",
        "category_name"             to "Kategoriename",
        "category_value"            to "Wert",
        "income_amount"             to "Betrag",
        "income_source"             to "Quelle",
        "expense_description"       to "Beschreibung",
        "enter_date"                to "Datum JJJJ-MM-TT (leer = heute)",
        "invalid_amount"            to "Ungültiger Betrag (muss > 0 sein)",
        "invalid_date"              to "Ungültiges Datumsformat",
        "not_enough_funds"          to "Nicht genug Geld",
        "category_exists"           to "Kategorie existiert bereits",
        "no_items"                  to "Noch keine Einträge",
        "unallocated"               to "Nicht zugewiesen",
        "language"                  to "Sprache",
        "theme"                     to "Design",
        "currency"                  to "Währung",
        "confirm_delete_expense"    to "Ausgabe löschen?",
        "confirm_delete_income"     to "Einnahme löschen?",
        "period_all"                to "Gesamt",
        "period_month"              to "Dieser Monat",
        "period_year"               to "Dieses Jahr",
        "income_lbl"                to "Einnahmen",
        "expenses_lbl"              to "Ausgaben",
        "balance_lbl"               to "Bilanz",
        "no_expenses_period"        to "Keine Ausgaben in diesem Zeitraum",
        "expenses_by_category"      to "Nach Kategorie",
        "by_day"                    to "Nach Tag",
        "by_month"                  to "Nach Monat",
        "spend_from"                to "Ausgeben von",
        "max"                       to "max",
        "all_sources"               to "Alle",
        "edit_expense"              to "Ausgabe bearbeiten",
        "edit_income"               to "Einnahme bearbeiten",
        "per_month"                 to "/Mo",
        "per_year"                  to "/Jahr",
        "theme_black"               to "Mitternacht",
        "theme_white"               to "Wolke",
        "theme_sakura"              to "Sakura",
        "theme_cruel_angel"         to "Grausamer Engel",
        "theme_hacker"              to "Hacker",
        "theme_mandarin"            to "Mandarine",
        "about"                     to "Über",
        "author"                    to "Autor",
        "version"                   to "Version",
        "open_source"               to "Open Source auf GitHub",
        "month_reset_day"           to "Monats-Stichtag",
        "month_reset_desc"          to "Der Filter „Dieser Monat“ beginnt an diesem Tag jedes Monats",
        "day"                       to "Tag"
    ),
    "ar" to mapOf(
        "app_name"                  to "Flowi",
        "total_money"               to "إجمالي الأموال",
        "add_income"                to "إضافة دخل",
        "add_category"              to "إضافة فئة",
        "edit_category"             to "تعديل الفئة",
        "budget"                    to "الميزانية",
        "daily"                     to "المصروفات",
        "expense_history"           to "سجل المصروفات",
        "income_history"            to "سجل الدخل",
        "settings"                  to "الإعدادات",
        "category"                  to "الفئة",
        "amount"                    to "المبلغ",
        "description"               to "الوصف",
        "date"                      to "التاريخ",
        "source"                    to "المصدر",
        "mode"                      to "الوضع",
        "overdraft"                 to "السحب على المكشوف",
        "spent"                     to "المُنفَق",
        "allocated"                 to "المُخصَّص",
        "remaining"                 to "المتبقي",
        "total_remaining"           to "إجمالي المتبقي",
        "percentage"                to "النسبة",
        "monthly"                   to "شهري",
        "annual"                    to "سنوي",
        "allow_overdraft"           to "السماح بالسحب على المكشوف",
        "yes"                       to "نعم",
        "no"                        to "لا",
        "ok"                        to "موافق",
        "cancel"                    to "إلغاء",
        "save"                      to "حفظ",
        "delete"                    to "حذف",
        "edit"                      to "تعديل",
        "reset"                     to "حذف جميع البيانات",
        "confirm_reset_title"       to "تأكيد",
        "confirm_reset"             to "حذف جميع البيانات؟ لا يمكن التراجع.",
        "confirm_delete_category"   to "حذف الفئة؟ ستبقى المصروفات في السجل.",
        "category_name"             to "اسم الفئة",
        "category_value"            to "القيمة",
        "income_amount"             to "المبلغ",
        "income_source"             to "المصدر",
        "expense_description"       to "الوصف",
        "enter_date"                to "التاريخ YYYY-MM-DD (فارغ = اليوم)",
        "invalid_amount"            to "مبلغ غير صالح (يجب أن يكون > 0)",
        "invalid_date"              to "تنسيق تاريخ غير صالح",
        "not_enough_funds"          to "رصيد غير كافٍ",
        "category_exists"           to "الفئة موجودة بالفعل",
        "no_items"                  to "لا يوجد شيء بعد",
        "unallocated"               to "غير مخصص",
        "language"                  to "اللغة",
        "theme"                     to "المظهر",
        "currency"                  to "العملة",
        "confirm_delete_expense"    to "حذف هذا المصروف؟",
        "confirm_delete_income"     to "حذف هذا الدخل؟",
        "period_all"                to "كل الوقت",
        "period_month"              to "هذا الشهر",
        "period_year"               to "هذا العام",
        "income_lbl"                to "الدخل",
        "expenses_lbl"              to "المصروفات",
        "balance_lbl"               to "الرصيد",
        "no_expenses_period"        to "لا توجد مصروفات في هذه الفترة",
        "expenses_by_category"      to "حسب الفئة",
        "by_day"                    to "حسب اليوم",
        "by_month"                  to "حسب الشهر",
        "spend_from"                to "الإنفاق من",
        "max"                       to "الحد الأقصى",
        "all_sources"               to "الكل",
        "edit_expense"              to "تعديل المصروف",
        "edit_income"               to "تعديل الدخل",
        "per_month"                 to "/شهر",
        "per_year"                  to "/سنة",
        "theme_black"               to "منتصف الليل",
        "theme_white"               to "سحابة",
        "theme_sakura"              to "ساكورا",
        "theme_cruel_angel"         to "الملاك القاسي",
        "theme_hacker"              to "هاكر",
        "theme_mandarin"            to "ماندرين",
        "about"                     to "حول",
        "author"                    to "المؤلف",
        "version"                   to "الإصدار",
        "open_source"               to "مفتوح المصدر على GitHub",
        "month_reset_day"           to "يوم إعادة تعيين الشهر",
        "month_reset_desc"          to "يبدأ فلتر «هذا الشهر» في هذا اليوم من كل شهر",
        "day"                       to "اليوم"
    ),
    "zh" to mapOf(
        "app_name"                  to "Flowi",
        "total_money"               to "总资金",
        "add_income"                to "添加收入",
        "add_category"              to "添加类别",
        "edit_category"             to "编辑类别",
        "budget"                    to "预算",
        "daily"                     to "支出",
        "expense_history"           to "支出记录",
        "income_history"            to "收入记录",
        "settings"                  to "设置",
        "category"                  to "类别",
        "amount"                    to "金额",
        "description"               to "描述",
        "date"                      to "日期",
        "source"                    to "来源",
        "mode"                      to "模式",
        "overdraft"                 to "透支",
        "spent"                     to "已花",
        "allocated"                 to "已分配",
        "remaining"                 to "剩余",
        "total_remaining"           to "总剩余",
        "percentage"                to "百分比",
        "monthly"                   to "每月",
        "annual"                    to "每年",
        "allow_overdraft"           to "允许透支",
        "yes"                       to "是",
        "no"                        to "否",
        "ok"                        to "确定",
        "cancel"                    to "取消",
        "save"                      to "保存",
        "delete"                    to "删除",
        "edit"                      to "编辑",
        "reset"                     to "删除所有数据",
        "confirm_reset_title"       to "确认",
        "confirm_reset"             to "删除所有数据？此操作无法撤销。",
        "confirm_delete_category"   to "删除此类别？相关支出将保留在记录中。",
        "category_name"             to "类别名称",
        "category_value"            to "数值",
        "income_amount"             to "金额",
        "income_source"             to "来源",
        "expense_description"       to "描述",
        "enter_date"                to "日期 YYYY-MM-DD（空白 = 今天）",
        "invalid_amount"            to "无效金额（必须 > 0）",
        "invalid_date"              to "日期格式无效",
        "not_enough_funds"          to "资金不足",
        "category_exists"           to "类别已存在",
        "no_items"                  to "暂无内容",
        "unallocated"               to "未分配",
        "language"                  to "语言",
        "theme"                     to "主题",
        "currency"                  to "货币",
        "confirm_delete_expense"    to "删除此支出？",
        "confirm_delete_income"     to "删除此收入？",
        "period_all"                to "全部时间",
        "period_month"              to "本月",
        "period_year"               to "今年",
        "income_lbl"                to "收入",
        "expenses_lbl"              to "支出",
        "balance_lbl"               to "余额",
        "no_expenses_period"        to "此时间段内无支出",
        "expenses_by_category"      to "按类别",
        "by_day"                    to "按日",
        "by_month"                  to "按月",
        "spend_from"                to "从...支出",
        "max"                       to "最大",
        "all_sources"               to "全部",
        "edit_expense"              to "编辑支出",
        "edit_income"               to "编辑收入",
        "per_month"                 to "/月",
        "per_year"                  to "/年",
        "theme_black"               to "午夜",
        "theme_white"               to "云朵",
        "theme_sakura"              to "樱花",
        "theme_cruel_angel"         to "残酷天使",
        "theme_hacker"              to "黑客",
        "theme_mandarin"            to "橘子",
        "about"                     to "关于",
        "author"                    to "作者",
        "version"                   to "版本",
        "open_source"               to "GitHub 开源",
        "month_reset_day"           to "月度重置日",
        "month_reset_desc"          to "“本月”筛选从每月的这一天开始",
        "day"                       to "第"
    )
)

// ── Helpers ───────────────────────────────────────────────────────────────────

val kDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
fun todayStr() : String = kDateFmt.format(Date())
fun parseDate(s: String): Date? = runCatching { kDateFmt.parse(s) }.getOrNull()

// shorthand to grab a translated string, falls back to the key itself if missing
fun t(tr: Map<String, String>, key: String, fallback: String = key) = tr[key] ?: fallback

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

fun filterByPeriod(date: String, period: String, monthResetDay: Int = 1): Boolean {
    val cal = Calendar.getInstance()
    val y   = cal.get(Calendar.YEAR)
    return when (period) {
        "month" -> {
            // yyyy-MM-dd strings compare correctly as plain strings
            val (s, e) = budgetMonthRange(monthResetDay.coerceIn(1, 31))
            date >= s && date < e
        }
        "year"  -> date.startsWith("%04d".format(y))
        else    -> true
    }
}

fun computeUnallocated(categories: Map<String, Category>, totalMoney: Double): Double {
    val used = categories.filter { it.key != "__unallocated__" }.values.sumOf { it.allocated(totalMoney) }
    return (totalMoney - used).coerceAtLeast(0.0)
}

// Helper that produces themed colors for OutlinedTextField
@Composable
fun otfColors(th: AppThemeData) = OutlinedTextFieldDefaults.colors(
    focusedTextColor       = th.fg,
    unfocusedTextColor     = th.fg,
    focusedBorderColor     = th.accent,
    unfocusedBorderColor   = th.fg.copy(alpha = 0.3f),
    focusedLabelColor      = th.accent,
    unfocusedLabelColor    = th.fg.copy(alpha = 0.55f),
    cursorColor            = th.accent
)

// ── Main Activity ─────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx   = LocalContext.current
            val prefs = ctx.getSharedPreferences("jb_v3", Context.MODE_PRIVATE)
            val state = remember {
                prefs.getString("data", null)
                    ?.let { runCatching { AppState.fromJson(it) }.getOrNull() }
                    ?: AppState()
            }
            // track total spent so LaunchedEffect knows when to save
            val spentHash by remember { derivedStateOf { state.categories.values.sumOf { it.spent } } }
            // auto-save whenever anything meaningful changes
            LaunchedEffect(
                state.totalMoney, state.language, state.themeName, state.currency,
                state.monthResetDay,
                state.categories.size, state.expenses.size, state.incomes.size, spentHash
            ) { prefs.edit().putString("data", state.toJson()).apply() }

            val th = kThemes[state.themeName] ?: kThemes["black"]!!
            val tr = kT[state.language] ?: kT["en"]!!
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
                background = th.bg, surface = th.cardBg,
                primary = th.accent, onBackground = th.fg, onSurface = th.fg
            )) { BudgetPlannerApp(state, th, tr) }
        }
    }
}

// ── Root App ──────────────────────────────────────────────────────────────────

@Composable
fun BudgetPlannerApp(state: AppState, th: AppThemeData, tr: Map<String, String>) {
    var tab by remember { mutableStateOf(0) }
    val sym  = kCurrencies[state.currency] ?: "$"
    val tabs = listOf(
        t(tr,"budget"), t(tr,"daily"),
        t(tr,"expense_history"), t(tr,"income_history"), t(tr,"settings")
    )

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // App logo badge — a small accent-colored dot badge
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .background(th.accent, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("F", color = th.bg, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            }
                            Text(
                                t(tr, "app_name"),
                                fontWeight = FontWeight.Bold,
                                color      = th.fg,
                                fontSize   = 20.sp,
                                letterSpacing = (-0.5).sp
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = th.bg)
                )
                // Floating pill tab strip
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(th.cardBg, RoundedCornerShape(16.dp))
                            .padding(4.dp)
                    ) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            tabs.forEachIndexed { i, label ->
                                Box(
                                    Modifier
                                        .background(
                                            if (tab == i) th.accent else Color.Transparent,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { tab = i }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        fontSize   = 12.sp,
                                        fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal,
                                        color      = if (tab == i) th.bg else th.fg.copy(0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = th.bg
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> BudgetTab(state, th, tr, sym)
                1 -> DailyTab(state, th, tr, sym)
                2 -> ExpenseHistoryTab(state, th, tr, sym)
                3 -> IncomeHistoryTab(state, th, tr, sym)
                4 -> SettingsTab(state, th, tr)
            }
        }
    }
}

// ── Budget Tab ────────────────────────────────────────────────────────────────

@Composable
fun BudgetTab(state: AppState, th: AppThemeData, tr: Map<String, String>, sym: String) {
    var showIncome        by remember { mutableStateOf(false) }
    var showCategory      by remember { mutableStateOf(false) }
    var spendTarget       by remember { mutableStateOf<String?>(null) }
    var editCategoryName  by remember { mutableStateOf<String?>(null) }   // ← NEW: category to edit
    var deleteCategoryName by remember { mutableStateOf<String?>(null) }  // ← NEW: category to delete
    var period            by remember { mutableStateOf("all") }

    val unallocAlloc = computeUnallocated(state.categories, state.totalMoney)
    val unallocSpent = state.categories["__unallocated__"]?.spent ?: 0.0

    val allExp  = state.expenses.toList()
    val allInc  = state.incomes.toList()
    val pExp    = allExp.filter { filterByPeriod(it.date, period, state.monthResetDay) }
    val pInc    = allInc.filter { filterByPeriod(it.date, period, state.monthResetDay) }
    val pExpSum = pExp.sumOf { it.amount }
    val pIncSum = pInc.sumOf { it.amount }
    val pBal    = pIncSum - pExpSum

    Column(Modifier.fillMaxSize()) {

        // ── Period chips ──
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "all"   to t(tr,"period_all"),
                "month" to t(tr,"period_month"),
                "year"  to t(tr,"period_year")
            ).forEach { (k, lbl) ->
                FilterChip(
                    selected = period == k,
                    onClick  = { period = k },
                    label    = { Text(lbl, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = th.accent,
                        selectedLabelColor     = th.bg
                    ),
                    shape = RoundedCornerShape(50)
                )
            }
        }

        // ── Summary card ──
        Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            shape  = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = th.cardBg),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${t(tr,"total_money")}: ${"%.2f".format(state.totalMoney)} $sym",
                    fontWeight = FontWeight.Bold,
                    color      = th.fg,
                    fontSize   = 18.sp
                )
                HorizontalDivider(color = th.fg.copy(0.07f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    // Income column
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("↑ ${"%.2f".format(pIncSum)} $sym", color = Color(0xFF34C759), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(t(tr,"income_lbl"), color = th.fg.copy(0.45f), fontSize = 11.sp)
                    }
                    // Expense column
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("↓ ${"%.2f".format(pExpSum)} $sym", color = Color(0xFFFF3B30), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(t(tr,"expenses_lbl"), color = th.fg.copy(0.45f), fontSize = 11.sp)
                    }
                    // Balance column
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "= ${"%.2f".format(pBal)} $sym",
                            color      = if (pBal >= 0) Color(0xFF34C759) else Color(0xFFFF3B30),
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(t(tr,"balance_lbl"), color = th.fg.copy(0.45f), fontSize = 11.sp)
                    }
                }
            }
        }

        // ── Action buttons ──
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick   = { showIncome = true },
                modifier  = Modifier.weight(1f).height(46.dp),
                colors    = ButtonDefaults.buttonColors(containerColor = th.cardBg, contentColor = th.fg),
                shape     = RoundedCornerShape(14.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = th.accent)
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

        // ── Category list ──
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Named categories
            state.categories.filter { it.key != "__unallocated__" }.forEach { (name, cat) ->
                item(key = name) {
                    val pSpent    = pExp.filter { it.category == name }.sumOf { it.amount }
                    val dispSpent = if (period == "all") cat.spent else pSpent
                    val alloc     = cat.allocated(state.totalMoney)
                    val modeStr   = when (cat.mode) {
                        "percentage" -> "${cat.value.toInt()}%"
                        "monthly"    -> "${"%.0f".format(cat.value)}$sym${t(tr,"per_month")}"
                        "annual"     -> "${"%.0f".format(cat.value)}$sym${t(tr,"per_year")}"
                        else          -> cat.value.toString()
                    }
                    CategoryCard(
                        name       = name,
                        modeStr    = modeStr,
                        alloc      = alloc,
                        spent      = dispSpent,
                        sym        = sym,
                        th         = th,
                        tr         = tr,
                        isDeletable = true,
                        onSpend    = { spendTarget = name },
                        onEdit     = { editCategoryName = name },    // ← NEW
                        onDelete   = { deleteCategoryName = name }   // ← NEW
                    )
                }
            }

            // Unallocated (virtual bucket for leftover money)
            if (unallocAlloc > 0) {
                item(key = "__unallocated__") {
                    val pSpent    = pExp.filter { it.category == "__unallocated__" }.sumOf { it.amount }
                    val dispSpent = if (period == "all") unallocSpent else pSpent
                    CategoryCard(
                        name        = t(tr,"unallocated"),
                        modeStr     = "~",
                        alloc       = unallocAlloc,
                        spent       = dispSpent,
                        sym         = sym,
                        th          = th,
                        tr          = tr,
                        isDeletable = false,    // can't delete the unallocated bucket
                        onSpend     = { spendTarget = "__unallocated__" },
                        onEdit      = {},
                        onDelete    = {}
                    )
                }
            }
        }
    }

    // ── Dialogs ──

    if (showIncome) {
        IncomeDialog(tr, th) { source, amount, date ->
            if (amount > 0) {
                state.totalMoney += amount
                state.incomes.add(Income(source, amount, date))
            }
            showIncome = false
        }
    }

    if (showCategory) {
        CategoryDialog(existing = null, tr = tr, th = th, sym = sym, state = state) { name, value, mode, overdraft ->
            if (name.isNotBlank() && value > 0 && !state.categories.containsKey(name)) {
                state.categories[name] = Category(name, value, mode, overdraft)
            }
            showCategory = false
        }
    }

    // ── Edit category dialog ──
    editCategoryName?.let { oldName ->
        val oldCat = state.categories[oldName]
        CategoryDialog(existing = oldCat, tr = tr, th = th, sym = sym, state = state) { newName, newValue, newMode, newOverdraft ->
            if (newName.isNotBlank() && newValue > 0) {
                if (newName != oldName) {
                    // Rename: update every expense that used the old category name
                    // We iterate backwards by index so we can safely modify while iterating
                    for (i in state.expenses.indices) {
                        if (state.expenses[i].category == oldName) {
                            state.expenses[i] = state.expenses[i].copy(category = newName)
                        }
                    }
                    state.categories.remove(oldName)
                }
                // Keep the spent amount but update all other fields
                state.categories[newName] = Category(newName, newValue, newMode, newOverdraft, oldCat?.spent ?: 0.0)
            }
            editCategoryName = null
        }
    }

    // ── Delete category confirmation ──
    deleteCategoryName?.let { name ->
        ConfirmDialog(t(tr, "confirm_delete_category"), tr, th) { ok ->
            if (ok) {
                state.categories.remove(name)
                // expenses stay in history so the totals don't get messed up
            }
            deleteCategoryName = null
        }
    }

    // ── Spend dialog ──
    spendTarget?.let { target ->
        val maxAmt = if (target == "__unallocated__")
            (unallocAlloc - unallocSpent).coerceAtLeast(0.0)
        else
            state.categories[target]?.remaining(state.totalMoney)?.coerceAtLeast(0.0) ?: 0.0

        val displayName = if (target == "__unallocated__") t(tr,"unallocated") else target

        SpendDialog(displayName, maxAmt, sym, tr, th) { amount, desc, date ->
            if (amount > 0) {
                val prev = state.categories[target]?.spent ?: 0.0
                state.categories[target] = (state.categories[target]
                    ?: Category(target, 0.0, "unallocated", false, 0.0))
                    .copy(spent = prev + amount)
                state.expenses.add(Expense(target, amount, desc, date))
            }
            spendTarget = null
        }
    }
}

// ── Category Card ─────────────────────────────────────────────────────────────
// This card now shows a progress bar (spent vs allocated) and has
// Edit / Delete buttons in addition to the Spend (+) button.

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
    onDelete: () -> Unit
) {
    val rem      = alloc - spent
    // coerceIn clamps the value between 0f and 1f so the progress bar never overflows
    val progress = if (alloc > 0) (spent / alloc).toFloat().coerceIn(0f, 1f) else 0f
    val overBudget = rem < 0

    Card(
        Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = th.cardBg),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // Top row: name + action buttons
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
                        "[$modeStr] · ${t(tr,"allocated")}: ${"%.2f".format(alloc)} $sym",
                        fontSize = 11.sp,
                        color    = th.fg.copy(0.45f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Edit/delete only show for real categories, not the unallocated bucket.
                    // Using Box+clickable instead of IconButton because IconButton forces a
                    // 48dp minimum touch target that causes the buttons to overlap each other.
                    if (isDeletable) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(th.fg.copy(0.07f), RoundedCornerShape(10.dp))
                                .clickable { onEdit() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Edit, null, tint = th.fg.copy(0.6f), modifier = Modifier.size(15.dp))
                        }
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(Color(0xFFFF3B30).copy(0.12f), RoundedCornerShape(10.dp))
                                .clickable { onDelete() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Delete, null, tint = Color(0xFFFF3B30), modifier = Modifier.size(15.dp))
                        }
                    }
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(th.accent, RoundedCornerShape(10.dp))
                            .clickable { onSpend() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, null, tint = th.bg, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Custom progress bar — two layered Boxes instead of LinearProgressIndicator.
            // Material3's LinearProgressIndicator adds a small dot at the end of the track
            // that we don't want, so we just draw it ourselves.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(th.fg.copy(0.08f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(if (overBudget) Color(0xFFFF3B30) else th.accent)
                )
            }

            // Bottom row: spent vs remaining
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${t(tr,"spent")}: ${"%.2f".format(spent)} $sym",
                    fontSize = 12.sp,
                    color    = th.fg.copy(0.5f)
                )
                Text(
                    "${t(tr,"remaining")}: ${"%.2f".format(rem)} $sym",
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (overBudget) Color(0xFFFF3B30) else th.accent
                )
            }
        }
    }
}

// ── Daily / Expenses Tab ──────────────────────────────────────────────────────

@Composable
fun DailyTab(state: AppState, th: AppThemeData, tr: Map<String, String>, sym: String) {
    var period    by remember { mutableStateOf("month") }
    var chartMode by remember { mutableStateOf("pie") }

    val filtered = state.expenses.filter { filterByPeriod(it.date, period, state.monthResetDay) }
    val catSums  = filtered.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }

    Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "month" to t(tr,"period_month"),
                "year"  to t(tr,"period_year"),
                "all"   to t(tr,"period_all")
            ).forEach { (k, lbl) ->
                FilterChip(
                    selected = period == k,
                    onClick  = { period = k },
                    label    = { Text(lbl, fontSize = 12.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = th.accent,
                        selectedLabelColor     = th.bg
                    ),
                    shape = RoundedCornerShape(50)
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t(tr,"no_expenses_period"), color = th.fg.copy(0.4f), textAlign = TextAlign.Center)
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = chartMode == "pie",
                onClick  = { chartMode = "pie" },
                label    = { Text(t(tr,"expenses_by_category"), fontSize = 12.sp) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = th.accent,
                    selectedLabelColor     = th.bg
                ),
                shape = RoundedCornerShape(50)
            )
            val barLbl = if (period == "year") t(tr,"by_month") else t(tr,"by_day")
            FilterChip(
                selected = chartMode == "bar",
                onClick  = { chartMode = "bar" },
                label    = { Text(barLbl, fontSize = 12.sp) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = th.accent,
                    selectedLabelColor     = th.bg
                ),
                shape = RoundedCornerShape(50)
            )
        }
        Spacer(Modifier.height(8.dp))

        if (chartMode == "pie") {
            SimplePieChart(catSums, th)
            Spacer(Modifier.height(14.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                val sorted = catSums.entries.sortedByDescending { it.value }
                itemsIndexed(sorted) { i, (cat, amt) ->
                    Row(
                        Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(kChartColors[i % kChartColors.size], RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(cat, Modifier.weight(1f), color = th.fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${"%.2f".format(amt)} $sym", fontWeight = FontWeight.SemiBold, color = th.fg, fontSize = 13.sp)
                    }
                }
            }
        } else {
            val barData: Map<String, Double> = if (period == "year") {
                val map = (1..12).associate { "%02d".format(it) to 0.0 }.toMutableMap()
                filtered.forEach { e -> val m = e.date.drop(5).take(2); map[m] = (map[m] ?: 0.0) + e.amount }
                map.toSortedMap()
            } else {
                val map = mutableMapOf<String, Double>()
                filtered.forEach { e -> val d = e.date.takeLast(2); map[d] = (map[d] ?: 0.0) + e.amount }
                map.toSortedMap()
            }
            SimpleBarChart(barData, sym, th)
        }
    }
}

@Composable
fun SimplePieChart(data: Map<String, Double>, th: AppThemeData) {
    val total = data.values.sum()
    Canvas(Modifier.size(180.dp)) {
        var start = -90f
        data.values.forEachIndexed { i, v ->
            val sweep = if (total > 0) (v / total * 360f).toFloat() else 0f
            drawArc(kChartColors[i % kChartColors.size], start, sweep, useCenter = true)
            start += sweep
        }
        // Draw a circle in the center to create the "donut" effect
        drawCircle(th.bg, size.minDimension / 3.8f)
    }
}

@Composable
fun SimpleBarChart(data: Map<String, Double>, sym: String, th: AppThemeData) {
    if (data.isEmpty()) return
    val maxVal  = data.values.maxOrNull()?.coerceAtLeast(0.01) ?: 1.0
    val entries = data.entries.toList()

    Canvas(Modifier.fillMaxWidth().height(200.dp).padding(bottom = 4.dp)) {
        val labelH = 22.dp.toPx()
        val chartH = size.height - labelH
        val total  = entries.size.coerceAtLeast(1)
        val barW   = (size.width / (total * 1.4f)).coerceAtMost(32.dp.toPx())
        val gap    = (size.width - barW * total) / (total + 1)
        val paint  = NativePaint().apply {
            color       = th.fg.copy(alpha = 0.55f).toArgb()
            textSize    = 8.5.sp.toPx()
            textAlign   = NativePaint.Align.CENTER
            isAntiAlias = true
        }
        entries.forEachIndexed { i, (label, value) ->
            val barH = (value / maxVal * chartH).toFloat().coerceAtLeast(2f)
            val x    = gap + i * (barW + gap)
            drawRect(kChartColors[i % kChartColors.size], Offset(x, chartH - barH), Size(barW, barH))
            val step = when { total > 20 -> 5; total > 10 -> 3; else -> 1 }
            if (i % step == 0) {
                drawContext.canvas.nativeCanvas.drawText(label, x + barW / 2f, size.height - 2.dp.toPx(), paint)
            }
        }
    }
}

// ── Expense History Tab ───────────────────────────────────────────────────────

@Composable
fun ExpenseHistoryTab(state: AppState, th: AppThemeData, tr: Map<String, String>, sym: String) {
    var filterCat by remember { mutableStateOf<String?>(null) }
    var editIdx   by remember { mutableStateOf<Int?>(null) }
    var delIdx    by remember { mutableStateOf<Int?>(null) }

    val allCats  = state.expenses.map { it.category }.distinct()
    // Newest first: sort by date descending; same-date entries fall back to
    // insertion order descending (the most recently added one wins).
    val indexed  = state.expenses.mapIndexed { i, e -> i to e }
        .sortedWith(compareByDescending<Pair<Int, Expense>> { it.second.date }.thenByDescending { it.first })
    val filtered = if (filterCat == null) indexed else indexed.filter { it.second.category == filterCat }

    Column(Modifier.fillMaxSize()) {
        if (allCats.size > 1) {
            val selIdx = if (filterCat == null) 0 else (allCats.indexOf(filterCat) + 1).coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = selIdx,
                containerColor   = th.bg,
                contentColor     = th.accent,
                edgePadding      = 0.dp
            ) {
                Tab(
                    selected = filterCat == null,
                    onClick  = { filterCat = null },
                    text     = { Text(t(tr,"all_sources"), color = if (filterCat == null) th.accent else th.fg.copy(0.5f), fontSize = 12.sp) }
                )
                allCats.forEach { cat ->
                    Tab(
                        selected = filterCat == cat,
                        onClick  = { filterCat = if (filterCat == cat) null else cat },
                        text     = { Text(cat, color = if (filterCat == cat) th.accent else th.fg.copy(0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t(tr,"no_items"), color = th.fg.copy(0.4f))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                itemsIndexed(filtered, key = { _, p -> p.first }) { _, (realIdx, exp) ->
                    HistoryCard(
                        headline   = "${exp.category} — ${"%.2f".format(exp.amount)} $sym",
                        supporting = "${exp.description.ifBlank { "—" }} · ${exp.date}",
                        th         = th,
                        onEdit     = { editIdx = realIdx },
                        onDelete   = { delIdx  = realIdx }
                    )
                }
            }
        }
    }

    editIdx?.let { idx ->
        val exp  = state.expenses[idx]
        val cats = state.categories.keys.filter { it != "__unallocated__" }
        EditExpenseDialog(exp, cats, tr, th) { newCat, newAmt, newDesc, newDate ->
            if (newAmt > 0) {
                val old = exp.category; val diff = newAmt - exp.amount
                if (old == newCat) {
                    state.categories[old]?.let { state.categories[old] = it.copy(spent = (it.spent + diff).coerceAtLeast(0.0)) }
                } else {
                    state.categories[old]?.let    { state.categories[old]    = it.copy(spent = (it.spent - exp.amount).coerceAtLeast(0.0)) }
                    state.categories[newCat]?.let { state.categories[newCat] = it.copy(spent = it.spent + newAmt) }
                }
                state.expenses[idx] = exp.copy(category = newCat, amount = newAmt, description = newDesc, date = newDate)
            }
            editIdx = null
        }
    }

    delIdx?.let { idx ->
        ConfirmDialog(t(tr,"confirm_delete_expense"), tr, th) { ok ->
            if (ok) {
                val e = state.expenses[idx]
                state.categories[e.category]?.let {
                    state.categories[e.category] = it.copy(spent = (it.spent - e.amount).coerceAtLeast(0.0))
                }
                state.expenses.removeAt(idx)
            }
            delIdx = null
        }
    }
}

// ── Income History Tab ────────────────────────────────────────────────────────

@Composable
fun IncomeHistoryTab(state: AppState, th: AppThemeData, tr: Map<String, String>, sym: String) {
    var filterSrc by remember { mutableStateOf<String?>(null) }
    var editIdx   by remember { mutableStateOf<Int?>(null) }
    var delIdx    by remember { mutableStateOf<Int?>(null) }

    val allSrcs  = state.incomes.map { it.source }.distinct()
    // Newest first — same sorting rule as the expense history
    val indexed  = state.incomes.mapIndexed { i, e -> i to e }
        .sortedWith(compareByDescending<Pair<Int, Income>> { it.second.date }.thenByDescending { it.first })
    val filtered = if (filterSrc == null) indexed else indexed.filter { it.second.source == filterSrc }

    Column(Modifier.fillMaxSize()) {
        if (allSrcs.size > 1) {
            val selIdx = if (filterSrc == null) 0 else (allSrcs.indexOf(filterSrc) + 1).coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = selIdx,
                containerColor   = th.bg,
                contentColor     = th.accent,
                edgePadding      = 0.dp
            ) {
                Tab(
                    selected = filterSrc == null,
                    onClick  = { filterSrc = null },
                    text     = { Text(t(tr,"all_sources"), color = if (filterSrc == null) th.accent else th.fg.copy(0.5f), fontSize = 12.sp) }
                )
                allSrcs.forEach { src ->
                    Tab(
                        selected = filterSrc == src,
                        onClick  = { filterSrc = if (filterSrc == src) null else src },
                        text     = { Text(src, color = if (filterSrc == src) th.accent else th.fg.copy(0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t(tr,"no_items"), color = th.fg.copy(0.4f))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                itemsIndexed(filtered, key = { _, p -> p.first }) { _, (realIdx, inc) ->
                    HistoryCard(
                        headline   = "${inc.source} — ${"%.2f".format(inc.amount)} $sym",
                        supporting = inc.date,
                        th         = th,
                        onEdit     = { editIdx = realIdx },
                        onDelete   = { delIdx  = realIdx }
                    )
                }
            }
        }
    }

    editIdx?.let { idx ->
        val inc = state.incomes[idx]
        EditIncomeDialog(inc, tr, th) { newSrc, newAmt, newDate ->
            if (newAmt > 0) {
                state.totalMoney += (newAmt - inc.amount)
                state.incomes[idx] = inc.copy(source = newSrc, amount = newAmt, date = newDate)
            }
            editIdx = null
        }
    }

    delIdx?.let { idx ->
        ConfirmDialog(t(tr,"confirm_delete_income"), tr, th) { ok ->
            if (ok) {
                state.totalMoney -= state.incomes[idx].amount
                state.incomes.removeAt(idx)
            }
            delIdx = null
        }
    }
}

@Composable
fun HistoryCard(headline: String, supporting: String, th: AppThemeData, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = th.cardBg),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        ListItem(
            headlineContent   = {
                Text(headline, fontWeight = FontWeight.SemiBold, color = th.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(supporting, color = th.fg.copy(0.5f), fontSize = 12.sp)
            },
            trailingContent   = {
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, null, tint = th.accent, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = th.cardBg)
        )
    }
}

// ── Settings Tab ──────────────────────────────────────────────────────────────

@Composable
fun SettingsTab(state: AppState, th: AppThemeData, tr: Map<String, String>) {
    var showReset     by remember { mutableStateOf(false) }
    // Each boolean controls whether that accordion section is open or closed
    var langExpanded  by remember { mutableStateOf(false) }
    var themeExpanded by remember { mutableStateOf(false) }
    var currExpanded  by remember { mutableStateOf(false) }
    var resetDayExpanded by remember { mutableStateOf(false) }
    var aboutExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {

        // ── Language section ──
        item {
            AccordionSection(
                title      = t(tr, "language"),
                subtitle   = kLanguageNames[state.language] ?: state.language,
                expanded   = langExpanded,
                th         = th,
                onToggle   = { langExpanded = !langExpanded }
            ) {
                kLanguageNames.forEach { (code, name) ->
                    RadioRow(label = name, selected = state.language == code, th = th) {
                        state.language = code
                    }
                }
            }
        }

        // ── Theme section ──
        item {
            AccordionSection(
                title    = t(tr, "theme"),
                subtitle = t(tr, "theme_${state.themeName}", state.themeName),
                expanded = themeExpanded,
                th       = th,
                onToggle = { themeExpanded = !themeExpanded }
            ) {
                kThemeKeys.forEach { key ->
                    val preview = kThemes[key]!!
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
                                    .border(1.dp, th.fg.copy(0.2f), RoundedCornerShape(4.dp))
                            )
                            Box(Modifier.size(16.dp).background(preview.accent, RoundedCornerShape(4.dp)))
                            Box(
                                Modifier.size(16.dp)
                                    .background(preview.cardBg, RoundedCornerShape(4.dp))
                                    .border(1.dp, th.fg.copy(0.2f), RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }
            }
        }

        // ── Currency section ──
        item {
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

        // ── Month reset day section ──
        item {
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
                    color    = th.fg.copy(0.5f),
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
                                    .background(
                                        if (selected) th.accent else th.fg.copy(0.06f),
                                        RoundedCornerShape(10.dp)
                                    )
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
                        // pad the last row so cells keep the same width
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // ── About section ──
        item {
            AccordionSection(
                title    = t(tr, "about"),
                subtitle = "Flowi v0.7",
                expanded = aboutExpanded,
                th       = th,
                onToggle = { aboutExpanded = !aboutExpanded }
            ) {
                AboutContent(th, tr)
            }
        }

        // ── Reset button ──
        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick   = { showReset = true },
                modifier  = Modifier.fillMaxWidth().height(52.dp),
                colors    = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30).copy(0.15f)),
                shape     = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) {
                Icon(Icons.Default.Delete, null, tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t(tr,"reset"), color = Color(0xFFFF3B30), fontWeight = FontWeight.SemiBold)
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
                    state.totalMoney = 0.0
                    state.categories.clear()
                    state.expenses.clear()
                    state.incomes.clear()
                    showReset = false
                }) { Text(t(tr,"yes"), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold) }
            },
            dismissButton    = {
                TextButton(onClick = { showReset = false }) { Text(t(tr,"no"), color = th.accent) }
            },
            containerColor   = th.cardBg,
            shape            = RoundedCornerShape(24.dp)
        )
    }
}

// ── Accordion Section ─────────────────────────────────────────────────────────

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
            // Header row — tappable to expand/collapse
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
                    // The subtitle shows the current value (e.g. "English") when collapsed
                    Text(subtitle, fontSize = 13.sp, color = th.accent)
                }
                // The arrow rotates 180° when expanded — Modifier.rotate() is from compose-ui
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint     = th.fg.copy(0.5f),
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(if (expanded) 180f else 0f)
                )
            }

            // Content is only shown when expanded — simple if/else, no animation needed
            if (expanded) {
                HorizontalDivider(color = th.fg.copy(0.06f))
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    content = content
                )
            }
        }
    }
}

// ── About Content ─────────────────────────────────────────────────────────────

@Composable
fun AboutContent(th: AppThemeData, tr: Map<String, String>) {
    // LocalUriHandler can open a URL in the default browser — clean Compose API
    val uriHandler = LocalUriHandler.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // App info row
        Row(
            Modifier
                .fillMaxWidth()
                .background(th.accent.copy(0.08f), RoundedCornerShape(14.dp))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // App badge — same as in the top bar
            Box(
                Modifier.size(48.dp).background(th.accent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("F", color = th.bg, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            }
            Column {
                Text("Flowi", fontWeight = FontWeight.Bold, color = th.fg, fontSize = 17.sp)
                Text("${t(tr,"version")} 0.7", fontSize = 12.sp, color = th.fg.copy(0.5f))
            }
        }

        // Author info
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(t(tr, "author"), color = th.fg.copy(0.6f), fontSize = 13.sp)
            Text("skotytaka", color = th.fg, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }

        // GitHub link — tappable, opens browser
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { uriHandler.openUri("https://github.com/skotytaka") }
                .background(th.fg.copy(0.04f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(t(tr, "open_source"), color = th.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Icon(Icons.Default.ArrowDropDown,
                null,
                tint     = th.accent,
                modifier = Modifier.size(20.dp).rotate(-90f)  // rotated arrow = "→"
            )
        }
    }
}

// ── Helpers: Settings Rows ────────────────────────────────────────────────────

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

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
fun IncomeDialog(tr: Map<String, String>, th: AppThemeData, onConfirm: (String, Double, String) -> Unit) {
    var source    by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var dateStr   by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onConfirm("", 0.0, "") },
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
                OutlinedTextField(
                    value = dateStr, onValueChange = { dateStr = it },
                    label = { Text(t(tr,"enter_date")) },
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amt  = amountStr.toDoubleOrNull() ?: 0.0
                val date = if (dateStr.isBlank() || parseDate(dateStr) == null) todayStr() else dateStr
                onConfirm(source.ifBlank { "N/A" }, amt, date)
            }) { Text(t(tr,"ok"), color = th.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton  = {
            TextButton(onClick = { onConfirm("", 0.0, "") }) { Text(t(tr,"cancel"), color = th.fg.copy(0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}

@Composable
fun CategoryDialog(
    existing: Category?,
    tr: Map<String, String>,
    th: AppThemeData,
    sym: String,
    state: AppState,
    onConfirm: (String, Double, String, Boolean) -> Unit
) {
    var name      by remember { mutableStateOf(existing?.name ?: "") }
    var valueStr  by remember { mutableStateOf(existing?.value?.toString() ?: "") }
    var mode      by remember { mutableStateOf(existing?.mode ?: "percentage") }
    var overdraft by remember { mutableStateOf(existing?.overdraft ?: false) }

    // The title changes depending on whether we're creating or editing
    val dialogTitle = if (existing != null) t(tr,"edit_category") else t(tr,"add_category")

    AlertDialog(
        onDismissRequest = { onConfirm("", 0.0, "", false) },
        title  = { Text(dialogTitle, color = th.fg, fontWeight = FontWeight.Bold) },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(t(tr,"category_name")) },
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp),
                    // If editing, lock the name field to prevent renaming (handled via separate rename flow)
                    // Actually we allow renaming — that's a useful feature!
                )
                OutlinedTextField(
                    value = valueStr, onValueChange = { valueStr = it },
                    label = { Text(t(tr,"category_value")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp)
                )
                Text(t(tr,"mode"), fontSize = 12.sp, color = th.fg.copy(0.6f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        "percentage" to t(tr,"percentage"),
                        "monthly"    to t(tr,"monthly"),
                        "annual"     to t(tr,"annual")
                    ).forEach { (m, lbl) ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = mode == m,
                                onClick  = { mode = m },
                                colors   = RadioButtonDefaults.colors(selectedColor = th.accent)
                            )
                            Text(lbl, fontSize = 11.sp, color = th.fg)
                        }
                    }
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
                onConfirm(name, valueStr.toDoubleOrNull() ?: 0.0, mode, overdraft)
            }) { Text(t(tr,"ok"), color = th.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton  = {
            TextButton(onClick = { onConfirm("", 0.0, "", false) }) { Text(t(tr,"cancel"), color = th.fg.copy(0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}

@Composable
fun SpendDialog(
    catName: String,
    maxAmt: Double,
    sym: String,
    tr: Map<String, String>,
    th: AppThemeData,
    onConfirm: (Double, String, String) -> Unit
) {
    var amountStr by remember { mutableStateOf("") }
    var desc      by remember { mutableStateOf("") }
    var dateStr   by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onConfirm(0.0, "", "") },
        title  = { Text("${t(tr,"spend_from")}: $catName", color = th.fg, fontWeight = FontWeight.Bold) },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Info chip showing the max available amount
                Box(
                    Modifier
                        .background(th.accent.copy(0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        "${t(tr,"max")}: ${"%.2f".format(maxAmt)} $sym",
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
                OutlinedTextField(
                    value = dateStr, onValueChange = { dateStr = it },
                    label = { Text(t(tr,"enter_date")) },
                    singleLine = true, colors = otfColors(th),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton  = {
            TextButton(onClick = {
                val date = if (dateStr.isBlank() || parseDate(dateStr) == null) todayStr() else dateStr
                onConfirm(amountStr.toDoubleOrNull() ?: 0.0, desc, date)
            }) { Text(t(tr,"ok"), color = th.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton  = {
            TextButton(onClick = { onConfirm(0.0, "", "") }) { Text(t(tr,"cancel"), color = th.fg.copy(0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}

@Composable
fun EditExpenseDialog(
    expense: Expense,
    categories: List<String>,
    tr: Map<String, String>,
    th: AppThemeData,
    onConfirm: (String, Double, String, String) -> Unit
) {
    var category  by remember { mutableStateOf(expense.category) }
    var amountStr by remember { mutableStateOf(expense.amount.toString()) }
    var desc      by remember { mutableStateOf(expense.description) }
    var date      by remember { mutableStateOf(expense.date) }
    var expanded  by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onConfirm(expense.category, expense.amount, expense.description, expense.date) },
        title  = { Text(t(tr,"edit_expense"), color = th.fg, fontWeight = FontWeight.Bold) },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedTextField(
                        value = category, onValueChange = {}, label = { Text(t(tr,"category")) },
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
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text    = { Text(cat, color = th.fg) },
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
                OutlinedTextField(
                    value = date, onValueChange = { date = it }, label = { Text(t(tr,"date")) },
                    singleLine = true, colors = otfColors(th), shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton  = {
            TextButton(onClick = {
                onConfirm(category, amountStr.toDoubleOrNull() ?: expense.amount, desc, date.ifBlank { expense.date })
            }) { Text(t(tr,"ok"), color = th.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton  = {
            TextButton(onClick = {
                onConfirm(expense.category, expense.amount, expense.description, expense.date)
            }) { Text(t(tr,"cancel"), color = th.fg.copy(0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}

@Composable
fun EditIncomeDialog(
    income: Income,
    tr: Map<String, String>,
    th: AppThemeData,
    onConfirm: (String, Double, String) -> Unit
) {
    var source    by remember { mutableStateOf(income.source) }
    var amountStr by remember { mutableStateOf(income.amount.toString()) }
    var date      by remember { mutableStateOf(income.date) }
    AlertDialog(
        onDismissRequest = { onConfirm(income.source, income.amount, income.date) },
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
                OutlinedTextField(
                    value = date, onValueChange = { date = it }, label = { Text(t(tr,"date")) },
                    singleLine = true, colors = otfColors(th), shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton  = {
            TextButton(onClick = {
                onConfirm(source.ifBlank { "N/A" }, amountStr.toDoubleOrNull() ?: income.amount, date.ifBlank { income.date })
            }) { Text(t(tr,"ok"), color = th.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton  = {
            TextButton(onClick = { onConfirm(income.source, income.amount, income.date) }) {
                Text(t(tr,"cancel"), color = th.fg.copy(0.6f))
            }
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
            TextButton(onClick = { onResult(true)  }) {
                Text(t(tr,"yes"), color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton    = {
            TextButton(onClick = { onResult(false) }) { Text(t(tr,"no"), color = th.accent) }
        },
        containerColor   = th.cardBg,
        shape            = RoundedCornerShape(24.dp)
    )
}
