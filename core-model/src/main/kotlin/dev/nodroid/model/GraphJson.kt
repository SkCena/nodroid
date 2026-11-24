package dev.nodroid.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** JSON (de)serialisation for WorkflowGraph v1. Unknown keys are ignored so v1 files stay forward-compatible. */
object GraphJson {
    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    fun encode(graph: WorkflowGraph): String = json.encodeToString(graph)

    fun decode(text: String): WorkflowGraph {
        val graph = json.decodeFromString<WorkflowGraph>(text)
        GraphValidator.validate(graph)
        return graph
    }
}

/** Structural validation of a graph before it is executed or stored. */
object GraphValidator {
    fun validate(graph: WorkflowGraph) {
        val nodeIds = graph.nodes.map { it.id }
        require(nodeIds.size == nodeIds.toSet().size) { "Duplicate node id in graph " + graph.id }
        require(nodeIds.isNotEmpty()) { "Graph must contain at least one node" }
        for (edge in graph.edges) {
            require(edge.fromNodeId in nodeIds) { "Edge " + edge.id + " references unknown source node " + edge.fromNodeId }
            require(edge.toNodeId in nodeIds) { "Edge " + edge.id + " references unknown target node " + edge.toNodeId }
            require(edge.fromNodeId != edge.toNodeId) { "Self-loop edge " + edge.id + " is not allowed" }
        }
        val edgeIds = graph.edges.map { it.id }
        require(edgeIds.size == edgeIds.toSet().size) { "Duplicate edge id in graph " + graph.id }
    }
}
