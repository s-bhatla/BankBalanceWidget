package local.balancewidget

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Telephony
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class Cards(context: Context) : SQLiteOpenHelper(context, "cards.db", null, 2) {
    data class Card(val id: Long, val bank: String, val suffix: String, val total: Long?,
        val due: LocalDate?, val minimum: Long?, val updated: Long, val outstanding: Long?,
        val cutoff: Long, val activityUpdated: Long) {
        val label get() = Banks.name(bank).removeSuffix(" Bank") + " • " + suffix
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cards (id INTEGER PRIMARY KEY AUTOINCREMENT, bank TEXT NOT NULL, suffix TEXT NOT NULL, total INTEGER, due TEXT, minimum INTEGER, updated INTEGER NOT NULL, outstanding INTEGER, cutoff INTEGER NOT NULL DEFAULT 0, activity_updated INTEGER NOT NULL DEFAULT 0, UNIQUE(bank,suffix))")
        db.execSQL("CREATE TABLE card_activity (card_id INTEGER NOT NULL, key TEXT NOT NULL, change INTEGER NOT NULL, time INTEGER NOT NULL, PRIMARY KEY(card_id,key))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2)
        db.execSQL("ALTER TABLE cards RENAME TO old_cards")
        onCreate(db)
        db.execSQL("INSERT INTO cards (id,bank,suffix,total,due,minimum,updated) SELECT id,bank,suffix,total,due,minimum,updated FROM old_cards")
        db.execSQL("DROP TABLE old_cards")
    }

    private fun Cursor.card() = Card(getLong(0), getString(1), getString(2),
        if (isNull(3)) null else getLong(3), if (isNull(4)) null else LocalDate.parse(getString(4)),
        if (isNull(5)) null else getLong(5),getLong(6),if (isNull(7)) null else getLong(7),getLong(8),getLong(9))

    fun all(): List<Card> = readableDatabase.rawQuery("SELECT * FROM cards ORDER BY due IS NULL,due,id", null).use {
        buildList { while (it.moveToNext()) add(it.card()) }
    }
    fun get(id: Long): Card? = readableDatabase.rawQuery("SELECT * FROM cards WHERE id=?", arrayOf(id.toString())).use {
        if (it.moveToFirst()) it.card() else null
    }

    fun correct(id: Long, total: Long?, due: LocalDate?, outstanding: Long?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = db.rawQuery("SELECT * FROM cards WHERE id=?",arrayOf(id.toString())).use {
                if (it.moveToFirst()) it.card() else return
            }
            val values = ContentValues().apply {
                if (total == null) putNull("total") else put("total",total)
                if (due == null) putNull("due") else put("due",due.toString())
                if (total != previous.total || due != previous.due) {
                    putNull("minimum")
                    put("updated",System.currentTimeMillis())
                }
                if (outstanding != previous.outstanding) {
                    if (outstanding == null) putNull("outstanding") else put("outstanding",outstanding)
                    put("cutoff",System.currentTimeMillis())
                    put("activity_updated",System.currentTimeMillis())
                }
            }
            db.update("cards",values,"id=?",arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun receive(sender: String, body: String, timestamp: Long): Boolean {
        if (!Banks.known(sender)) return false
        val bank = Banks.key(sender)
        val day = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        val bill = CardSms.parse(body,day)
        val activity = if (bill == null) CardActivitySms.parse(body) else null
        val suffix = bill?.suffix ?: activity?.suffix ?: return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            var previous = db.rawQuery("SELECT * FROM cards WHERE bank=? AND suffix=?",arrayOf(bank,suffix)).use {
                if (it.moveToFirst()) it.card() else null
            }
            var changed = false
            if (previous == null) {
                db.execSQL("INSERT INTO cards (bank,suffix,updated) VALUES (?,?,0)",arrayOf(bank,suffix))
                previous = db.rawQuery("SELECT * FROM cards WHERE bank=? AND suffix=?",arrayOf(bank,suffix)).use {
                    check(it.moveToFirst())
                    it.card()
                }
                changed = true
            }
            if (bill != null && timestamp > previous.updated &&
                (previous.due == null || !bill.due.isBefore(previous.due))) {
                db.execSQL("UPDATE cards SET total=?,due=?,minimum=?,updated=? WHERE id=?",
                    arrayOf<Any?>(bill.total,bill.due.toString(),bill.minimum,timestamp,previous.id))
                changed = true
            }
            if (activity != null && previous.outstanding != null && timestamp > previous.cutoff) {
                val key = activity.reference?.let { "ref:$it:" + activity.change.compareTo(0L) }
                    ?: "sms:$timestamp:" + body.hashCode()
                val values = ContentValues().apply {
                    put("card_id",previous.id)
                    put("key",key)
                    put("change",activity.change)
                    put("time",timestamp)
                }
                if (db.insertWithOnConflict("card_activity",null,values,SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    val next = Math.addExact(previous.outstanding,activity.change)
                    db.execSQL("UPDATE cards SET outstanding=?,activity_updated=? WHERE id=?",
                        arrayOf<Any>(next,maxOf(timestamp,previous.activityUpdated),previous.id))
                    changed = true
                }
            }
            db.setTransactionSuccessful()
            return changed
        } catch (_: ArithmeticException) { return false }
        finally { db.endTransaction() }
    }

    fun scan(context: Context): Int {
        val alerts = checkNotNull(context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf("address","body","date_sent","date"),null,null,"date DESC, _id DESC")).use { cursor ->
            buildList {
                repeat(500) {
                    if (!cursor.moveToNext()) return@buildList
                    val sender = cursor.getString(0) ?: return@repeat
                    val body = cursor.getString(1) ?: return@repeat
                    val sent = cursor.getLong(2)
                    val bill = CardSms.parse(body,Instant.ofEpochMilli(cursor.getLong(3)).atZone(ZoneId.systemDefault()).toLocalDate())
                    if (sent <= 0 && bill == null && CardActivitySms.parse(body)?.reference == null) return@repeat
                    add(Triple(sender,body,if (sent > 0) sent else cursor.getLong(3)))
                }
            }
        }
        return alerts.sortedBy { it.third }.count { receive(it.first,it.second,it.third) }
    }
}
