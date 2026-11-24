package dev.nodroid.engine

import dev.nodroid.model.NodeDescriptor
import dev.nodroid.model.PortType
import dev.nodroid.model.WorkflowGraph

/** Validates every edge against the source/target port types declared in the descriptors. */
object PortValidator {
    data class Violation(val edgeId: String, val message: String)

    fun validate(graph: WorkflowGraph, descriptors: Map<String, NodeDescriptor>): List<Violation> {
        val violations = mutableListOf<Violation>()
        val nodeById = graph.nodes.associateBy { it.id }
        for (edge in graph.edges) {
            val from = nodeById[edge.fromNodeId]
            val to = nodeById[edge.toNodeId]
            if (from == null || to == null) {
                violations.add(Violation(edge.id, "Edge references a missing node"))
                continue
            }
            val fromDesc = descriptors[from.type]
            val toDesc = descriptors[to.type]
            val fromPort = fromDesc?.outputs?.firstOrNull { it.id == edge.fromPortId }
            val toPort = toDesc?.inputs?.firstOrNull { it.id == edge.toPortId }
            if (fromDesc == null || toDesc == null) continue
            if (fromPort == null) violations.add(Violation(edge.id, "Unknown output port " + edge.fromPortId + " on " + from.type))
            if (toPort == null) violations.add(Violation(edge.id, "Unknown input port " + edge.toPortId + " on " + to.type))
            if (fromPort != null && toPort != null && !compatible(fromPort.type, toPort.type)) {
                violations.add(Violation(edge.id, "Type mismatch: " + fromPort.type + " cannot feed " + toPort.type))
            }
        }
        return violations
    }

    /** ANY accepts everything; otherwise types must match exactly. */
    fun compatible(source: PortType, target: PortType): Boolean =
        source == PortType.ANY || target == PortType.ANY || source == target
}
