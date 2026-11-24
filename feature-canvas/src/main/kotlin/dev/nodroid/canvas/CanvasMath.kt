package dev.nodroid.canvas

import dev.nodroid.model.Edge
import dev.nodroid.model.Node
import dev.nodroid.model.WorkflowGraph
import kotlin.math.pow

/** Pure canvas math (no Compose): snap, Bezier wires, viewport culling, layered auto-layout. Unit-tested. */
object CanvasMath {
    data class Point(val x: Float, val y: Float)
    data class Viewport(val offsetX: Float, val offsetY: Float, val scale: Float, val width: Float, val height: Float)

    /** Magnetic snap to a grid (default 20 world units). */
    fun snap(value: Float, grid: Float = 20f): Float = (value / grid).toInt() * grid

    /** Cubic Bezier point at t in [0,1] for a horizontal wire from p0 to p3. */
    fun bezierPoint(p0: Point, p3: Point, t: Float): Point {
        val dx = kotlin.math.abs(p3.x - p0.x).coerceAtLeast(80f)
        val p1 = Point(p0.x + dx, p0.y)
        val p2 = Point(p3.x - dx, p3.y)
        val u = 1 - t
        val x = u.pow(3) * p0.x + 3 * u.pow(2) * t * p1.x + 3 * u * t.pow(2) * p2.x + t.pow(3) * p3.x
        val y = u.pow(3) * p0.y + 3 * u.pow(2) * t * p1.y + 3 * u * t.pow(2) * p2.y + t.pow(3) * p3.y
        return Point(x, y)
    }

    /** True when a node rect (world coords) intersects the visible viewport. */
    fun isVisible(nodeX: Float, nodeY: Float, nodeWidth: Float, nodeHeight: Float, viewport: Viewport): Boolean {
        val left = (0 - viewport.offsetX) / viewport.scale
        val top = (0 - viewport.offsetY) / viewport.scale
        val right = (viewport.width - viewport.offsetX) / viewport.scale
        val bottom = (viewport.height - viewport.offsetY) / viewport.scale
        return nodeX < right && nodeX + nodeWidth > left && nodeY < bottom && nodeY + nodeHeight > top
    }

    /** Sugiyama-style layered layout: topological level -> column, index in level -> row. */
    fun autoLayout(graph: WorkflowGraph, columnGap: Float = 280f, rowGap: Float = 190f): Map<String, Point> {
        val levelOf = mutableMapOf<String, Int>()
        val order = dev.nodroid.engine.CycleDetector.topologicalOrder(graph)
        for (nodeId in order) {
            val incoming = graph.incoming(nodeId)
            levelOf[nodeId] = if (incoming.isEmpty()) 0 else incoming.maxOf { (levelOf[it.fromNodeId] ?: 0) + 1 }
        }
        val perLevel = mutableMapOf<Int, Int>()
        val result = mutableMapOf<String, Point>()
        for (nodeId in order) {
            val level = levelOf[nodeId] ?: 0
            val row = perLevel.getOrDefault(level, 0)
            perLevel[level] = row + 1
            result[nodeId] = Point(level * columnGap, row * rowGap)
        }
        return result
    }

    /** Port anchor positions for a node card (left input, right output), in world coords. */
    fun portAnchors(node: Node, cardWidth: Float = 220f, cardHeight: Float = 96f): Pair<Point, Point> =
        Point(node.x, node.y + cardHeight / 2) to Point(node.x + cardWidth, node.y + cardHeight / 2)

    /** Wire polyline sampling for hit-testing / animation (pulse position at progress). */
    fun wirePoints(from: Point, to: Point, steps: Int = 24): List<Point> =
        (0..steps).map { bezierPoint(from, to, it.toFloat() / steps) }
}
