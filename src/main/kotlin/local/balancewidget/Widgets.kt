package local.balancewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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

    override fun onDeleted(context: Context, ids: IntArray) {
        bindings(context).edit().apply { ids.forEach { remove(it.toString()) } }.apply()
        privacy(context).edit().apply { ids.forEach { remove(revealKey(it)) } }.apply()
    }

    companion object {
        private const val TOGGLE_AMOUNTS = "local.balancewidget.TOGGLE_AMOUNTS"
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
        private val metricValues = intArrayOf(R.id.metric_value_0, R.id.metric_value_1, R.id.metric_value_2)

        private fun renderAmount(context: Context, view: RemoteViews, id: Int, value: String,
            target: Float, availableDp: Int, color: Int) {
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
            view.setContentDescription(id, if (value == "••••") "Amounts hidden" else value)
        }

        private fun rupees(paise: Long, compact: Boolean): String {
            val amount = BigDecimal.valueOf(paise, 2)
            if (compact && amount.abs() >= BigDecimal(1000)) {
                val units = when {
                    amount.abs() >= BigDecimal(10000000) -> 7 to "Cr"
                    amount.abs() >= BigDecimal(100000) -> 5 to "L"
                    else -> 3 to "k"
                }
                return (if (paise < 0) "-" else "") + "₹" + amount.abs().movePointLeft(units.first)
                    .setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + units.second
            }
            return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
                minimumFractionDigits = if (paise % 100 == 0L) 0 else 2
            }.format(amount)
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
            view.setInt(R.id.privacy_button, "setColorFilter", palette.ink)
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

            view.setOnClickPendingIntent(R.id.tile, PendingIntent.getActivity(context, id,
                Intent(context, Setup::class.java).putExtra("account_id", bindings(context).getLong(id.toString(), 1L)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            view.setTextViewText(R.id.title, if (spending) "Spent today" else "Balance")
            val settings = privacy(context)
            val privacyEnabled = settings.getBoolean(MASK_AMOUNTS, false)
            val masked = privacyEnabled && !settings.getBoolean(revealKey(id), false)
            Ledger(context).use { ledger ->
                val account = ledger.account(bindings(context).getLong(id.toString(), 1L))
                if (account != null && privacyEnabled) {
                    val provider = if (spending) Spending::class.java else Balance::class.java
                    val intent = Intent(context, provider).setAction(TOGGLE_AMOUNTS)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    view.setViewVisibility(R.id.privacy_button, View.VISIBLE)
                    view.setImageViewResource(R.id.privacy_button, if (masked) R.drawable.eye_show else R.drawable.eye_hide)
                    view.setContentDescription(R.id.privacy_button,
                        context.getString(if (masked) R.string.show_amounts else R.string.hide_amounts))
                    view.setOnClickPendingIntent(R.id.privacy_button, PendingIntent.getBroadcast(context, id, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                } else {
                    view.setViewVisibility(R.id.privacy_button, View.GONE)
                }
                val title = account?.let {
                    if (narrow) Banks.name(it.sender).substringBefore(" ") else it.label
                }
                if (account == null) {
                    renderAmount(context, view, R.id.value, "Set up", 14f, width - 2 * margin, palette.ink)
                } else if (masked || !spending) {
                    val estimatedLabel = !masked && !account.reported && width / fontScale >= 155
                    view.setTextViewText(R.id.title, if (estimatedLabel) "$title · Est." else title)
                    val compact = narrow || width / fontScale < 145
                    val amount = if (masked) "••••" else
                        (if (account.reported || estimatedLabel) "" else "~") + rupees(account.balance, compact)
                    renderAmount(context, view, R.id.value, amount, amountSize, width - 2 * margin, palette.ink)
                } else {
                    val today = LocalDate.now()
                    val week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    val daily = ledger.spentSince(account.id, today)
                    view.setTextViewText(R.id.title, if (narrow) "Today" else if (columns) "$title · Spending" else "$title · Today")
                    if (columns || stacked) {
                        val weekly = ledger.spentSince(account.id, week)
                        val monthly = ledger.spentSince(account.id, today.withDayOfMonth(1))
                        if (columns) {
                            view.setViewVisibility(R.id.value_block, View.GONE)
                            view.setViewVisibility(R.id.metrics, View.VISIBLE)
                            val slot = (width - 2 * margin - 38) / 3
                            for ((index, amount) in listOf(daily, weekly, monthly).withIndex()) {
                                val target = when {
                                    height >= 145 -> if (index == 0) 27f else 23f
                                    else -> if (index == 0) 23f else 20f
                                }
                                view.setTextColor(metricLabels[index], palette.muted)
                                renderAmount(context, view, metricValues[index], rupees(amount, slot / fontScale < 90),
                                    target, slot, palette.ink)
                            }
                        } else {
                            view.setViewVisibility(R.id.detail, View.VISIBLE)
                            val compact = width / fontScale < 170
                            view.setTextColor(R.id.detail_label_0, palette.muted)
                            view.setTextColor(R.id.detail_label_1, palette.muted)
                            val detailSize = if (height >= 145) 15f else 13f
                            val detailWidth = (width - 2 * margin) * 3 / 5
                            renderAmount(context, view, R.id.detail_value_0, rupees(weekly, compact),
                                detailSize, detailWidth, palette.ink)
                            renderAmount(context, view, R.id.detail_value_1, rupees(monthly, compact),
                                detailSize, detailWidth, palette.ink)
                        }
                    }
                    if (!columns) {
                        renderAmount(context, view, R.id.value, rupees(daily, narrow || width / fontScale < 145),
                            if (stacked) minOf(amountSize, if (height >= 145) 30f else 24f) else minOf(amountSize, 33f), width - 2 * margin, palette.ink)
                    }
                }

            }
            manager.updateAppWidget(id, view)
        }
    }
}
