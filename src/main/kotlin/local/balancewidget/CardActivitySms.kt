package local.balancewidget

internal object CardActivitySms {
    data class Entry(val suffix: String, val change: Long, val reference: String?)
    private fun regex(value: String) = Regex(value, RegexOption.IGNORE_CASE)
    private val card = regex("""\b(?:credit\s*card|card|cc)\s*(?:no\.?|number|ending(?:\s+(?:in|with))?)?\s*[:#-]?\s*[x*•]*(\d{4})(?!\d)""")
    private const val MONEY = """(?:INR|Rs\.?|₹)\s*[:.]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)(?!\d|[,.]\d)"""
    private val blocked = regex("""\b(?:OTP|declined|failed|unsuccessful|pending|will\s+be|due|statement|minimum|offer)\b""")
    private val debit = listOf(
        regex("""\b(?:spent|charged|debited|purchase(?:\s+of)?)\s+(?:for\s+)?""" + MONEY),
        regex(MONEY + """\s+(?:has\s+been\s+|was\s+)?(?:spent|charged|debited)\b"""),
        regex("""\b(?:transaction|purchase)\s+of\s+""" + MONEY + """[\s\S]{0,100}\b(?:made|successful|completed)\b"""),
        regex("""\bused[\s\S]{0,60}\bfor\s+""" + MONEY)
    )
    private val credit = listOf(
        regex("""\b(?:refund(?:\s+of)?|payment(?:\s+of)?|reversal(?:\s+of)?)\s+(?:received\s+(?:for\s+)?)?""" + MONEY + """[\s\S]{0,80}\b(?:credited|received|processed|successful)\b"""),
        regex(MONEY + """\s+(?:has\s+been\s+|was\s+)?(?:credited|refunded|reversed)\b"""),
        regex("""\b(?:received|credited)\s+(?:payment\s+(?:of\s+)?)?""" + MONEY)
    )
    private val ref = regex("""\b(?:ref(?:erence)?(?:\s*(?:no|id)\.?)?|txn(?:\s*id)?|transaction\s*id)\s*[:#.-]?\s*([A-Z0-9]{8,30})\b""")

    fun parse(body: String): Entry? {
        if (blocked.containsMatchIn(body)) return null
        val suffix = card.findAll(body).map { it.groupValues[1] }.distinct().singleOrNull() ?: return null
        return try {
            val outgoing = debit.flatMap { it.findAll(body).map { match -> BankSms.paise(match.groupValues[1]) }.toList() }.distinct()
            val incoming = credit.flatMap { it.findAll(body).map { match -> BankSms.paise(match.groupValues[1]) }.toList() }.distinct()
            if ((outgoing.isEmpty()) == incoming.isEmpty()) return null
            val amounts = if (outgoing.isNotEmpty()) outgoing else incoming
            val amount = amounts.singleOrNull()?.takeIf { it > 0 } ?: return null
            Entry(suffix,if (outgoing.isNotEmpty()) amount else -amount,
                ref.find(body)?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT))
        } catch (_: ArithmeticException) { null }
        catch (_: NumberFormatException) { null }
    }
}
