package dev.nodroid.nodes

import dev.nodroid.engine.ConfigReader
import dev.nodroid.engine.ExecutionContext
import dev.nodroid.engine.ExpressionParser
import dev.nodroid.engine.NodeExecutor
import dev.nodroid.engine.resolveVaultPlaceholders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.dokar.quickjs.quickJs

/** Persistent KV store interface; Room provides the implementation via [RoomKvAdapter]. */
interface KvStore {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun delete(key: String)
    suspend fun increment(key: String, by: Long = 1): Long
}

/** Template context for a node: flat inputs plus an `input` alias so {{input.text}} works. */
fun ExecutionContext.templateContext(): Map<String, Any?> {
    val ctx = inputs.toMutableMap()
    ctx["input"] = inputs
    return ctx
}

fun ExecutionContext.rendered(key: String, default: String = ""): String {
    val raw = ConfigReader.string(node.config, key, default)
    val rendered = ExpressionParser.render(raw, templateContext())
    return resolveVaultPlaceholders(rendered, vault)
}

fun ExecutionContext.cfg(key: String, default: String = ""): String = ConfigReader.string(node.config, key, default)

/** All executors that need no Android Context. */
object PureNodeExecutors {
    val triggerPassthrough: NodeExecutor = NodeExecutor { ctx ->
        mapOf<String, Any?>("triggered" to true) + ctx.inputs
    }

    val ifElse: NodeExecutor = NodeExecutor { ctx ->
        val left = ctx.rendered("left")
        val right = ctx.rendered("right")
        val op = ctx.cfg("operator", "==")
        val leftNum = left.toDoubleOrNull()
        val rightNum = right.toDoubleOrNull()
        val result = when (op) {
            "==" -> left == right
            "!=" -> left != right
            ">" -> (leftNum != null && rightNum != null && leftNum > rightNum)
            "<" -> (leftNum != null && rightNum != null && leftNum < rightNum)
            ">=" -> (leftNum != null && rightNum != null && leftNum >= rightNum)
            "<=" -> (leftNum != null && rightNum != null && leftNum <= rightNum)
            "contains" -> left.contains(right)
            "isEmpty" -> left.isEmpty()
            else -> throw IllegalArgumentException("Unknown operator: " + op)
        }
        mapOf<String, Any?>("result" to result, "left" to left, "right" to right, "_nextPort" to if (result) "true" else "false")
    }

    val switch: NodeExecutor = NodeExecutor { ctx ->
        val value = ctx.rendered("value")
        var port = "default"
        for (i in 0..2) {
            val caseValue = ctx.rendered("case$i")
            if (caseValue.isNotEmpty() && caseValue == value) { port = "case$i"; break }
        }
        mapOf<String, Any?>("value" to value, "_nextPort" to port)
    }

    val merge: NodeExecutor = NodeExecutor { ctx ->
        mapOf<String, Any?>("merged" to true) + ctx.inputs
    }

    val forEach: NodeExecutor = NodeExecutor { ctx ->
        val raw = ctx.rendered("items")
        val items: List<Any?> = when {
            ctx.inputs["items"] is List<*> -> ctx.inputs["items"] as List<Any?>
            raw.trim().startsWith("[") -> {
                val parsed = ExpressionParser.jsonToAny(Json.parseToJsonElement(raw))
                (parsed as? List<*>) ?: listOf(parsed)
            }
            raw.isBlank() -> emptyList()
            else -> listOf(raw)
        }
        val maxIterations = ConfigReader.int(ctx.node.config, "maxIterations", 100).coerceIn(1, 1000)
        val batchSize = ConfigReader.int(ctx.node.config, "batchSize", 10).coerceIn(1, 100)
        val capped = items.take(maxIterations)
        val batches = capped.chunked(batchSize)
        mapOf<String, Any?>(
            "items" to capped, "count" to capped.size, "batches" to batches.size,
            "batch" to (batches.firstOrNull() ?: emptyList<Any?>()),
            "truncated" to (items.size > maxIterations),
        )
    }

    val delayNode: NodeExecutor = NodeExecutor { ctx ->
        val requested = ConfigReader.int(ctx.node.config, "milliseconds", 1000).coerceAtLeast(0)
        val actual = requested.coerceAtMost(300_000)
        delay(actual.toLong())
        mapOf<String, Any?>("requestedMs" to requested, "delayedMs" to actual, "capped" to (requested > actual))
    }

    val errorCatch: NodeExecutor = NodeExecutor { ctx ->
        val error = ctx.inputs["error"]?.toString() ?: ctx.cfg("fallbackValue")
        mapOf<String, Any?>("error" to error, "failedNode" to (ctx.inputs["failedNode"] ?: ""), "recovered" to true) + ctx.inputs.filterKeys { it != "error" }
    }

