package com.omni.aiassistant.device

import com.omni.aiassistant.core.device.MediaKind
import org.junit.Assert.*
import org.junit.Test

/**
 * Grammar-level edge cases for [DeviceCommandParser]: prefixes, politeness,
 * verb-last forms, chaining, and the wallpaper noun+verb gate.
 *
 * These tests document the current production contract only.
 */
class DeviceCommandParserSyntaxTest {
    private fun actions(raw: String): List<DeviceAction> {
        val parsed = DeviceCommandParser.parse(raw)
        assertNotNull("Expected the parser to accept: $raw", parsed)
        return parsed!!.actions
    }

    private fun assertNoActions(raw: String) {
        assertTrue("Expected no action for: $raw", actions(raw).isEmpty())
    }

    @Test fun requiresSupportedPrefixAndHandlesBlankCommands() {
        assertNull(DeviceCommandParser.parse("camera"))
        assertNull(DeviceCommandParser.parse("device camera"))
        assertNull(DeviceCommandParser.parse("گوشی دوربین"))
        assertNoActions("/device")
        assertNoActions("device:")
        assertNoActions("گوشی:")
        assertTrue(actions("  /DeViCe   camera ").single() is DeviceAction.Camera)
        assertTrue(actions("DEVICE: GALLERY").single() is DeviceAction.Gallery)
        assertTrue(actions("گوشی: مخاطبین").single() is DeviceAction.Contacts)
    }

    @Test fun parsesExplicitEnglishAndPersianCommandWords() {
        assertTrue(actions("/device camera").single() is DeviceAction.Camera)
        assertTrue(actions("/device gallery").single() is DeviceAction.Gallery)
        assertTrue(actions("/device contacts").single() is DeviceAction.Contacts)
        assertTrue(actions("/device settings").single() is DeviceAction.AppSettings)
        assertTrue(actions("/device app settings").single() is DeviceAction.AppSettings)
        assertTrue(actions("/device info").single() is DeviceAction.DeviceInfo)
        assertTrue(actions("/device device info").single() is DeviceAction.DeviceInfo)
        assertTrue(actions("گوشی: دوربین").single() is DeviceAction.Camera)
        assertTrue(actions("گوشی: گالری").single() is DeviceAction.Gallery)
        assertTrue(actions("گوشی: تصاویر").single() is DeviceAction.Gallery)
        assertTrue(actions("گوشی: تنظیمات").single() is DeviceAction.AppSettings)
        assertTrue(actions("گوشی: اطلاعات دستگاه").single() is DeviceAction.DeviceInfo)
        assertTrue(actions("گوشی: اطلاعات گوشی").single() is DeviceAction.DeviceInfo)
    }

    @Test fun stripsPoliteAndVoicePrefixes() {
        assertTrue(actions("گوشی: لطفا دوربین").single() is DeviceAction.Camera)
        assertTrue(actions("گوشی: برو گالری").single() is DeviceAction.Gallery)

        val find = actions("گوشی: لطفا فیلم godfather رو پیدا کن").single()
            as DeviceAction.FindFile
        assertEquals("godfather", find.query)
        assertEquals(MediaKind.VIDEO, find.kind)

        val play = actions("گوشی: برو آهنگ شجریان رو پخش کن").single()
            as DeviceAction.PlayFile
        assertEquals("شجریان", play.query)
        assertEquals(MediaKind.AUDIO, play.kind)
    }

    @Test fun parsesEveryPersianVerbLastVariant() {
        val findSuffixes = listOf(
            "رو پیدا کن", "را پیدا کن", "رو بگرد", "را بگرد",
            "رو جستجو کن", "را جستجو کن", "رو نشون بده", "را نمایش بده",
        )
        for (suffix in findSuffixes) {
            val action = actions("گوشی: godfather $suffix").single()
            assertTrue("suffix=$suffix produced $action", action is DeviceAction.FindFile)
            assertEquals("godfather", (action as DeviceAction.FindFile).query)
        }

        val playSuffixes = listOf(
            "رو پخش کن", "را پخش کن", "رو اجرا کن", "را اجرا کن", "رو باز کن", "را باز کن",
        )
        for (suffix in playSuffixes) {
            val action = actions("گوشی: godfather $suffix").single()
            assertTrue("suffix=$suffix produced $action", action is DeviceAction.PlayFile)
            assertEquals("godfather", (action as DeviceAction.PlayFile).query)
        }

        for (suffix in listOf("رو دانلود کن", "را دانلود کن")) {
            val action = actions("گوشی: godfather $suffix").single()
            assertTrue("suffix=$suffix produced $action", action is DeviceAction.DownloadFile)
            val download = action as DeviceAction.DownloadFile
            assertNull(download.url)
            assertEquals("godfather", download.query)
        }

        for (suffix in listOf("رو پاک کن", "را پاک کن", "رو حذف کن", "را حذف کن")) {
            val action = actions("گوشی: godfather $suffix").single()
            assertTrue("suffix=$suffix produced $action", action is DeviceAction.DeleteFile)
            assertEquals("godfather", (action as DeviceAction.DeleteFile).exactName)
        }
    }

    @Test fun parsesChainsAndCapsAtFourActions() {
        val mixed = actions("/device Camera THEN Gallery; contacts سپس info")
        assertEquals(4, mixed.size)
        assertTrue(mixed[0] is DeviceAction.Camera)
        assertTrue(mixed[1] is DeviceAction.Gallery)
        assertTrue(mixed[2] is DeviceAction.Contacts)
        assertTrue(mixed[3] is DeviceAction.DeviceInfo)

        val five = actions("/device camera; gallery; contacts; info; settings")
        assertEquals(4, five.size)
        assertTrue(five.last() is DeviceAction.DeviceInfo)

        // The four-slot limit applies to raw parts, so an invalid slot consumes one.
        val invalidSlot = actions("/device nonsense; camera; gallery; contacts; info")
        assertEquals(3, invalidSlot.size)
        assertTrue(invalidSlot[0] is DeviceAction.Camera)
        assertTrue(invalidSlot[1] is DeviceAction.Gallery)
        assertTrue(invalidSlot[2] is DeviceAction.Contacts)

        val fileChain = actions("گوشی: فیلم godfather رو پیدا کن سپس godfather رو پخش کن")
        assertEquals(2, fileChain.size)
        assertTrue(fileChain[0] is DeviceAction.FindFile)
        assertTrue(fileChain[1] is DeviceAction.PlayFile)
    }

    @Test fun wallpaperNeedsBothNounAndVerb() {
        val accepted = listOf(
            "گوشی: اینو بک‌گراند کن",
            "گوشی: این عکس رو والپیپر کن",
            "گوشی: پس‌زمینه بذار",
            "گوشی: بک گراند بکن",
            "/device set as wallpaper",
            "/device make background",
            "device: background set",
        )
        accepted.forEach { raw ->
            val action = actions(raw).single()
            assertTrue("Expected wallpaper for: $raw, got $action", action is DeviceAction.SetWallpaper)
        }

        listOf(
            "گوشی: والپیپر",
            "گوشی: بک‌گراند",
            "گوشی: پس‌زمینه",
            "/device wallpaper",
            "/device background",
            "گوشی: والپیپر قشنگه",
        ).forEach(::assertNoActions)
    }
}
