package com.estrin217.terminal.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Test de integración que verifica la extracción/carga de la librería nativa.
 * Nota: Ejecutar en un dispositivo/emulador; puede fallar en entornos sin soporte nativo.
 */
@RunWith(AndroidJUnit4::class)
class NativeLibIntegrationTest {
    @Test
    fun ensureNativeLibLoads() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val loaded = TerminalCore.ensureLoaded(ctx)
        assertTrue("Native library should be loaded or installed", loaded)

        // Verifica que el archivo .so exista en el directorio nativo de la app
        val nativeLib = File(ctx.applicationInfo.nativeLibraryDir, "libterminal_core.so")
        assertTrue("Native library should exist in native lib dir", nativeLib.exists())
    }
}
