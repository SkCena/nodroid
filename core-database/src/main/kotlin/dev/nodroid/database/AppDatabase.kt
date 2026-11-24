package dev.nodroid.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.nodroid.engine.CheckpointStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Database(
    entities = [WorkflowEntity::class, RunLogEntity::class, RunCheckpointEntity::class, KeyValueEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workflowDao(): WorkflowDao
    abstract fun runLogDao(): RunLogDao
    abstract fun runCheckpointDao(): RunCheckpointDao
    abstract fun keyValueDao(): KeyValueDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "nodroid.db")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { instance = it }
        }
    }
}

/** Durable [CheckpointStore] backed by Room, so a crashed or delayed run can resume. */
class RoomCheckpointStore(private val dao: RunCheckpointDao) : CheckpointStore {
    override suspend fun save(runId: String, nodeId: String, stateJson: String) {
        withContext(Dispatchers.IO) { dao.save(RunCheckpointEntity(runId, nodeId, stateJson)) }
    }
    override suspend fun load(runId: String, nodeId: String): String? =
        withContext(Dispatchers.IO) { dao.load(runId, nodeId)?.stateJson }
    override suspend fun completedNodeIds(runId: String): Set<String> =
        withContext(Dispatchers.IO) { dao.completedNodeIds(runId).toSet() }
    override suspend fun clear(runId: String) {
        withContext(Dispatchers.IO) { dao.clearRun(runId) }
    }
}

/** Persistent key-value state used by the KV node (`get`/`set`/`increment`/`delete`). */
class RoomKvStore(private val dao: KeyValueDao) {
    suspend fun get(key: String): String? = withContext(Dispatchers.IO) { dao.get(key)?.value }
    suspend fun set(key: String, value: String) = withContext(Dispatchers.IO) { dao.set(KeyValueEntity(key, value)) }
    suspend fun delete(key: String) = withContext(Dispatchers.IO) { dao.delete(key) }
    suspend fun increment(key: String, by: Long = 1): Long {
        val current = get(key)?.toLongOrNull() ?: 0L
        val next = current + by
        set(key, next.toString())
        return next
    }
    suspend fun getAll(): Map<String, String> = withContext(Dispatchers.IO) { dao.getAll().associate { it.key to it.value } }
}
