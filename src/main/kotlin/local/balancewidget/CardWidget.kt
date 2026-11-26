package local.balancewidget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class CardWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context,manager,it) }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context,manager,id)
    }
    override fun onDeleted(context: Context, ids: IntArray) {
        preferences(context).edit().apply {
            ids.forEach { remove("card_$it"); remove("revealed_$it") }
        }.apply()
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TOGGLE && intent.action != REFRESH) {
            super.onReceive(context,intent)
            return
        }
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1)
        val manager = AppWidgetManager.getInstance(context)
        if (manager.getAppWidgetInfo(id)?.provider != ComponentName(context,CardWidget::class.java)) return
        if (intent.action == TOGGLE) {
            if (!Widgets.privacyEnabled(context)) return
            val prefs = preferences(context)
            prefs.edit().putBoolean("revealed_$id",!prefs.getBoolean("revealed_$id",false)).apply()
            render(context,manager,id)
        } else {
            if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
            val result = goAsync()
            worker.execute {
                try {
                    Cards(context).use { it.scan(context) }
                } catch (failure: RuntimeException) {
                    android.util.Log.w("BankBalance","Card refresh failed",failure)
                } finally {
                    refresh(context)
                    result.finish()
                }
            }
        }
    }

    companion object {
        private const val TOGGLE = "local.balancewidget.CARD_TOGGLE"
        private const val REFRESH = "local.balancewidget.CARD_REFRESH"
        private val worker = Executors.newSingleThreadExecutor()
        private fun preferences(context: Context) = context.getSharedPreferences("card_widgets",Context.MODE_PRIVATE)
        fun bind(context: Context, id: Int, card: Long) {
            preferences(context).edit().putLong("card_$id",card).apply()
            render(context,AppWidgetManager.getInstance(context),id)
        }
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context,CardWidget::class.java)).forEach { render(context,manager,it) }
        }
        fun resetPrivacy(context: Context) {
            val prefs = preferences(context)
            prefs.edit().apply { prefs.all.keys.filter { it.startsWith("revealed_") }.forEach(::remove) }.apply()
            refresh(context)
        }
        private fun broadcast(context: Context, id: Int, action: String) = PendingIntent.getBroadcast(context,id,
            Intent(context,CardWidget::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        @Synchronized private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val palette = Palette.current(context)
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,120)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,110)
            val compact = width < 150
            val pad = if (height < 110) 6 else if (height < 145) 8 else if (width >= 235) 16 else 12
            val pixels = (pad * context.resources.displayMetrics.density).toInt()
            val view = RemoteViews(context.packageName,R.layout.card_widget)
            val surfaces = intArrayOf(R.drawable.tile_palette_0,R.drawable.tile_palette_1,R.drawable.tile_palette_2,
                R.drawable.tile_palette_3,R.drawable.tile_palette_4,R.drawable.tile_palette_5,
                R.drawable.tile_palette_6,R.drawable.tile_palette_7)
            view.setInt(R.id.card_tile,"setBackgroundResource",surfaces[palette.ordinal])
            view.setViewPadding(R.id.card_tile,pixels,pixels,pixels,pixels)
            view.setTextColor(R.id.card_title,palette.muted)
            view.setTextColor(R.id.card_due,palette.ink)
            view.setTextColor(R.id.card_source,palette.muted)
            for (label in intArrayOf(R.id.card_balance_label,R.id.card_bill_label,R.id.card_date_label)) {
                view.setTextColor(label,palette.muted)
            }
            val ruleColor = (palette.muted and 0x00FFFFFF) or 0x55000000
            view.setInt(R.id.card_header_rule,"setBackgroundColor",ruleColor)
            view.setInt(R.id.card_bill_rule,"setBackgroundColor",ruleColor)
            view.setViewVisibility(R.id.card_balance_label,if (height >= 160) View.VISIBLE else View.GONE)
            view.setInt(R.id.card_refresh,"setColorFilter",palette.ink)
            view.setOnClickPendingIntent(R.id.card_refresh,broadcast(context,id,REFRESH))
            Cards(context).use { store ->
                val card = store.get(preferences(context).getLong("card_$id",0))
                val edit = PendingIntent.getActivity(context,id,
                    Intent(context,CardSetup::class.java).setData(android.net.Uri.parse("balancewidget://card/" + (card?.id ?: 0L))).putExtra("card_id",card?.id ?: 0L),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                view.setOnClickPendingIntent(R.id.card_tile,edit)
                val privacy = card != null && Widgets.privacyEnabled(context)
                val masked = privacy && !preferences(context).getBoolean("revealed_$id",false)
                val value = when {
                    card == null -> "Set up"
                    masked -> PrivacyFace.current(context).symbol
                    card.outstanding == null -> "Set balance"
                    else -> BankSms.rupees(card.outstanding,compact,width < 235)
                }
                val click = if (privacy) broadcast(context,id,TOGGLE) else edit
                view.setOnClickPendingIntent(R.id.card_amount_area,click)
                view.setOnClickPendingIntent(R.id.card_amount,click)
                view.setOnClickPendingIntent(R.id.card_bill_area,click)
                view.setOnClickPendingIntent(R.id.card_bill_amount,click)
                view.setInt(R.id.card_amount_area,"setBackgroundResource",if (privacy && !masked) R.drawable.amount_inset else 0)
                val inset = if (privacy) 4 else 0
                val ipx = (inset * context.resources.displayMetrics.density).toInt()
                view.setViewPadding(R.id.card_amount_area,ipx,0,ipx,0)
                val headerText = if (card == null) "Card balance" else
                    "Card · " + card.label
                val header = SpannableString(headerText).apply {
                    setSpan(StyleSpan(Typeface.BOLD),0,4,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(palette.ink),0,4,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                view.setTextViewText(R.id.card_title,header)
                view.setInt(R.id.card_title,"setMaxLines",if (height >= 160) 2 else 1)
                Widgets.renderAmount(context,view,R.id.card_amount,value,
                    if (height >= 170 && width >= 235) 42f else if (height < 110) 18f else if (height < 145) 22f else 30f,
                    width - 2 * pad - 2 * inset,if (masked) palette.muted else palette.ink,
                    if (privacy) context.getString(if (masked) R.string.show_amounts else R.string.hide_amounts) else null)
                val due = card?.due
                val billAmount = card?.total
                val date = due?.format(DateTimeFormatter.ofPattern("d MMM",Locale.ENGLISH))
                val billText = when {
                    billAmount == null -> "—"
                    masked -> PrivacyFace.current(context).symbol
                    else -> BankSms.rupees(billAmount,compact,width < 235)
                }
                val slot = (width - 2 * pad - 21) / 2
                Widgets.renderAmount(context,view,R.id.card_bill_amount,billText,
                    if (height >= 175) 17f else 14f,slot,palette.ink,
                    if (privacy) context.getString(if (masked) R.string.show_amounts else R.string.hide_amounts) else null)
                view.setTextViewText(R.id.card_due,date ?: "—")
                view.setContentDescription(R.id.card_due,if (date == null) "No statement due date" else "Payment due " +
                    due?.format(DateTimeFormatter.ofPattern("d MMM uuuu",Locale.ENGLISH)))
                view.setTextViewTextSize(R.id.card_due,TypedValue.COMPLEX_UNIT_SP,if (compact) 10f else if (height >= 175) 16f else 13f)
                view.setViewVisibility(R.id.card_statement_area,if (height < 110) View.GONE else View.VISIBLE)
                view.setViewVisibility(R.id.card_source,if (height < 110) View.VISIBLE else View.GONE)
                view.setTextViewText(R.id.card_source,"Due " + (date ?: "—"))
            }
            manager.updateAppWidget(id,view)
        }
    }
}
