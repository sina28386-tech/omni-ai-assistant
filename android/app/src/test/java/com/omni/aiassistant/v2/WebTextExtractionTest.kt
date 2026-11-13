package com.omni.aiassistant.v2

import com.omni.aiassistant.device.tools.web.htmlToText
import com.omni.aiassistant.device.tools.web.parseResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text extraction behind `web_fetch` and `web_search_query`.
 *
 * The extracted text is untrusted content that is handed to the model, so
 * the test focuses on "the readable parts survive and the executable parts
 * do not".
 */
class WebTextExtractionTest {

    @Test
    fun stripsScriptsStylesAndTags() {
        val html = """
            <html><head><style>body{color:red}</style>
            <script>alert('x')</script></head>
            <body><h1>Title</h1><p>First&nbsp;paragraph</p>
            <p>Second paragraph</p></body></html>
        """.trimIndent()
        val text = htmlToText(html)
        assertTrue(text.contains("Title"))
        assertTrue(text.contains("First paragraph"))
        assertTrue(text.contains("Second paragraph"))
        assertTrue(!text.contains("alert"))
        assertTrue(!text.contains("color:red"))
        assertTrue(!text.contains("<"))
    }

    @Test
    fun decodesCommonEntities() {
        val text = htmlToText("<p>a &amp; b &lt;c&gt; &#39;d&#39; &mdash; e</p>")
        assertTrue(text.contains("a & b"))
        assertTrue(text.contains("<c>"))
        assertTrue(text.contains("'d'"))
        assertTrue(text.contains("—"))
    }

    @Test
    fun collapsesWhitespaceButKeepsLineBreaks() {
        val text = htmlToText("<p>one</p><p>two</p>")
        assertEquals("one\ntwo", text)
    }

    @Test
    fun extractsSearchResultsAndUnwrapsRedirects() {
        val html = """
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage">Example title</a>
              <a class="result__snippet">A short snippet</a>
            </div>
        """.trimIndent()
        val results = parseResults(html, 5)
        assertEquals(1, results.size)
        assertEquals("Example title", results[0].title)
        assertEquals("https://example.com/page", results[0].url)
        assertEquals("A short snippet", results[0].snippet)
    }

    @Test
    fun respectsTheResultLimit() {
        val html = (1..10).joinToString("") {
            """<a class="result__a" href="https://example.com/$it">Title $it</a>"""
        }
        assertEquals(3, parseResults(html, 3).size)
    }

    @Test
    fun ignoresMarkupWithoutResults() {
        assertTrue(parseResults("<p>nothing here</p>", 5).isEmpty())
    }
}
