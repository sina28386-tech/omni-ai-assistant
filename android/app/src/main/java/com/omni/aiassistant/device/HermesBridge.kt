package com.omni.aiassistant.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class PcStatus(
    val system: String,
    val node: String,
    val desktopFiles: Int,
) {
    val summary: String get() = "$system ($node) · $desktopFiles Desktop files"
}

/**
 * Transport for the Hermes PC bridge (`scripts/hermes_pc_agent.py`).
 *
 * The endpoint and bearer token always come from the caller (ultimately
 * `AppSettings.hermesUrl` / `AppSettings.hermesToken`). [DEFAULT_BASE_URL] exists
 * only so the old zero-argument `check()` call still compiles for the emulator;
 * production screens and tools must pass the configured values.
 *
 * Security rules enforced here:
 * - only `http`/`https` URLs with a host, no user-info, query or fragment;
 * - redirects are never followed, so the Authorization header cannot be
 *   replayed to another origin;
 * - the token is sent only as an `Authorization: Bearer` header and is scrubbed
 *   from any error text that reaches the UI or the model;
 * - response bodies are capped at 64 KiB and outputs at 8 000 characters.
 */
object HermesBridge {
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8765"

    private const val STATUS_PATH = "/status"
    private const val EXECUTE_PATH = "/execute"
    private const val MAX_RESPONSE_CHARS = 64 * 1024
    private const val MAX_OUTPUT_CHARS = 8_000

    /** Returns a cleaned base URL, or null when it is blank/invalid/unsafe. */
    fun normalizeBaseUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        // User-info would put credentials in the URL and can be logged by
        // proxies; query/fragment has no meaning for this API.
        if (uri.userInfo != null) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        return trimmed
    }

    suspend fun check(
        baseUrl: String = DEFAULT_BASE_URL,
        token: String? = null,
    ): Result<PcStatus> = withContext(Dispatchers.IO) {
        try {
            val base = requireBaseUrl(baseUrl)
            val json = requestJson(
                url = "$base$STATUS_PATH",
                method = "GET",
                body = null,
                token = token,
                connectTimeoutMs = 4_000,
                readTimeoutMs = 6_000,
            )
            Result.success(
                PcStatus(
                    system = json.optString("system", "Windows").ifBlank { "Windows" },
                    node = json.optString("node", "PC").ifBlank { "PC" },
                    desktopFiles = json.optInt("desktop_files_count", 0).coerceAtLeast(0),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(safeFailure(error, token))
        }
    }

    suspend fun execute(
        baseUrl: String = DEFAULT_BASE_URL,
        action: String,
        payload: String = "",
        token: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base = requireBaseUrl(baseUrl)
            val body = JSONObject().apply {
                put("action", action)
                put("payload", payload)
            }.toString()
            val json = requestJson(
                url = "$base$EXECUTE_PATH",
                method = "POST",
                body = body,
                token = token,
                connectTimeoutMs = 6_000,
                readTimeoutMs = 20_000,
            )
            val success = json.optBoolean("success", true)
            val output = sanitize(
                json.optString("output", "").ifBlank { "Done" }.take(MAX_OUTPUT_CHARS),
                token,
            )
            if (!success) {
                error(output.take(300).ifBlank { "The PC bridge rejected the action." })
            }
            Result.success(output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(safeFailure(error, token))
        }
    }

    private fun requireBaseUrl(raw: String): String =
        normalizeBaseUrl(raw)
            ?: error("Invalid PC bridge URL. Use http://host:port or https://host:port.")

    private fun requestJson(
        url: String,
        method: String,
        body: String?,
        token: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            // Following a redirect could forward the bearer token to a
            // different host. Treat any 3xx as a failure instead.
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Omni-Android")
            val secret = token?.trim().orEmpty()
            if (secret.isNotEmpty()) {
                // A CR/LF in the token would be a header injection attempt.
                require(secret.none { it == '\r' || it == '\n' }) {
                    "Invalid PC bridge token."
                }
                setRequestProperty("Authorization", "Bearer $secret")
            }
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                // Close the error body without reading it: it is untrusted and
                // never needed for debugging on the phone.
                runCatching { connection.errorStream?.close() }
                error(httpErrorMessage(code))
            }
            val text = connection.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    val buffer = CharArray(4_096)
                    val builder = StringBuilder()
                    var total = 0
                    while (total < MAX_RESPONSE_CHARS) {
                        val read = reader.read(
                            buffer,
                            0,
                            minOf(buffer.size, MAX_RESPONSE_CHARS - total),
                        )
                        if (read <= 0) break
                        builder.append(buffer, 0, read)
                        total += read
                    }
                    builder.toString()
                }
            }
            if (text.isBlank()) error("The PC bridge returned an empty response.")
            return runCatching { JSONObject(text) }.getOrElse {
                error("The PC bridge returned an invalid response.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpErrorMessage(code: Int): String = when (code) {
        HttpURLConnection.HTTP_UNAUTHORIZED ->
            "The PC bridge rejected the token (HTTP 401)."

        HttpURLConnection.HTTP_FORBIDDEN ->
            "The PC bridge refused the request (HTTP 403)."

        HttpURLConnection.HTTP_NOT_FOUND ->
            "PC bridge endpoint not found (HTTP 404). Check the address."

        in 300..399 ->
            "The PC bridge redirected the request (HTTP $code). Redirects are not allowed."

        in 500..599 ->
            "The PC bridge reported an error (HTTP $code)."

        else -> "The PC bridge returned HTTP $code."
    }

    private fun safeFailure(error: Throwable, token: String?): Throwable {
        val message = error.message?.take(300)?.ifBlank { null } ?: "PC bridge request failed."
        return IllegalStateException(sanitize(message, token))
    }

    private fun sanitize(text: String, token: String?): String {
        val secret = token?.trim().orEmpty()
        return if (secret.isEmpty()) text else text.replace(secret, "***")
    }
}
