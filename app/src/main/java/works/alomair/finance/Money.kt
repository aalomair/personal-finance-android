package works.alomair.finance

import java.math.BigDecimal
import java.util.Currency
import java.util.Locale

object Money {
    fun normalize(value: String): String = buildString {
        for (c in value) append(when (c) {
            in '٠'..'٩' -> '0' + (c - '٠')
            in '۰'..'۹' -> '0' + (c - '۰')
            '٫' -> '.'
            '٬' -> ','
            else -> c
        })
    }

    fun exponent(currency: String): Int {
        require(currency.matches(Regex("[A-Z]{3}"))) { "CURRENCY" }
        val digits = Currency.getInstance(currency).defaultFractionDigits
        require(digits in 0..3) { "CURRENCY" }
        return digits
    }

    fun minor(value: String, currency: String): Long {
        val normalized = normalize(value).trim()
        require(normalized.matches(Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?"))) { "AMOUNT" }
        val decimal = BigDecimal(normalized.replace(",", ""))
        try {
            return decimal.movePointRight(exponent(currency)).longValueExact().also {
                require(it > 0) { "AMOUNT" }
            }
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("AMOUNT_PRECISION_OR_RANGE")
        }
    }

    fun decimal(minor: Long, currency: String): BigDecimal = BigDecimal.valueOf(minor, exponent(currency))

    fun format(minor: Long, currency: String, locale: Locale): String =
        java.text.NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = Currency.getInstance(currency)
            minimumFractionDigits = exponent(currency)
            maximumFractionDigits = exponent(currency)
        }.format(decimal(minor, currency))
}
