package com.promolandia.tvmagnet

import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class LaunchTest {
    @Test fun launcherAndClipboardDoNotCrashWithoutVideos() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        controller.pause().stop()
        Assert.assertNotNull(controller.get().title)
        controller.destroy()
    }
}
