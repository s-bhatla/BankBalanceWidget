package local.balancewidget

import org.junit.Assert.*
import org.junit.Test

class CardActivitySmsTest {
    @Test fun purchasesIncreaseAndRefundsReduceOutstanding() {
        assertEquals(123450L,CardActivitySms.parse("Spent Rs.1,234.50 on HDFC Credit Card XX1234 at Example. Avl limit Rs.9000.")?.change)
        assertEquals(-50000L,CardActivitySms.parse("Refund of INR 500 has been credited to your Credit Card XX1234.")?.change)
        assertEquals(-250000L,CardActivitySms.parse("Payment of INR 2500 received towards ICICI Credit Card ending 1234.")?.change)
    }
    @Test fun rejectsFailuresAndBillMessages() {
        assertNull(CardActivitySms.parse("Payment of INR 500 failed on Credit Card XX1234"))
        assertNull(CardActivitySms.parse("INR 500 spent on Credit Card XX1234. Total due INR 900."))
        assertNull(CardActivitySms.parse("OTP 123456 for purchase of INR 500 on Credit Card XX1234"))
        assertNull(CardActivitySms.parse("Your credit limit is INR 50000 on Credit Card XX1234"))
    }
    @Test fun extractsReferencesAndRejectsConflictingDirections() {
        assertEquals("ABC123456789",CardActivitySms.parse("INR 500 charged on Credit Card XX1234 Ref ABC123456789")?.reference)
        assertNull(CardActivitySms.parse("Spent INR 500 and INR 200 credited on Credit Card XX1234"))
    }
}
