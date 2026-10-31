package works.alomair.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

// No balances, currency conversion, or cross-currency total exists in this contract.
data class CurrencySummary(
    val currency: String, val income: Long, val expense: Long,
    val transferIn: Long, val transferOut: Long, val byCategory: Map<String, Long>,
) {
    val net: Long get() = Math.subtractExact(income, expense)
    fun categoryShare(key: String): BigDecimal = if (expense == 0L) BigDecimal.ZERO.setScale(4)
        else BigDecimal.valueOf(byCategory[key] ?: 0L).divide(BigDecimal.valueOf(expense), 4, RoundingMode.HALF_UP)
}

object FinanceSummary {
    fun previous(month: YearMonth): YearMonth = month.minusMonths(1)
    fun next(month: YearMonth): YearMonth = month.plusMonths(1)
    fun month(rows: List<FinanceTransaction>, month: YearMonth, zone: ZoneId): List<CurrencySummary> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
        val selected = rows.filter { Instant.ofEpochMilli(it.occurredAt).let { time -> time >= start && time < end } }
        return selected.groupBy { it.currency }.toSortedMap(compareBy<String> { it != "SAR" }.thenBy { it }).map { (currency, transactions) ->
            fun total(type: String, direction: String): Long = transactions.filter { it.type == type && it.direction == direction }
                .fold(0L) { sum, row -> Math.addExact(sum, row.amountMinor) }
            val categories = transactions.filter { it.type == "expense" }.groupBy { it.categoryKey }
                .mapValues { (_, values) -> values.fold(0L) { sum, row -> Math.addExact(sum, row.amountMinor) } }
            CurrencySummary(currency, total("income", "in"), total("expense", "out"),
                total("transfer", "in"), total("transfer", "out"), categories)
        }
    }
}
