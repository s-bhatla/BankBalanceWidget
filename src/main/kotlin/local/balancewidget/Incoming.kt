package local.balancewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.concurrent.Executors

class Incoming : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val first = parts.firstOrNull() ?: return
        val sender = first.originatingAddress ?: return
        val body = StringBuilder()
        for (part in parts) {
            if (part == null || part.originatingAddress != sender) return
            body.append(part.messageBody ?: return)
        }
        val pending = goAsync()
        worker.execute {
            try {
                Ledger(context).use {
                    val bankChanged = it.receive(sender, body.toString(), first.timestampMillis)
                    val cardChanged = Cards(context).use { cards ->
                        cards.receive(sender, body.toString(), first.timestampMillis)
                    }
                    if (bankChanged || cardChanged) Widgets.refresh(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
