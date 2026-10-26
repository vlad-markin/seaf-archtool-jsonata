package su.seaf.archtool.jsonata.tools.gate

import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The local backend of the core on a lake (design §2.1 p. 1–2): the data of the gate. Every request evaluates a JSONata
 * query on the backend with its cache off. The request format is configurable, because it belongs to the core:
 * [bodyTemplate] has `%q` for the query as a JSON string and `%r` for the role (role mode v1), [headers] are sent as
 * they are (authorization).
 */
class BackendClient(
    private val url: String,
    private val bodyTemplate: String = DEFAULT_BODY,
    private val headers: Map<String, String> = emptyMap(),
    private val role: String? = null,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val sources = ConcurrentHashMap<String, Value?>()

    /** The result of [query] on the backend (`undefined` when the response is empty). */
    fun query(query: String): Value? {
        val body = bodyTemplate.replace("%q", JsonWriter.stringify(StringValue.of(query)))
            .replace("%r", role?.let { JsonWriter.stringify(StringValue.of(it)) } ?: "null")
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .also { b -> headers.forEach { (k, v) -> b.header(k, v) } }
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() / 100 != 2) throw IllegalStateException("backend ${response.statusCode()}: ${response.body().take(500)}")
        val text = response.body().trim()
        return if (text.isEmpty()) null else JsonataJson.parse(text)
    }

    /** The manifest export: `($)` — without the parentheses the backend reads `$` as an id (RV-2). */
    fun manifest(): Value? = query("(\$)")

    /** `$parsesource` of [source] on the backend, memoized: both sides of the gate see the same data. */
    fun parseSource(source: Value?): Value? {
        val key = JsonWriter.stringify(source ?: su.seaf.archtool.jsonata.value.NullValue)
        return sources.computeIfAbsent(key) { query("(\$parsesource($key))") ?: MISSING }.takeIf { it !== MISSING }
    }

    companion object {
        /** The default URL path of the core's JSONata endpoint. */
        const val DEFAULT_PATH: String = "/seaf-core/api/core/storage/jsonata"

        /** The assumed request body; check it against the core and pass --backend-body when it differs. */
        const val DEFAULT_BODY: String = "{\"query\": %q, \"cache\": \"none\", \"role\": %r}"

        private val MISSING: Value = StringValue.of("\u0000missing")
    }
}
