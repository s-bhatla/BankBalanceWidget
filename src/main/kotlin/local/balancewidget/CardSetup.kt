package local.balancewidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.View
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.widget.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.concurrent.thread

class CardSetup : Activity() {
    private lateinit var form: LinearLayout
    private var widget = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(state: Bundle?) {
        val palette = Palette.current(this)
        setTheme(if (palette.dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        widget = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widget != AppWidgetManager.INVALID_APPWIDGET_ID) setResult(RESULT_CANCELED)
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val gap = (24 * resources.displayMetrics.density).toInt()
            setPadding(gap,gap,gap,gap)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.surface)
            addView(form)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
                insets
            }
        })
        show()
        val id = intent.getLongExtra("card_id",0)
        if (id != 0L) Cards(this).use { it.get(id) }?.let(::edit)
    }

    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        intent = next
        widget = next.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        show()
        val id = next.getLongExtra("card_id",0L)
        if (id != 0L) Cards(this).use { it.get(id) }?.let(::edit)
    }

    private fun show() {
        form.removeAllViews()
        val palette = Palette.current(this)
        form.addView(TextView(this).apply { text = "Credit cards"; textSize = 34f; setTypeface(null,Typeface.BOLD); setTextColor(palette.ink) })
        note("Track purchases, refunds and payments from your card alerts.",form,8)
        section("Cards")
        val cards = Cards(this).use { it.all() }
        if (cards.isEmpty()) note("Scan recent SMS to find your cards. Then set the current outstanding from your bank app.",form,0)
        for (card in cards) {
            button(card.label + if (card.outstanding == null) " · Set balance" else " · Tracking") {
                if (widget != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    CardWidget.bind(this,widget,card.id)
                    setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widget))
                    finish()
                } else edit(card)
            }
        }
        section("Bank alerts")
        button("Scan recent SMS") {
            if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) scan()
            else AlertDialog.Builder(this).setTitle("Read recent card alerts?")
                .setMessage("Checks up to 500 recent inbox messages on this device for card statement amounts and payment due dates. Saves card endings and bill details locally.")
                .setNegativeButton("Cancel",null).setPositiveButton("Continue") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.READ_SMS),1)
                }.show()
        }
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            button("Allow new card alerts") {
                AlertDialog.Builder(this).setTitle("Use incoming SMS?")
                    .setMessage("Checks incoming SMS in the background for card bills. Processing stays on your phone.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Continue") { _, _ ->
                        requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS),2)
                    }.show()
            }
        }
        form.addView(TextView(this).apply {
            text = "Track ongoing purchases, refunds and payments. Tap a card to set its current outstanding from your bank app. Bill amount and due date stay separate."
            setTextColor(palette.muted)
            textSize = 14f
        })
    }

    private fun scan() {
        val progress = AlertDialog.Builder(this).setMessage("Checking card statements…").setCancelable(false).show()
        thread {
            val result = runCatching { Cards(this).use { it.scan(this) } }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                CardWidget.refresh(this)
                show()
                Toast.makeText(this,if (result.isSuccess) "Checked recent card alerts" else "Could not read SMS",Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if (code == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) scan() else show()
    }

    private fun edit(card: Cards.Card) {
        val palette = Palette.current(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24),dp(8),dp(24),dp(16))
        }
        fun field(label: String, amount: Long?, large: Boolean): EditText {
            body.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(palette.muted)
                setPadding(0,dp(if (large) 8 else 24),0,dp(4))
            })
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                typeface = resources.getFont(R.font.plex_mono)
                textSize = if (large) 24f else 18f
                setTextColor(palette.ink)
                setHintTextColor(palette.muted)
                backgroundTintList = ColorStateList.valueOf(palette.muted)
                hint = "₹0"
                setSingleLine(true)
                minHeight = dp(48)
                amount?.let { setText(BankSms.groupAmountInput(BigDecimal.valueOf(it,2).toPlainString())) }
                body.addView(this,LinearLayout.LayoutParams(-1,-2))
            }
        }
        val current = field("Current outstanding (₹)",card.outstanding,true)
        note("Use the amount in your bank app. New supported alerts update this estimate.",body,8)
        body.addView(View(this).apply {
            setBackgroundColor((palette.muted and 0x00FFFFFF) or 0x55000000)
        },LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin=dp(24) })
        val input = field("Last statement total (₹, optional)",card.total,false)
        var due = card.due
        val dateFormat = DateTimeFormatter.ofPattern("d MMM uuuu",Locale.ENGLISH)
        val date = styledButton(due?.let { "Due " + it.format(dateFormat) } ?: "Set statement due date")
        date.setOnClickListener {
            val initial = due ?: LocalDate.now()
            DatePickerDialog(this,{ _, y,m,d ->
                due = LocalDate.of(y,m+1,d)
                date.text = "Due " + due?.format(dateFormat)
            },initial.year,initial.monthValue-1,initial.dayOfMonth).show()
        }
        body.addView(date,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        note("Keep the statement total and due date together. This is separate from your current outstanding.",body,12)
        val dialog = AlertDialog.Builder(this).setTitle(card.label)
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val amount = input.text.toString().trim().takeIf { it.isNotBlank() }
            val outstanding = current.text.toString().trim().takeIf { it.isNotBlank() }
            val total = amount?.let { runCatching { BankSms.paise(it) }.getOrNull() }
            val balance = outstanding?.let { runCatching { BankSms.paise(it) }.getOrNull() }
            when {
                amount != null && (total == null || total < 0) -> input.error = "Enter a valid statement total"
                outstanding != null && balance == null -> current.error = "Enter a valid outstanding amount"
                (total == null) != (due == null) -> input.error = "Set both statement total and due date, or leave both empty"
                else -> {
                    Cards(this).use { it.correct(card.id,total,due,balance) }
                    CardWidget.refresh(this)
                    dialog.dismiss()
                    show()
                }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun note(value: String, parent: LinearLayout, top: Int) {
        parent.addView(TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(Palette.current(this@CardSetup).muted)
            setLineSpacing(dp(3).toFloat(),1f)
            setPadding(0,dp(top),0,0)
        })
    }

    private fun section(value: String) {
        val palette = Palette.current(this)
        form.addView(TextView(this).apply {
            text = value
            textSize = 18f
            setTypeface(null,Typeface.BOLD)
            setTextColor(palette.ink)
            setPadding(0,dp(28),0,dp(10))
        })
        form.addView(View(this).apply {
            setBackgroundColor((palette.muted and 0x00FFFFFF) or 0x55000000)
        },LinearLayout.LayoutParams(-1,dp(1)).apply { bottomMargin=dp(8) })
    }

    private fun styledButton(label: String): Button {
        val palette = Palette.current(this)
        return Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            setTextColor(palette.ink)
            background = RippleDrawable(ColorStateList.valueOf((palette.muted and 0x00FFFFFF) or 0x33000000),
                GradientDrawable().apply { setColor(palette.action); cornerRadius=dp(14).toFloat() },null)
            minHeight = dp(54)
            setPadding(dp(16),dp(10),dp(16),dp(10))
        }
    }

    private fun button(label: String, action: () -> Unit) {
        form.addView(styledButton(label).apply {
            setOnClickListener { action() }
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8); bottomMargin=dp(4) })
    }
}