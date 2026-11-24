package dev.nodroid.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphTest {
    private fun sampleGraph() = WorkflowGraph(
        id = "g1", name = "Sample",
        nodes = listOf(
            Node("a", "trigger.manual", "Manual"),
            Node("b", "logic.if", "If", config = mapOf("condition" to JsonPrimitive("x > 1"))),
            Node("c", "data.template", "Template"),
        ),
        edges = listOf(
            Edge("e1", "a", "out", "b", "in"),
            Edge("e2", "b", "true", "c", "in"),
        ),
    )

    @Test fun `graph stores nodes and edges`() {
        val g = sampleGraph()
        assertEquals(3, g.nodes.size); assertEquals(2, g.edges.size)
    }
    @Test fun `nodeById finds node`() { assertEquals("If", sampleGraph().nodeById("b")?.label) }
    @Test fun `nodeById returns null for unknown`() { assertNull(sampleGraph().nodeById("zz")) }
    @Test fun `outgoing returns edges from node`() { assertEquals(1, sampleGraph().outgoing("a").size) }
    @Test fun `incoming returns edges to node`() { assertEquals(1, sampleGraph().incoming("c").size) }
    @Test fun `triggers filters trigger nodes`() { assertEquals(listOf("a"), sampleGraph().triggers().map { it.id }) }
    @Test fun `blank id rejected`() { assertThrows(IllegalArgumentException::class.java) { WorkflowGraph(id = "", name = "x") } }
    @Test fun `blank name rejected`() { assertThrows(IllegalArgumentException::class.java) { WorkflowGraph(id = "x", name = " ") } }
    @Test fun `unsupported schema version rejected`() { assertThrows(IllegalArgumentException::class.java) { WorkflowGraph(schemaVersion = 2, id = "x", name = "y") } }
    @Test fun `json round trip preserves graph`() {
        val g = sampleGraph()
        val decoded = GraphJson.decode(GraphJson.encode(g))
        assertEquals(g, decoded)
    }
    @Test fun `json decode ignores unknown keys`() {
        val decoded = GraphJson.decode("{\"schemaVersion\":1,\"id\":\"g\",\"name\":\"N\",\"futureField\":123,\"nodes\":[{\"id\":\"a\",\"type\":\"trigger.manual\",\"label\":\"M\"}],\"edges\":[]}")
        assertEquals("g", decoded.id)
    }
    @Test fun `validator rejects duplicate node ids`() {
        val g = WorkflowGraph(id = "g", name = "N", nodes = listOf(Node("a", "t", "1"), Node("a", "t", "2")))
        assertThrows(IllegalArgumentException::class.java) { GraphValidator.validate(g) }
    }
    @Test fun `validator rejects edge to unknown node`() {
        val g = WorkflowGraph(id = "g", name = "N", nodes = listOf(Node("a", "t", "1")), edges = listOf(Edge("e", "a", "out", "ghost", "in")))
        assertThrows(IllegalArgumentException::class.java) { GraphValidator.validate(g) }
    }
    @Test fun `validator rejects self loop`() {
        val g = WorkflowGraph(id = "g", name = "N", nodes = listOf(Node("a", "t", "1")), edges = listOf(Edge("e", "a", "out", "a", "in")))
        assertThrows(IllegalArgumentException::class.java) { GraphValidator.validate(g) }
    }
    @Test fun `validator rejects empty graph`() {
        assertThrows(IllegalArgumentException::class.java) { GraphValidator.validate(WorkflowGraph(id = "g", name = "N")) }
    }
    @Test fun `validator accepts valid graph`() { GraphValidator.validate(sampleGraph()) }
    @Test fun `port types enumerate all six`() { assertEquals(6, PortType.values().size) }
    @Test fun `config field schema defaults`() {
        val f = ConfigFieldSchema("url", "URL", ConfigFieldSchema.FieldKind.TEXT)
        assertFalse(f.required); assertEquals("", f.defaultValue); assertTrue(f.options.isEmpty())
    }
    @Test fun `descriptor carries category and trigger flag`() {
        val d = NodeDescriptor("trigger.manual", "Manual", NodeDescriptor.NodeCategory.TRIGGER, "Run now", isTrigger = true)
        assertTrue(d.isTrigger); assertEquals(NodeDescriptor.NodeCategory.TRIGGER, d.category)
    }
    @Test fun `node config json element readable`() {
        val n = Node("x", "t", "L", config = mapOf("k" to buildJsonObject { put("a", 1) }))
        assertNotNull(n.config["k"])
    }
}
