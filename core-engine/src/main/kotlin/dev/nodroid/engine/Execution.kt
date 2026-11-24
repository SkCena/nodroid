package dev.nodroid.engine

import dev.nodroid.model.Node
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Result of executing one node. */
sealed interface NodeResult {
    val nodeId: String
    val durationMs: Long
    data class Success(override val nodeId: String, val output: Map<String, Any?>, override val durationMs: Long) : NodeResult
    data class Skipped(override val nodeId: String, val reason: String, override val durationMs: Long = 0) : NodeResult
    data class Failed(override val nodeId: String, val error: String, override val durationMs: Long) : NodeResult
}

/** What a node executor receives. `inputs` merges all incoming edge outputs (later edges win on key clash). */
data class ExecutionContext(
    val graphId: String,
    val runId: String,
    val node: Node,
    val inputs: Map<String, Any?>,
    val vault: (String) -> String?,
    val checkpoint: CheckpointStore,
    val runSubflow: suspend (graphId: String, inputs: Map<String, Any?>) -> Map<String, Any?>,
    val resolveTemplate: (String, Map<String, Any?>) -> String,
)

/** A node executor. Implementations live in :core-nodes; the interface lives here so the runner stays pure. */
fun interface NodeExecutor {
    suspend fun execute(context: ExecutionContext): Map<String, Any?>
}

/** Durable checkpoint SPI: the runner writes one record per node before/after execution so a crashed run can resume. */
interface CheckpointStore {
    suspend fun save(runId: String, nodeId: String, stateJson: String)
    suspend fun load(runId: String, nodeId: String): String?
    suspend fun completedNodeIds(runId: String): Set<String>
    suspend fun clear(runId: String)
}

/** In-memory checkpoint store (used by tests and by in-process runs; Room provides the durable one). */
class InMemoryCheckpointStore : CheckpointStore {
    private val data = mutableMapOf<String, MutableMap<String, String>>()
    override suspend fun save(runId: String, nodeId: String, stateJson: String) {
        data.getOrPut(runId) { mutableMapOf() }[nodeId] = stateJson
    }
    override suspend fun load(runId: String, nodeId: String): String? = data[runId]?.get(nodeId)
    override suspend fun completedNodeIds(runId: String): Set<String> = data[runId]?.keys?.toSet() ?: emptySet()
    override suspend fun clear(runId: String) { data.remove(runId) }
}

/** Resolves `{{vault.KEY}}` placeholders inside a config string; missing keys resolve to empty string. */
fun resolveVaultPlaceholders(text: String, vault: (String) -> String?): String {
    val regex = Regex("\\{\\{vault\\.([A-Za-z0-9_]+)\\}\\}")
    return regex.replace(text) { match -> vault(match.groupValues[1]) ?: "" }
}

/** Reads typed config values from a node's raw JSON config map. */
object ConfigReader {
    fun string(config: Map<String, JsonElement>, key: String, default: String = ""): String {
        val el = config[key] ?: return default
        return when (el) {
            is JsonPrimitive -> if (el.isString) el.content else el.toString()
            else -> el.toString()
        }
    }
    fun boolean(config: Map<String, JsonElement>, key: String, default: Boolean = false): Boolean =
        string(config, key, default.toString()).toBooleanStrictOrNull() ?: default
    fun int(config: Map<String, JsonElement>, key: String, default: Int = 0): Int =
        string(config, key, default.toString()).toIntOrNull() ?: default
    fun double(config: Map<String, JsonElement>, key: String, default: Double = 0.0): Double =
        string(config, key, default.toString()).toDoubleOrNull() ?: default
}
