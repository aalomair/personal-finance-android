package works.alomair.finance

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ValidationTest {
    private val time = Instant.parse("2026-10-30T09:30:00Z").toEpochMilli()
    private fun message(body: String, sender: String = "SyntheticBank") = PendingMessage(sender = sender,
        body = body, receivedAt = time, bodyHash = "synthetic", queuedProviderId = "GO")
    private fun draft(amount: String = "12.50", currency: String = "SAR", bank: String = "SyntheticBank") = JSONObject()
        .put("is_transaction", true).put("type", "expense").put("direction", "out").put("amount", amount)
        .put("currency", currency).put("occurred_at", JSONObject.NULL).put("merchant", "Test Cafe")
        .put("bank", bank).put("account", "4321").put("category", "coffee").put("reference", "SYN12345").put("confidence", "0.95")
    private fun validate(msg: PendingMessage, json: JSONObject) = ExtractionValidator.validate(json.toString(), msg, SeedCategories.all.map { it.key })

    @Test fun englishAndArabicAmountsAreGroundedAcrossBanks() {
        for (bank in listOf("SyntheticBank", "Test Bank A", "demo-bank", "SampleBank-B")) {
            val sms = message("Purchase SAR 12.50 at Test Cafe card 4321 ref SYN12345", bank)
            val result = validate(sms, draft(bank = bank))
            assertTrue("${result.errorCode}", result.transaction != null)
            assertEquals(1250L, result.transaction!!.amountMinor)
        }
        val ar = message("شراء بمبلغ ١٢٫٥٠ ر.س لدى Test Cafe بطاقة ٤٣٢١ مرجع SYN12345")
        assertNotNull(validate(ar, draft()).transaction)
    }

    @Test fun hallucinationsAmbiguityAndLowConfidenceGoToReview() {
        val sms = message("Purchase SAR 12.50 at Test Cafe card 4321 ref SYN12345")
        assertEquals("AMOUNT_NOT_IN_SMS", validate(sms, draft("99.99")).errorCode)
        assertEquals("CURRENCY_MISMATCH", validate(sms, draft(currency = "USD")).errorCode)
        assertEquals("LOW_CONFIDENCE", validate(sms, draft().put("confidence", "0.79")).errorCode)
        assertEquals("ACCOUNT_NOT_IN_SMS", validate(sms, draft().put("account", "9999")).errorCode)
        assertEquals("REFERENCE_NOT_IN_SMS", validate(sms, draft().put("reference", "SYN00000")).errorCode)
        assertEquals("ENUM", validate(sms, draft().put("type", "expense").put("direction", "in")).errorCode)
        assertEquals("CATEGORY", validate(sms, draft().put("category", "invented")).errorCode)
        assertEquals("MERCHANT_NOT_IN_SMS", validate(sms, draft().put("merchant", "Imaginary Shop")).errorCode)
        assertEquals("AMBIGUOUS_AMOUNTS", validate(message(sms.body + " balance SAR 2000.00"), draft()).errorCode)
        assertEquals("NOT_TRANSACTION", validate(sms, draft().put("is_transaction", false)).errorCode)
        assertEquals("INCOMPLETE", validate(sms, draft().removeField("type")).errorCode)
    }

    @Test fun datesMustBeGroundedAndSaneOtherwiseReceivedTimeIsUsed() {
        val sms = message("Purchase SAR 12.50 at Test Cafe card 4321 ref SYN12345")
        assertEquals(time, validate(sms, draft()).transaction!!.occurredAt)
        assertEquals("DATE_NOT_IN_SMS", validate(sms, draft().put("occurred_at", "2026-10-30T12:30:00+03:00")).errorCode)
        assertNotNull(validate(message(sms.body + " 2026-10-30 12:30"), draft().put("occurred_at", "2026-10-30T12:30:00+03:00")).transaction)
        assertEquals("DATE_RANGE", validate(message(sms.body + " 2020-01-01"), draft().put("occurred_at", "2020-01-01")).errorCode)
        assertEquals("DATE", validate(sms, draft().put("occurred_at", "not a date")).errorCode)
    }

    @Test fun fencesAreAcceptedMalformedJsonIsRetryableAndOtpFiltered() {
        val sms = message("Purchase SAR 12.50 at Test Cafe card 4321 ref SYN12345")
        assertNotNull(ExtractionValidator.validate("```json\n${draft()}\n```", sms, listOf("coffee")).transaction)
        assertThrows(ApiFailure::class.java) { ExtractionValidator.validate("{broken", sms, listOf("coffee")) }
        assertFalse(SmsFilter.isCandidate("OTP 123456 for purchase SAR 12.50. Do not share."))
        assertFalse(SmsFilter.isCandidate("رمز التحقق ١٢٣٤٥٦ لعملية شراء بمبلغ ١٢ ر.س"))
        assertFalse(SmsFilter.isCandidate("Your monthly news is ready"))
        assertTrue(SmsFilter.isCandidate(sms.body))
        assertTrue(SmsFilter.isCandidate("حوالة واردة بمبلغ ١٠٠ ريال"))
        assertTrue(SmsFilter.isCandidate("Credit USD 100.00"))
    }

    private fun JSONObject.removeField(key: String): JSONObject { remove(key); return this }
}
