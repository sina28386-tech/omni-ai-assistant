package com.omni.aiassistant.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileAgentDeviceTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var runner: FileAgentRunner

    private fun exec(
        actions: List<com.omni.aiassistant.device.DeviceAction>,
        confirmedDeleteName: String? = null,
    ): List<FileOutcome> = runBlocking { runner.run(actions, confirmedDeleteName) }

    private val targetName = "godfather 1972 bluray.mp4"
    private val decoyName = "godfather part 2.mkv"

    @Before fun seedManyFiles() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "fileAgentBench")
        root.deleteRecursively()
        root.mkdirs()
        File(root, targetName).createNewFile()
        File(root, decoyName).createNewFile()
        File(root, "godfather theme.mp3").createNewFile()
        for (i in 0 until 500) {
            File(root, "clip %04d.mp4".format(i)).createNewFile()
            File(root, "شبح قسمت %03d.mp4".format(i)).createNewFile()
            if (i % 2 == 0) File(root, "song %04d.mp3".format(i)).createNewFile()
        }
        // ~1253 files total
        runner = FileAgentRunner(context, FileIndexProvider(listOf(root)))
    }

    @Test fun searchAcrossManyFilesIsFastAndExact() {
        val provider = FileIndexProvider(listOf(root))
        val t0 = System.nanoTime()
        val records = provider.snapshot()
        assertTrue("expected 1000+ files, got ${records.size}", records.size >= 1000)
        val index = com.omni.aiassistant.core.device.FileSearch.buildIndex(records)
        val hits = com.omni.aiassistant.core.device.FileSearch.search(
            index, "godfather", com.omni.aiassistant.core.device.MediaKind.VIDEO,
        )
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("walk+index+search too slow: ${ms}ms", ms < 8_000)
        assertTrue(hits.isNotEmpty())
        assertEquals(targetName, hits[0].record.name)
    }

    @Test fun persianQueryFindsPersianFiles() {
        val provider = FileIndexProvider(listOf(root))
        val index = com.omni.aiassistant.core.device.FileSearch.buildIndex(provider.snapshot())
        val hits = com.omni.aiassistant.core.device.FileSearch.search(index, "شبح")
        assertEquals(10, hits.size)
        assertTrue(hits.all { it.record.name.startsWith("شبح") })
    }

    @Test fun deleteGateNeverDeletesByMistake() {
        val before = root.listFiles()!!.size

        // 1. vague target → Blocked, nothing touched
        var out = exec(listOf(com.omni.aiassistant.device.DeviceAction.DeleteFile("همه")))
        assertTrue(out.single() is FileOutcome.Blocked)
        // 2. ambiguous short name → Blocked (two godfather videos match)
        out = exec(listOf(com.omni.aiassistant.device.DeviceAction.DeleteFile("godfather")))
        assertTrue(out.single() is FileOutcome.Blocked)
        // 3. exact name without confirmation → DeleteConfirm only, file intact
        out = exec(listOf(com.omni.aiassistant.device.DeviceAction.DeleteFile(targetName)))
        val ask = out.single()
        assertTrue(ask is FileOutcome.DeleteConfirm)
        assertTrue(File(root, targetName).exists())
        // 4. exact name WITH the precise confirmation → only the target goes
        out = exec(
            listOf(com.omni.aiassistant.device.DeviceAction.DeleteFile(targetName)),
            confirmedDeleteName = targetName,
        )
        assertTrue(out.single() is FileOutcome.Deleted)
        assertFalse(File(root, targetName).exists())
        assertTrue("decoy must survive", File(root, decoyName).exists())
        assertEquals(before - 1, root.listFiles()!!.size)
    }

    @Test fun batchStopsAtFirstAttentionStep() {
        val out = exec(
            listOf(
                com.omni.aiassistant.device.DeviceAction.FindFile("godfather", com.omni.aiassistant.core.device.MediaKind.ANY),
                // unconfirmed delete must stop the batch: the third step never runs
                com.omni.aiassistant.device.DeviceAction.DeleteFile(targetName),
                com.omni.aiassistant.device.DeviceAction.FindFile("inception", com.omni.aiassistant.core.device.MediaKind.ANY),
            ),
        )
        assertEquals(2, out.size)
        assertTrue(out[0] is FileOutcome.Searched)
        assertTrue(out[1] is FileOutcome.DeleteConfirm)
        assertTrue("file must survive unconfirmed batch delete", File(root, targetName).exists())
    }

    @Test fun downloadsSmallFileWithSystemDownloader() {
        assumeTrue("device offline, skipping download test", isOnline())
        val out = exec(
            listOf(
                com.omni.aiassistant.device.DeviceAction.DownloadFile(
                    url = "https://raw.githubusercontent.com/github/gitignore/main/Android.gitignore",
                    query = null,
                ),
            ),
        )
        val done = out.single()
        assertTrue("expected Downloaded, got $done", done is FileOutcome.Downloaded)
        val name = (done as FileOutcome.Downloaded).name
        val dest = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), name)
        val deadline = System.currentTimeMillis() + 45_000
        while (!dest.exists() && System.currentTimeMillis() < deadline) Thread.sleep(1_000)
        assertTrue("downloaded file missing: ${dest.absolutePath}", dest.exists() && dest.length() > 0)
    }

    @Test fun wallpaperSetsFromRealImageFile() {
        val img = File(root, "wallpaper-test.png")
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.MAGENTA)
        FileOutputStream(img).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        // No path bound → honest failure, never a guess.
        val missing = exec(listOf(com.omni.aiassistant.device.DeviceAction.SetWallpaper("test")))
        assertTrue(missing.single() is FileOutcome.Failed)
        val out = exec(
            listOf(com.omni.aiassistant.device.DeviceAction.SetWallpaper("test", img.absolutePath)),
        )
        val done = out.single()
        assertTrue("expected WallpaperSet, got $done", done is FileOutcome.WallpaperSet)
    }

    private fun isOnline(): Boolean = try {
        val url = java.net.URL("https://raw.githubusercontent.com/github/gitignore/main/README.md")
        (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "HEAD"
        }.responseCode < 500
    } catch (e: Exception) {
        false
    }
}
