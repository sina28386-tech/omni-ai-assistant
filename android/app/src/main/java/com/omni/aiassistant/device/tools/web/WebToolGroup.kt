package com.omni.aiassistant.device.tools.web

import android.content.Context
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolArgs
import com.omni.aiassistant.core.agent.ToolArgs.int
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolGroup
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.device.capability.CapabilityCenter
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Direct internet access.
 *
 * This group is the counterpart of the screen tools: it reads the open web
 * *without* opening anything on the phone. The user can therefore ask for
 * "the internet version" of a page and get it without leaving Arman.
 */
class WebToolGroup(
    context: Context,
    capabilities: CapabilityCenter,
    private val settings: SettingsRepository,
    private val httpClient: OkHttpClient,
) : ToolGroup {

    override fun tools(): List<Tool> = listOf(
        WebFetchTool(httpClient),
        WebSearchQueryTool(httpClient),
    )
}

private class WebFetchTool(
    private val httpClient: OkHttpClient,
) : Tool {

    override val spec = ToolSpec(
        name = "web_fetch",
        description = "Read a web page over the internet and return its text. Use this when the " +
            "user wants the ONLINE version of something (for example \"check Twitter on the " +
            "internet\"), NOT the version inside an app on the phone. For an app on the phone " +
            "use ui_open_app plus screen_read instead. Only http and https URLs are allowed.",
        category = ToolCategory.WEB,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "url" to ToolSchema.string("Absolute http(s) URL to read"),
                "max_chars" to ToolSchema.integer(
                    "Maximum number of characters to return",
                    minimum = 200,
                    maximum = 20_000,
                    default = 6_000,
                ),
            ),
            required = listOf("url"),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome =
        withContext(Dispatchers.IO) {
            val rawUrl = arguments.string("url")?.trim()
            if (rawUrl.isNullOrEmpty()) return@withContext ToolOutcome.Failure("Missing required argument 'url'")
            val url = normalizeUrl(rawUrl)
                ?: return@withContext ToolOutcome.Failure(
                    "Only absolute http:// or https:// URLs can be read.",
                )
            val maxChars = ToolArgs.clamp(arguments.int("max_chars") ?: 6_000, 200, 20_000)
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    if (!response.isSuccessful) {
                        return@withContext ToolOutcome.Failure(
                            "The server answered with HTTP $code for that URL.",
                        )
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                    val declaredLength = response.body?.contentLength() ?: -1L
                    if (declaredLength > MAX_BYTES) {
                        return@withContext ToolOutcome.Failure(
                            "That page is too large to read (${declaredLength / 1024} KB).",
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        return@withContext ToolOutcome.Failure("The page was empty.")
                    }
                    val text = if (contentType.contains("html", ignoreCase = true)) {
                        htmlToText(body)
                    } else {
                        body.trim()
                    }
                    val clipped = text.take(maxChars)
                    ToolOutcome.Success(
                        summary = "Read ${clipped.length} characters from ${URL(url).host}",
                        data = buildJsonObject {
                            put("url", url)
                            put("contentType", contentType)
                            put("characters", clipped.length)
                            put("truncated", text.length > clipped.length)
                            put("text", clipped)
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (io: IOException) {
                ToolOutcome.Failure("Could not reach that page: ${io.message?.take(120) ?: "network error"}")
            } catch (error: Exception) {
                ToolOutcome.Failure("web_fetch failed (${error::class.simpleName})")
            }
        }
}

private class WebSearchQueryTool(
    private val httpClient: OkHttpClient,
) : Tool {

    override val spec = ToolSpec(
        name = "web_search_query",
        description = "Search the open internet and return titles, snippets and links. Use this " +
            "for public information the user asks about online. It does NOT read anything " +
            "inside the user's own apps; for that open the app on the phone with " +
            "ui_open_app and read it with screen_read. Works for non-English queries, " +
            "including Persian.",
        category = ToolCategory.WEB,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "query" to ToolSchema.string("Search text"),
                "limit" to ToolSchema.integer(
                    "Maximum number of results",
                    minimum = 1,
                    maximum = 10,
                    default = 5,
                ),
            ),
            required = listOf("query"),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome =
        withContext(Dispatchers.IO) {
            val query = arguments.string("query")?.trim()
            if (query.isNullOrEmpty()) {
                return@withContext ToolOutcome.Failure("Missing required argument 'query'")
            }
            if (query.length > 200) {
                return@withContext ToolOutcome.Failure("The search text is too long")
            }
            val limit = ToolArgs.clamp(arguments.int("limit") ?: 5, 1, 10)
            val url = "$SEARCH_ENDPOINT?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ToolOutcome.Failure(
                            "The search engine answered with HTTP ${response.code}.",
                        )
                    }
                    val declaredLength = response.body?.contentLength() ?: -1L
                    if (declaredLength > MAX_BYTES) {
                        return@withContext ToolOutcome.Failure(
                            "The search engine returned a page that is too large.",
                        )
                    }
                    val html = response.body?.string().orEmpty()
                    val results = parseResults(html, limit)
                    if (results.isEmpty()) {
                        return@withContext ToolOutcome.Failure(
                            "The search returned no usable results for \"${query.take(60)}\".",
                        )
                    }
                    ToolOutcome.Success(
                        summary = "Found ${results.size} online result(s) for \"${query.take(60)}\"",
                        data = buildJsonObject {
                            put("query", query)
                            put("count", results.size)
                            put("results", buildJsonArray {
                                results.forEach { result ->
                                    add(
                                        buildJsonObject {
                                            put("title", result.title)
                                            put("url", result.url)
                                            put("snippet", result.snippet)
                                        },
                                    )
                                }
                            })
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (io: IOException) {
                ToolOutcome.Failure("Could not reach the search engine: ${io.message?.take(120) ?: "network error"}")
            } catch (error: Exception) {
                ToolOutcome.Failure("web_search_query failed (${error::class.simpleName})")
            }
        }
}

/* -------------------------------------------------------------------------- */
/* Helpers                                                                    */
/* -------------------------------------------------------------------------- */

internal data class SearchResult(val title: String, val url: String, val snippet: String)
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36 Omni/2.0"

private const val SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/"
private const val MAX_BYTES = 1_500_000L

private fun normalizeUrl(raw: String): String? {
    val candidate = if (raw.startsWith("http://") || raw.startsWith("https://")) {
        raw
    } else {
        "https://$raw"
    }
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    return uri.toString()
}

/**
 * Turns an HTML document into readable text.
 *
 * It is not a parser: scripts, styles and tags are removed and the remaining
 * entities are decoded. The result is only used as *untrusted content* for the
 * model, so a rough extraction is enough and keeps the app dependency-free.
 */
internal fun htmlToText(html: String): String {
    var text = html
    text = Regex("(?is)<script[^>]*>.*?</script>").replace(text, " ")
    text = Regex("(?is)<style[^>]*>.*?</style>").replace(text, " ")
    text = Regex("(?is)<noscript[^>]*>.*?</noscript>").replace(text, " ")
    text = Regex("(?is)<!--.*?-->").replace(text, " ")
    text = Regex("(?is)<br\\s*/?>").replace(text, "\n")
    text = Regex("(?is)</(p|div|li|h[1-6]|tr|section|article)>").replace(text, "\n")
    text = Regex("(?is)<[^>]+>").replace(text, " ")
    text = decodeEntities(text)
    return text
        .lines()
        .map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .trim()
}

private fun decodeEntities(input: String): String {
    val builder = StringBuilder(input.length)
    var index = 0
    while (index < input.length) {
        val char = input[index]
        if (char != '&') {
            builder.append(char)
            index++
            continue
        }
        val end = input.indexOf(';', index + 1)
        if (end < 0 || end - index > 10) {
            builder.append(char)
            index++
            continue
        }
        val entity = input.substring(index + 1, end)
        val replacement = when {
            entity.startsWith("#x", ignoreCase = true) ->
                entity.drop(2).toIntOrNull(16)?.let { codePointToString(it) }
            entity.startsWith("#") ->
                entity.drop(1).toIntOrNull()?.let { codePointToString(it) }
            else -> NAMED_ENTITIES[entity.lowercase()]
        }
        if (replacement == null) {
            builder.append(char)
            index++
        } else {
            builder.append(replacement)
            index = end + 1
        }
    }
    return builder.toString()
}

private fun codePointToString(code: Int): String? =
    runCatching { String(Character.toChars(code)) }.getOrNull()

private val NAMED_ENTITIES: Map<String, String> = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "laquo" to "«",
    "raquo" to "»",
    "middot" to "·",
    "times" to "×",
    "copy" to "©",
    "reg" to "®",
    "eacute" to "é",
    "uuml" to "ü",
    "ouml" to "ö",
    "auml" to "ä",
)

/**
 * Extracts the result rows from a DuckDuckGo HTML answer.
 *
 * The markup is matched with a tolerant regular expression rather than a full
 * parser, because the search endpoint is a fixture, not a contract we can
 * rely on for years.
 */
internal fun parseResults(html: String, limit: Int): List<SearchResult> {
    val results = mutableListOf<SearchResult>()
    val anchor = Regex(
        "(?is)<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
    )
    val snippet = Regex(
        "(?is)<a[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>",
    )
    val snippets = snippet.findAll(html).map { htmlToText(it.groupValues[1]) }.toList()
    var index = 0
    for (match in anchor.findAll(html)) {
        if (results.size >= limit) break
        val title = htmlToText(match.groupValues[2])
        if (title.isBlank()) continue
        val url = unwrapRedirect(match.groupValues[1])
        val snippetText = snippets.getOrNull(index).orEmpty()
        index++
        results += SearchResult(title = title.take(200), url = url, snippet = snippetText.take(400))
    }
    return results
}

/** DuckDuckGo wraps outbound links; the real target is in the `uddg` parameter. */
private fun unwrapRedirect(raw: String): String {
    val candidate = if (raw.startsWith("//")) "https:$raw" else raw
    val marker = candidate.indexOf("uddg=")
    if (marker < 0) return candidate
    val value = candidate.substring(marker + 5)
        .substringBefore('&')
        .substringBefore(' ')
    return runCatching { java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
        .getOrDefault(value)
}
