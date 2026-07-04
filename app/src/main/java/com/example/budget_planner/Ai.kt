@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.budget_planner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

// ── AI analysis (v0.9) ───────────────────────────────────────────────────────
// The Stats screen button builds a plain-text summary of the user's budget,
// prepends the localized prompt (so the answer arrives in the app language),
// and sends it to the provider chosen in Settings with the user's own API key.

val kAiProviders = linkedMapOf(
    "gemini" to "Gemini",
    "openai" to "ChatGPT",
    "claude" to "Claude"
)

object Ai {

    /**
     * Everything the model needs for a deep analysis (v0.9.1): today's date with
     * weekday, the budget-month rule and how far into it we are, each category's
     * budget rule, and dated entries with weekdays so day-of-week patterns and
     * spending pace can be reasoned about.
     */
    fun buildDataSummary(state: AppState, sym: String): String {
        val sb = StringBuilder()
        val (mStart, mEnd) = budgetMonthRange(state.monthResetDay)
        fun catName(c: String) = if (c == "__unallocated__") "Unallocated" else c
        val wdFmt = SimpleDateFormat("EEE", Locale.US)
        fun wd(date: String) = parseDate(date)?.let { wdFmt.format(it) } ?: "?"
        val today = todayStr()
        val dayMs = 86_400_000L
        val startMs = parseDate(mStart)?.time ?: 0L
        val elapsedDays = (((parseDate(today)?.time ?: startMs) - startMs) / dayMs + 1).coerceAtLeast(1)
        val totalDays = ((((parseDate(mEnd)?.time ?: startMs)) - startMs) / dayMs).coerceAtLeast(1)

        val pctBase = percentBase(state.categories, state.totalMoney)
        fun kindOf(c: String) = state.categories[c]?.kind ?: "spending"

        sb.appendLine("CURRENCY: $sym")
        sb.appendLine("TODAY: $today (${SimpleDateFormat("EEEE", Locale.US).format(parseDate(today)!!)})")
        sb.appendLine("BUDGET RULE: the user's budget month restarts on day ${state.monthResetDay} of every calendar month (allocations and \"this month\" totals reset then).")
        sb.appendLine("ALLOCATION RULE: fixed monthly/annual categories are funded first; percentage categories then share the remaining money (${pctBase.money()}) in their ratio.")
        sb.appendLine("CATEGORY TYPES: SAVINGS categories (emergency funds, investing, etc.) are money the user deliberately sets aside — NOT consumption. Treat them as savings contributions, never as overspending.")
        sb.appendLine("CURRENT BUDGET MONTH: $mStart (inclusive) .. $mEnd (exclusive) — today is day $elapsedDays of $totalDays (${(elapsedDays * 100 / totalDays)}% of the period elapsed).")
        sb.appendLine("CURRENT TOTAL MONEY: ${state.totalMoney.money()}")
        sb.appendLine()
        sb.appendLine("== CATEGORIES AND THEIR BUDGET RULES ==")
        state.orderedCategoryNames().forEach { name ->
            val c = state.categories[name] ?: return@forEach
            val rule = when (c.mode) {
                "percentage" -> "${c.value}% of the money left after fixed categories"
                "monthly"    -> "fixed ${c.value.money()} per month"
                "annual"     -> "${c.value.money()} per year (= ${(c.value / 12.0).money()}/month)"
                else          -> c.mode
            }
            val type = if (c.kind == "saving") "SAVINGS" else "SPENDING"
            sb.appendLine("- $name [$rule] [type: $type]: allocated now ${c.allocated(state.totalMoney, pctBase).money()}, spent all time ${c.spent.money()}")
        }
        val unalloc = computeUnallocated(state.categories, state.totalMoney)
        if (unalloc > 0) {
            val uSpent = state.categories["__unallocated__"]?.spent ?: 0.0
            sb.appendLine("- Unallocated [everything not assigned to a category]: allocated ${unalloc.money()}, spent ${uSpent.money()}")
        }
        sb.appendLine()
        sb.appendLine("== ALL TIME ==")
        sb.appendLine("Total income: ${state.incomes.sumOf { it.amount }.money()}")
        val allSpend = state.expenses.filter { kindOf(it.category) == "spending" }.sumOf { it.amount }
        val allSave  = state.expenses.filter { kindOf(it.category) == "saving" }.sumOf { it.amount }
        sb.appendLine("Total real spending: ${allSpend.money()}")
        sb.appendLine("Total set aside into savings/funds: ${allSave.money()}")
        sb.appendLine("Recent incomes (date, weekday, source, amount):")
        state.incomes.sortedByDescending { it.date }.take(15).forEach {
            sb.appendLine("  + ${it.date} (${wd(it.date)}) ${it.source}: ${it.amount.money()}")
        }
        sb.appendLine()
        sb.appendLine("== CURRENT BUDGET MONTH ==")
        val mExp = state.expenses.filter { it.date >= mStart && it.date < mEnd }
        val mInc = state.incomes.filter { it.date >= mStart && it.date < mEnd }
        sb.appendLine("Month income total: ${mInc.sumOf { it.amount }.money()}")
        mInc.take(30).forEach { sb.appendLine("  + ${it.date} (${wd(it.date)}) ${it.source}: ${it.amount.money()}") }
        val mSpend = mExp.filter { kindOf(it.category) == "spending" }.sumOf { it.amount }
        val mSave  = mExp.filter { kindOf(it.category) == "saving" }.sumOf { it.amount }
        sb.appendLine("Month real spending so far: ${mSpend.money()} after $elapsedDays of $totalDays days")
        sb.appendLine("Month savings contributions so far: ${mSave.money()}")
        sb.appendLine("Expenses by category this month:")
        mExp.groupBy { it.category }
            .map { (c, l) -> c to l.sumOf { e -> e.amount } }
            .sortedByDescending { it.second }
            .forEach { (c, v) ->
                val tag = if (kindOf(c) == "saving") " (savings)" else ""
                sb.appendLine("  - ${catName(c)}$tag: ${v.money()}")
            }
        sb.appendLine("Expenses by day this month (date, weekday, total):")
        mExp.groupBy { it.date }.toSortedMap()
            .forEach { (d, l) -> sb.appendLine("  $d (${wd(d)}): ${l.sumOf { e -> e.amount }.money()}") }
        sb.appendLine()
        sb.appendLine("== RECENT EXPENSES (newest first, up to 40; date | weekday | category | description | amount) ==")
        state.expenses.sortedByDescending { it.date }.take(40).forEach {
            sb.appendLine("${it.date} | ${wd(it.date)} | ${catName(it.category)} | ${it.description.ifBlank { "-" }} | ${it.amount.money()}")
        }
        return sb.toString().take(11000)
    }

