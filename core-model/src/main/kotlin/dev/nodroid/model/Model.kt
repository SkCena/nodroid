package dev.nodroid.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Port data types. ANY accepts every other type. */
@Serializable
enum class PortType { ANY, STRING, NUMBER, BOOLEAN, JSON, FILE_URI }

/** A node port (input or output). */
@Serializable
data class Port(
    val id: String,
    val name: String,
    val type: PortType = PortType.ANY,
    val required: Boolean = false,
)

/** One field of a node's dynamic config form. */
@Serializable
data class ConfigFieldSchema(
    val key: String,
    val label: String,
    val kind: FieldKind,
    val required: Boolean = false,
    val defaultValue: String = "",
    val options: List<String> = emptyList(),
    val hint: String = "",
    val multiline: Boolean = false,
) {
    @Serializable
    enum class FieldKind { TEXT, NUMBER, BOOLEAN, SELECT, SECRET, CODE, FILE, MULTILINE_TEXT }
}

/** Static description of a node type; the UI renders config forms from this. */
@Serializable
data class NodeDescriptor(
    val type: String,
    val displayName: String,
    val category: NodeCategory,
    val description: String,
    val inputs: List<Port> = listOf(Port("in", "Input", PortType.ANY)),
    val outputs: List<Port> = listOf(Port("out", "Output", PortType.ANY)),
    val configFields: List<ConfigFieldSchema> = emptyList(),
    val isTrigger: Boolean = false,
) {
    @Serializable
    enum class NodeCategory { TRIGGER, LOGIC, DATA, NETWORK, AI, LOCAL }
}

/** A node instance inside a workflow graph. */
@Serializable
data class Node(
    val id: String,
    val type: String,
    val label: String,
    val config: Map<String, JsonElement> = emptyMap(),
    val x: Float = 0f,
    val y: Float = 0f,
)

/** A directed edge from one node's output port to another node's input port. */
@Serializable
data class Edge(
    val id: String,
    val fromNodeId: String,
    val fromPortId: String,
    val toNodeId: String,
    val toPortId: String,
)

/** A complete workflow graph, JSON schema version 1. */
@Serializable
data class WorkflowGraph(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val nodes: List<Node> = emptyList(),
    val edges: List<Edge> = emptyList(),
) {
    init {
        require(schemaVersion == 1) { "Unsupported graph schema version: " + schemaVersion }
        require(id.isNotBlank()) { "Graph id must not be blank" }
        require(name.isNotBlank()) { "Graph name must not be blank" }
    }

    fun nodeById(id: String): Node? = nodes.firstOrNull { it.id == id }
    fun outgoing(nodeId: String): List<Edge> = edges.filter { it.fromNodeId == nodeId }
    fun incoming(nodeId: String): List<Edge> = edges.filter { it.toNodeId == nodeId }
    fun triggers(): List<Node> = nodes.filter { it.type.startsWith("trigger.") }
}
