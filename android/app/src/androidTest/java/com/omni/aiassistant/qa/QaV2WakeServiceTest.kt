package com.omni.aiassistant.qa

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omni.aiassistant.core.wake.WakeCommandBus
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.device.wake.ArmanWakeService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * The wake-word listener.
 *
 * The emulator has no Persian speech pack, so the test uses the English one
 * that every image ships with. What is verified is the behaviour that matters:
 * the microphone foreground service really starts, it keeps running, it
 * publishes a recognised command to the shell, and it stops again on request.
 */
@RunWith(AndroidJUnit4::class)
class QaV2WakeServiceTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        settings = GlobalContext.get().get()
        // A microphone foreground service is only allowed with the runtime
        // permission in place. The settings screen asks for it; the test does
        // the same thing through the shell.
        grantPermission(android.Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            grantPermission(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        settings.update {
            it.copy(
                wakeWordEnabled = true,
                wakeWordPhrase = "Arman",
                // The emulator only ships an English speech pack.
                voiceLanguageTag = "en-US",
            )
        }
    }

    private fun grantPermission(permission: String) {
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} $permission")
            .use { }
    }

    @Test
    fun theForegroundServiceStartsKeepsRunningAndStopsAgain() {
        ArmanWakeService.start(context)
        waitUntil("the wake service never appeared") { serviceRunning() }

        // The service must survive the first recogniser cycle: it listens
        // continuously for the wake word instead of giving up after one try.
        Thread.sleep(2_500)
        assertTrue("the wake service stopped while it should be listening", serviceRunning())

        ArmanWakeService.stop(context)
        waitUntil("the wake service never stopped") { !serviceRunning() }
    }

    @Test
    fun aRecognisedCommandIsHandedToTheShell() {
        WakeCommandBus.publish("check the battery")
        val command = WakeCommandBus.commands.value
        assertNotNull("the command was not published", command)
        assertEquals("check the battery", command?.text)
        WakeCommandBus.consume()
        assertEquals(null, WakeCommandBus.commands.value)
    }

    private fun serviceRunning(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == ArmanWakeService::class.java.name }
    }

    private fun waitUntil(message: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        throw AssertionError(message)
    }
}
