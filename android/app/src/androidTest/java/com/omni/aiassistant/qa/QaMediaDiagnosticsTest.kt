package com.omni.aiassistant.qa

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The audio fixture must be playable by the platform itself.
 *
 * When playback fails it is easy to blame the player. This test proves the
 * file first: the container must be recognised and MediaPlayer must prepare
 * it without a listener. If this test is green and playback still fails, the
 * fault is in our code; if it is red, the fixture is broken - which is exactly
 * what happened once with a shortened WAV header.
 */
@RunWith(AndroidJUnit4::class)
class QaMediaDiagnosticsTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        runCatching {
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().uiAutomation
                .executeShellCommand(
                    "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
                )
                .use { }
        }
        file = QaWavFixture.write("omar-diagnostic.wav")
    }

    @After
    fun tearDown() {
        runCatching { file.delete() }
    }

    @Test
    fun thePlatformRecognisesAndPreparesTheFixture() {
        assertTrue("the fixture was not written", file.isFile && file.length() > 44)

        val head = java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(12)
            input.read(buffer)
            buffer
        }
        assertEquals("RIFF", String(head, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(head, 8, 4, Charsets.US_ASCII))

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals("the container did not yield one track", 1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME)
            assertTrue("unexpected track mime: $mime", mime?.startsWith("audio/") == true)
        } catch (error: Exception) {
            fail("the platform cannot parse the fixture: $error")
        } finally {
            extractor.release()
        }

        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            assertTrue("the prepared duration is not positive", player.duration > 0)
        } catch (error: Exception) {
            fail("MediaPlayer cannot prepare the fixture: $error")
        } finally {
            runCatching { player.release() }
        }
    }
}
