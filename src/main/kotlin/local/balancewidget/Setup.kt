package local.balancewidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.WindowInsetsController
import android.os.Build
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.math.BigDecimal
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class Setup : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var editId = 0L
    private lateinit var form: LinearLayout

    private lateinit var palette: Palette

    override fun onCreate(state: Bundle?) {
        palette = Palette.current(this)
        setTheme(if (palette.dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
            setResult(RESULT_CANCELED)
            widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                finish()
                return
            }
        } else {
            editId = intent.getLongExtra("account_id", 0L)
        }
        val padding = (24 * resources.displayMetrics.density).roundToInt()
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.surface)
            addView(form, FrameLayout.LayoutParams(-1, -2))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        })
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(if (palette.dark) 0 else bars, bars)
        }
        showAccounts()
        Widgets.refresh(this)
        if (editId != 0L) {
            Ledger(this).use { it.account(editId) }?.let { account ->
                edit(account)
            }
        }
    }

    private fun showAccounts() {
        form.removeAllViews()
        val configuring = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        heading(if (configuring) "Choose an account" else "Bank Balance", 34f)
        val accounts = Ledger(this).use { it.accounts() }
        if (!configuring && accounts.isNotEmpty()) heading("Accounts", 15f, 24)
        if (accounts.isEmpty()) {
            copy("Scan your recent bank alerts to add an account.")
        }
        for (account in accounts) {
            button(if (configuring) account.label else account.label + "  ›") {
                if (configuring) {
                    Widgets.bind(this, widgetId, account.id)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                } else {
                    edit(account)
                }
            }
        }
        if (!configuring && accounts.any { Widgets.lastRefresh(this, it.id) != null }) {
            heading("Last SMS refresh", 15f, 20)
            for (account in accounts) {
                Widgets.lastRefresh(this, account.id)?.let { copy(account.label + " · " + it) }
            }
        }
        heading("Bank alerts", 15f, 24)
        button("Scan recent SMS", primary = accounts.isEmpty()) {
            if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) scan()
            else requestSmsAccess(inbox = true)
        }
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            button("Allow new SMS updates") {
                requestSmsAccess(inbox = false)
            }
        }
        copy("Scans up to 100 recent messages on this phone.")
        if (!configuring) {
            heading("Appearance", 15f, 28)
            button("Privacy policy") { showPrivacyPolicy() }
            button("Color theme  ·  " + palette.title + "  ›") { pickPalette() }
            form.addView(Switch(this).apply {
                text = getString(R.string.mask_widget_amounts)
                textSize = 15f
                setTextColor(palette.ink)
                minHeight = dp(56)
                setPadding(dp(14), 0, dp(14), 0)
                background = panel(palette.action)
                thumbTintList = ColorStateList.valueOf(palette.ink)
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(palette.muted, palette.surface))
                isChecked = Widgets.privacyEnabled(this@Setup)
                setOnCheckedChangeListener { _, enabled -> Widgets.setPrivacyEnabled(this@Setup, enabled) }
            }, row())
        }
    }

    private fun requestSmsAccess(inbox: Boolean) {
        val purpose = if (inbox) {
            "Bank Balance reads up to 100 recent inbox messages when you scan or refresh. It uses matching bank alerts to find accounts and update balances and spending."
        } else {
            "Bank Balance checks incoming SMS in the background so matching bank alerts can update your balance and spending widgets."
        }
        AlertDialog.Builder(this)
            .setTitle(if (inbox) "Read recent bank alerts?" else "Use new bank alerts?")
            .setMessage(purpose + "\n\nSMS processing stays on this device. The app saves selected account details and transaction data locally. It does not upload messages or store full message text.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(
                    arrayOf(if (inbox) Manifest.permission.READ_SMS else Manifest.permission.RECEIVE_SMS),
                    if (inbox) 1 else 2
                )
            }
            .show()
    }

    private fun showPrivacyPolicy() {
        val url = BuildConfig.PRIVACY_POLICY_URL
        if (url.isNotBlank()) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                AlertDialog.Builder(this).setTitle("Privacy policy").setMessage(url)
                    .setPositiveButton("Close", null).show()
            }
        } else {
            AlertDialog.Builder(this).setTitle("Privacy")
                .setMessage("Bank Balance processes transaction SMS on your device. It saves selected accounts and transaction details locally. You can revoke SMS permissions in Android settings and delete the app’s data from Android settings.")
                .setPositiveButton("Close", null).show()
        }
    }


    private fun pickPalette() {
        val palettes = Palette.entries
        AlertDialog.Builder(this).setTitle("Color theme")
            .setSingleChoiceItems(palettes.map { it.title }.toTypedArray(), palettes.indexOf(palette)) { dialog, index ->
                val selected = palettes[index]
                dialog.dismiss()
                if (selected != palette) {
                    Palette.save(this, selected)
                    recreate()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun heading(value: String, size: Float, top: Int = 0) {
        form.addView(TextView(this).apply {
            text = value
            textSize = size
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (size >= 30) palette.ink else palette.muted)
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top)
            bottomMargin = dp(if (size >= 30) 6 else 2)
        })
    }

    private fun copy(value: String) {
        form.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(palette.muted)
        }, row())
    }

    private fun row() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }

    private fun panel(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()


    private fun edit(account: Ledger.Account) {
        amountDialog(account.label, account.balance) { amount ->
            Ledger(this).use { it.correct(account.id, amount) }
            Widgets.refresh(this)
            if (editId != 0L) finish() else showAccounts()
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) scan()
        else showAccounts()
    }

    private fun scan() {
        val progress = AlertDialog.Builder(this).setMessage("Scanning recent SMS…").setCancelable(false).show()
        thread {
            val result = runCatching { Inbox.scan(this) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                val known = Ledger(this).use { ledger -> ledger.accounts().map { it.sender to it.suffix }.toSet() }
                val candidates = result.getOrNull()?.filter { (it.sender to it.suffix) !in known }
                if (candidates.isNullOrEmpty()) {
                    val message = if (result.isFailure) "Android did not allow the inbox scan."
                        else "No new matching bank accounts in the latest 100 messages."
                    AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                val selected = BooleanArray(candidates.size)
                val labels = candidates.map { "${Banks.name(it.sender)} • ${it.suffix} (${it.sender})" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Select accounts")
                    .setMultiChoiceItems(labels, selected) { _, index, checked -> selected[index] = checked }
                    .setNeutralButton("Select all") { _, _ -> addAccounts(candidates) }
                    .setPositiveButton("Continue") { _, _ -> addAccounts(candidates.filterIndexed { index, _ -> selected[index] }) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun addAccounts(candidates: List<Inbox.Candidate>) {
        val candidate = candidates.firstOrNull() ?: return showAccounts()
        amountDialog("${Banks.name(candidate.sender)} • ${candidate.suffix}", candidate.balance,
            if (candidate.balance != null) "Balance from the latest matching alert. Check it against your bank."
            else "Enter the current balance. The latest alert did not include one.",
            onSkip = { addAccounts(candidates.drop(1)) }) { amount ->
            Ledger(this).use { it.add(candidate, amount, amount == candidate.balance) }
            addAccounts(candidates.drop(1))
        }
    }

    private fun amountDialog(label: String, balance: Long?, message: String? = null,
        onSkip: (() -> Unit)? = null, onSave: (Long) -> Unit) {
        val input = EditText(this).apply {
            hint = "Current balance (₹)"
            keyListener = DigitsKeyListener.getInstance("0123456789-,.")
            setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED)
            balance?.let { setText(BankSms.groupAmountInput(BigDecimal.valueOf(it, 2).toPlainString())) }
            addTextChangedListener(object : TextWatcher {
                private var grouping = false

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(value: Editable) {
                    if (grouping) return
                    val original = value.toString()
                    val formatted = BankSms.groupAmountInput(original)
                    if (formatted == original) return
                    val caret = selectionStart.coerceIn(0, original.length)
                    val digitsBeforeCaret = original.take(caret).count { it != ',' }
                    var position = 0
                    var seen = 0
                    while (position < formatted.length && seen < digitsBeforeCaret) {
                        if (formatted[position++] != ',') seen++
                    }
                    grouping = true
                    try {
                        value.replace(0, value.length, formatted)
                        setSelection(position.coerceAtMost(value.length))
                    } finally {
                        grouping = false
                    }
                }
            })
        }
        val dialog = AlertDialog.Builder(this).setTitle(label).setMessage(message).setView(input)
            .setNegativeButton(if (onSkip == null) "Cancel" else "Skip") { _, _ -> onSkip?.invoke() }
            .setPositiveButton("Save", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val amount = runCatching { BankSms.paise(input.text.toString().trim()) }.getOrNull()
            if (amount == null) input.error = "Enter a valid balance with up to two decimal places"
            else {
                dialog.dismiss()
                onSave(amount)
            }
        }
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit) {
        form.addView(Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(54)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextColor(if (primary) palette.surface else palette.ink)
            background = RippleDrawable(ColorStateList.valueOf(palette.muted and 0x33FFFFFF),
                panel(if (primary) palette.ink else palette.action), null)
            setOnClickListener { action() }
        }, row())
    }
}