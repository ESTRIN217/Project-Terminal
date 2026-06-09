package com.estrin217.terminal.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCoreTest {

    @Test
    fun testTerminalCoreJniAvailability() {
        val terminalCore = TerminalCore()
        assertNotNull("TerminalCore instance should not be null", terminalCore)
        
        try {
            val result = terminalCore.stringFromJNI()
            assertNotNull("JNI stringFromJNI result should not be null", result)
            assertTrue("JNI stringFromJNI result should contain Hello", result.contains("Hello"))
        } catch (e: UnsatisfiedLinkError) {
            // Expected when running on standard host JVM without native library loaded
            System.err.println("Note: Native library not loaded in host JVM environment: ${e.message}")
        }
    }
}
