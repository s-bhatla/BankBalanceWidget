package local.balancewidget

import android.content.Context
import android.provider.Telephony

internal object Inbox {
    data class Candidate(val sender: String, val suffix: String, val balance: Long?)

    fun scan(context: Context): List<Candidate> {
        val found = linkedMapOf<Pair<String, String>, Candidate>()
        val columns = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY)
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, columns, null, null,
            "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC")?.use { cursor ->
            repeat(100) {
                if (!cursor.moveToNext()) return found.values.toList()
                val sender = Banks.key(cursor.getString(0) ?: return@repeat)
                if (!sender.matches(Regex("[A-Z][A-Z0-9]{4,11}"))) return@repeat
                val body = cursor.getString(1) ?: return@repeat
                val suffix = BankSms.accountSuffix(body) ?: return@repeat
                val entry = BankSms.parse(body, suffix) ?: return@repeat
                found.putIfAbsent(sender to suffix, Candidate(sender, suffix, entry.available))
            }
        }
        return found.values.toList()
    }

    private data class Alert(val sender: String, val body: String, val time: Long)

    fun refresh(context: Context, ledger: Ledger, account: Ledger.Account): Int {
        val columns = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE_SENT, Telephony.Sms.DATE)
        val alerts = checkNotNull(context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, columns, null, null, "date DESC, _id DESC"
        )).use { cursor ->
            buildList {
                repeat(100) {
                    if (!cursor.moveToNext()) return@buildList
                    val sender = cursor.getString(0) ?: return@repeat
                    if (Banks.key(sender) != account.sender) return@repeat
                    val body = cursor.getString(1) ?: return@repeat
                    val sent = cursor.getLong(2)
                    // A reference-less live alert uses the carrier timestamp as its key.
                    if (sent <= 0 && BankSms.parse(body, account.suffix)?.reference == null) return@repeat
                    val time = if (sent > 0) sent else cursor.getLong(3)
                    add(Alert(sender, body, time))
                }
            }
        }
        return alerts.asReversed().sortedBy { it.time }.count {
            ledger.receive(it.sender, it.body, it.time, account.id)
        }
    }}