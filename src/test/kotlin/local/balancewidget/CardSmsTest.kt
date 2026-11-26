package local.balancewidget

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CardSmsTest {
    private val received = LocalDate.of(2026,11,20)

    @Test fun readsTotalAndMinimumSeparately() {
        val bill = CardSms.parse("HDFC Bank Credit Card ending XX1234: Total Amount Due: Rs.12,345.67. Minimum Amount Due: Rs.500.00. Payment Due Date: 05-Dec-26.",received)
        assertEquals(CardSms.Bill("1234",1234567,LocalDate.of(2026,12,5),50000),bill)
    }
    @Test fun readsDueOnAndNumericDate() {
        assertEquals(CardSms.Bill("4321",1545000,LocalDate.of(2026,12,5),null),
            CardSms.parse("Your ICICI Credit Card XX4321 bill of Rs.15,450.00 is due on 05/12/2026.",received))
    }
    @Test fun infersYearFromSmsDate() {
        assertEquals(LocalDate.of(2027,1,5),CardSms.parse(
            "SBI Card ending 1234 Total Due INR 1000 Due date 5 Jan",LocalDate.of(2026,12,27))?.due)
    }
    @Test fun rejectsMinimumOnlyPurchaseAndInvalidDate() {
        assertNull(CardSms.parse("Credit Card XX1234 minimum amount due Rs.500 due on 5 Dec 2026",received))
        assertNull(CardSms.parse("Spent Rs.100 on Credit Card XX1234. Available limit Rs.900",received))
        assertNull(CardSms.parse("Credit Card XX1234 Total Due Rs.100 Due date 31/11/2026",received))
        assertNull(CardSms.parse("Total due Rs.100 Due date 5 Dec 2026",received))
    }
    @Test fun doesNotUseMinimumAsTotal() {
        val bill = CardSms.parse("Credit Card XX1234 Total Due INR 5000 Minimum Due INR 250 Due on 5 Dec 2026",received)
        assertEquals(500000L,bill?.total)
        assertEquals(25000L,bill?.minimum)
    }
}
