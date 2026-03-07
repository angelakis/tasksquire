package com.tasksquire.android.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE status = 'PENDING' OR status = 'WAITING' ORDER BY entry DESC")
    fun observePendingTasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE status = 'COMPLETED' ORDER BY modified DESC")
    fun observeCompletedTasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE status = 'DELETED' ORDER BY modified DESC")
    fun observeDeletedTasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE uuid = :uuid")
    suspend fun getTaskByUuid(uuid: String): Task?

    @Query("SELECT * FROM tasks WHERE isModifiedLocally = 1")
    suspend fun getModifiedTasks(): List<Task>

    @Query("SELECT * FROM tasks")
    suspend fun getAllTasks(): List<Task>

    @Query("SELECT * FROM tasks")
    fun observeAllTasks(): Flow<List<Task>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<Task>)

    @Update
    suspend fun updateTask(task: Task)

    @Query("DELETE FROM tasks WHERE uuid = :uuid")
    suspend fun deleteTaskByUuid(uuid: String)

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    @Query("UPDATE tasks SET status = 'COMPLETED', modified = :modifiedTime, isModifiedLocally = 1 WHERE uuid = :uuid")
    suspend fun completeTask(uuid: String, modifiedTime: Long = System.currentTimeMillis())

    @Query("UPDATE tasks SET status = 'DELETED', modified = :modifiedTime, isModifiedLocally = 1 WHERE uuid = :uuid")
    suspend fun markTaskDeleted(uuid: String, modifiedTime: Long = System.currentTimeMillis())

    @Query("UPDATE tasks SET isSynced = 1, isModifiedLocally = 0 WHERE uuid = :uuid")
    suspend fun markTaskSynced(uuid: String)

    @Query("SELECT * FROM tasks WHERE project = :project AND status = 'PENDING'")
    fun observeTasksByProject(project: String): Flow<List<Task>>

    @Query("SELECT DISTINCT project FROM tasks WHERE project IS NOT NULL AND status = 'PENDING'")
    fun observeProjects(): Flow<List<String>>

    @Query("SELECT DISTINCT tags FROM tasks WHERE tags IS NOT NULL AND tags != '' AND status = 'PENDING'")
    fun observeTagStrings(): Flow<List<String>>

    @Query("SELECT * FROM tasks WHERE (status = 'PENDING' OR status = 'WAITING') AND description LIKE '%' || :query || '%'")
    fun searchPendingTasks(query: String): Flow<List<Task>>
}
