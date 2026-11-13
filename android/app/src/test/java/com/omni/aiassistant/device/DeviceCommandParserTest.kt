package com.omni.aiassistant.device

import org.junit.Assert.*
import org.junit.Test

class DeviceCommandParserTest {
    @Test fun requiresExplicitPrefix() {
        assertNull(DeviceCommandParser.parse("dial 12345"))
        assertNotNull(DeviceCommandParser.parse("/device dial 12345"))
    }

    @Test fun parsesPersianAndEnglishCommands() {
        val parsed = DeviceCommandParser.parse("گوشی: دوربین سپس نقشه شیراز")!!
        assertEquals(2, parsed.actions.size)
        assertTrue(parsed.actions[0] is DeviceAction.Camera)
        assertEquals("شیراز", (parsed.actions[1] as DeviceAction.Maps).query)
    }

    @Test fun limitsChainsToFour() {
        val parsed = DeviceCommandParser.parse("/device camera; gallery; contacts; info; camera")!!
        assertEquals(4, parsed.actions.size)
    }

    @Test fun validatesPhoneAndEmail() {        val dial = DeviceCommandParser.parse("/device dial +491234567")!!
        assertTrue(dial.actions.single() is DeviceAction.Dial)
        val invalid = DeviceCommandParser.parse("/device dial javascript:alert(1)")!!
        assertTrue(invalid.actions.isEmpty())
        val email = DeviceCommandParser.parse("/device email test@example.com subject")!!
        assertTrue(email.actions.single() is DeviceAction.Email)
    }

    @Test fun parsesVoiceStyleFindCommands() {
        val voice = DeviceCommandParser.parse("گوشی: برو فیلم godfather رو پیدا کن")!!
        val find = voice.actions.single() as DeviceAction.FindFile
        assertEquals("godfather", find.query)
        assertEquals(com.omni.aiassistant.core.device.MediaKind.VIDEO, find.kind)

        val verbFirst = DeviceCommandParser.parse("گوشی: پیدا کن فیلم godfather")!!
        assertEquals("godfather", (verbFirst.actions.single() as DeviceAction.FindFile).query)

        val music = DeviceCommandParser.parse("گوشی: آهنگ شجریان رو پیدا کن")!!
        val musicFind = music.actions.single() as DeviceAction.FindFile
        assertEquals("شجریان", musicFind.query)
        assertEquals(com.omni.aiassistant.core.device.MediaKind.AUDIO, musicFind.kind)

        val english = DeviceCommandParser.parse("/device find movie inception")!!
        val enFind = english.actions.single() as DeviceAction.FindFile
        assertEquals("inception", enFind.query)
        assertEquals(com.omni.aiassistant.core.device.MediaKind.VIDEO, enFind.kind)
    }

    @Test fun parsesPlayAndDownloadCommands() {
        val play = DeviceCommandParser.parse("گوشی: فیلم godfather رو پخش کن")!!
        assertEquals("godfather", (play.actions.single() as DeviceAction.PlayFile).query)

        val url = DeviceCommandParser.parse("/device download https://example.com/a.mp4")!!
        assertEquals("https://example.com/a.mp4", (url.actions.single() as DeviceAction.DownloadFile).url)

        val named = DeviceCommandParser.parse("گوشی: godfather رو دانلود کن")!!
        val namedDl = named.actions.single() as DeviceAction.DownloadFile
        assertEquals(null, namedDl.url)
        assertEquals("godfather", namedDl.query)
    }

    @Test fun deleteRequiresExplicitExactTarget() {
        val exact = DeviceCommandParser.parse("/device delete godfather 1972 bluray.mp4")!!
        assertEquals("godfather 1972 bluray.mp4", (exact.actions.single() as DeviceAction.DeleteFile).exactName)

        val voiceExact = DeviceCommandParser.parse("گوشی: godfather 1972 رو پاک کن")!!
        assertTrue(voiceExact.actions.single() is DeviceAction.DeleteFile)

        // Vague, wildcard, or missing targets produce NO action — nothing deletable by mistake.
        assertTrue(DeviceCommandParser.parse("گوشی: همه رو پاک کن")!!.actions.isEmpty())
        assertTrue(DeviceCommandParser.parse("گوشی: فیلم رو پاک کن")!!.actions.isEmpty())
        assertTrue(DeviceCommandParser.parse("گوشی: *.mp4 رو پاک کن")!!.actions.isEmpty())
        assertTrue(DeviceCommandParser.parse("/device delete ")!!.actions.isEmpty())
    }

    @Test fun fileActionsChainLikeAgentSteps() {
        val batch = DeviceCommandParser.parse("گوشی: فیلم godfather رو پیدا کن سپس godfather رو پخش کن")!!
        assertEquals(2, batch.actions.size)
        assertTrue(batch.actions[0] is DeviceAction.FindFile)
        assertTrue(batch.actions[1] is DeviceAction.PlayFile)
    }

    @Test fun wallpaperNeedsExplicitNounAndVerb() {
        val fa = DeviceCommandParser.parse("گوشی: اینو بک‌گراند کن")!!
        assertTrue(fa.actions.single() is DeviceAction.SetWallpaper)
        val fa2 = DeviceCommandParser.parse("گوشی: والپیپر گوشی بذار")!!
        assertTrue(fa2.actions.single() is DeviceAction.SetWallpaper)
        val en = DeviceCommandParser.parse("/device set as wallpaper")!!
        assertTrue(en.actions.single() is DeviceAction.SetWallpaper)
        // Bare nouns with no verb must not trigger anything.
        assertTrue(DeviceCommandParser.parse("گوشی: والپیپر")!!.actions.isEmpty())
        assertTrue(DeviceCommandParser.parse("گوشی: بک‌گراند")!!.actions.isEmpty())
    }
}
