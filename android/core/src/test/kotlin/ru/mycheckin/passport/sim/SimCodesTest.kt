package ru.mycheckin.passport.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimCodesTest {
    @Test fun barcodeIccid() {
        assertEquals(SimCode(SimCode.Kind.ICCID, "89701015388529130733"), SimCodes.fromBarcode("89701015388529130733"))
        assertEquals(SimCode(SimCode.Kind.ICCID, "8970101538852913073"), SimCodes.fromBarcode("8970101538852913073F"))
        assertEquals(SimCode(SimCode.Kind.PHONE, "79896296939"), SimCodes.fromBarcode("+7 989 629-69-39"))
        assertNull(SimCodes.fromBarcode("4601234567890"))   // штрихкод товара — не SIM
    }

    @Test fun textOnSimPlastic() {
        val text = """
            МТС
            ICCID 8970 1015 3885 2913 0733
            Ваш номер: +7 (989) 629-69-39
            PIN 1234 PUK 12345678
        """.trimIndent()
        val found = SimCodes.fromText(text)
        assertTrue(SimCode(SimCode.Kind.ICCID, "89701015388529130733") in found)
        assertTrue(SimCode(SimCode.Kind.PHONE, "79896296939") in found)
        assertEquals(2, found.size)   // PIN/PUK не принимаются за номер
    }

    @Test fun phoneFormats() {
        assertEquals(listOf("76610047024"), SimCodes.fromText("7 661 004-70-24").map { it.value })
        assertEquals(listOf("79885701906"), SimCodes.fromText("8-988-570-19-06").map { it.value })
        assertEquals(listOf("79885701906"), SimCodes.fromText("988 570 19 06").map { it.value })
    }

    @Test fun iccidWithOrWithoutCheckDigit() {
        assertTrue(SimCodes.sameIccid("8970101538852913073", "89701015388529130733"))
        assertTrue(SimCodes.sameIccid("89701015388529130733", "89701015388529130733"))
        assertTrue(!SimCodes.sameIccid("89701015388529130734", "89701015388529130733"))
    }
}
