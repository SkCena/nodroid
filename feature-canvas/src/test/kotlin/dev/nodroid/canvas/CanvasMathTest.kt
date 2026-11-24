package dev.nodroid.canvas

import dev.nodroid.model.Edge
import dev.nodroid.model.Node
import dev.nodroid.model.WorkflowGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CanvasMathTest {
    @Test fun `snap rounds down to grid`() {
        assertEquals(40f, CanvasMath.snap(47f))
        assertEquals(0f, CanvasMath.snap(19f))
        assertEquals(100f, CanvasMath.snap(100f))
    }
    @Test fun `snap with custom grid`() { assertEquals(30f, CanvasMath.snap(35f, 10f)) }
    @Test fun `bezier endpoints match anchors`() {
        val p0 = CanvasMath.Point(0f, 0f)
        val p3 = CanvasMath.Point(200f, 100f)
        assertEquals(p0, CanvasMath.bezierPoint(p0, p3, 0f))
        assertEquals(p3, CanvasMath.bezierPoint(p0, p3, 1f))
    }
    @Test fun `bezier midpoint is between endpoints`() {
        val mid = CanvasMath.bezierPoint(CanvasMath.Point(0f, 0f), CanvasMath.Point(200f, 0f), 0.5f)
        assertTrue(mid.x > 0f && mid.x < 200f)
        assertEquals(0f, mid.y, 0.001f)
    }
    @Test fun `bezier is monotonic in x for horizontal wire`() {
        val points = CanvasMath.wirePoints(CanvasMath.Point(0f, 0f), CanvasMath.Point(300f, 120f))
        assertEquals(25, points.size)
        for (i in 1 until points.size) assertTrue(points[i].x >= points[i - 1].x)
    }
    @Test fun `viewport culling keeps visible node`() {
        val vp = CanvasMath.Viewport(0f, 0f, 1f, 1000f, 1000f)
        assertTrue(CanvasMath.isVisible(100f, 100f, 220f, 96f, vp))
    }
    @Test fun `viewport culling drops offscreen node`() {
        val vp = CanvasMath.Viewport(0f, 0f, 1f, 1000f, 1000f)
        assertFalse(CanvasMath.isVisible(2000f, 2000f, 220f, 96f, vp))
    }
    @Test fun `viewport culling respects pan offset`() {
        val vp = CanvasMath.Viewport(-2000f, 0f, 1f, 1000f, 1000f)
        assertTrue(CanvasMath.isVisible(2000f, 100f, 220f, 96f, vp))
    }
    @Test fun `viewport culling respects zoom`() {
        val vp = CanvasMath.Viewport(0f, 0f, 0.5f, 1000f, 1000f)
        assertTrue(CanvasMath.isVisible(1500f, 100f, 220f, 96f, vp))
    }
    @Test fun `auto layout puts sources in column zero`() {
        val graph = WorkflowGraph(id = "g", name = "L", nodes = listOf(Node("a", "t", "A"), Node("b", "t", "B"), Node("c", "t", "C")),
            edges = listOf(Edge("e1", "a", "out", "b", "in"), Edge("e2", "b", "out", "c", "in")))
        val layout = CanvasMath.autoLayout(graph)
        assertEquals(0f, layout["a"]!!.x)
        assertEquals(280f, layout["b"]!!.x)
        assertEquals(560f, layout["c"]!!.x)
    }
    @Test fun `auto layout stacks siblings in rows`() {
        val graph = WorkflowGraph(id = "g", name = "D", nodes = listOf(Node("a", "t", "A"), Node("b", "t", "B"), Node("c", "t", "C")),
            edges = listOf(Edge("e1", "a", "out", "b", "in"), Edge("e2", "a", "out", "c", "in")))
        val layout = CanvasMath.autoLayout(graph)
        assertEquals(layout["b"]!!.x, layout["c"]!!.x, 0.001f)
        assertTrue(abs(layout["b"]!!.y - layout["c"]!!.y) > 0f)
    }
    @Test fun `port anchors are left and right edges`() {
        val node = Node("n", "t", "N", x = 100f, y = 50f)
        val (left, right) = CanvasMath.portAnchors(node)
        assertEquals(100f, left.x); assertEquals(320f, right.x); assertEquals(98f, left.y)
    }
    @Test fun `templates are five and all valid`() {
        assertEquals(5, StarterTemplates.ALL.size)
        for (template in StarterTemplates.ALL) {
            dev.nodroid.model.GraphValidator.validate(template)
            assertTrue(template.nodes.isNotEmpty())
        }
    }
    @Test fun `templates use only safe node types`() {
        val forbidden = listOf("sms", "call", "accessibility", "shizuku")
        for (template in StarterTemplates.ALL) {
            for (node in template.nodes) {
                assertFalse(forbidden.any { node.type.contains(it) })
            }
        }
    }
    @Test fun `viewmodel undo redo round trip`() {
        val graph = WorkflowGraph(id = "g", name = "G", nodes = listOf(Node("a", "trigger.manual", "M")))
        val vm = CanvasViewModel(graph)
        vm.addNode("logic.if", "If", 100f, 100f)
        assertEquals(2, vm.state.value.graph.nodes.size)
        assertTrue(vm.state.value.canUndo)
        vm.undo()
        assertEquals(1, vm.state.value.graph.nodes.size)
        assertTrue(vm.state.value.canRedo)
        vm.redo()
        assertEquals(2, vm.state.value.graph.nodes.size)
    }
    @Test fun `viewmodel connect rejects cycle`() {
        val graph = WorkflowGraph(id = "g", name = "G", nodes = listOf(Node("a", "trigger.manual", "A"), Node("b", "logic.if", "B")),
            edges = listOf(Edge("e1", "a", "out", "b", "in")))
        val vm = CanvasViewModel(graph)
        vm.connect("b", "out", "a", "in")
        assertEquals(1, vm.state.value.graph.edges.size)
    }
    @Test fun `viewmodel connect adds edge`() {
        val graph = WorkflowGraph(id = "g", name = "G", nodes = listOf(Node("a", "trigger.manual", "A"), Node("b", "logic.if", "B")))
        val vm = CanvasViewModel(graph)
        vm.connect("a", "out", "b", "in")
        assertEquals(1, vm.state.value.graph.edges.size)
    }
    @Test fun `viewmodel auto layout moves nodes to columns`() {
        val graph = WorkflowGraph(id = "g", name = "G", nodes = listOf(Node("a", "trigger.manual", "A", x = 999f, y = 999f), Node("b", "logic.if", "B", x = 888f, y = 888f)),
            edges = listOf(Edge("e1", "a", "out", "b", "in")))
        val vm = CanvasViewModel(graph)
        vm.autoLayout()
        assertEquals(0f, vm.state.value.graph.nodes.first { it.id == "a" }.x)
        assertEquals(280f, vm.state.value.graph.nodes.first { it.id == "b" }.x)
    }
}
