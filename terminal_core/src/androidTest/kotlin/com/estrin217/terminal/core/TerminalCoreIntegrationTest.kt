package com.estrin217.terminal.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TerminalCoreIntegrationTest {

    @Test
    fun testNativeLibrariesAreInLibDir() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val prootFile = TerminalConfig.getPRootExecutable(appContext)
        
        assertNotNull("PRoot executable file reference should not be null", prootFile)
        val path = prootFile.absolutePath
        assertTrue("PRoot should be located inside a native /lib directory structure, actual path: $path", path.contains("/lib"))
    }

    @Test
    fun testTerminalCoreJniLoadsAndRunsSuccessfully() {
        val terminalCore = TerminalCore()
        val result = terminalCore.stringFromJNI()
        assertNotNull("JNI string result should not be null", result)
        assertTrue("JNI string should match C++ output format", result.contains("Hello from C++"))
    }
}
