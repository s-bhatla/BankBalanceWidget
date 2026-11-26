package local.balancewidget

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

internal object BankSms {
    private const val MONEY = """([0-9][0-9,]*(?:\.[0-9]{1,2})?)(?!\d|[,.]\d)"""
    private const val INR = """(?:INR|Rupees|Rs\.?|₹)\s*"""
    private val account = pattern("""\b(?:A\s*/?\s*C(?:COUNT)?|Acct|Acc|Account)\s*(?:(?:no\.?|ending(?:\s+(?:in|with))?)\s*)?[:#-]?\s*(?:[X*•]+\s*\d{0,16}?)?(\d{3,4})(?!\d)""")
    private val balance = pattern("""\b(?:(?:avl\.?|avail\.?|available)\s*)?(?:balance|bal\.?)\s*(?:is\s+)?[:=–-]?\s*""" + INR + "(-?$MONEY)")
    private val reference = pattern("""\b(?:UPI\s*)?(?:(?:Ref(?:erence)?(?:\s*No\.?|\s*ID)?|UTR|RRN)\s*[:#.-]?|UPI\s*:)\s*([A-Z0-9]{8,30})\b""")
    private val ignore = pattern("""\b(?:OTP|one.time password|failed|declined|pending|collect request|scheduled|will be debited)\b""")
    private val debits = amounts("(?:debited|spent|sent|withdrawn|paid)")
    private val credits = amounts("(?:credited|received|deposited)")

    data class Entry(val change: Long, val available: Long?, val reference: String?)

    fun senderId(address: String): String = address.trim().uppercase(Locale.ROOT)
        .replace(Regex("^[A-Z]{2}-"), "").replace(Regex("-[STPG]$"), "")

    fun accountSuffix(sms: String): String? =
        account.findAll(sms).map { it.groupValues[1] }.distinct().singleOrNull()

    fun parse(sms: String, suffix: String): Entry? {
        if (ignore.containsMatchIn(sms)) return null
        if (accountSuffix(sms) != suffix) return null
        return try {
            val debit = findAmount(debits, sms)
            val credit = findAmount(credits, sms)
            if ((debit == null) == (credit == null)) return null
            val amount = debit ?: checkNotNull(credit)
            if (amount <= 0) return null
            Entry(if (debit == null) amount else -amount,
                balance.find(sms)?.let { paise(it.groupValues[1]) },
                reference.find(sms)?.groupValues?.get(1)?.uppercase(Locale.ROOT))
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun groupAmountInput(value: String): String {
        val raw = value.replace(",", "")
        val whole = raw.substringBefore('.')
        val sign = if (whole.startsWith('-')) "-" else ""
        val digits = whole.removePrefix("-")
        if (digits.length <= 3 || digits.any { it !in '0'..'9' }) return raw
        val prefix = digits.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return sign + prefix + "," + digits.takeLast(3) + raw.substring(whole.length)
    }

    fun paise(value: String): Long = BigDecimal(value.replace(",", "")).movePointRight(2).longValueExact()

    fun rupees(paise: Long, compact: Boolean, wholeRupees: Boolean = false): String {
        val amount = BigDecimal.valueOf(paise, 2)
        val magnitude = amount.abs()
        if (compact && magnitude >= BigDecimal(1000)) {
            val (shift, unit) = when {
                magnitude >= BigDecimal(10000000) -> 7 to "Cr"
                magnitude >= BigDecimal(100000) -> 5 to "L"
                else -> 3 to "k"
            }
            return (if (paise < 0) "-" else "") + "₹" + magnitude.movePointLeft(shift)
                .setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + unit
        }
        return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
            minimumFractionDigits = if (wholeRupees || paise % 100 == 0L) 0 else 2
        }.format(if (wholeRupees) amount.setScale(0, RoundingMode.DOWN) else amount)
    }

    private fun findAmount(patterns: List<Regex>, sms: String): Long? {
        val candidates = patterns.flatMap { regex ->
            regex.findAll(sms).map { paise(it.groupValues[1]) }.toList()
        }.distinct()
        if (candidates.size > 1) throw NumberFormatException("Ambiguous transaction amount")
        return candidates.singleOrNull()
    }

    private fun amounts(direction: String) = listOf(
        pattern("""\b""" + direction + """\s+(?:(?:by|with)\s+)?(?:$INR)?$MONEY"""),
        pattern("""\b""" + direction + """\s+for\s+$INR$MONEY"""),
        pattern("""$INR$MONEY\s+(?:(?:was|has\s+been)\s+)?""" + direction + """\b""")
    )

    private fun pattern(value: String) = Regex(value, RegexOption.IGNORE_CASE)
}
