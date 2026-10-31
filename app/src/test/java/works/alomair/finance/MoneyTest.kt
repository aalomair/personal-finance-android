package works.alomair.finance

import org.junit.Assert.*
import org.junit.Test

class MoneyTest {
    @Test fun exactMinorUnitsRespectCurrencyExponent() {
        assertEquals(123450L, Money.minor("1,234.50", "SAR"))
        assertEquals(1234L, Money.minor("١٫٢٣٤", "KWD"))
        assertEquals(123L, Money.minor("123", "JPY"))
        assertEquals(10L, Money.minor("0.10", "SAR"))
        assertThrows(IllegalArgumentException::class.java) { Money.minor("1.001", "SAR") }
        assertThrows(IllegalArgumentException::class.java) { Money.minor("1.1", "JPY") }
        assertThrows(IllegalArgumentException::class.java) { Money.minor("NaN", "SAR") }
        assertThrows(IllegalArgumentException::class.java) { Money.minor("-1", "SAR") }
    }
}
