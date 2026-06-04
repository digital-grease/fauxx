package com.fauxx.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the foreground-service type in the *merged* manifest of whichever flavor is under test.
 * [android.app.Service.startForeground] throws when the runtime type doesn't match what the
 * manifest declares, so a flavor that loses or changes `foregroundServiceType` would crash the
 * engine at start — exactly the kind of per-flavor manifest drift a source grep can miss. Both
 * the play and full flavors must declare `specialUse`.
 */
@RunWith(AndroidJUnit4::class)
class ManifestFgsTypeTest {

    @Test
    fun phantomForegroundService_declaresSpecialUseType() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val component = ComponentName(context, PhantomForegroundService::class.java)

        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)

        assertEquals(
            "PhantomForegroundService must declare foregroundServiceType=specialUse in the merged " +
                "manifest (was ${info.foregroundServiceType})",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            info.foregroundServiceType,
        )
    }
}
