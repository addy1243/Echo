package dev.echo.app

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the OS-facing DreamService contract without changing system dream settings. */
class EchoDreamServiceManifestTest {
    @Test fun dreamServiceIsBoundToAndroidAndHasDreamMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, EchoDreamService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertTrue(info.exported)
        assertEquals("android.permission.BIND_DREAM_SERVICE", info.permission)
        assertNotNull(info.metaData?.getInt("android.service.dream", 0)?.takeIf { it != 0 })
    }
}
