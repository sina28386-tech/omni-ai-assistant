package com.omni.aiassistant.device

import com.omni.aiassistant.core.device.MediaKind
import org.junit.Assert.*
import org.junit.Test

/**
 * Target-validation and payload-shape edge cases for [DeviceCommandParser].
 * Complements the grammar tests; no production behavior is changed here.
 */
class DeviceCommandParserValidationTest {
    private fun actions(raw: String): List<DeviceAction> {
        val parsed = DeviceCommandParser.parse(raw)
        assertNotNull("Expected the parser to accept: $raw", parsed)
        return parsed!!.actions
    }

    private fun assertNoActions(raw: String) {
        assertTrue("Expected no action for: $raw", actions(raw).isEmpty())
    }

    @Test fun deleteRejectsVagueWildcardAndTraversalTargets() {
        val rejected = listOf(
            "/device delete all",
            "device: delete everything",
            "/device delete files",
            "/device delete فایل ها",
            "/device delete فایلها",
            "/device delete همه",
            "/device delete تمام",
            "/device delete کل",
            "/device delete *",
            "/device delete *.mp4",
            "/device delete 100%",
            "/device delete ../secret.mp4",
            "/device delete movies/../../secret.mp4",
            "/device delete ..",
            "/device delete ",
            "/device delete movie",
            "/device delete song",
            "/device delete photo",
            "گوشی: همه رو پاک کن",
            "گوشی: فیلم رو پاک کن",
            "گوشی: عکس را حذف کن",
            "گوشی: آهنگ رو حذف کن",
            "گوشی: *.mp4 رو پاک کن",
            "گوشی: ab رو پاک کن",
        )
        rejected.forEach(::assertNoActions)
    }

    @Test fun deleteAcceptsExactNamesWithSpacesUnicodeAndLengthLimit() {
        val exact = actions("/device delete godfather 1972 bluray.mp4")
            .single() as DeviceAction.DeleteFile
        assertEquals("godfather 1972 bluray.mp4", exact.exactName)

        val persian = actions("گوشی: شبح جنگل ۲.mp4 رو پاک کن")
            .single() as DeviceAction.DeleteFile
        assertEquals("شبح جنگل ۲.mp4", persian.exactName)

        val arabic = actions("گوشی: فيلم قديمي.mkv را حذف کن")
            .single() as DeviceAction.DeleteFile
        assertEquals("فيلم قديمي.mkv", arabic.exactName)

        val zwnj = actions("گوشی: می‌خواهم.mp4 رو پاک کن")
            .single() as DeviceAction.DeleteFile
        assertEquals("می‌خواهم.mp4", zwnj.exactName)

        val trailingObject = actions("/device delete godfather رو")
            .single() as DeviceAction.DeleteFile
        assertEquals("godfather", trailingObject.exactName)

        val longTarget = "a".repeat(260) + ".mp4"
        val truncated = actions("/device delete $longTarget")
            .single() as DeviceAction.DeleteFile
        assertEquals(200, truncated.exactName.length)
    }

    @Test fun infersMediaKindForFindAndPlay() {
        val video = actions("/device find movie inception").single() as DeviceAction.FindFile
        assertEquals(MediaKind.VIDEO, video.kind)
        assertEquals("inception", video.query)

        val persianVideo = actions("گوشی: پیدا کن فیلم پدرخوانده")
            .single() as DeviceAction.FindFile
        assertEquals(MediaKind.VIDEO, persianVideo.kind)
        assertEquals("پدرخوانده", persianVideo.query)

        val lateKind = actions("گوشی: پیدا کن پدرخوانده فیلم")
            .single() as DeviceAction.FindFile
        assertEquals(MediaKind.VIDEO, lateKind.kind)
        assertEquals("پدرخوانده", lateKind.query)

        val image = actions("/device find photo family.jpg").single() as DeviceAction.FindFile
        assertEquals(MediaKind.IMAGE, image.kind)
        assertEquals("family.jpg", image.query)

        val audio = actions("/device find audio shajarian.mp3").single() as DeviceAction.FindFile
        assertEquals(MediaKind.AUDIO, audio.kind)
        assertEquals("shajarian.mp3", audio.query)

        val videoExt = actions("/device find video movie.2024.mkv")
            .single() as DeviceAction.FindFile
        assertEquals(MediaKind.VIDEO, videoExt.kind)
        assertEquals("movie.2024.mkv", videoExt.query)

        val filler = actions("/device find یه فیلم inception").single() as DeviceAction.FindFile
        assertEquals(MediaKind.VIDEO, filler.kind)
        assertEquals("inception", filler.query)

        val play = actions("گوشی: فیلم godfather رو پخش کن")
            .single() as DeviceAction.PlayFile
        assertEquals(MediaKind.VIDEO, play.kind)
        assertEquals("godfather", play.query)
    }

