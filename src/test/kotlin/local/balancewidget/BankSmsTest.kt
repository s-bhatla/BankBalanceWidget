package local.balancewidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankSmsTest {
    @Test fun normalizesSender() {
        assertEquals("HDFCBK", BankSms.senderId("VM-HDFCBK-S"))
    }

    @Test fun parsesDebitWithReportedBalanceAndReference() {
        assertEquals(BankSms.Entry(-50000, 950000, "123456789012"), BankSms.parse(
            "Sent Rs.500.00 from HDFC Bank A/C XX1234. Avl Bal Rs.9,500. UPI Ref 123456789012", "1234"))
    }

    @Test fun parsesCurrencyLessDebit() {
        assertEquals(BankSms.Entry(-12500, null, null), BankSms.parse(
            "Dear UPI user A/C X1234 debited by 125.0 trf to Example", "1234"))
    }

    @Test fun parsesCredit() {
        assertEquals(BankSms.Entry(200000, null, null), BankSms.parse(
            "INR 2,000.00 credited to A/c XX1234", "1234"))
    }

    @Test fun parsesIciciDebitWithThreeDigitAccountAndMerchantCredit() {
        val sms = "ICICI Bank Acct XX123 debited for Rs 50.00 on 01-Nov-26; Example Merchant credited. UPI: 123456789012."
        assertEquals("ICICIB", BankSms.senderId("VM-ICICIB-S"))
        assertEquals("123", BankSms.accountSuffix(sms))
        assertEquals(BankSms.Entry(-5000, null, "123456789012"), BankSms.parse(sms, "123"))
    }

    @Test fun parsesIciciRupeesDebitAndBalance() {
        val sms = "ICICI: Rupees 99.00 debited for UPI/Example on 01-11-26 A/c 1234. Bal INR 900.00."
        assertEquals(BankSms.Entry(-9900, 90000, null), BankSms.parse(sms, "1234"))
    }

    @Test fun hdfcCreditAndDebitResolveToSameAccount() {
        val debit = "Sent Rs.50.00 from HDFC Bank A/C XX1234. UPI Ref 123456789012."
        val credit = "Rs.70.00 credited to HDFC Bank A/c XX1234. Avl Bal Rs.120.00."
        val debitKey = Banks.key("VM-HDFCBK-S") to BankSms.accountSuffix(debit)
        val creditKey = Banks.key("AD-HDFCBN-T") to BankSms.accountSuffix(credit)
        assertEquals("HDFCBK" to "1234", debitKey)
        assertEquals(debitKey, creditKey)
        assertEquals(-5000L, BankSms.parse(debit, "1234")?.change)
        assertEquals(7000L, BankSms.parse(credit, "1234")?.change)
    }

    @Test fun resolvesOtherBankAliasesAndKeepsUnknownSendersSeparate() {
        for ((first, second) in listOf(
            "SBIINB" to "SBIUPI", "ICICIB" to "ICICIBK", "AXISBK" to "AXISBN",
            "PNBSMS" to "PNBBNK", "BOBTXN" to "BOBMSG", "CANBNK" to "CAANBK",
            "UBISMS" to "UNIONB"
        )) assertEquals(Banks.key(first), Banks.key(second))
        assertEquals("HDFCXX", Banks.key("VM-HDFCXX-S"))
    }

    @Test fun parsesAccountEndingAndPerfectTense() {
        val sms = "INR 250.00 has been debited from A/c ending in 7700."
        assertEquals(BankSms.Entry(-25000, null, null), BankSms.parse(sms, "7700"))
    }

    @Test fun parsesIciciAvailableBalanceWithSeparatorAndLongMask() {
        val sms = "ICICI Bank Acct XX98761234 debited for Rs 50.00. Available Balance is INR 1,250.00."
        assertEquals("1234", BankSms.accountSuffix(sms))
        assertEquals(BankSms.Entry(-5000, 125000, null), BankSms.parse(sms, "1234"))
        val credit = "INR 250.00 credited to A/c XX1234. Avl Bal- INR 1,500.00."
        assertEquals(BankSms.Entry(25000, 150000, null), BankSms.parse(credit, "1234"))
    }

    @Test fun groupsBalanceInputWithoutChangingPaise() {
        assertEquals("12,34,567.89", BankSms.groupAmountInput("1234567.89"))
        assertEquals("-1,234.", BankSms.groupAmountInput("-1234."))
        assertEquals("1,234.00", BankSms.groupAmountInput("1,234.00"))
        assertEquals("123", BankSms.groupAmountInput("123"))
        assertEquals(123456789L, BankSms.paise(BankSms.groupAmountInput("1234567.89")))
    }

    @Test fun compactAmountsKeepOneDecimalRoundedDown() {
        for (wholeRupees in listOf(true, false)) {
            assertEquals("₹11.5k", BankSms.rupees(1159999, compact = true, wholeRupees = wholeRupees))
            assertEquals("₹11k", BankSms.rupees(1100000, compact = true, wholeRupees = wholeRupees))
            assertEquals("₹99.9k", BankSms.rupees(9999999, compact = true, wholeRupees = wholeRupees))
            assertEquals("₹1.2L", BankSms.rupees(12999900, compact = true, wholeRupees = wholeRupees))
            assertEquals("₹99.9L", BankSms.rupees(999999999, compact = true, wholeRupees = wholeRupees))
            assertEquals("₹1.9Cr", BankSms.rupees(1999999900, compact = true, wholeRupees = wholeRupees))
            assertEquals("-₹11.5k", BankSms.rupees(-1159999, compact = true, wholeRupees = wholeRupees))
        }
    }

    @Test fun ignoresUnsupportedOrAmbiguousAlerts() {
        for (sms in listOf(
            "OTP 777777 for A/c XX1234 debited by Rs.20",
            "A/c XX9999 debited by Rs.20",
            "A/c XX1234 debited by Rs.20 and credited by Rs.10",
            "A/c XX1234 Avl Bal Rs.800",
            "A/c XX1234 debited by Rs.20.123"
        )) assertNull(sms, BankSms.parse(sms, "1234"))
    }
}
