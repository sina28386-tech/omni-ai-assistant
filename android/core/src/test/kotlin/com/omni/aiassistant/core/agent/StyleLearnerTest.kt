package com.omni.aiassistant.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleLearnerTest {

    private fun fields(text: String): Map<StyleLearner.Field, String> =
        StyleLearner.observe(text).associate { it.field to it.value }

    @Test
    fun `persian name instruction is learned`() {
        val updates = StyleLearner.observe("از این به بعد منو صدا کن آرش")
        assertEquals("آرش", updates.first { it.field == StyleLearner.Field.NAME }.value)
    }

    @Test
    fun `english name instruction is learned`() {
        assertEquals("Arman", fields("call me Arman")[StyleLearner.Field.NAME])
    }

    @Test
    fun `persian answer language is learned in both word orders`() {
        assertEquals("en", fields("از این به بعد انگلیسی جواب بده")[StyleLearner.Field.LANGUAGE])
        assertEquals("en", fields("جوابت را انگلیسی بنویس")[StyleLearner.Field.LANGUAGE])
        assertEquals("fa", fields("answer in Persian from now on")[StyleLearner.Field.LANGUAGE])
    }

    @Test
    fun `formal and informal are distinguished`() {
        assertEquals("formal", fields("لطفاً رسمی حرف بزن")[StyleLearner.Field.FORMALITY])
        assertEquals("informal", fields("غیررسمی حرف بزن")[StyleLearner.Field.FORMALITY])
    }

    @Test
    fun `persian zwnj and spaced variants of informal are not read as formal`() {
        assertEquals("informal", fields("غیر\u200Cرسمی حرف بزن")[StyleLearner.Field.FORMALITY])
        assertEquals("informal", fields("غیر رسمی حرف بزن")[StyleLearner.Field.FORMALITY])
        assertEquals("informal", fields("بی\u200Cتکلف حرف بزن")[StyleLearner.Field.FORMALITY])
    }

    @Test
    fun `length preference is learned`() {
        assertEquals("short", fields("از این به بعد کوتاه جواب بده")[StyleLearner.Field.VERBOSITY])
        assertEquals("short", fields("be brief please")[StyleLearner.Field.VERBOSITY])
        assertEquals("detailed", fields("بیشتر توضیح بده")[StyleLearner.Field.VERBOSITY])
    }

    @Test
    fun `emoji preference is learned`() {
        assertEquals("never", fields("بدون ایموجی جواب بده")[StyleLearner.Field.EMOJI])
        assertEquals("never", fields("no emoji")[StyleLearner.Field.EMOJI])
        assertEquals("sometimes", fields("با ایموجی بنویس")[StyleLearner.Field.EMOJI])
    }

    @Test
    fun `short message is not a length preference`() {
        assertTrue(StyleLearner.observe("یه پیام کوتاه بفرست").isEmpty())
        assertTrue(StyleLearner.observe("کوتاه").isEmpty())
    }

    @Test
    fun `standing instruction is quoted but does not invent a style`() {
        val updates = StyleLearner.observe("از این به بعد هر روز ساعت هشت یادم بنداز")
        assertEquals(listOf(StyleLearner.Field.NOTE), updates.map { it.field })
        assertTrue(updates.single().value.contains("هر روز"))
    }

    @Test
    fun `a normal request changes nothing`() {
        assertTrue(StyleLearner.observe("برام یه پیتزا سفارش بده").isEmpty())
        assertTrue(StyleLearner.observe("").isEmpty())
    }

    @Test
    fun `merged profile keeps one copy of a repeated note`() {
        val update = StyleLearner.Update(StyleLearner.Field.NOTE, "answer in Persian", "from now on answer in Persian")
        val once = UserStyle().with(update, 1L)
        val twice = once.with(update, 2L)
        assertEquals(1, twice.notes.size)
        assertEquals(2, twice.evidenceCount)
    }

    @Test
    fun `clear removes every learned field`() {
        val style = UserStyle()
            .with(StyleLearner.Update(StyleLearner.Field.NAME, "Arman", "call me Arman"), 1L)
            .with(StyleLearner.Update(StyleLearner.Field.VERBOSITY, "short", "be brief"), 1L)
        assertFalse(style.isEmpty)
        assertTrue(UserStyle().isEmpty)
    }
}