    @Test fun findAllowsEmptyQueriesButPlayDoesNot() {
        val movies = actions("/device find movie").single() as DeviceAction.FindFile
        assertEquals("", movies.query)
        assertEquals(MediaKind.VIDEO, movies.kind)

        val photos = actions("/device find photo").single() as DeviceAction.FindFile
        assertEquals("", photos.query)
        assertEquals(MediaKind.IMAGE, photos.kind)

        val songs = actions("/device find song").single() as DeviceAction.FindFile
        assertEquals("", songs.query)
        assertEquals(MediaKind.AUDIO, songs.kind)

        listOf(
            "/device find",
            "/device find یه",
            "/device find یک",
            "/device play",
            "/device play movie",
            "/device play یه",
        ).forEach(::assertNoActions)
    }

    @Test fun capsLongFindPlayAndWebQueries() {
        val longQuery = "a".repeat(300)

        val find = actions("/device find movie $longQuery").single() as DeviceAction.FindFile
        assertEquals(200, find.query.length)
        assertEquals(longQuery.take(200), find.query)

        val play = actions("/device play $longQuery").single() as DeviceAction.PlayFile
        assertEquals(200, play.query.length)

        val search = actions("/device search " + "q".repeat(600)).single()
            as DeviceAction.WebSearch
        assertEquals(500, search.query.length)

        val faSearch = actions("گوشی: جستجو " + "پ".repeat(600)).single()
            as DeviceAction.WebSearch
        assertEquals(500, faSearch.query.length)

        val maps = actions("گوشی: نقشه " + "م".repeat(400)).single()
            as DeviceAction.Maps
        assertEquals(300, maps.query.length)
        assertEquals("شیراز", (actions("گوشی: نقشه شیراز").single() as DeviceAction.Maps).query)
    }

    @Test fun extractsDirectDownloadUrlsAndNamedDownloads() {
        val https = actions("/device download https://example.com/a.mp4")
            .single() as DeviceAction.DownloadFile
        assertEquals("https://example.com/a.mp4", https.url)
        assertNull(https.query)

        val localhost = actions("/device download http://localhost:8080/file.mp4")
            .single() as DeviceAction.DownloadFile
        assertEquals("http://localhost:8080/file.mp4", localhost.url)

        val lab = actions("/device download http://10.0.2.2:8080/file.bin")
            .single() as DeviceAction.DownloadFile
        assertEquals("http://10.0.2.2:8080/file.bin", lab.url)

        val named = actions("/device download godfather 1972")
            .single() as DeviceAction.DownloadFile
        assertNull(named.url)
        assertEquals("godfather 1972", named.query)

        val persian = actions("گوشی: فیلم godfather رو دانلود کن")
            .single() as DeviceAction.DownloadFile
        assertNull(persian.url)
        assertEquals("godfather", persian.query)

        val firstTokenOnly = actions("/device download https://example.com/a.mp4 extra words")
            .single() as DeviceAction.DownloadFile
        assertEquals("https://example.com/a.mp4", firstTokenOnly.url)
        assertNull(firstTokenOnly.query)

        val longUrl = "https://example.com/" + "a".repeat(2500)
        val cut = actions("/device download $longUrl").single() as DeviceAction.DownloadFile
        assertEquals(2000, cut.url!!.length)

        assertNoActions("/device download")
        assertNoActions("/device download   ")

        // A non-http scheme is treated as a name query, never as a direct URL.
        val ftp = actions("/device download ftp://example.com/a.mp4")
            .single() as DeviceAction.DownloadFile
        assertNull(ftp.url)
        assertEquals("ftp://example.com/a.mp4", ftp.query)
    }

