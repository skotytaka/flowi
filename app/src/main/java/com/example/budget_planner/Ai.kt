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

    /** Everything the model needs: month window, all-time totals, per-day breakdown. */
    fun buildDataSummary(state: AppState, sym: String): String {
        val sb = StringBuilder()
        val (mStart, mEnd) = budgetMonthRange(state.monthResetDay)
        fun catName(c: String) = if (c == "__unallocated__") "Unallocated" else c

        sb.appendLine("CURRENCY: $sym")
        sb.appendLine("TODAY: ${todayStr()}")
        sb.appendLine("CURRENT TOTAL MONEY: ${state.totalMoney.money()}")
        sb.appendLine()
        sb.appendLine("== ALL TIME ==")
        sb.appendLine("Total income: ${state.incomes.sumOf { it.amount }.money()}")
        sb.appendLine("Total expenses: ${state.expenses.sumOf { it.amount }.money()}")
        sb.appendLine("Categories (allocated now / spent all time):")
        state.orderedCategoryNames().forEach { name ->
            val c = state.categories[name] ?: return@forEach
            sb.appendLine("- $name: allocated ${c.allocated(state.totalMoney).money()}, spent ${c.spent.money()}")
        }
        val unalloc = computeUnallocated(state.categories, state.totalMoney)
        if (unalloc > 0) {
            val uSpent = state.categories["__unallocated__"]?.spent ?: 0.0
            sb.appendLine("- Unallocated: allocated ${unalloc.money()}, spent ${uSpent.money()}")
        }
        sb.appendLine()
        sb.appendLine("== CURRENT BUDGET MONTH ($mStart to $mEnd) ==")
        val mExp = state.expenses.filter { it.date >= mStart && it.date < mEnd }
        val mInc = state.incomes.filter { it.date >= mStart && it.date < mEnd }
        sb.appendLine("Month income total: ${mInc.sumOf { it.amount }.money()}")
        mInc.take(30).forEach { sb.appendLine("  + ${it.date} ${it.source}: ${it.amount.money()}") }
        sb.appendLine("Month expenses total: ${mExp.sumOf { it.amount }.money()}")
        sb.appendLine("Expenses by category this month:")
        mExp.groupBy { it.category }
            .map { (c, l) -> c to l.sumOf { e -> e.amount } }
            .sortedByDescending { it.second }
            .forEach { (c, v) -> sb.appendLine("  - ${catName(c)}: ${v.money()}") }
        sb.appendLine("Expenses by day this month:")
        mExp.groupBy { it.date }.toSortedMap()
            .forEach { (d, l) -> sb.appendLine("  $d: ${l.sumOf { e -> e.amount }.money()}") }
        sb.appendLine()
        sb.appendLine("== RECENT EXPENSES (newest first, up to 40) ==")
        state.expenses.sortedByDescending { it.date }.take(40).forEach {
            sb.appendLine("${it.date} | ${catName(it.category)} | ${it.description.ifBlank { "-" }} | ${it.amount.money()}")
        }
        return sb.toString().take(9000)
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
            if (code !in 200..299) throw RuntimeException("HTTP $code ${text.take(300)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
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
            .onFailure { error = "${t(tr, "ai_error")}\n${it.message ?: ""}" }
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
