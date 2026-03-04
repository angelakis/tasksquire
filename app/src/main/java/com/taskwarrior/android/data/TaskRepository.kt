package com.taskwarrior.android.data

import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {

    fun observePendingTasks(): Flow<List<Task>> = taskDao.observePendingTasks()
    fun observeCompletedTasks(): Flow<List<Task>> = taskDao.observeCompletedTasks()
    fun observeProjects(): Flow<List<String>> = taskDao.observeProjects()
    fun observeTagStrings(): Flow<List<String>> = taskDao.observeTagStrings()

    suspend fun getTaskByUuid(uuid: String): Task? = taskDao.getTaskByUuid(uuid)
    suspend fun getModifiedTasks(): List<Task> = taskDao.getModifiedTasks()
    suspend fun getAllTasks(): List<Task> = taskDao.getAllTasks()

    suspend fun addTask(task: Task) {
        taskDao.insertTask(task)
    }

    suspend fun updateTask(task: Task) {
        taskDao.updateTask(task.copy(modified = System.currentTimeMillis(), isModifiedLocally = true))
    }

    suspend fun completeTask(uuid: String) {
        taskDao.completeTask(uuid)
    }

    suspend fun deleteTask(uuid: String) {
        taskDao.markTaskDeleted(uuid)
    }

    suspend fun markTaskSynced(uuid: String) {
        taskDao.markTaskSynced(uuid)
    }

    suspend fun importTasks(tasks: List<Task>) {
        taskDao.insertTasks(tasks)
    }

    suspend fun upsertTasksFromSync(tasks: List<Task>) {
        taskDao.insertTasks(tasks.map { it.copy(isSynced = true, isModifiedLocally = false) })
    }
}
