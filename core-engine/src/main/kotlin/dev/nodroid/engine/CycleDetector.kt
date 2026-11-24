package dev.nodroid.engine

import dev.nodroid.model.WorkflowGraph

class CycleDetectedException(message: String) : IllegalStateException(message)

/** Kahn's algorithm: returns nodes in topological order, or throws [CycleDetectedException]. */
object CycleDetector {
    fun topologicalOrder(graph: WorkflowGraph): List<String> {
        val inDegree = mutableMapOf<String, Int>()
        val adjacency = mutableMapOf<String, MutableList<String>>()
        for (node in graph.nodes) {
            inDegree[node.id] = 0
            adjacency[node.id] = mutableListOf()
        }
        for (edge in graph.edges) {
            adjacency[edge.fromNodeId]?.add(edge.toNodeId)
            inDegree[edge.toNodeId] = (inDegree[edge.toNodeId] ?: 0) + 1
        }
        val queue = ArrayDeque<String>()
        inDegree.filter { it.value == 0 }.keys.sorted().forEach { queue.add(it) }
        val order = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            order.add(current)
            for (next in adjacency[current].orEmpty()) {
                inDegree[next] = (inDegree[next] ?: 0) - 1
                if (inDegree[next] == 0) queue.add(next)
            }
        }
        if (order.size != graph.nodes.size) {
            val cyclic = graph.nodes.map { it.id }.filter { it !in order }
            throw CycleDetectedException("Cycle detected involving nodes: " + cyclic.joinToString(", "))
        }
        return order
    }

    fun hasCycle(graph: WorkflowGraph): Boolean = try {
        topologicalOrder(graph)
        false
    } catch (e: CycleDetectedException) {
        true
    }
}
