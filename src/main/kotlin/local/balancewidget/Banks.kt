package local.balancewidget

internal object Banks {
    private data class Bank(val name: String, val senders: List<String>)

    // Exact transaction headers. Keep unknown headers independent.
    private val banks = listOf(
        Bank("HDFC Bank", listOf("HDFCBK", "HDFCBN")),
        Bank("SBI", listOf("SBIINB", "SBIUPI", "SBIBNK", "SBIPSG", "SBYONO")),
        Bank("ICICI Bank", listOf("ICICIB", "ICICIBK", "ICICIT")),
        Bank("Axis Bank", listOf("AXISBK", "AXISBN")),
        Bank("Kotak Mahindra Bank", listOf("KOTAKB", "KMBLNK")),
        Bank("Punjab National Bank", listOf("PNBSMS", "PNBBNK")),
        Bank("Bank of Baroda", listOf("BOBSMS", "BOBTXN", "BOBMSG")),
        Bank("Canara Bank", listOf("CANBNK", "CNRBNK", "CAANBK")),
        Bank("Union Bank of India", listOf("UBISMS", "UNIONB")),
        Bank("Bank of India", listOf("BOIIND")),
        Bank("Indian Bank", listOf("INDBNK")),
        Bank("Indian Overseas Bank", listOf("IOBCHN")),
        Bank("Central Bank of India", listOf("CENTBK")),
        Bank("IDBI Bank", listOf("IDBIBK")),
        Bank("IDFC FIRST Bank", listOf("IDFCFB", "IDFCBK")),
        Bank("YES Bank", listOf("YESBNK")),
        Bank("IndusInd Bank", listOf("INDUSB", "INDBKS")),
        Bank("Federal Bank", listOf("FEDBNK")),
        Bank("AU Small Finance Bank", listOf("AUBANK")),
        Bank("RBL Bank", listOf("RBLBNK"))
    )
    private val bySender = banks.flatMap { bank -> bank.senders.map { it to bank } }.toMap()

    fun key(address: String): String {
        val sender = BankSms.senderId(address)
        return bySender[sender]?.senders?.first() ?: sender
    }

    fun name(sender: String): String = bySender[sender]?.name ?: sender
}
