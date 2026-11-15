package local.balancewidget

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId

internal class Ledger(context: Context) : SQLiteOpenHelper(context, "ledger.db", null, 3) {
    data class Account(val id: Long, val sender: String, val suffix: String, val balance: Long,
        val cutoff: Long, val latest: Long, val reported: Boolean, val snapshot: Long) {
        val label: String get() = "${Banks.name(sender)} • ${suffix}"
    }

    override fun onCreate(db: SQLiteDatabase) {
        createRedirects(db)
        db.execSQL("CREATE TABLE accounts (id INTEGER PRIMARY KEY, sender TEXT NOT NULL, suffix TEXT NOT NULL, balance INTEGER NOT NULL, cutoff INTEGER NOT NULL, latest INTEGER NOT NULL, reported INTEGER NOT NULL, snapshot INTEGER NOT NULL, UNIQUE(sender,suffix))")
        db.execSQL("CREATE TABLE transactions (account_id INTEGER NOT NULL, key TEXT NOT NULL, change INTEGER NOT NULL, time INTEGER NOT NULL, PRIMARY KEY(account_id,key))")
    }

    private fun createRedirects(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE account_redirects (old_id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1) {
            onCreate(db)
            migrateSingleAccount(db)
        } else {
            createRedirects(db)
        }
        mergeBankAliases(db)
    }

    private fun migrateSingleAccount(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO accounts SELECT id,sender,suffix,balance,cutoff,latest,reported,snapshot FROM account")
        db.execSQL("INSERT INTO transactions SELECT 1,key,change,time FROM entries WHERE EXISTS (SELECT 1 FROM accounts WHERE id=1)")
        db.execSQL("DROP TABLE entries")
        db.execSQL("DROP TABLE account")
    }

    private fun mergeBankAliases(db: SQLiteDatabase) {
        val accounts = db.rawQuery("SELECT * FROM accounts", null).use {
            buildList { while (it.moveToNext()) add(it.account()) }
        }
        for ((key, group) in accounts.groupBy { Banks.key(it.sender) to it.suffix }) {
            // Prefer the newest recorded balance. Separate ledgers may have missed
            // activity from the other sender, so the merged balance needs verification.
            val keep = group.maxWith(compareBy<Account> { maxOf(it.cutoff, it.latest) }.thenBy { it.id })
            for (other in group.filter { it.id != keep.id }) {
                db.execSQL("INSERT OR IGNORE INTO transactions SELECT ?,key,change,time FROM transactions WHERE account_id=?",
                    arrayOf<Any>(keep.id, other.id))
                db.execSQL("DELETE FROM transactions WHERE account_id=?", arrayOf<Any>(other.id))
                db.execSQL("INSERT INTO account_redirects VALUES (?,?)", arrayOf<Any>(other.id, keep.id))
                db.execSQL("DELETE FROM accounts WHERE id=?", arrayOf<Any>(other.id))
            }
            db.execSQL("UPDATE accounts SET sender=?,reported=? WHERE id=?",
                arrayOf<Any>(key.first, if (group.size == 1 && keep.reported) 1 else 0, keep.id))
        }
    }

    private fun Cursor.account() = Account(getLong(0), getString(1), getString(2),
        getLong(3), getLong(4), getLong(5), getInt(6) != 0, getLong(7))

    fun accounts(): List<Account> =
        readableDatabase.rawQuery("SELECT * FROM accounts ORDER BY id", null).use {
            buildList { while (it.moveToNext()) add(it.account()) }
        }

    fun account(id: Long): Account? =
        readableDatabase.rawQuery("SELECT * FROM accounts WHERE id=COALESCE((SELECT account_id FROM account_redirects WHERE old_id=?), ?)", arrayOf(id.toString(), id.toString())).use {
            if (it.moveToFirst()) it.account() else null
        }

    fun add(candidate: Inbox.Candidate, balance: Long, reported: Boolean) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO accounts (id,sender,suffix,balance,cutoff,latest,reported,snapshot) VALUES ((SELECT MAX(id)+1 FROM (SELECT id FROM accounts UNION ALL SELECT old_id FROM account_redirects)),?,?,?,?,0,?,0)",
            arrayOf<Any>(candidate.sender, candidate.suffix, balance, System.currentTimeMillis(), if (reported) 1 else 0))
    }

    fun correct(id: Long, balance: Long) {
        writableDatabase.execSQL("UPDATE accounts SET balance=?,cutoff=?,latest=0,reported=0,snapshot=0 WHERE id=?",
            arrayOf<Any>(balance, System.currentTimeMillis(), id))
    }

    fun receive(sender: String, body: String, time: Long): Boolean {
        val suffix = BankSms.accountSuffix(body) ?: return false
        val entry = BankSms.parse(body, suffix) ?: return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val account = db.rawQuery("SELECT * FROM accounts WHERE sender=? AND suffix=?",
                arrayOf(Banks.key(sender), suffix)).use {
                if (it.moveToFirst()) it.account() else return false
            }
            if (time <= account.cutoff) return false
            val key = entry.reference?.let { "ref:$it:${entry.change.compareTo(0L)}" }
                ?: "sms:$time:${body.hashCode()}"
            val values = ContentValues().apply {
                put("account_id", account.id)
                put("key", key)
                put("change", entry.change)
                put("time", time)
            }
            if (db.insertWithOnConflict("transactions", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) return false

            if (time > account.snapshot) {
                val reported = entry.available?.takeIf { time >= account.latest }
                val balance = reported ?: Math.addExact(account.balance, entry.change)
                db.execSQL("UPDATE accounts SET balance=?,latest=?,reported=?,snapshot=? WHERE id=?",
                    arrayOf<Any>(balance, maxOf(time, account.latest), if (reported != null) 1 else 0,
                        if (reported != null) time else account.snapshot, account.id))
            }
            db.setTransactionSuccessful()
            return true
        } catch (_: ArithmeticException) {
            return false
        } finally {
            db.endTransaction()
        }
    }

    fun spentSince(id: Long, start: LocalDate): Long {
        val timestamp = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(-change),0) FROM transactions WHERE account_id=? AND change<0 AND time>=?",
            arrayOf(id.toString(), timestamp.toString())).use {
            it.moveToFirst()
            it.getLong(0)
        }
    }
}