    @Test fun downloadParserExtractsUrlsVerbatimAndDefersSafetyPolicy() {
        // The parser only classifies a token as a direct URL. Scheme/host/credential
        // rejection is implemented in FileAgentRunner.download (Android-only), so
        // these assertions pin extraction, not execution policy.
        val publicHttp = actions("/device download http://example.com/a.mp4")
            .single() as DeviceAction.DownloadFile
        assertEquals("http://example.com/a.mp4", publicHttp.url)
        assertNull(publicHttp.query)

        val credentials = actions("/device download https://user:pass@example.com/a.mp4")
            .single() as DeviceAction.DownloadFile
        assertEquals("https://user:pass@example.com/a.mp4", credentials.url)
        assertNull(credentials.query)

        val malformed = actions("/device download https://")
            .single() as DeviceAction.DownloadFile
        assertEquals("https://", malformed.url)
        assertNull(malformed.query)
    }

    @Test fun validatesPhoneNumbersAtTheEdges() {
        val valid = listOf(
            "/device dial 123",
            "/device dial +491234567",
            "/device dial (555) 123-4567",
            "/device dial +1 (555) 010-9999",
            "/device phone 09123456789",
            "گوشی: شماره 09123456789",
            "گوشی: تماس +49 123 456",
            "/device dial " + "1".repeat(30),
        )
        valid.forEach { raw ->
            val action = actions(raw).single()
            assertTrue("Expected dial for: $raw, got $action", action is DeviceAction.Dial)
        }

        listOf(
            "/device dial 12",
            "/device dial +",
            "/device dial 123ABC",
            "/device dial ۱۲۳۴۵",
            "/device dial " + "1".repeat(31),
            "گوشی: شماره 12",
        ).forEach(::assertNoActions)
    }

    @Test fun validatesEmailsAndSmsPayloads() {
        val email = actions("/device email test@example.com").single() as DeviceAction.Email
        assertEquals("test@example.com", email.address)
        assertEquals("", email.subject)

        val plusTag = actions("گوشی: ایمیل user+tag@sub.example.co.uk سلام دنیا")
            .single() as DeviceAction.Email
        assertEquals("user+tag@sub.example.co.uk", plusTag.address)
        assertEquals("سلام دنیا", plusTag.subject)

        val longSubject = actions("/device email test@example.com " + "s".repeat(350))
            .single() as DeviceAction.Email
        assertEquals(300, longSubject.subject.length)

        listOf(
            "/device email not-an-email",
            "/device email a@localhost",
            "/device email test@example.com.",
            "/device email test@@example.com",
            "گوشی: ایمیل bad",
        ).forEach(::assertNoActions)

        val sms = actions("/device sms +491234 hello world").single() as DeviceAction.Sms
        assertEquals("+491234", sms.phoneNumber)
        assertEquals("hello world", sms.body)

        val persianSms = actions("گوشی: پیامک 0912 سلام").single() as DeviceAction.Sms
        assertEquals("0912", persianSms.phoneNumber)
        assertEquals("سلام", persianSms.body)

        val noBody = actions("/device sms 0912").single() as DeviceAction.Sms
        assertEquals("", noBody.body)

        val longBody = actions("/device sms 0912 " + "b".repeat(1200))
            .single() as DeviceAction.Sms
        assertEquals(1000, longBody.body.length)

        assertNoActions("/device sms 12 hello")
    }
}
