package com.omni.aiassistant.qa

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omni.aiassistant.device.media.MediaPlaybackService
import com.omni.aiassistant.device.tools.ToolRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Music on the phone.
 *
 * The test writes a real audio file into the shared music folder, asks the
 * assistant to find it and to play it, and then checks that the playback
 * service is really running with the right track - the same service that
 * keeps playing after the app is closed.
 */
@RunWith(AndroidJUnit4::class)
class QaV2MediaPlaybackTest {

    private lateinit var context: Context
    private lateinit var registry: ToolRegistry
    private var track: File? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = GlobalContext.get().get()
        grantAllFilesAccess()
        track = QaWavFixture.write("omar-test-tone.wav")
    }

    @After
    fun tearDown() {
        runCatching { MediaPlaybackService.command(context, MediaPlaybackService.ACTION_STOP) }
        track?.delete()
    }

    @Test
    fun theAssistantFindsATrackAndReallyPlaysIt() = runBlocking {
        val search = registry.tool("media_search")?.execute(
            buildJsonObject {
                put("query", JsonPrimitive("omar-test-tone"))
                put("kind", JsonPrimitive("audio"))
            },
        )
        assertTrue("media_search failed: $search", search is com.omni.aiassistant.core.agent.ToolOutcome.Success)
        val searchText = search.toString()
        assertTrue("the track was not found: $searchText", searchText.contains("omar-test-tone"))

        val play = registry.tool("media_play")?.execute(
            buildJsonObject { put("query", JsonPrimitive("omar-test-tone.wav")) },
        )
        assertTrue("media_play failed: $play", play is com.omni.aiassistant.core.agent.ToolOutcome.Success)

        // The service owns a MediaPlayer; the state flow is what the chat
        // timeline and the notification read.
        val deadline = System.currentTimeMillis() + 15_000
        var state = MediaPlaybackService.state.value
        while (!state.playing && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            state = MediaPlaybackService.state.value
        }
        assertTrue("the playback never started: $state", state.playing)
        assertTrue("the wrong track is playing: $state", state.title.contains("omar-test-tone"))

        val stopped = registry.tool("media_stop")?.execute(buildJsonObject { })
        assertTrue("media_stop failed: $stopped", stopped is com.omni.aiassistant.core.agent.ToolOutcome.Success)
    }

    private fun grantAllFilesAccess() {
        runCatching {
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().uiAutomation
                .executeShellCommand(
                    "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow",
                )
                .use { }
        }
    }
}