    val subflow: NodeExecutor = NodeExecutor { ctx ->
        val workflowId = ctx.rendered("workflowId")
        require(workflowId.isNotBlank()) { "Sub-workflow ID is blank" }
        val depth = ctx.runId.count { it == ':' }
        require(depth < 5) { "Sub-workflow recursion depth cap (5) reached at " + workflowId }
        val output = ctx.runSubflow(workflowId, ctx.inputs)
        mapOf<String, Any?>("output" to output)
    }

    val regexExtractor: NodeExecutor = NodeExecutor { ctx ->
        val input = ctx.rendered("input")
        val preset = ctx.cfg("preset", "custom")
        val pattern = when (preset) {
            "url" -> "https?://[^\\s\"'<>]+"
            "email" -> "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
            "number" -> "-?\\d+(\\.\\d+)?"
            else -> ctx.rendered("pattern")
        }
        require(pattern.isNotBlank()) { "Regex pattern is blank" }
        val matches = Regex(pattern).findAll(input).map { it.value }.toList()
        mapOf<String, Any?>("matches" to matches, "first" to (matches.firstOrNull() ?: ""), "count" to matches.size)
    }

    val jsonPath: NodeExecutor = NodeExecutor { ctx ->
        val raw = ctx.rendered("input")
        val path = ctx.rendered("path", "$.data")
        val data: Any? = when {
            ctx.inputs["json"] is Map<*, *> || ctx.inputs["json"] is List<*> -> ctx.inputs["json"]
            raw.trim().startsWith("{") || raw.trim().startsWith("[") -> ExpressionParser.jsonToAny(Json.parseToJsonElement(raw))
            else -> ctx.inputs
        }
        val value = JsonPath.eval(data, path)
        mapOf<String, Any?>("value" to value, "found" to (value != null))
    }

    val htmlScraper: NodeExecutor = NodeExecutor { ctx ->
        val client = HttpSupport.sharedClient
        val url = ctx.rendered("url")
        val html = if (url.isNotBlank()) HttpSupport.getText(client, url) else ctx.rendered("html", ctx.inputs["html"]?.toString() ?: "")
        require(html.isNotBlank()) { "HTML scraper needs a URL or HTML input" }
        val selector = ctx.rendered("selector", "h1")
        val attribute = ctx.rendered("attribute", "text")
        val doc = Jsoup.parse(html)
        val items = doc.select(selector).map { el ->
            when {
                attribute == "text" -> el.text()
                attribute == "html" -> el.html()
                attribute.startsWith("attr:") -> el.attr(attribute.removePrefix("attr:"))
                else -> el.text()
            }
        }
        mapOf<String, Any?>("items" to items, "first" to (items.firstOrNull() ?: ""), "count" to items.size)
    }

    val templateFormatter: NodeExecutor = NodeExecutor { ctx ->
        val isCsv = ConfigReader.boolean(ctx.node.config, "csv", false)
        // CSV mode renders the template once per row, so it must stay raw here;
        // pre-rendering it against the (row-less) inputs would erase the row fields.
        val template = if (isCsv) ctx.cfg("template") else ctx.rendered("template")
        if (!isCsv) {
            mapOf<String, Any?>("text" to ExpressionParser.render(template, ctx.templateContext()))
        } else {
            val rowsRaw = ctx.rendered("rows")
            val rows = when {
                ctx.inputs["rows"] is List<*> -> ctx.inputs["rows"] as List<*>
                rowsRaw.trim().startsWith("[") -> (ExpressionParser.jsonToAny(Json.parseToJsonElement(rowsRaw)) as? List<*>) ?: emptyList<Any?>()
                else -> emptyList<Any?>()
            }
            val lines = rows.map { row ->
                val rowCtx = ctx.templateContext().toMutableMap()
                if (row is Map<*, *>) rowCtx.putAll(row.mapKeys { it.key.toString() })
                rowCtx["row"] = row
                ExpressionParser.render(template, rowCtx)
            }
            mapOf<String, Any?>("text" to lines.joinToString("\n"), "count" to lines.size)
        }
    }

    val javascript: NodeExecutor = NodeExecutor { ctx ->
        val code = ctx.cfg("code")
        require(code.isNotBlank()) { "JavaScript code is blank" }
        require(code.length <= 65_536) { "JavaScript code exceeds 64 KiB" }
        val inputJson = Json.encodeToString(JsonElement.serializer(), ExpressionParser.anyToJson(ctx.inputs))
        val script = "var input = " + inputJson + ";\n" + code
        val result = withContext(Dispatchers.Default) {
            quickJs {
                memoryLimit = 16L * 1024L * 1024L
                evaluationTimeoutMillis = 3000L
                evaluate<Any?>(script)
            }
        }
        mapOf<String, Any?>("result" to result)
    }

