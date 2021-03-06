package flhan.de.financemanager.common.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.DecimalFormat

class CurrencyStringTest {

    private val decimalSeparator by lazy {
        val format = DecimalFormat.getInstance() as DecimalFormat
        format.decimalFormatSymbols.decimalSeparator
    }

    private val currencySymbol by lazy {
        (DecimalFormat.getInstance() as DecimalFormat).currency.symbol
    }

    @Test
    fun displayStringWithInitialDouble() {
        val initialValue = 123.41
        val expected = "123" + decimalSeparator + "41 " + currencySymbol
        val currencyString = CurrencyString(initialValue)

        val result = currencyString.displayString

        assertEquals(expected, result)
    }

    @Test
    fun displayStringWithInitialString() {
        val initialValue = "123.41"
        val expected = "123" + decimalSeparator + "41 " + currencySymbol
        val currencyString = CurrencyString(initialValue)

        val result = currencyString.displayString

        assertEquals(expected, result)
    }

    @Test
    fun displayStringWithNullValue() {
        val expected = "0" + decimalSeparator + "00 " + currencySymbol
        val currencyString = CurrencyString(null)

        val result = currencyString.displayString

        assertEquals(expected, result)
    }

    @Test
    fun testAmountByString() {
        val initialValue = "123.41"
        val expected = 123.41
        val currencyString = CurrencyString(initialValue)

        val result = currencyString.amount

        assertEquals(expected, result)
    }

    @Test
    fun testAmountByDouble() {
        val initialValue = 123.41
        val currencyString = CurrencyString(initialValue)

        val result = currencyString.amount

        assertEquals(initialValue, result)
    }

    @Test
    fun testAmountWhenEmpty() {
        val expected = 0.0
        val currencyString = CurrencyString()

        val result = currencyString.amount

        assertEquals(expected, result)
    }

    @Test
    fun testAmountTwoDecimalPlaces() {
        val initialValue = 123.41
        val currencyString = CurrencyString(initialValue)

        val result = currencyString.amount

        assertEquals(initialValue, result)
    }

    @Test
    fun testAmountOneDecimalPlaces() {
        val initialValue = 123.4
        val currencyString = CurrencyString(initialValue)

        val result = currencyString.amount

        assertEquals(initialValue, result)
    }

    @Test
    fun testAmountThreeDecimalPlaces() {
        val initialValue = 123.456
        val expected = 123.46
        val currencyString = CurrencyString(initialValue)

        val result = currencyString.amount

        assertEquals(expected, result)
    }

    @Test
    fun testDisplayStringThreeDecimalPlaces() {
        val initialValue = 123.456
        val expected = "123" + decimalSeparator + "46 " + currencySymbol
        val currencyString = CurrencyString(initialValue)

        val result = currencyString.displayString

        assertEquals(expected, result)
    }

    @Test
    fun testAmountNoDecimalPlaces() {
        val initialValue = "123".toDouble()
        val currencyString = CurrencyString(initialValue)

        val result = currencyString.amount

        assertEquals(initialValue, result)
    }
}