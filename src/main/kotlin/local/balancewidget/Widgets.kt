package local.balancewidget

import android.Manifest
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import kotlin.math.ceil
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

abstract class Widgets : AppWidgetProvider() {
    class Balance : Widgets()
    class Spending : Widgets()

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it, this is Spending) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, id, this is Spending)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == REFRESH_AMOUNTS) {
            refreshFromInbox(context, intent)
            return
        }
        if (intent.action != TOGGLE_AMOUNTS) {
            super.onReceive(context, intent)
            return
        }
        if (!privacyEnabled(context)) return
        val manager = AppWidgetManager.getInstance(context)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (manager.getAppWidgetInfo(id)?.provider != ComponentName(context, javaClass)) return
        val settings = privacy(context)
        val key = revealKey(id)
        settings.edit().putBoolean(key, !settings.getBoolean(key, false)).apply()
        render(context, manager, id, this is Spending)
    }

    private fun refreshFromInbox(context: Context, intent: Intent) {
        val manager = AppWidgetManager.getInstance(context)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (manager.getAppWidgetInfo(id)?.provider != ComponentName(context, javaClass)) return
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Open Bank Balance and allow inbox access to refresh.", Toast.LENGTH_LONG).show()
            return
        }
        val accountId = bindings(context).getLong(id.toString(), 1L)
        val pending = goAsync()
        refreshWorker.execute {
            try {
                Ledger(context).use { ledger ->
                    val account = ledger.account(accountId) ?: return@execute
                    val recovered = Inbox.refresh(context, ledger, account)
                    refreshLog(context).edit().putLong(account.id.toString(), System.currentTimeMillis()).apply()
                    refresh(context)
                    showRefreshResult(context, if (recovered == 0) "Checked recent SMS. No updates."
                        else "Recovered $recovered update(s) from SMS.")
                }
            } catch (failure: RuntimeException) {
                Log.w("BankBalance", "Inbox refresh failed", failure)
                showRefreshResult(context, "Could not refresh from SMS.")
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        bindings(context).edit().apply { ids.forEach { remove(it.toString()) } }.apply()
        privacy(context).edit().apply { ids.forEach { remove(revealKey(it)) } }.apply()
    }

    companion object {
        private const val TOGGLE_AMOUNTS = "local.balancewidget.TOGGLE_AMOUNTS"
        private const val REFRESH_AMOUNTS = "local.balancewidget.REFRESH_AMOUNTS"
        private val refreshWorker = Executors.newSingleThreadExecutor()

        private fun refreshLog(context: Context) =
            context.getSharedPreferences("account_refresh_log", Context.MODE_PRIVATE)

        internal fun lastRefresh(context: Context, accountId: Long): String? =
            refreshLog(context).getLong(accountId.toString(), 0L).takeIf { it > 0 }
                ?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }

        private fun showRefreshResult(context: Context, message: String) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        private const val MASK_AMOUNTS = "mask_amounts"

        private fun privacy(context: Context) = context.getSharedPreferences("widget_privacy", Context.MODE_PRIVATE)
        private fun revealKey(id: Int) = "revealed_$id"

        internal fun privacyEnabled(context: Context) = privacy(context).getBoolean(MASK_AMOUNTS, false)

        internal fun setPrivacyEnabled(context: Context, enabled: Boolean) {
            privacy(context).edit().clear().putBoolean(MASK_AMOUNTS, enabled).apply()
            refresh(context)
        }

        private fun bindings(context: Context) = context.getSharedPreferences("widget_accounts", Context.MODE_PRIVATE)

        internal fun bind(context: Context, widgetId: Int, accountId: Long) {
            val manager = AppWidgetManager.getInstance(context)
            val provider = manager.getAppWidgetInfo(widgetId)?.provider ?: return
            if (provider.packageName != context.packageName) return
            bindings(context).edit().putLong(widgetId.toString(), accountId).apply()
            render(context, manager, widgetId, provider.className == Spending::class.java.name)
        }

        internal fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            for (type in listOf(Balance::class.java, Spending::class.java)) {
                manager.getAppWidgetIds(ComponentName(context, type)).forEach {
                    render(context, manager, it, type == Spending::class.java)
                }
            }
        }

        private val backgrounds = intArrayOf(R.drawable.tile_palette_0, R.drawable.tile_palette_1, R.drawable.tile_palette_2, R.drawable.tile_palette_3, R.drawable.tile_palette_4, R.drawable.tile_palette_5, R.drawable.tile_palette_6, R.drawable.tile_palette_7)
        private val metricLabels = intArrayOf(R.id.metric_label_0, R.id.metric_label_1, R.id.metric_label_2)
        private val metricBoxes = intArrayOf(R.id.metric_box_0, R.id.metric_box_1, R.id.metric_box_2)
        private val metricValues = intArrayOf(R.id.metric_value_0, R.id.metric_value_1, R.id.metric_value_2)

        private fun renderAmount(context: Context, view: RemoteViews, id: Int, value: String,
            target: Float, availableDp: Int, color: Int, privacyAction: String? = null) {
            val display = context.resources.displayMetrics
            val width = (availableDp * display.density).toInt().coerceAtLeast(1)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = context.resources.getFont(R.font.plex_mono)
                this.color = color
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, target, display)
            }
            val lines = value.lines()
            val maxWidth = lines.maxOf { paint.measureText(it) }.coerceAtLeast(1f)
            if (maxWidth > width) paint.textSize *= width / maxWidth
            val lineWidth = lines.maxOf { paint.measureText(it) }
            val metrics = paint.fontMetrics
            val lineHeight = ceil(metrics.descent - metrics.ascent).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(ceil(lineWidth).toInt().coerceAtLeast(1),
                lineHeight * lines.size, Bitmap.Config.ARGB_8888)
            bitmap.density = display.densityDpi
            val canvas = Canvas(bitmap)
            lines.forEachIndexed { index, line ->
                canvas.drawText(line, 0f, index * lineHeight - metrics.ascent, paint)
            }
            view.setImageViewBitmap(id, bitmap)
            val description = if (PrivacyFace.entries.any { it.symbol == value }) "Amount hidden" else value
            view.setContentDescription(id, if (privacyAction == null) description else "$description. $privacyAction")
        }

        private fun rupees(paise: Long, compact: Boolean, wholeRupees: Boolean = false): String {
            val amount = BigDecimal.valueOf(paise, 2).let { if (wholeRupees) it.setScale(0, java.math.RoundingMode.DOWN) else it }
            if (compact && amount.abs() >= BigDecimal(1000)) {
                val units = when {
                    amount.abs() >= BigDecimal(10000000) -> 7 to "Cr"
                    amount.abs() >= BigDecimal(100000) -> 5 to "L"
                    else -> 3 to "k"
                }
                return (if (paise < 0) "-" else "") + "₹" + amount.abs().movePointLeft(units.first)
                    .setScale(if (wholeRupees) 0 else 1, if (wholeRupees) java.math.RoundingMode.DOWN else java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + units.second
            }
            return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
                minimumFractionDigits = if (wholeRupees || paise % 100 == 0L) 0 else 2
            }.format(amount)
        }

        private fun spendingHeader(label: String, detail: String?, palette: Palette): CharSequence {
            val text = SpannableString(if (detail == null) label else "$label · $detail")
            text.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(palette.ink), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return text
        }

        @Synchronized
        private fun render(context: Context, manager: AppWidgetManager, id: Int, spending: Boolean) {
            val view = RemoteViews(context.packageName, R.layout.widget)
            val palette = Palette.current(context)
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (spending) 120 else 55)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (spending) 115 else 55)
            val narrow = width < 105
            val shallow = height < 95
            val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
            val columns = spending && width >= 250 * fontScale && height >= 76
            val stacked = spending && !columns && height >= 106 * fontScale && width >= 105
            val amountSize = when {
                narrow -> if (height >= 95) 18f else 14f
                shallow -> if (width >= 235) 30f else 24f
                height >= 175 && width >= 290 -> 54f
                height >= 145 && width >= 200 -> 42f
                width >= 190 -> 34f
                else -> 27f
            }
            val margin = when {
                narrow -> 6
                shallow -> 12
                height >= 145 -> 16
                else -> 12
            }
            val px = (margin * context.resources.displayMetrics.density).toInt()
            view.setViewPadding(R.id.tile, px, px, px, px)
            view.setInt(R.id.tile, "setBackgroundResource", backgrounds[palette.ordinal])
            view.setInt(R.id.refresh_button, "setColorFilter", palette.ink)
            val ruleColor = (palette.muted and 0x00FFFFFF) or 0x55000000
            for (rule in intArrayOf(R.id.header_rule, R.id.period_rule, R.id.column_rule_1, R.id.column_rule_2)) {
                view.setInt(rule, "setBackgroundColor", ruleColor)
            }
            view.setViewVisibility(R.id.header_rule, if (height < 50) View.GONE else View.VISIBLE)
            view.setTextColor(R.id.title, palette.muted)
            view.setTextViewTextSize(R.id.title, TypedValue.COMPLEX_UNIT_SP, if (narrow) 10f else if (height >= 145) 14f else 12f)
            view.setViewVisibility(R.id.metrics, View.GONE)
            view.setViewVisibility(R.id.value_block, View.VISIBLE)
            view.setViewVisibility(R.id.detail, View.GONE)

            val editIntent = PendingIntent.getActivity(context, id,
                Intent(context, Setup::class.java).putExtra("account_id", bindings(context).getLong(id.toString(), 1L)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            view.setOnClickPendingIntent(R.id.tile, editIntent)
            val spendsLabel = context.getString(R.string.widget_spends)
            view.setTextViewText(R.id.title, if (spending) spendingHeader(spendsLabel, null, palette) else "Balance")
            val settings = privacy(context)
            val privacyEnabled = settings.getBoolean(MASK_AMOUNTS, false)
            val masked = privacyEnabled && !settings.getBoolean(revealKey(id), false)
            if (Build.VERSION.SDK_INT >= 31) {
                val buttonWidth = when {
                    narrow -> 34f
                    height >= 135 -> 48f
                    else -> 42f
                }
                val buttonHeight = when {
                    height < 56 -> 24f
                    height >= 135 -> 48f
                    else -> 32f
                }
                view.setViewLayoutWidth(R.id.refresh_button, buttonWidth, TypedValue.COMPLEX_UNIT_DIP)
                view.setViewLayoutHeight(R.id.refresh_button, buttonHeight, TypedValue.COMPLEX_UNIT_DIP)
            }
            Ledger(context).use { ledger ->
                val account = ledger.account(bindings(context).getLong(id.toString(), 1L))
                if (account != null) {
                    val provider = if (spending) Spending::class.java else Balance::class.java
                    val refreshIntent = Intent(context, provider).setAction(REFRESH_AMOUNTS)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    view.setViewVisibility(R.id.refresh_button, View.VISIBLE)
                    view.setOnClickPendingIntent(R.id.refresh_button, PendingIntent.getBroadcast(context, id,
                        refreshIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    lastRefresh(context, account.id)?.let {
                        view.setContentDescription(R.id.refresh_button, "Refresh from recent SMS. Last refresh: $it")
                    }
                }
                val privacyAction = if (account != null && privacyEnabled) {
                    context.getString(if (masked) R.string.show_amounts else R.string.hide_amounts)
                } else null
                val amountClick = if (privacyAction == null) editIntent else {
                    val provider = if (spending) Spending::class.java else Balance::class.java
                    val intent = Intent(context, provider).setAction(TOGGLE_AMOUNTS)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    PendingIntent.getBroadcast(context, id, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                }
                val surfaces = metricBoxes + intArrayOf(R.id.value_block, R.id.detail)
                val amounts = metricValues + intArrayOf(R.id.value, R.id.detail_value_0, R.id.detail_value_1)
                (surfaces + amounts).forEach { view.setOnClickPendingIntent(it, amountClick) }
                val amountBackground = if (privacyAction != null && !masked) R.drawable.amount_inset else 0
                val inset = if (privacyAction == null) 0 else (4 * context.resources.displayMetrics.density).toInt()
                for (surface in surfaces) {
                    view.setInt(surface, "setBackgroundResource", amountBackground)
                    view.setViewPadding(surface, inset, 0, inset, 0)
                }
                val insetWidth = if (privacyAction != null) 8 else 0
                val amountInk = if (masked) palette.muted else palette.ink
                val hiddenFace = if (masked) PrivacyFace.current(context).symbol else null
                fun amountText(paise: Long, compact: Boolean): String =
                    hiddenFace ?: rupees(paise, compact)
                val title = account?.let {
                    if (narrow) Banks.name(it.sender).substringBefore(" ") else it.label
                }
                val shortBank = account?.let {
                    Banks.name(it.sender).removeSuffix(" Bank").substringBefore(' ') + " " + it.suffix
                }
                if (account == null) {
                    renderAmount(context, view, R.id.value, "Set up", 14f, width - 2 * margin, palette.ink)
                } else if (!spending) {
                    val estimatedLabel = !masked && !account.reported && width / fontScale >= 155
                    view.setTextViewText(R.id.title, when {
                        estimatedLabel -> "$title · Est."
                        else -> title
                    })
                    val compact = narrow || width / fontScale < 145
                    val amount = hiddenFace ?:
                        (if (account.reported || estimatedLabel) "" else "~") + rupees(account.balance, compact, wholeRupees = width < 235)
                    renderAmount(context, view, R.id.value, amount, amountSize, width - 2 * margin - insetWidth, amountInk, privacyAction)
                } else {
                    val today = LocalDate.now()
                    val week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    val daily = if (masked) 0L else ledger.spentSince(account.id, today)
                    view.setTextViewText(R.id.title, when {
                        narrow -> spendingHeader(spendsLabel, "Today", palette)
                        columns -> spendingHeader(spendsLabel, shortBank, palette)
                        else -> spendingHeader(spendsLabel, "$shortBank · Today", palette)
                    })
                    if (columns || stacked) {
                        val weekly = if (masked) 0L else ledger.spentSince(account.id, week)
                        val monthly = if (masked) 0L else ledger.spentSince(account.id, today.withDayOfMonth(1))
                        if (columns) {
                            view.setViewVisibility(R.id.value_block, View.GONE)
                            view.setViewVisibility(R.id.metrics, View.VISIBLE)
                            val slot = (width - 2 * margin - 38) / 3 - insetWidth
                            for ((index, amount) in listOf(daily, weekly, monthly).withIndex()) {
                                val target = when {
                                    height >= 145 -> if (index == 0) 27f else 23f
                                    else -> if (index == 0) 23f else 20f
                                }
                                view.setTextColor(metricLabels[index], palette.muted)
                                renderAmount(context, view, metricValues[index], amountText(amount, slot / fontScale < 90),
                                    target, slot, amountInk, privacyAction)
                            }
                        } else {
                            view.setViewVisibility(R.id.detail, View.VISIBLE)
                            val compact = width / fontScale < 170
                            view.setTextColor(R.id.detail_label_0, palette.muted)
                            view.setTextColor(R.id.detail_label_1, palette.muted)
                            val detailSize = if (height >= 145) 15f else 13f
                            val detailWidth = (width - 2 * margin) * 3 / 5
                            renderAmount(context, view, R.id.detail_value_0, amountText(weekly, compact),
                                detailSize, detailWidth, amountInk, privacyAction)
                            renderAmount(context, view, R.id.detail_value_1, amountText(monthly, compact),
                                detailSize, detailWidth, amountInk, privacyAction)
                        }
                    }
                    if (!columns) {
                        renderAmount(context, view, R.id.value, amountText(daily, narrow || width / fontScale < 145),
                            if (stacked) minOf(amountSize, if (height >= 145) 30f else 24f) else minOf(amountSize, 33f), width - 2 * margin - insetWidth, amountInk, privacyAction)
                    }
                }

            }
            manager.updateAppWidget(id, view)
        }
    }
}
