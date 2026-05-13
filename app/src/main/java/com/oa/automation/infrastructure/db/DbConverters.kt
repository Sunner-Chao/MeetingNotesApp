package com.oa.automation.infrastructure.db

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.oa.automation.domain.model.Task

class DbConverters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>): String = gson.toJson(value)

    @TypeConverter
    fun toStringList(value: String): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, listType) ?: emptyList()
    }

    @TypeConverter
    fun fromTaskList(value: List<Task>): String = gson.toJson(value)

    @TypeConverter
    fun toTaskList(value: String): List<Task> {
        val listType = object : TypeToken<List<Task>>() {}.type
        return gson.fromJson(value, listType) ?: emptyList()
    }
}
