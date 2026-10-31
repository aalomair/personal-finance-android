package works.alomair.finance

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class SummaryTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private fun row(type: String, direction: String, amount: Long, currency: String, whenIso: String, category: String = "other") =
        FinanceTransaction(type = type, direction = direction, amountMinor = amount, currency = currency,
            occurredAt = Instant.parse(whenIso).toEpochMilli(), categoryKey = category)

    @Test fun monthlyTotalsAreExactCurrencySeparatedAndTransfersExcluded() {
        val rows = listOf(
            row("income", "in", 10000, "SAR", "2026-10-01T00:00:00Z"),
            row("expense", "out", 10, "SAR", "2026-10-02T00:00:00Z", "coffee"),
            row("expense", "out", 20, "SAR", "2026-10-03T00:00:00Z", "coffee"),
            row("expense", "out", 1000, "USD", "2026-10-03T00:00:00Z", "shopping"),
            row("transfer", "out", 5000, "SAR", "2026-10-04T00:00:00Z"),
            row("transfer", "in", 3000, "SAR", "2026-10-04T00:00:00Z"),
        )
        val totals = FinanceSummary.month(rows, YearMonth.of(2026, 10), zone)
        assertEquals(listOf("SAR", "USD"), totals.map { it.currency })
        val sar = totals.first()
        assertEquals(10000L, sar.income)
        assertEquals(30L, sar.expense)
        assertEquals(9970L, sar.net)
        assertEquals(3000L, sar.transferIn)
        assertEquals(5000L, sar.transferOut)
        assertEquals(30L, sar.byCategory["coffee"])
        assertEquals("1.0000", sar.categoryShare("coffee").toPlainString())
        assertEquals(-1000L, totals[1].net)
    }

    @Test fun boundariesUseHalfOpenLocalMonthsAndNavigationCrossesYear() {
        val oct = YearMonth.of(2026, 10)
        val rows = listOf(
            row("income", "in", 1, "JPY", "2026-09-30T20:59:59Z"),
            row("income", "in", 2, "JPY", "2026-09-30T21:00:00Z"),
            row("income", "in", 4, "JPY", "2026-10-31T20:59:59Z"),
            row("income", "in", 8, "JPY", "2026-10-31T21:00:00Z"),
        )
        assertEquals(6L, FinanceSummary.month(rows, oct, zone).single().income)
        assertEquals(YearMonth.of(2027, 1), FinanceSummary.next(YearMonth.of(2026, 12)))
        assertEquals(YearMonth.of(2025, 12), FinanceSummary.previous(YearMonth.of(2026, 1)))
        assertTrue(FinanceSummary.month(rows, YearMonth.of(2020, 1), zone).isEmpty())
    }

    @Test fun englishAndArabicSarFormattingUsesExactAmount() {
        val english = Money.format(123450, "SAR", Locale.forLanguageTag("en-SA"))
        val arabic = Money.format(123450, "SAR", Locale.forLanguageTag("ar-SA"))
        assertTrue(english, english.contains("1,234.50"))
        assertTrue(english, english.contains("SAR"))
        assertTrue(arabic, Money.normalize(arabic).contains("1,234.50"))
        assertTrue(arabic, arabic.contains("ر.س"))
        assertEquals("1.234", Money.decimal(1234, "KWD").toPlainString())
        assertEquals("123", Money.decimal(123, "JPY").toPlainString())
    }

    @Test fun categorySharesAndOverflowCannotSilentlyCorruptMoney() {
        val rows = listOf(
            row("expense", "out", 1, "SAR", "2026-10-01T00:00:00Z", "coffee"),
            row("expense", "out", 2, "SAR", "2026-10-01T00:00:00Z", "dining"),
        )
        val summary = FinanceSummary.month(rows, YearMonth.of(2026, 10), zone).single()
        assertEquals("0.3333", summary.categoryShare("coffee").toPlainString())
        assertEquals("0.6667", summary.categoryShare("dining").toPlainString())
        assertThrows(ArithmeticException::class.java) {
            FinanceSummary.month(listOf(rows[0].copy(amountMinor = Long.MAX_VALUE), rows[1]), YearMonth.of(2026, 10), zone)
        }
    }
}