    suspend fun analyze(provider: String, apiKey: String, prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (provider) {
                    "openai" -> {
                        val body = JSONObject()
                            .put("model", "gpt-4o-mini")
                            .put("messages", JSONArray().put(
                                JSONObject().put("role", "user").put("content", prompt)
                            ))
                        val resp = post(
                            "https://api.openai.com/v1/chat/completions",
                            mapOf("Authorization" to "Bearer $apiKey"),
                            body
                        )
                        JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content")
                    }
                    "claude" -> {
                        val body = JSONObject()
                            .put("model", "claude-haiku-4-5")
                            .put("max_tokens", 1500)
                            .put("messages", JSONArray().put(
                                JSONObject().put("role", "user").put("content", prompt)
                            ))
                        val resp = post(
                            "https://api.anthropic.com/v1/messages",
                            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
                            body
                        )
                        JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
                    }
                    else -> { // gemini (default)
                        val body = JSONObject().put("contents", JSONArray().put(
                            JSONObject().put("parts", JSONArray().put(
                                JSONObject().put("text", prompt)
                            ))
                        ))
                        val resp = post(
                            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
                            mapOf("x-goog-api-key" to apiKey),
                            body
                        )
                        JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts")
                            .getJSONObject(0).getString("text")
                    }
                }
            }
        }

    private fun post(urlStr: String, headers: Map<String, String>, body: JSONObject): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod  = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout    = 90_000
            conn.doOutput       = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw AiHttpException(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }
}

/** HTTP failure that keeps the status code + body so the UI can explain it. */
class AiHttpException(val code: Int, val body: String) :
    RuntimeException("HTTP $code ${body.take(280)}")

/**
 * v0.9.1: translate common failures into a human explanation key —
 * wrong API key, exhausted quota/credits, provider overload, no internet.
 */
fun aiErrorHintKey(e: Throwable): String? {
    if (e is AiHttpException) {
        val b = e.body.lowercase(Locale.US)
        return when {
            e.code == 401 || e.code == 403 ||
                b.contains("api_key_invalid") || b.contains("api key not valid") ||
                b.contains("incorrect api key") || b.contains("invalid x-api-key") ||
                b.contains("authentication") -> "ai_err_key"
            e.code == 429 || e.code == 402 ||
                b.contains("quota") || b.contains("rate limit") || b.contains("rate_limit") ||
                b.contains("credit balance") || b.contains("insufficient_quota") ||
                b.contains("billing") -> "ai_err_quota"
            e.code in 500..599 || b.contains("overloaded") -> "ai_err_busy"
            else -> null
        }
    }
    return if (e is java.io.IOException) "ai_err_net" else null
}

@Composable
fun AiAnalysisDialog(
    state: AppState,
    th: AppThemeData,
    tr: Map<String, String>,
    sym: String,
    onDismiss: () -> Unit
) {
    var result  by remember { mutableStateOf<String?>(null) }
    var error   by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val noKey = state.aiApiKey.isBlank()

    LaunchedEffect(attempt) {
        result = null; error = null
        if (noKey) { error = t(tr, "ai_no_key"); return@LaunchedEffect }
        val prompt = t(tr, "ai_prompt") + "\n\n" + Ai.buildDataSummary(state, sym)
        Ai.analyze(state.aiProvider, state.aiApiKey.trim(), prompt)
            .onSuccess { result = it.trim() }
            .onFailure { e ->
                // v0.9.1: explain common failures in plain words first,
                // then show the technical detail underneath.
                val hint = aiErrorHintKey(e)?.let { t(tr, it) }
                error = buildString {
                    if (hint != null) { append(hint); append("\n\n") }
                    append(t(tr, "ai_error"))
                    e.message?.let { append("\n").append(it.take(280)) }
                }
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("✨ ${t(tr, "ai_analysis")}", color = th.fg, fontWeight = FontWeight.Bold) },
        text = {
            Box(Modifier.heightIn(min = 120.dp, max = 420.dp)) {
                when {
                    result != null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(result!!, color = th.fg, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                    error != null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(error!!, color = kBad, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    else -> Column(
                        Modifier.fillMaxWidth().padding(top = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(color = th.accent)
                        Text(t(tr, "ai_loading"), color = th.fg.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (error != null && !noKey) {
                TextButton(onClick = { attempt++ }) {
                    Text(t(tr, "retry"), color = th.accent, fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(t(tr, "ok"), color = th.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t(tr, "cancel"), color = th.fg.copy(alpha = 0.6f)) }
        },
        containerColor = th.cardBg,
        shape          = RoundedCornerShape(24.dp)
    )
}
