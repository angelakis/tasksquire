package com.taskwarrior.android.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromTaskStatus(status: TaskStatus): String = status.name

    @TypeConverter
    fun toTaskStatus(name: String): TaskStatus = TaskStatus.valueOf(name)

    @TypeConverter
    fun fromTaskPriority(priority: TaskPriority): String = priority.name

    @TypeConverter
    fun toTaskPriority(name: String): TaskPriority = TaskPriority.valueOf(name)
}
