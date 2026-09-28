package com.homesync.app.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AdultTaskCategory(val displayName: String, val icon: String) {
    HOUSEHOLD("Household", "🏠"),
    PARENTING("Parenting", "👨‍👩‍👧"),
    UTILITIES("Bills & Home", "💡"),
    WELLNESS("Wellness", "🧘"),
    ERRANDS("Errands", "🛒")
}

enum class TaskPriority(val label: String, val colorHex: Long) {
    HIGH("Urgent", 0xFFEF4444),
    MEDIUM("Medium", 0xFFF59E0B),
    LOW("Normal", 0xFF10B981)
}

data class AdultTask(
    val id: String,
    val title: String,
    val category: AdultTaskCategory,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val isCompleted: Boolean = false,
    val timeEstimate: String = "",
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

data class AdultTaskSummary(
    val total: Int,
    val completed: Int,
    val pending: Int,
    val completionPercent: Float,
    val urgentCount: Int
)

object AdultTaskManager {
    private const val PREFS_NAME = "homesync_adult_tasks_prefs"
    private const val KEY_TASKS_JSON = "adult_tasks_json"
    private const val KEY_INITIALIZED = "adult_tasks_initialized"

    private val DEFAULT_ADULT_TASKS = listOf(
        AdultTask(
            id = "def_1",
            title = "🛒 Grocery Shopping & Restock Essentials",
            category = AdultTaskCategory.ERRANDS,
            priority = TaskPriority.HIGH,
            timeEstimate = "45m",
            isCompleted = false
        ),
        AdultTask(
            id = "def_2",
            title = "👨‍👩‍👧 Review Child's Homework & School Diary",
            category = AdultTaskCategory.PARENTING,
            priority = TaskPriority.HIGH,
            timeEstimate = "20m",
            isCompleted = true
        ),
        AdultTask(
            id = "def_3",
            title = "💡 Pay Monthly Electricity & Wi-Fi Bill",
            category = AdultTaskCategory.UTILITIES,
            priority = TaskPriority.HIGH,
            timeEstimate = "10m",
            isCompleted = false
        ),
        AdultTask(
            id = "def_4",
            title = "🪴 Water Balcony Plants & Garden",
            category = AdultTaskCategory.HOUSEHOLD,
            priority = TaskPriority.MEDIUM,
            timeEstimate = "15m",
            isCompleted = true
        ),
        AdultTask(
            id = "def_5",
            title = "🧺 Weekly Laundry & Linens Wash",
            category = AdultTaskCategory.HOUSEHOLD,
            priority = TaskPriority.MEDIUM,
            timeEstimate = "30m",
            isCompleted = false
        ),
        AdultTask(
            id = "def_6",
            title = "🍲 Prepare Healthy Dinner & Meal Prep",
            category = AdultTaskCategory.HOUSEHOLD,
            priority = TaskPriority.MEDIUM,
            timeEstimate = "40m",
            isCompleted = false
        ),
        AdultTask(
            id = "def_7",
            title = "🔒 Check Child Device Screen Time & App Limit",
            category = AdultTaskCategory.PARENTING,
            priority = TaskPriority.MEDIUM,
            timeEstimate = "10m",
            isCompleted = true
        ),
        AdultTask(
            id = "def_8",
            title = "🧘 30-Minute Evening Walk or Workout",
            category = AdultTaskCategory.WELLNESS,
            priority = TaskPriority.LOW,
            timeEstimate = "30m",
            isCompleted = false
        ),
        AdultTask(
            id = "def_9",
            title = "🗑️ Take Out Waste & Recycling Bins",
            category = AdultTaskCategory.HOUSEHOLD,
            priority = TaskPriority.LOW,
            timeEstimate = "5m",
            isCompleted = false
        )
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getTasks(context: Context): List<AdultTask> {
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_TASKS_JSON, null)

        if (jsonString.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<AdultTask>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val categoryName = obj.optString("category", AdultTaskCategory.HOUSEHOLD.name)
                val category = try {
                    AdultTaskCategory.valueOf(categoryName)
                } catch (_: Exception) {
                    AdultTaskCategory.HOUSEHOLD
                }

                val priorityName = obj.optString("priority", TaskPriority.MEDIUM.name)
                val priority = try {
                    TaskPriority.valueOf(priorityName)
                } catch (_: Exception) {
                    TaskPriority.MEDIUM
                }

                list.add(
                    AdultTask(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", ""),
                        category = category,
                        priority = priority,
                        isCompleted = obj.optBoolean("isCompleted", false),
                        timeEstimate = obj.optString("timeEstimate", ""),
                        isCustom = obj.optBoolean("isCustom", false),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        completedAt = if (obj.has("completedAt") && !obj.isNull("completedAt")) obj.optLong("completedAt") else null
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun saveTasks(context: Context, tasks: List<AdultTask>) {
        val prefs = getPrefs(context)
        val jsonArray = JSONArray()
        for (task in tasks) {
            val obj = JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                put("category", task.category.name)
                put("priority", task.priority.name)
                put("isCompleted", task.isCompleted)
                put("timeEstimate", task.timeEstimate)
                put("isCustom", task.isCustom)
                put("createdAt", task.createdAt)
                if (task.completedAt != null) {
                    put("completedAt", task.completedAt)
                }
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_TASKS_JSON, jsonArray.toString()).apply()
    }

    @Synchronized
    fun toggleTask(context: Context, taskId: String): List<AdultTask> {
        val current = getTasks(context)
        val updated = current.map { task ->
            if (task.id == taskId) {
                val newStatus = !task.isCompleted
                task.copy(
                    isCompleted = newStatus,
                    completedAt = if (newStatus) System.currentTimeMillis() else null
                )
            } else {
                task
            }
        }
        saveTasks(context, updated)
        return updated
    }

    @Synchronized
    fun addTask(
        context: Context,
        title: String,
        category: AdultTaskCategory,
        priority: TaskPriority = TaskPriority.MEDIUM,
        timeEstimate: String = ""
    ): List<AdultTask> {
        val current = getTasks(context).toMutableList()
        val newTask = AdultTask(
            id = "custom_" + UUID.randomUUID().toString().take(8),
            title = title.trim(),
            category = category,
            priority = priority,
            isCompleted = false,
            timeEstimate = timeEstimate.trim(),
            isCustom = true,
            createdAt = System.currentTimeMillis()
        )
        // Add new custom task to the top
        current.add(0, newTask)
        saveTasks(context, current)
        return current
    }

    @Synchronized
    fun deleteTask(context: Context, taskId: String): List<AdultTask> {
        val current = getTasks(context).filter { it.id != taskId }
        saveTasks(context, current)
        return current
    }

    @Synchronized
    fun resetAllTasks(context: Context): List<AdultTask> {
        val current = getTasks(context).map { it.copy(isCompleted = false, completedAt = null) }
        saveTasks(context, current)
        return current
    }

    @Synchronized
    fun restoreDefaultTasks(context: Context): List<AdultTask> {
        saveTasks(context, DEFAULT_ADULT_TASKS)
        return DEFAULT_ADULT_TASKS
    }

    fun getSummary(tasks: List<AdultTask>): AdultTaskSummary {
        val total = tasks.size
        val completed = tasks.count { it.isCompleted }
        val pending = total - completed
        val percent = if (total > 0) completed.toFloat() / total.toFloat() else 0f
        val urgent = tasks.count { !it.isCompleted && it.priority == TaskPriority.HIGH }
        return AdultTaskSummary(
            total = total,
            completed = completed,
            pending = pending,
            completionPercent = percent,
            urgentCount = urgent
        )
    }
}
