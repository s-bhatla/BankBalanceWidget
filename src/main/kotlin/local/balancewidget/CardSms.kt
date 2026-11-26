package local.balancewidget

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

internal object CardSms {
    data class Bill(val suffix: String, val total: Long, val due: LocalDate, val minimum: Long?)
    private fun regex(text: String) = Regex(text, RegexOption.IGNORE_CASE)
    private val card = regex("""\b(?:credit\s*card|card|cc)\s*(?:no\.?|number|ending(?:\s+(?:in|with))?)?\s*[:#-]?\s*[x*•]*(\d{4})(?!\d)""")
    private const val MONEY = """(?:INR|Rs\.?|₹)\s*[:.]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)(?!\d|[,.]\d)"""
    private val totals = listOf(
        regex("""\btotal\s*(?:amount\s*|payment\s*)?due\s*(?:is\s*)?[:=-]?\s*""" + MONEY),
        regex("""\b(?:bill|statement)\s+(?:of|for)\s+""" + MONEY + """\s+(?:is\s+)?(?:generated|due)"""),
        regex(MONEY + """\s+(?:is\s+)?due\s+(?:on|by)\b""")
    )
    private val minimum = regex("""\bmin(?:imum)?\.?\s*(?:amount\s*)?due\s*[:=-]?\s*""" + MONEY)
    private val due = regex("""\b(?:payment\s+due\s+date|due\s+date|pay(?:ment)?\s+by|due\s+(?:on|by))\s*[:=-]?\s*(\d{1,2}[-/ ](?:[A-Za-z]{3,9}|\d{1,2})(?:[-/ ]\d{2,4})?)\b""")
    private val blocked = regex("""\b(?:OTP|one.time.password|minimum\s+(?:payment\s+)?due\s+only|offer|loan)\b""")

    fun parse(body: String, received: LocalDate): Bill? {
        if (blocked.containsMatchIn(body)) return null
        val suffix = card.findAll(body).map { it.groupValues[1] }.distinct().singleOrNull() ?: return null
        val dueText = due.find(body)?.groupValues?.get(1) ?: return null
        val date = date(dueText, received) ?: return null
        val amounts = totals.flatMap { pattern ->
            pattern.findAll(body).filter { match ->
                !regex("""\bmin(?:imum)?\.?\s*(?:amount\s*)?due\s*[:=-]?\s*$""")
                    .containsMatchIn(body.substring(0, match.range.first).takeLast(24))
            }.map { it.groupValues[1] }.toList()
        }
        return try {
            val total = amounts.map(BankSms::paise).distinct().singleOrNull() ?: return null
            val min = minimum.find(body)?.groupValues?.get(1)?.let(BankSms::paise)
            if (total < 0 || (min != null && min > total)) return null
            Bill(suffix, total, date, min)
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    internal fun date(text: String, received: LocalDate): LocalDate? {
        val parts = text.trim().split(Regex("[-/ ]+"))
        if (parts.size !in 2..3) return null
        val year = if (parts.size == 3) parts[2].toIntOrNull()?.let { if (it < 100) 2000 + it else it } else received.year
        if (year == null) return null
        val format = if (parts[1].all(Char::isDigit)) "d-M-uuuu" else
            if (parts[1].length == 3) "d-MMM-uuuu" else "d-MMMM-uuuu"
        val formatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(format)
            .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
        val parsed = runCatching { LocalDate.parse(parts[0] + "-" + parts[1] + "-" + year, formatter) }.getOrNull() ?: return null
        return if (parts.size == 2) {
            listOf(parsed.minusYears(1), parsed, parsed.plusYears(1))
                .filter { !it.isBefore(received.minusDays(60)) && !it.isAfter(received.plusDays(90)) }
                .minByOrNull { kotlin.math.abs(it.toEpochDay() - received.toEpochDay()) }
        } else parsed
    }
}