    fun kvExecutor(store: KvStore): NodeExecutor = NodeExecutor { ctx ->
        val op = ctx.cfg("operation", "get")
        val key = ctx.rendered("key")
        require(key.isNotBlank()) { "KV key is blank" }
        when (op) {
            "get" -> mapOf<String, Any?>("key" to key, "value" to store.get(key), "found" to (store.get(key) != null))
            "set" -> {
                val value = ctx.rendered("value")
                store.set(key, value)
                mapOf<String, Any?>("key" to key, "value" to value, "stored" to true)
            }
            "increment" -> {
                val next = store.increment(key, 1)
                mapOf<String, Any?>("key" to key, "value" to next)
            }
            "delete" -> {
                store.delete(key)
                mapOf<String, Any?>("key" to key, "deleted" to true)
            }
            else -> throw IllegalArgumentException("Unknown KV operation: " + op)
        }
    }

    fun httpExecutor(client: OkHttpClient): NodeExecutor = NodeExecutor { ctx ->
        val url = ctx.rendered("url")
        require(url.isNotBlank()) { "HTTP URL is blank" }
        val method = ctx.cfg("method", "GET").uppercase()
        val retries = ConfigReader.int(ctx.node.config, "retries", 2).coerceIn(0, 5)
        val headersJson = ctx.rendered("headers", "{}")
        val headers = try {
            val parsed = ExpressionParser.jsonToAny(Json.parseToJsonElement(headersJson.ifBlank { "{}" }))
            (parsed as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        val bodyText = ctx.rendered("body")
        HttpSupport.execute(client, url, method, headers, bodyText, retries)
    }

    fun webhookExecutor(client: OkHttpClient): NodeExecutor = NodeExecutor { ctx ->
        val url = ctx.rendered("url")
        require(url.isNotBlank()) { "Webhook URL is blank" }
        val payload = ctx.rendered("payload", "{}")
        HttpSupport.execute(client, url, "POST", mapOf("Content-Type" to "application/json"), payload, 2)
    }

    fun llmExecutor(client: OkHttpClient): NodeExecutor = NodeExecutor { ctx ->
        val baseUrl = ctx.rendered("baseUrl").trimEnd('/')
        val apiKey = ctx.rendered("apiKey")
        require(apiKey.isNotBlank()) { "LLM API key is missing — add it to the vault as {{vault.KEY}}" }
        val model = ctx.rendered("model")
        val prompt = ctx.rendered("prompt")
        val temperature = ConfigReader.double(ctx.node.config, "temperature", 0.7)
        val requestJson = buildString {
            append("{\"model\":")
            append(Json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(model)))
            append(",\"messages\":[{\"role\":\"user\",\"content\":")
            append(Json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(prompt)))
            append("}],\"temperature\":")
            append(temperature.toString())
            append("}")
        }
        val result = HttpSupport.execute(client, baseUrl + "/chat/completions", "POST",
            mapOf("Content-Type" to "application/json", "Authorization" to "Bearer " + apiKey), requestJson, 1)
        val body = result["body"]?.toString() ?: ""
        val content = try {
            val parsed = ExpressionParser.jsonToAny(Json.parseToJsonElement(body)) as? Map<*, *>
            val choices = parsed?.get("choices") as? List<*>
            val first = choices?.firstOrNull() as? Map<*, *>
            ((first?.get("message") as? Map<*, *>)?.get("content")?.toString()) ?: body
        } catch (e: Exception) {
            body
        }
        mapOf<String, Any?>("content" to content, "model" to model, "status" to result["status"])
    }
}

/** Shared OkHttp helpers: retries with back-off, JSON-aware response parsing. */
object HttpSupport {
    val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun getText(client: OkHttpClient, url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code + " fetching " + url)
            return response.body?.string() ?: ""
        }
    }

    fun execute(client: OkHttpClient, url: String, method: String, headers: Map<String, String>, bodyText: String, retries: Int): Map<String, Any?> {
        var lastError: IOException? = null
        for (attempt in 0..retries) {
            try {
                val body = if (method == "GET" || method == "DELETE") null
                    else bodyText.toRequestBody("application/json".toMediaType())
                val builder = Request.Builder().url(url)
                for ((k, v) in headers) builder.addHeader(k, v)
                if (body != null) builder.method(method, body) else builder.method(method, null)
                client.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string() ?: ""
                    val json: Any? = try {
                        if (text.trim().startsWith("{") || text.trim().startsWith("[")) ExpressionParser.jsonToAny(Json.parseToJsonElement(text)) else null
                    } catch (e: Exception) {
                        null
                    }
                    return mapOf<String, Any?>(
                        "status" to response.code, "body" to text, "json" to json,
                        "ok" to response.isSuccessful, "attempt" to (attempt + 1),
                    )
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < retries) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("HTTP request failed for " + url)
    }
}
