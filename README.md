<img src="docs/icon.png" width="96" alt="Flowi icon">

# Flowi

Personal budget planner for Android. Income is split across categories by rule, every expense is tracked against its category, and all data stays on the device.

**Stack:** Kotlin · Jetpack Compose · Material 3 · no third-party libraries · Android 7.0+ (API 24)
**Version:** 0.9.7

## Functions

**Budget**
- Total money, income, expenses and balance at a glance
- Categories with three allocation modes: percent of income, fixed monthly amount, fixed annual amount
- Percent categories share what is left after fixed ones and are capped at 100% in total
- Category types: spending or savings/fund
- Optional overdraft per category, with warnings when a category runs low
- Unallocated bucket for money not assigned to any category
- Long-press drag to reorder categories

**Income and expenses**
- Add, edit and delete income (source, amount, date) and expenses (category, amount, description, date)
- Split a single expense across several categories
- Date picker with a "today" shortcut

**Stats and history**
- Charts by category, by day and by month
- Period filter: all time, this month, this year
- Configurable month reset day (the budget month can start on any day)
- Full history of income and expenses with filtering

**AI analysis (optional)**
- Sends a summary of the budget to Gemini, ChatGPT or Claude and shows the analysis in the app language
- Uses your own API key, stored only on the phone; nothing is sent without confirmation

**Backup**
- Export all data to a JSON file and import it on another device

**Personalisation**
- Languages: English, Ukrainian, German, Arabic, Chinese
- Currencies: Dollar, Euro, Hryvnia, Pound, Zloty, Koruna, Rupiah
- Themes: Matcha, Midnight, Sakura, Cruel Angel, Mandarin (the app icon follows the theme)

## Build

Open the folder in Android Studio or IntelliJ IDEA, wait for Gradle sync and run the `app` configuration. From the command line:

```
gradlew assembleDebug
```

## Structure

```
app/src/main/java/com/example/budget_planner/
  MainActivity.kt   entry point, storage, navigation
  Models.kt         data model and budget math
  Screens.kt        Budget, Stats, History, Settings
  Components.kt     charts, cards, shared UI
  Dialogs.kt        add/edit dialogs with validation
  Ai.kt             AI analysis providers
  Strings.kt        translations
  Theme.kt          themes
```
