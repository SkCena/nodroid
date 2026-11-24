package dev.nodroid.engine

import dev.nodroid.model.Edge
import dev.nodroid.model.Node
import dev.nodroid.model.NodeDescriptor
import dev.nodroid.model.Port
import dev.nodroid.model.PortType
import dev.nodroid.model.WorkflowGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    // ---------- CycleDetector ----------
    private fun linearGraph() = WorkflowGraph(id = "g", name = "L", nodes = listOf(Node("a", "t", "A"), Node("b", "t", "B"), Node("c", "t", "C")),
        edges = listOf(Edge("e1", "a", "out", "b", "in"), Edge("e2", "b", "out", "c", "in")))
    private fun cyclicGraph() = WorkflowGraph(id = "g", name = "C", nodes = listOf(Node("a", "t", "A"), Node("b", "t", "B")),
        edges = listOf(Edge("e1", "a", "out", "b", "in"), Edge("e2", "b", "out", "a", "in")))

    @Test fun `topological order respects edges`() {
        assertEquals(listOf("a", "b", "c"), CycleDetector.topologicalOrder(linearGraph()))
    }
    @Test fun `single node graph has trivial order`() {
        val g = WorkflowGraph(id = "g", name = "S", nodes = listOf(Node("only", "t", "O")))
        assertEquals(listOf("only"), CycleDetector.topologicalOrder(g))
    }
    @Test fun `diamond graph orders before after`() {
        val g = WorkflowGraph(id = "g", name = "D", nodes = listOf(Node("a", "t", "A"), Node("b", "t", "B"), Node("c", "t", "C"), Node("d", "t", "D")),
            edges = listOf(Edge("e1", "a", "out", "b", "in"), Edge("e2", "a", "out", "c", "in"), Edge("e3", "b", "out", "d", "in"), Edge("e4", "c", "out", "d", "in")))
        val order = CycleDetector.topologicalOrder(g)
        assertTrue(order.indexOf("a") < order.indexOf("d"))
        assertTrue(order.indexOf("b") < order.indexOf("d"))
    }
    @Test fun `cycle throws with node names`() {
        val ex = assertThrows(CycleDetectedException::class.java) { CycleDetector.topologicalOrder(cyclicGraph()) }
        assertTrue(ex.message!!.contains("a"))
    }
    @Test fun `hasCycle true for cyclic`() { assertTrue(CycleDetector.hasCycle(cyclicGraph())) }
    @Test fun `hasCycle false for acyclic`() { assertFalse(CycleDetector.hasCycle(linearGraph())) }
    @Test fun `self loop is a cycle`() {
        val g = WorkflowGraph(id = "g", name = "S", nodes = listOf(Node("a", "t", "A")), edges = listOf(Edge("e1", "a", "out", "a", "in")))
        assertTrue(CycleDetector.hasCycle(g))
    }

    // ---------- PortValidator ----------
    private fun descriptor(type: String, out: PortType = PortType.STRING, inp: PortType = PortType.STRING) =
        NodeDescriptor(type, type, NodeDescriptor.NodeCategory.DATA, "d",
            inputs = listOf(Port("in", "In", inp)), outputs = listOf(Port("out", "Out", out)))

    @Test fun `compatible same types pass`() {
        val g = linearGraph()
        val descs = mapOf("t" to descriptor("t"))
        assertTrue(PortValidator.validate(g, descs).isEmpty())
    }
    @Test fun `any source feeds any target`() {
        assertTrue(PortValidator.compatible(PortType.ANY, PortType.NUMBER))
        assertTrue(PortValidator.compatible(PortType.STRING, PortType.ANY))
    }
    @Test fun `mismatched types are violations`() {
        val g = linearGraph()
        val descs = mapOf("t" to NodeDescriptor("t", "t", NodeDescriptor.NodeCategory.DATA, "d",
            inputs = listOf(Port("in", "In", PortType.NUMBER)), outputs = listOf(Port("out", "Out", PortType.STRING))))
        assertEquals(2, PortValidator.validate(g, descs).size)
    }
    @Test fun `unknown port is a violation`() {
        val g = WorkflowGraph(id = "g", name = "U", nodes = listOf(Node("a", "t", "A"), Node("b", "t", "B")),
            edges = listOf(Edge("e1", "a", "nope", "b", "in")))
        assertEquals(1, PortValidator.validate(g, mapOf("t" to descriptor("t"))).size)
    }
    @Test fun `number cannot feed boolean`() { assertFalse(PortValidator.compatible(PortType.NUMBER, PortType.BOOLEAN)) }
    @Test fun `json cannot feed string`() { assertFalse(PortValidator.compatible(PortType.JSON, PortType.STRING)) }

    // ---------- ExpressionParser ----------
    private val ctx = mapOf<String, Any?>(
        "user" to mapOf("name" to "  Sk Akram ", "age" to 23, "tags" to listOf("a", "b")),
        "price" to mapOf("value" to "42.567"),
        "missing" to null,
    )
    @Test fun `plain text unchanged`() { assertEquals("hello", ExpressionParser.render("hello", ctx)) }
    @Test fun `simple path resolves`() { assertEquals("  Sk Akram ", ExpressionParser.render("{{user.name}}", ctx)) }
    @Test fun `trim and upper filters chain`() { assertEquals("SK AKRAM", ExpressionParser.render("{{user.name | trim | upper}}", ctx)) }
    @Test fun `lower filter`() { assertEquals("sk akram", ExpressionParser.render("{{user.name | trim | lower}}", ctx)) }
    @Test fun `list index access`() { assertEquals("b", ExpressionParser.render("{{user.tags[1]}}", ctx)) }
    @Test fun `default fallback on missing`() { assertEquals("guest", ExpressionParser.render("{{missing.name ?? \"guest\"}}", ctx)) }
    @Test fun `default not used when present`() { assertEquals("23", ExpressionParser.render("{{user.age ?? 99}}", ctx)) }
    @Test fun `number filter parses`() { assertEquals(42.567, ExpressionParser.evaluate("price.value | number", ctx)) }
    @Test fun `round filter with digits`() { assertEquals(42.57, ExpressionParser.evaluate("price.value | number | round(2)", ctx)) }
    @Test fun `round filter no digits`() { assertEquals(43.0, ExpressionParser.evaluate("price.value | number | round", ctx)) }
    @Test fun `base64 round trip`() {
        val encoded = ExpressionParser.render("{{user.name | trim | base64Encode}}", ctx)
        assertEquals("Sk Akram", ExpressionParser.render("{{x | base64Decode}}", mapOf("x" to encoded)))
    }
    @Test fun `urlEncode encodes spaces`() { assertEquals("Sk+Akram", ExpressionParser.render("{{user.name | trim | urlEncode}}", ctx)) }
    @Test fun `regexExtract first group`() { assertEquals("42", ExpressionParser.render("{{price.value | regexExtract(\"([0-9]+)\")}}", ctx)) }
    @Test fun `regexExtract no match returns empty`() { assertEquals("", ExpressionParser.render("{{user.name | regexExtract(\"[0-9]+\")}}", ctx)) }
    @Test fun `jsonStringify map`() { assertEquals("{\"name\":\"  Sk Akram \",\"age\":23,\"tags\":[\"a\",\"b\"]}", ExpressionParser.render("{{user | jsonStringify}}", ctx)) }
    @Test fun `jsonParse returns map`() {
        val out = ExpressionParser.evaluate("x | jsonParse", mapOf("x" to "{\"b\":\"hi\"}"))
        assertEquals("hi", (out as Map<*, *>)["b"])
    }
    @Test fun `dateFormat formats epoch millis`() {
        val out = ExpressionParser.render("{{ts | dateFormat(\"yyyy\")}}", mapOf("ts" to 1790000000000L))
        assertEquals(4, out.length)
    }
    @Test fun `unknown filter throws`() { assertThrows(ExpressionException::class.java) { ExpressionParser.render("{{user.name | nope}}", ctx) } }
    @Test fun `unterminated string throws`() { assertThrows(ExpressionException::class.java) { ExpressionParser.render("{{user.name | regexExtract(\"abc)}}", ctx) } }
    @Test fun `multiple placeholders in one template`() {
        assertEquals("Sk Akram is 23", ExpressionParser.render("{{user.name | trim}} is {{user.age}}", ctx))
    }

    // ---------- Vault placeholders ----------
    @Test fun `vault placeholder resolves`() {
        assertEquals("Bearer secret-1", resolveVaultPlaceholders("Bearer {{vault.API_KEY}}", { if (it == "API_KEY") "secret-1" else null }))
    }
    @Test fun `missing vault key resolves empty`() {
        assertEquals("Bearer ", resolveVaultPlaceholders("Bearer {{vault.NOPE}}", { null }))
    }
    @Test fun `plain text without vault untouched`() {
        assertEquals("no secrets here", resolveVaultPlaceholders("no secrets here", { "x" }))
    }

    // ---------- DagRunner ----------
    private fun echoExecutor(output: Map<String, Any?> = mapOf("ok" to true)) = NodeExecutor { output }
    @Test fun `runner executes linear graph in order`() = runBlocking {
        val seen = mutableListOf<String>()
        val execs = mapOf<String, NodeExecutor>("t" to NodeExecutor { ctx2 -> seen.add(ctx2.node.id); mapOf("v" to ctx2.node.id) })
        val report = DagRunner(execs).run(linearGraph(), "r1")
        assertTrue(report.success)
        assertEquals(listOf("a", "b", "c"), seen)
        assertEquals("c", report.finalOutputs["v"])
    }
    @Test fun `runner fails on cycle`() = runBlocking {
        val report = DagRunner(emptyMap()).run(cyclicGraph(), "r2")
        assertFalse(report.success)
        assertNotNull(report.error)
    }
    @Test fun `runner reports unknown executor`() = runBlocking {
        val report = DagRunner(emptyMap()).run(linearGraph(), "r3")
        assertFalse(report.success)
    }
    @Test fun `runner passes upstream output as input`() = runBlocking {
        val execs = mapOf<String, NodeExecutor>(
            "t" to NodeExecutor { ctx2 -> if (ctx2.node.id == "a") mapOf("n" to 5) else mapOf("n" to ((ctx2.inputs["n"] as? Int ?: 0) + 1)) },
        )
        val report = DagRunner(execs).run(linearGraph(), "r4")
        assertTrue(report.success)
        assertEquals(7, report.finalOutputs["n"])
    }
    @Test fun `runner branches on nextPort and skips other branch`() = runBlocking {
        val graph = WorkflowGraph(id = "g", name = "B", nodes = listOf(Node("a", "t", "A"), Node("b", "t", "B"), Node("c", "t", "C")),
            edges = listOf(Edge("e1", "a", "yes", "b", "in"), Edge("e2", "a", "no", "c", "in")))
        val execs = mapOf<String, NodeExecutor>("t" to NodeExecutor { ctx2 ->
            if (ctx2.node.id == "a") mapOf("_nextPort" to "yes", "v" to "yes-branch") else mapOf("v" to ctx2.node.id)
        })
        val report = DagRunner(execs).run(graph, "r5")
        assertTrue(report.success)
        assertTrue(report.results["b"] is NodeResult.Success)
        assertTrue(report.results["c"] is NodeResult.Skipped)
    }
    @Test fun `runner error catcher absorbs failure`() = runBlocking {
        val graph = WorkflowGraph(id = "g", name = "E", nodes = listOf(Node("a", "t", "A"), Node("catch", "flow.error_catch", "Catch")),
            edges = listOf(Edge("e1", "a", "out", "catch", "in")))
        val execs = mapOf<String, NodeExecutor>(
            "t" to NodeExecutor { throw IllegalStateException("boom") },
            "flow.error_catch" to NodeExecutor { ctx2 -> mapOf("caught" to (ctx2.inputs["error"] ?: "none")) },
        )
        val report = DagRunner(execs).run(graph, "r6")
        assertTrue(report.results["a"] is NodeResult.Failed)
        assertEquals("boom", report.results["catch"]?.let { (it as NodeResult.Success).output["caught"] })
    }
    @Test fun `runner without catcher fails run`() = runBlocking {
        val execs = mapOf<String, NodeExecutor>("t" to NodeExecutor { throw IllegalStateException("boom") })
        val report = DagRunner(execs).run(linearGraph(), "r7")
        assertFalse(report.success)
        assertTrue(report.error!!.contains("boom"))
    }
    @Test fun `checkpoint resume skips completed nodes`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.save("r8", "a", "{\"status\":\"done\",\"output\":{\"v\":\"saved\"}}")
        var aRan = false
        val execs = mapOf<String, NodeExecutor>("t" to NodeExecutor { ctx2 ->
            if (ctx2.node.id == "a") aRan = true
            mapOf("v" to ctx2.node.id)
        })
        val report = DagRunner(execs, store).run(linearGraph(), "r8")
        assertTrue(report.success)
        assertFalse(aRan)
        assertEquals("saved", report.results["a"]?.let { (it as NodeResult.Success).output["v"] })
    }
    @Test fun `in-memory checkpoint store round trip`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.save("r9", "n1", "state-1")
        assertEquals("state-1", store.load("r9", "n1"))
        assertEquals(setOf("n1"), store.completedNodeIds("r9"))
        store.clear("r9")
        assertTrue(store.completedNodeIds("r9").isEmpty())
        assertNull(store.load("r9", "n1"))
    }
    @Test fun `subflow executes nested graph`() = runBlocking {
        val sub = WorkflowGraph(id = "sub", name = "Sub", nodes = listOf(Node("s1", "t", "S1")))
        val main = WorkflowGraph(id = "g", name = "M", nodes = listOf(Node("a", "flow.subflow", "Call")))
        val execs = mapOf<String, NodeExecutor>("flow.subflow" to NodeExecutor { ctx2 ->
            val out = ctx2.runSubflow("sub", mapOf("in" to 1))
            mapOf("subResult" to out)
        }, "t" to echoExecutor(mapOf("sOut" to 42)))
        val report = DagRunner(execs).run(main, "r10", subflowGraphs = mapOf("sub" to sub))
        assertTrue(report.success)
    }
    @Test fun `config reader parses typed values`() {
        val cfg = mapOf("a" to kotlinx.serialization.json.JsonPrimitive("hello"), "b" to kotlinx.serialization.json.JsonPrimitive("42"), "c" to kotlinx.serialization.json.JsonPrimitive("true"))
        assertEquals("hello", ConfigReader.string(cfg, "a"))
        assertEquals(42, ConfigReader.int(cfg, "b"))
        assertTrue(ConfigReader.boolean(cfg, "c"))
        assertEquals("def", ConfigReader.string(cfg, "missing", "def"))
        assertEquals(7, ConfigReader.int(cfg, "missing", 7))
    }
}
