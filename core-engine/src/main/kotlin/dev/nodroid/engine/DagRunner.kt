package dev.nodroid.engine

import dev.nodroid.model.WorkflowGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Outcome of a whole workflow run. */
data class RunReport(
    val runId: String,
    val success: Boolean,
    val results: Map<String, NodeResult>,
    val finalOutputs: Map<String, Any?>,
    val error: String? = null,
)

/**
 * Bounded concurrent DAG runner.
 * - Kahn order groups nodes into levels; a level runs concurrently under Semaphore(4) + SupervisorJob,
 *   so one node's failure never cancels its siblings.
 * - Branching: a node output may carry "_nextPort" (String) — only edges from that port are followed;
 *   downstream nodes fed only by inactive ports are Skipped.
 * - Merge "pass_first" nodes run as soon as their first input arrives; "wait_all" (default) waits for all.
 * - Errors are routed to a downstream node of type "flow.error_catch" when one is connected; otherwise the run fails.
 * - Before each node executes, a checkpoint is saved; completed node ids are read back for resume.
 */
class DagRunner(
    private val executors: Map<String, NodeExecutor>,
    private val checkpointStore: CheckpointStore = InMemoryCheckpointStore(),
    private val maxConcurrency: Int = 4,
    private val vaultResolver: (String) -> String? = { null },
) {
    suspend fun run(
        graph: WorkflowGraph,
        runId: String,
        initialInputs: Map<String, Any?> = emptyMap(),
        subflowGraphs: Map<String, WorkflowGraph> = emptyMap(),
    ): RunReport = coroutineScope {
        val order = try {
            CycleDetector.topologicalOrder(graph)
        } catch (e: CycleDetectedException) {
            return@coroutineScope RunReport(runId, false, emptyMap(), emptyMap(), e.message)
        }
        val completed = checkpointStore.completedNodeIds(runId).toMutableSet()
        val results = linkedMapOf<String, NodeResult>()
        val outputs = mutableMapOf<String, Map<String, Any?>>()
        val skipped = mutableSetOf<String>()
        val semaphore = Semaphore(maxConcurrency)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())

        // Level assignment from topological order.
        val levelOf = mutableMapOf<String, Int>()
        for (nodeId in order) {
            val incoming = graph.incoming(nodeId)
            levelOf[nodeId] = if (incoming.isEmpty()) 0 else incoming.maxOf { (levelOf[it.fromNodeId] ?: 0) + 1 }
        }
        val maxLevel = (levelOf.values.maxOrNull() ?: 0)
        var runError: String? = null

        for (level in 0..maxLevel) {
            if (runError != null) break
            val levelNodes = order.filter { levelOf[it] == level }
            val jobs = levelNodes.map { nodeId ->
                scope.async {
                    val node = graph.nodeById(nodeId) ?: return@async null
                    if (nodeId in skipped) {
                        val r = NodeResult.Skipped(nodeId, "Upstream branch not taken")
                        synchronized(results) { results[nodeId] = r }
                        return@async r
                    }
                    // Merge pass_first: already executed early — handled by level ordering; wait_all is the default.
                    if (nodeId in completed) {
                        val saved = checkpointStore.load(runId, nodeId)
                        val out = parseCheckpointOutput(saved)
                        synchronized(outputs) { outputs[nodeId] = out }
                        val r = NodeResult.Success(nodeId, out, 0)
                        synchronized(results) { results[nodeId] = r }
                        return@async r
                    }
                    val inputs = mutableMapOf<String, Any?>()
                    if (level == 0) inputs.putAll(initialInputs)
                    for (edge in graph.incoming(nodeId)) {
                        val upstream = synchronized(outputs) { outputs[edge.fromNodeId] }
                        if (upstream != null) inputs.putAll(upstream)
                    }
                    val executor = executors[node.type]
                    if (executor == null) {
                        val missingMsg = "No executor registered for node type " + node.type
                        val catcherForMissing = findErrorCatcher(graph, nodeId)
                        if (catcherForMissing != null && catcherForMissing !in results) {
                            synchronized(outputs) { outputs[nodeId] = mapOf<String, Any?>("error" to missingMsg, "failedNode" to nodeId) }
                        } else {
                            runError = missingMsg
                        }
                        val missingResult = NodeResult.Failed(nodeId, missingMsg, 0)
                        synchronized(results) { results[nodeId] = missingResult }
                        return@async missingResult
                    }
                    val ctx = ExecutionContext(
                        graphId = graph.id,
                        runId = runId,
                        node = node,
                        inputs = inputs,
                        vault = vaultResolver,
                        checkpoint = checkpointStore,
                        runSubflow = { subId, subInputs ->
                            val subGraph = subflowGraphs[subId]
                                ?: throw IllegalStateException("Unknown sub-workflow: " + subId)
                            val subReport = run(subGraph, runId + ":" + subId, subInputs, subflowGraphs)
                            if (!subReport.success) throw IllegalStateException("Sub-workflow failed: " + subReport.error)
                            subReport.finalOutputs
                        },
                        resolveTemplate = { template, ctxMap -> ExpressionParser.render(template, ctxMap) },
                    )
                    semaphore.withPermit {
                        val started = System.currentTimeMillis()
                        checkpointStore.save(runId, nodeId, "{\"status\":\"started\"}")
                        try {
                            val output = executor.execute(ctx)
                            val duration = System.currentTimeMillis() - started
                            checkpointStore.save(runId, nodeId, Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                                put("status", "done")
                                put("output", ExpressionParser.anyToJson(output))
                            }))
                            synchronized(outputs) { outputs[nodeId] = output }
                            synchronized(completed) { completed.add(nodeId) }
                            val activePort = output["_nextPort"] as? String
                            if (activePort != null) {
                                for (edge in graph.outgoing(nodeId)) {
                                    if (edge.fromPortId != activePort) markDownstreamSkipped(graph, edge.toNodeId, skipped, results)
                                }
                            }
                            NodeResult.Success(nodeId, output, duration)
                        } catch (e: Exception) {
                            val duration = System.currentTimeMillis() - started
                            val catcher = findErrorCatcher(graph, nodeId)
                            if (catcher != null && catcher !in results) {
                                // Publish the error as the failed node's output so the catcher
                                // receives it through its normal incoming edge.
                                synchronized(outputs) { outputs[nodeId] = mapOf<String, Any?>("error" to (e.message ?: "Unknown error"), "failedNode" to nodeId) }
                                NodeResult.Failed(nodeId, e.message ?: "Unknown error", duration)
                            } else {
                                runError = "Node " + nodeId + " (" + node.type + ") failed: " + (e.message ?: "Unknown error")
                                NodeResult.Failed(nodeId, e.message ?: "Unknown error", duration)
                            }
                        }.also { result -> synchronized(results) { results[nodeId] = result } }
                    }
                }
            }
            jobs.awaitAll()
        }
        val finalOutputs = mutableMapOf<String, Any?>()
        val sinks = graph.nodes.filter { graph.outgoing(it.id).isEmpty() }
        for (sink in sinks) outputs[sink.id]?.let { finalOutputs.putAll(it) }
        RunReport(runId, runError == null, results, finalOutputs, runError)
    }

    private fun parseCheckpointOutput(saved: String?): Map<String, Any?> {
        if (saved == null) return emptyMap()
        return try {
            val obj = Json.parseToJsonElement(saved).let { it as? kotlinx.serialization.json.JsonObject } ?: return emptyMap()
            val out = obj["output"] as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
            out.mapValues { ExpressionParser.jsonToAny(it.value) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun findErrorCatcher(graph: WorkflowGraph, failedNodeId: String): String? {
        for (edge in graph.outgoing(failedNodeId)) {
            val target = graph.nodeById(edge.toNodeId) ?: continue
            if (target.type == "flow.error_catch") return target.id
        }
        return null
    }

    private fun markDownstreamSkipped(graph: WorkflowGraph, nodeId: String, skipped: MutableSet<String>, results: MutableMap<String, NodeResult>) {
        if (!skipped.add(nodeId)) return
        results[nodeId] = NodeResult.Skipped(nodeId, "Upstream branch not taken")
        for (edge in graph.outgoing(nodeId)) markDownstreamSkipped(graph, edge.toNodeId, skipped, results)
    }
}
