package dev.nodroid.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val graphJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "run_logs")
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val workflowId: String,
    val nodeId: String,
    val status: String,
    val message: String = "",
    val durationMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
)

@Entity(tableName = "run_checkpoints", primaryKeys = ["runId", "nodeId"])
data class RunCheckpointEntity(
    val runId: String,
    val nodeId: String,
    val stateJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "key_value_state")
data class KeyValueEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface WorkflowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workflow: WorkflowEntity)
    @Query("SELECT * FROM workflows ORDER BY updatedAt DESC")
    suspend fun getAll(): List<WorkflowEntity>
    @Query("SELECT * FROM workflows WHERE id = :id")
    suspend fun getById(id: String): WorkflowEntity?
    @Query("DELETE FROM workflows WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RunLogDao {
    @Insert
    suspend fun insert(log: RunLogEntity)
    @Query("SELECT * FROM run_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int = 100): List<RunLogEntity>
    @Query("SELECT * FROM run_logs WHERE runId = :runId ORDER BY timestamp ASC")
    suspend fun forRun(runId: String): List<RunLogEntity>
    /** Keeps only the latest 500 rows; older rows are deleted. Call after every insert batch. */
    @Query("DELETE FROM run_logs WHERE id NOT IN (SELECT id FROM run_logs ORDER BY timestamp DESC LIMIT 500)")
    suspend fun pruneToLatest500()
    @Query("SELECT COUNT(*) FROM run_logs")
    suspend fun count(): Int
}

@Dao
interface RunCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(checkpoint: RunCheckpointEntity)
    @Query("SELECT * FROM run_checkpoints WHERE runId = :runId AND nodeId = :nodeId")
    suspend fun load(runId: String, nodeId: String): RunCheckpointEntity?
    @Query("SELECT nodeId FROM run_checkpoints WHERE runId = :runId")
    suspend fun completedNodeIds(runId: String): List<String>
    @Query("DELETE FROM run_checkpoints WHERE runId = :runId")
    suspend fun clearRun(runId: String)
}

@Dao
interface KeyValueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(entry: KeyValueEntity)
    @Query("SELECT * FROM key_value_state WHERE key = :key")
    suspend fun get(key: String): KeyValueEntity?
    @Query("DELETE FROM key_value_state WHERE key = :key")
    suspend fun delete(key: String)
    @Query("SELECT * FROM key_value_state ORDER BY key ASC")
    suspend fun getAll(): List<KeyValueEntity>
}
