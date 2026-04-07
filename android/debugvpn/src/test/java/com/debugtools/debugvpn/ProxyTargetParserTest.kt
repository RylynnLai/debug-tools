package com.debugtools.debugvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyTargetParserTest {
    @Test
    fun parseConnectTarget_withExplicitPort() {
        val result = ProxyTargetParser.parseConnectTarget("example.com:8443")
        assertEquals("example.com", result?.host)
        assertEquals(8443, result?.port)
    }

    @Test
    fun parseConnectTarget_withoutPort_defaults443() {
        val result = ProxyTargetParser.parseConnectTarget("example.com")
        assertEquals("example.com", result?.host)
        assertEquals(443, result?.port)
    }

    @Test
    fun parseConnectTarget_blankHost_returnsNull() {
        val result = ProxyTargetParser.parseConnectTarget(":443")
        assertNull(result)
    }
}

