package dev.nodroid.runtime

import android.content.Context
import dev.nodroid.database.AppDatabase
import dev.nodroid.database.RoomCheckpointStore
import dev.nodroid.database.RoomKvStore
import dev.nodroid.engine.DagRunner
import dev.nodroid.model.GraphJson
import dev.nodroid.model.WorkflowGraph
import dev.nodroid.nodes.NodeRegistry
import dev.nodroid.nodes.RoomKvAdapter
import dev.nodroid.security.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Holds the runtime dependencies for WorkManager workers and broadcast receivers, which cannot
 * use constructor injection. Initialised once by the Application class.
 */
object RuntimeHolder {
    lateinit var appContext: Context
        private set
    private lateinit var vault: VaultManager
    private lateinit var database: AppDatabase

    fun init(context: Context) {
        appContext = context.applicationContext
        vault = VaultManager(appContext)
        database = AppDatabase.get(appContext)
    }

    fun vaultManager(): VaultManager = vault
    fun database(): AppDatabase = database

    suspend fun loadGraph(workflowId: String): WorkflowGraph? {
        val entity = database.workflowDao().getById(workflowId) ?: return null
        return GraphJson.decode(entity.graphJson)
    }

    /** Runs a workflow end-to-end and persists a run log + checkpoint state. */
    suspend fun runWorkflow(workflowId: String, runId: String, initialInputs: Map<String, Any?> = emptyMap()): Boolean {
        val graph = loadGraph(workflowId) ?: return false
        val kvStore = RoomKvAdapter(RoomKvStore(database.keyValueDao()))
        val executors = NodeRegistry.create(appContext, kvStore)
        val checkpointStore = RoomCheckpointStore(database.runCheckpointDao())
        val runner = DagRunner(executors, checkpointStore, vaultResolver = { key ->
            kotlinx.coroutines.runBlocking { vault.get(key) }
        })
        val report = runner.run(graph, runId, initialInputs)
        val logDao = database.runLogDao()
        for ((nodeId, result) in report.results) {
            val status = when (result) {
                is dev.nodroid.engine.NodeResult.Success -> "SUCCESS"
                is dev.nodroid.engine.NodeResult.Skipped -> "SKIPPED"
                is dev.nodroid.engine.NodeResult.Failed -> "FAILED"
            }
            val message = when (result) {
                is dev.nodroid.engine.NodeResult.Failed -> result.error
                is dev.nodroid.engine.NodeResult.Skipped -> result.reason
                else -> ""
            }
            logDao.insert(dev.nodroid.database.RunLogEntity(runId = runId, workflowId = workflowId, nodeId = nodeId, status = status, message = message, durationMs = result.durationMs))
        }
        logDao.pruneToLatest500()
        return report.success
    }

    fun runWorkflowAsync(workflowId: String, runId: String, initialInputs: Map<String, Any?> = emptyMap()) {
        CoroutineScope(Dispatchers.Default).launch { runWorkflow(workflowId, runId, initialInputs) }
    }
}
