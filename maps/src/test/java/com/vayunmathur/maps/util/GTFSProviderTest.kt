package com.vayunmathur.maps.util
import com.vayunmathur.maps.data.GTFSProvider

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GTFS `routes.txt` routinely quotes `route_long_name`, so a naive
 * `split(",")` shifts every column after it and the colour lookup silently
 * returns the wrong field.
 */
class GTFSProviderTest {

    @Test
    fun `splits a plain row`() {
        assertEquals(
            listOf("R1", "N", "Judah", "0", "0000FF"),
            GTFSProvider.parseCsvLine("R1,N,Judah,0,0000FF"),
        )
    }

    @Test
    fun `keeps a comma inside a quoted field`() {
        val fields = GTFSProvider.parseCsvLine("R1,N,\"Judah, Ocean Beach\",0,0000FF")
        assertEquals(5, fields.size)
        assertEquals("Judah, Ocean Beach", fields[2])
        // The columns after the quoted field must not shift.
        assertEquals("0000FF", fields[4])
    }

    @Test
    fun `unescapes a doubled quote`() {
        assertEquals(
            listOf("R1", "The \"L\" Line", "3"),
            GTFSProvider.parseCsvLine("R1,\"The \"\"L\"\" Line\",3"),
        )
    }

    @Test
    fun `keeps empty fields including trailing ones`() {
        assertEquals(listOf("R1", "", "3", ""), GTFSProvider.parseCsvLine("R1,,3,"))
    }

    @Test
    fun `strips a utf8 bom from the header`() {
        assertEquals(
            listOf("route_id", "route_short_name"),
            GTFSProvider.parseCsvLine("\uFEFFroute_id,route_short_name"),
        )
    }

    @Test
    fun `strips a trailing carriage return from a crlf file`() {
        assertEquals(listOf("R1", "0000FF"), GTFSProvider.parseCsvLine("R1,0000FF\r"))
    }
}
