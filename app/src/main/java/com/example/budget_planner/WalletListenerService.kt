package com.example.budget_planner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

// ── Google Wallet auto-expenses (v0.9) ───────────────────────────────────────
// A NotificationListenerService sees the Google Wallet "tap to pay" pop-up
// (e.g. "PARKOVANI BRATISLAVA / €0.50 with Revolut Mastercard ••8344"),
// extracts the amount + merchant and records an expense automatically, then
// posts its own confirmation notification so the user stays in control.
//
// If the app UI is alive, the expense goes straight into the Compose state via
// WalletBridge (and is auto-saved). Otherwise it is queued in SharedPreferences
// and merged on the next launch by MainActivity.

object WalletBridge {
    /** Set by MainActivity while the UI exists; returns true when consumed. */
    @Volatile var liveHandler: ((amount: Double, merchant: String) -> Boolean)? = null
}

const val kAutoChannelId = "flowi_auto_expense"

fun ensureAutoChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(kAutoChannelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    kAutoChannelId, "Flowi auto-expenses",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }
}

class WalletListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in kWalletPackages) return

        val prefs = getSharedPreferences("jb_v3", Context.MODE_PRIVATE)
        val root  = runCatching { JSONObject(prefs.getString("data", null) ?: return) }
            .getOrNull() ?: return
        if (!root.optBoolean("auto_wallet_enabled", false)) return

        val extras = sbn.notification.extras
        val title  = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text   = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()

        val amount   = parseWalletAmount(text) ?: parseWalletAmount(title) ?: return
        val merchant = title.trim().ifBlank { "Google Wallet" }

        // De-duplicate: Wallet often re-posts/updates the same notification
        val sig = "$merchant|$amount"
        val now = System.currentTimeMillis()
        if (prefs.getString("auto_sig", "") == sig &&
            now - prefs.getLong("auto_sig_time", 0L) < 30_000L
        ) return
        prefs.edit().putString("auto_sig", sig).putLong("auto_sig_time", now).apply()

        // Record: live into the running app, or queue for the next launch
        val live = WalletBridge.liveHandler
        if (live != null) {
            Handler(Looper.getMainLooper()).post { runCatching { live(amount, merchant) } }
        } else {
            val arr = runCatching { JSONArray(prefs.getString("auto_pending", "[]")) }
                .getOrDefault(JSONArray())
            arr.put(
                JSONObject()
                    .put("amount", amount)
                    .put("description", merchant)
                    .put("date", todayStr())
            )
            prefs.edit().putString("auto_pending", arr.toString()).apply()
        }

        postConfirmation(root, merchant, amount)
    }

    /** "Expense added automatically: MERCHANT · 0.50 → Category" in the app language. */
    private fun postConfirmation(root: JSONObject, merchant: String, amount: Double) {
        val lang = root.optString("language", "en")
        val tr   = kT[lang] ?: kT.getValue("en")
        val rawCat = root.optString("auto_wallet_category", "__unallocated__")
        val exists = root.optJSONObject("categories")?.has(rawCat) == true
        val cat    = if (rawCat == "__unallocated__" || !exists) t(tr, "unallocated") else rawCat

        ensureAutoChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, kAutoChannelId)
            .setSmallIcon(R.drawable.flowi_icon)
            .setContentTitle(t(tr, "auto_expense_added"))
            .setContentText("$merchant · ${amount.money()} → $cat")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // Fails silently if the user denied POST_NOTIFICATIONS — expense is still saved.
        runCatching {
            (getSystemService(NotificationManager::class.java))
                ?.notify((System.currentTimeMillis() % 100_000).toInt(), notif)
        }
    }

    companion object {
        val kWalletPackages = setOf("com.google.android.apps.walletnfcrel")
    }
}

private val kCurrencyMarkers = listOf(
    "€", "$", "£", "₴", "Kč", "zł", "Rp",
    "EUR", "USD", "GBP", "UAH", "CZK", "PLN", "IDR"
)

/**
 * Pulls the paid amount out of a Wallet notification line like
 * "€0.50 with Revolut Mastercard ••8344" or "0,50 € s kartou…".
 * A number counts only if it has a decimal part or sits next to a currency
 * marker — this keeps card digits ("••8344") from being mistaken for money.
 */
fun parseWalletAmount(raw: String): Double? {
    if (raw.isBlank()) return null
    val s = raw.substringBefore(" with ")
    // Either digit groups joined by space/NBSP/dot/comma ("1 234", "1.234", "1,234")
    // or a plain digit run, optionally followed by a 1-2 digit decimal part.
    val rx = Regex("(?:\\d{1,3}(?:[ \\u00A0.,]\\d{3})+|\\d+)(?:[.,]\\d{1,2})?")
    for (m in rx.findAll(s)) {
        val numStr = m.value
        val before = s.substring((m.range.first - 4).coerceAtLeast(0), m.range.first)
        val afterEnd = (m.range.last + 5).coerceAtMost(s.length)
        val after  = s.substring((m.range.last + 1).coerceAtMost(s.length), afterEnd)
        val nearCurrency = kCurrencyMarkers.any { before.contains(it) || after.contains(it) }
        val hasSep       = numStr.contains('.') || numStr.contains(',')
        if (!nearCurrency && !hasSep) continue

        // The LAST . or , is the decimal separator only when 1-2 digits follow it;
        // otherwise every separator in the number is a thousands separator.
        val t = numStr.replace(" ", "").replace("\u00A0", "")
        val i = maxOf(t.lastIndexOf('.'), t.lastIndexOf(','))
        val normalized = when {
            i < 0 -> t
            t.length - i - 1 in 1..2 ->
                (t.substring(0, i).filter { it.isDigit() }.ifEmpty { "0" }) + "." + t.substring(i + 1)
            else -> t.filter { it.isDigit() }
        }
        val v = normalized.toDoubleOrNull()
        if (v != null && v > 0.0) return v
    }
    return null
}
