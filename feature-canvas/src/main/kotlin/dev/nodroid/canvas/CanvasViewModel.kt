package dev.nodroid.canvas

import androidx.lifecycle.ViewModel
import dev.nodroid.engine.CycleDetector
import dev.nodroid.model.Edge
import dev.nodroid.model.Node
import dev.nodroid.model.WorkflowGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class CanvasState(
    val graph: WorkflowGraph,
    val selectedNodeId: String? = null,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val runningNodeIds: Set<String> = emptySet(),
    val nodeTimingsMs: Map<String, Long> = emptyMap(),
)

/** Canvas state + Undo/Redo command stack (snapshots of the whole graph). */
class CanvasViewModel(initialGraph: WorkflowGraph) : ViewModel() {
    private val undoStack = ArrayDeque<WorkflowGraph>()
    private val redoStack = ArrayDeque<WorkflowGraph>()
    private val _state = MutableStateFlow(CanvasState(graph = initialGraph))
    val state: StateFlow<CanvasState> = _state.asStateFlow()

    private fun pushHistory() {
        undoStack.addLast(_state.value.graph)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        refreshHistoryFlags()
    }

    private fun refreshHistoryFlags() {
        _state.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.graph)
        _state.update { it.copy(graph = previous, selectedNodeId = null) }
        refreshHistoryFlags()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.graph)
        _state.update { it.copy(graph = next, selectedNodeId = null) }
        refreshHistoryFlags()
    }

    fun addNode(type: String, label: String, x: Float, y: Float) {
        pushHistory()
        val node = Node(id = UUID.randomUUID().toString(), type = type, label = label,
            x = CanvasMath.snap(x), y = CanvasMath.snap(y))
        _state.update { it.copy(graph = it.graph.copy(nodes = it.graph.nodes + node), selectedNodeId = node.id) }
    }

    fun moveNode(nodeId: String, x: Float, y: Float) {
        _state.update { state ->
            val nodes = state.graph.nodes.map { if (it.id == nodeId) it.copy(x = CanvasMath.snap(x), y = CanvasMath.snap(y)) else it }
            state.copy(graph = state.graph.copy(nodes = nodes))
        }
    }

    fun beginMove(nodeId: String) { pushHistory() }

    fun deleteNode(nodeId: String) {
        pushHistory()
        _state.update { state ->
            state.copy(
                graph = state.graph.copy(
                    nodes = state.graph.nodes.filter { it.id != nodeId },
                    edges = state.graph.edges.filter { it.fromNodeId != nodeId && it.toNodeId != nodeId },
                ),
                selectedNodeId = null,
            )
        }
    }

    fun connect(fromNodeId: String, fromPortId: String, toNodeId: String, toPortId: String) {
        if (fromNodeId == toNodeId) return
        pushHistory()
        val edge = Edge(id = UUID.randomUUID().toString(), fromNodeId = fromNodeId, fromPortId = fromPortId, toNodeId = toNodeId, toPortId = toPortId)
        val candidate = _state.value.graph.copy(edges = _state.value.graph.edges + edge)
        if (CycleDetector.hasCycle(candidate)) {
            undoStack.removeLastOrNull()
            refreshHistoryFlags()
            return
        }
        _state.update { it.copy(graph = candidate) }
    }

    fun select(nodeId: String?) { _state.update { it.copy(selectedNodeId = nodeId) } }

    fun setViewport(offsetX: Float, offsetY: Float, scale: Float) {
        _state.update { it.copy(offsetX = offsetX, offsetY = offsetY, scale = scale.coerceIn(0.25f, 3f)) }
    }

    fun autoLayout() {
        pushHistory()
        val positions = CanvasMath.autoLayout(_state.value.graph)
        val nodes = _state.value.graph.nodes.map { node ->
            val point = positions[node.id] ?: return@map node
            node.copy(x = point.x, y = point.y)
        }
        _state.update { it.copy(graph = it.graph.copy(nodes = nodes), offsetX = 0f, offsetY = 0f, scale = 1f) }
    }

    fun setRunning(nodeIds: Set<String>, timings: Map<String, Long> = emptyMap()) {
        _state.update { it.copy(runningNodeIds = nodeIds, nodeTimingsMs = timings) }
    }

    fun updateNodeConfig(nodeId: String, config: Map<String, kotlinx.serialization.json.JsonElement>) {
        pushHistory()
        val nodes = _state.value.graph.nodes.map { if (it.id == nodeId) it.copy(config = config) else it }
        _state.update { it.copy(graph = it.graph.copy(nodes = nodes)) }
    }
}
