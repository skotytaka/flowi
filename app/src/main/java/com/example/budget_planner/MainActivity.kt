@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.budget_planner

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// ── Main Activity ─────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jb_v3", Context.MODE_PRIVATE)
        setContent {
            val state = remember {
                prefs.getString("data", null)
                    ?.let { runCatching { AppState.fromJson(it) }.getOrNull() }
                    ?: AppState()
            }

            // Debounced auto-save: any snapshot state change re-serializes, but we
            // wait 300ms of quiet before writing, so rapid edits cost one write.
            LaunchedEffect(Unit) {
                snapshotFlow { state.toJson() }
                    .collectLatest { json ->
                        delay(300)
                        prefs.edit().putString("data", json).apply()
                    }
            }

            // Animated theme: switching themes cross-fades every color in the app
            val base = kThemes[state.themeName] ?: kThemes.getValue("black")
            val th = AppThemeData(
                bg     = animateColorAsState(base.bg,     tween(350), label = "thBg").value,
                fg     = animateColorAsState(base.fg,     tween(350), label = "thFg").value,
                cardBg = animateColorAsState(base.cardBg, tween(350), label = "thCard").value,
                accent = animateColorAsState(base.accent, tween(350), label = "thAccent").value
            )
            val tr = kT[state.language] ?: kT.getValue("en")

            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(
                    background = th.bg, surface = th.cardBg,
                    primary = th.accent, onBackground = th.fg, onSurface = th.fg
                )
            ) { FlowiApp(state, th, tr) }
        }
    }
}

// ── Root app: bottom navigation + quick-add FAB + animated screen switching ──

@Composable
fun FlowiApp(state: AppState, th: AppThemeData, tr: Map<String, String>) {
    var tab          by remember { mutableIntStateOf(0) }
    var fabOpen      by remember { mutableStateOf(false) }
    var quickIncome  by remember { mutableStateOf(false) }
    var quickExpense by remember { mutableStateOf(false) }
    val sym = kCurrencies[state.currency] ?: "$"

    LaunchedEffect(tab) { fabOpen = false }

    Scaffold(
        containerColor = th.bg,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            Modifier.size(30.dp).background(th.accent, CircleShape),
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
        },
        bottomBar = {
            NavigationBar(containerColor = th.cardBg, tonalElevation = 0.dp) {
                val labels = listOf(t(tr,"budget"), t(tr,"stats"), t(tr,"history"), t(tr,"settings"))
                labels.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick  = { tab = i },
                        icon = {
                            when (i) {
                                0    -> Icon(Icons.Default.Home, null, Modifier.size(22.dp))
                                1    -> BarChartIcon()
                                2    -> ListLinesIcon()
                                else -> Icon(Icons.Default.Settings, null, Modifier.size(22.dp))
                            }
                        },
                        label  = { Text(label, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor   = th.accent,
                            selectedTextColor   = th.accent,
                            unselectedIconColor = th.fg.copy(alpha = 0.4f),
                            unselectedTextColor = th.fg.copy(alpha = 0.4f),
                            indicatorColor      = th.accent.copy(alpha = 0.14f)
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            // Quick-add: the + expands into "Add Expense" / "Add Income"
            if (tab != 3) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AnimatedVisibility(
                        visible = fabOpen,
                        enter   = fadeIn(tween(160)) + slideInVertically(tween(160)) { it / 3 },
                        exit    = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 3 }
                    ) {
                        ExtendedFloatingActionButton(
                            text           = { Text(t(tr,"add_income"), fontSize = 13.sp) },
                            icon           = { Text("↑", color = kGood, fontWeight = FontWeight.Bold) },
                            onClick        = { fabOpen = false; quickIncome = true },
                            containerColor = th.cardBg,
                            contentColor   = th.fg
                        )
                    }
                    AnimatedVisibility(
                        visible = fabOpen,
                        enter   = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 3 },
                        exit    = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 3 }
                    ) {
                        ExtendedFloatingActionButton(
                            text           = { Text(t(tr,"add_expense"), fontSize = 13.sp) },
                            icon           = { Text("↓", color = kBad, fontWeight = FontWeight.Bold) },
                            onClick        = { fabOpen = false; quickExpense = true },
                            containerColor = th.cardBg,
                            contentColor   = th.fg
                        )
                    }
                    FloatingActionButton(
                        onClick        = { fabOpen = !fabOpen },
                        containerColor = th.accent,
                        contentColor   = th.bg
                    ) {
                        val rot by animateFloatAsState(if (fabOpen) 45f else 0f, tween(220), label = "fabRot")
                        Icon(Icons.Default.Add, null, Modifier.rotate(rot))
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            // Screens slide toward the direction of navigation
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(260)) { dir * it / 10 } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(200)) { -dir * it / 10 } + fadeOut(tween(160)))
                },
                label = "screen"
            ) { screen ->
                when (screen) {
                    0    -> HomeScreen(state, th, tr, sym)
                    1    -> StatsScreen(state, th, tr, sym)
                    2    -> HistoryScreen(state, th, tr, sym)
                    else -> SettingsScreen(state, th, tr)
                }
            }
        }
    }

    // Quick-add dialogs (opened from the FAB, usable from any screen)
    if (quickIncome)  IncomeDialog(state, tr, th) { quickIncome = false }
    if (quickExpense) SpendDialog(null, state, tr, th, sym) { quickExpense = false }
}

// ── Custom nav icons (drawn, so no extra icon dependency is needed) ──────────

@Composable
fun BarChartIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val w  = size.width / 5
        val cr = CornerRadius(w / 2, w / 2)
        drawRoundRect(color, Offset(0f,     size.height * 0.45f), Size(w, size.height * 0.55f), cr)
        drawRoundRect(color, Offset(w * 2,  size.height * 0.20f), Size(w, size.height * 0.80f), cr)
        drawRoundRect(color, Offset(w * 4,  0f),                  Size(w, size.height),          cr)
    }
}

@Composable
fun ListLinesIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val h  = size.height / 6
        val cr = CornerRadius(h / 2, h / 2)
        drawRoundRect(color, Offset(0f, size.height * 0.08f), Size(size.width,         h), cr)
        drawRoundRect(color, Offset(0f, size.height * 0.42f), Size(size.width * 0.78f, h), cr)
        drawRoundRect(color, Offset(0f, size.height * 0.76f), Size(size.width * 0.56f, h), cr)
    }
}
