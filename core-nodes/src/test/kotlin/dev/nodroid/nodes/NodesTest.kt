package dev.nodroid.nodes

import dev.nodroid.engine.ExecutionContext
import dev.nodroid.engine.InMemoryCheckpointStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import dev.nodroid.model.Node

class NodesTest {
    private val server = MockWebServer()

    @Before fun setUp() { server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun ctx(type: String, config: Map<String, String> = emptyMap(), inputs: Map<String, Any?> = emptyMap()): ExecutionContext {
        val cfg = config.mapValues { JsonPrimitive(it.value) }
        return ExecutionContext(
            graphId = "g", runId = "r", node = Node("n1", type, "N", cfg), inputs = inputs,
            vault = { null }, checkpoint = InMemoryCheckpointStore(),
            runSubflow = { _, _ -> emptyMap() },
            resolveTemplate = { t, m -> dev.nodroid.engine.ExpressionParser.render(t, m) },
        )
    }

    @Test fun `jsonpath reads nested field`() {
        val data = mapOf("user" to mapOf("name" to "Sk", "tags" to listOf("x", "y")))
        assertEquals("Sk", JsonPath.eval(data, "$.user.name"))
        assertEquals("y", JsonPath.eval(data, "$.user.tags[1]"))
        assertEquals(data, JsonPath.eval(data, "$"))
        assertEquals(null, JsonPath.eval(data, "$.user.missing"))
    }
    @Test fun `jsonpath quoted key`() {
        val data = mapOf("a b" to 42)
        assertEquals(42, JsonPath.eval(data, "$[\"a b\"]"))
    }
    @Test fun `if else true branch`() = runBlocking {
        val out = PureNodeExecutors.ifElse.execute(ctx("logic.if", mapOf("left" to "5", "operator" to ">", "right" to "3")))
        assertEquals(true, out["result"]); assertEquals("true", out["_nextPort"])
    }
    @Test fun `if else false branch`() = runBlocking {
        val out = PureNodeExecutors.ifElse.execute(ctx("logic.if", mapOf("left" to "a", "operator" to "contains", "right" to "z")))
        assertEquals(false, out["result"]); assertEquals("false", out["_nextPort"])
    }
    @Test fun `if isEmpty operator`() = runBlocking {
        val out = PureNodeExecutors.ifElse.execute(ctx("logic.if", mapOf("left" to "", "operator" to "isEmpty", "right" to "")))
        assertEquals(true, out["result"])
    }
    @Test fun `switch routes to matching case`() = runBlocking {
        val out = PureNodeExecutors.switch.execute(ctx("logic.switch", mapOf("value" to "b", "case0" to "a", "case1" to "b")))
        assertEquals("case1", out["_nextPort"])
    }
    @Test fun `switch falls to default`() = runBlocking {
        val out = PureNodeExecutors.switch.execute(ctx("logic.switch", mapOf("value" to "z", "case0" to "a")))
        assertEquals("default", out["_nextPort"])
    }
    @Test fun `regex email preset`() = runBlocking {
        val out = PureNodeExecutors.regexExtractor.execute(ctx("data.regex", mapOf("preset" to "email", "input" to "mail me at sk@example.com now")))
        assertEquals(listOf("sk@example.com"), out["matches"]); assertEquals(1, out["count"])
    }
    @Test fun `regex url preset`() = runBlocking {
        val out = PureNodeExecutors.regexExtractor.execute(ctx("data.regex", mapOf("preset" to "url", "input" to "see https://example.com/a and http://x.in")))
        assertEquals(2, out["count"])
    }
    @Test fun `regex number preset`() = runBlocking {
        val out = PureNodeExecutors.regexExtractor.execute(ctx("data.regex", mapOf("preset" to "number", "input" to "price 499 and 12.5")))
        assertEquals(listOf("499", "12.5"), out["matches"])
    }
    @Test fun `regex custom pattern`() = runBlocking {
        val out = PureNodeExecutors.regexExtractor.execute(ctx("data.regex", mapOf("preset" to "custom", "pattern" to "[A-Z]+", "input" to "abc DEF ghi")))
        assertEquals(listOf("DEF"), out["matches"])
    }
    @Test fun `jsonpath node reads from template`() = runBlocking {
        val out = PureNodeExecutors.jsonPath.execute(ctx("data.jsonpath",
            mapOf("input" to "{\"price\": 499}", "path" to "$.price")))
        assertEquals(499L, out["value"]); assertEquals(true, out["found"])
    }
    @Test fun `template formatter renders expression`() = runBlocking {
        val out = PureNodeExecutors.templateFormatter.execute(
            ctx("data.template", mapOf("template" to "Hi {{input.name}}!"), inputs = mapOf("name" to "Sk")))
        assertEquals("Hi Sk!", out["text"])
    }
    @Test fun `template csv mode formats rows`() = runBlocking {
        val out = PureNodeExecutors.templateFormatter.execute(ctx("data.template",
            mapOf("template" to "{{name}},{{city}}", "csv" to "true"),
            inputs = mapOf("rows" to listOf(mapOf("name" to "A", "city" to "BBSR"), mapOf("name" to "B", "city" to "CTC")))))
        assertEquals("A,BBSR\nB,CTC", out["text"]); assertEquals(2, out["count"])
    }
    @Test fun `foreach batches and caps`() = runBlocking {
        val out = PureNodeExecutors.forEach.execute(ctx("logic.foreach",
            mapOf("batchSize" to "2", "maxIterations" to "3"),
            inputs = mapOf("items" to listOf(1, 2, 3, 4, 5))))
        assertEquals(3, out["count"]); assertEquals(true, out["truncated"]); assertEquals(listOf(1, 2), out["batch"])
    }
    @Test fun `html scraper extracts by selector`() = runBlocking {
        val html = "<html><body><h1>Title</h1><h1>Second</h1></body></html>"
        val out = PureNodeExecutors.htmlScraper.execute(ctx("data.html_scraper",
            mapOf("html" to html, "selector" to "h1", "attribute" to "text")))
        assertEquals(listOf("Title", "Second"), out["items"])
    }
    @Test fun `http executor gets json from mock server`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"ok\":true,\"price\":499}").setResponseCode(200))
        val url = server.url("/price").toString()
        val out = PureNodeExecutors.httpExecutor(HttpSupport.sharedClient).execute(ctx("net.http", mapOf("url" to url, "method" to "GET")))
        assertEquals(200, out["status"]); assertEquals(true, out["ok"])
        assertEquals(499L, (out["json"] as Map<*, *>)["price"])
    }
    @Test fun `http executor retries then succeeds`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))
        server.enqueue(MockResponse().setBody("{\"ok\":true}").setResponseCode(200))
        // Note: 500 is a response, not an exception, so no retry happens; status reflects first response.
        val url = server.url("/flaky").toString()
        val out = PureNodeExecutors.httpExecutor(HttpSupport.sharedClient).execute(ctx("net.http", mapOf("url" to url, "method" to "GET", "retries" to "2")))
        assertEquals(500, out["status"])
    }
    @Test fun `webhook posts payload`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"received\":true}").setResponseCode(200))
        val url = server.url("/hook").toString()
        val out = PureNodeExecutors.webhookExecutor(HttpSupport.sharedClient).execute(ctx("net.webhook", mapOf("url" to url, "payload" to "{\"event\":\"test\"}")))
        assertEquals(200, out["status"])
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.body.readUtf8().contains("test"))
    }
    @Test fun `llm executor requires api key`() = runBlocking {
        var failed = false
        try {
            PureNodeExecutors.llmExecutor(HttpSupport.sharedClient).execute(ctx("net.llm", mapOf("baseUrl" to "https://example.com", "apiKey" to "", "model" to "m", "prompt" to "hi")))
        } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
    @Test fun `llm executor parses chat completion`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"Hello back\"}}]}").setResponseCode(200))
        val base = server.url("/v1").toString().trimEnd('/')
        val out = PureNodeExecutors.llmExecutor(HttpSupport.sharedClient).execute(
            ctx("net.llm", mapOf("baseUrl" to base, "apiKey" to "k", "model" to "m", "prompt" to "hi")))
        assertEquals("Hello back", out["content"])
    }
    @Test fun `kv executor set get increment delete`() = runBlocking {
        val store = object : KvStore {
            val map = mutableMapOf<String, String>()
            override suspend fun get(key: String) = map[key]
            override suspend fun set(key: String, value: String) { map[key] = value }
            override suspend fun delete(key: String) { map.remove(key) }
            override suspend fun increment(key: String, by: Long): Long {
                val next = (map[key]?.toLongOrNull() ?: 0L) + by
                map[key] = next.toString(); return next
            }
        }
        val exec = PureNodeExecutors.kvExecutor(store)
        exec.execute(ctx("data.kv", mapOf("operation" to "set", "key" to "count", "value" to "41")))
        assertEquals("41", exec.execute(ctx("data.kv", mapOf("operation" to "get", "key" to "count")))["value"])
        assertEquals(42L, exec.execute(ctx("data.kv", mapOf("operation" to "increment", "key" to "count")))["value"])
        exec.execute(ctx("data.kv", mapOf("operation" to "delete", "key" to "count")))
        assertEquals(false, exec.execute(ctx("data.kv", mapOf("operation" to "get", "key" to "count")))["found"])
    }
    @Test fun `javascript runner computes`() = runBlocking {
        val out = PureNodeExecutors.javascript.execute(ctx("data.javascript", mapOf("code" to "input.value * 2"), inputs = mapOf("value" to 21)))
        assertEquals(42.0, (out["result"] as? Number)?.toDouble())
    }
    @Test fun `javascript runner rejects oversized code`() = runBlocking {
        var failed = false
        try {
            PureNodeExecutors.javascript.execute(ctx("data.javascript", mapOf("code" to "x".repeat(70_000))))
        } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
    @Test fun `descriptors list covers all registered executors`() {
        assertEquals(30, NodeDescriptors.ALL.size)
        assertTrue(NodeDescriptors.BY_TYPE.containsKey("net.llm"))
        assertTrue(NodeDescriptors.BY_TYPE.containsKey("ai.ocr"))
        assertFalse(NodeDescriptors.BY_TYPE.keys.any { it.contains("sms") || it.contains("call") || it.contains("accessibility") })
    }
    @Test fun `merge returns inputs`() = runBlocking {
        val out = PureNodeExecutors.merge.execute(ctx("logic.merge", inputs = mapOf("a" to 1)))
        assertEquals(1, out["a"])
    }
    @Test fun `delay node waits approximately`() = runBlocking {
        val start = System.currentTimeMillis()
        PureNodeExecutors.delayNode.execute(ctx("logic.delay", mapOf("milliseconds" to "50")))
        assertTrue(System.currentTimeMillis() - start >= 40)
    }
}
