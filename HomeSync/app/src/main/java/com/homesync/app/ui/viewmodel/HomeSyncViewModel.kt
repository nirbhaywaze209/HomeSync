package com.homesync.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Data class representing a single mission/chore task.
 */
data class MissionItem(
    val id: Int,
    val icon: String,
    val title: String,
    val pointValue: Int,
    val coinValue: Int,
    val isCompleted: Boolean = false,
    val progress: Float = if (isCompleted) 1f else 0f
)

/**
 * Data class representing a guardian-side child task entry.
 */
data class GuardianTask(
    val id: Int,
    val title: String,
    val isCompleted: Boolean = false,
    val progressPercent: Int = if (isCompleted) 100 else 0
)

/**
 * Centralized ViewModel providing state for the HomeSync UI screens.
 * Uses Compose State for reactivity and provides mock data for previews.
 */
class HomeSyncViewModel : ViewModel() {

    // ========================================
    // Child Profile State
    // ========================================
    var childName by mutableStateOf("Child")
        private set

    var childPoints by mutableIntStateOf(0)
        private set

    var childCoins by mutableIntStateOf(0)
        private set

    var childLevel by mutableIntStateOf(1)
        private set

    var childAvatar by mutableStateOf("🧒")
        private set

    // ========================================
    // Daily Missions State
    // ========================================
    private val _missions = mutableStateListOf<MissionItem>()
    val missions: List<MissionItem> get() = _missions

    fun toggleMission(id: Int) {
        val index = _missions.indexOfFirst { it.id == id }
        if (index >= 0) {
            val item = _missions[index]
            val newCompleted = !item.isCompleted
            _missions[index] = item.copy(
                isCompleted = newCompleted,
                progress = if (newCompleted) 1f else 0f
            )
            if (newCompleted) {
                childPoints += item.pointValue
                childCoins += item.coinValue
            }
        }
    }

    // ========================================
    // Screen Time State
    // ========================================
    var screenTimeUsedMinutes by mutableIntStateOf(0)
        private set

    var screenTimeTotalMinutes by mutableIntStateOf(360)
        private set

    val screenTimeRemainingMinutes: Int
        get() = (screenTimeTotalMinutes - screenTimeUsedMinutes).coerceAtLeast(0)

    val screenTimeProgress: Float
        get() = if (screenTimeTotalMinutes > 0)
            (screenTimeUsedMinutes.toFloat() / screenTimeTotalMinutes.toFloat()).coerceIn(0f, 1f)
        else 0f

    // ========================================
    // Guardian — Child Tasks State
    // ========================================
    private val _guardianTasks = mutableStateListOf<GuardianTask>()
    val guardianTasks: List<GuardianTask> get() = _guardianTasks

    fun toggleGuardianTask(id: Int) {
        val index = _guardianTasks.indexOfFirst { it.id == id }
        if (index >= 0) {
            val item = _guardianTasks[index]
            val newCompleted = !item.isCompleted
            _guardianTasks[index] = item.copy(
                isCompleted = newCompleted,
                progressPercent = if (newCompleted) 100 else 0
            )
        }
    }

    // ========================================
    // Guardian Profile State
    // ========================================
    var guardianName by mutableStateOf("Guardian")
        private set

    var isDevicePaused by mutableStateOf(false)
        private set

    fun togglePauseDevice() {
        isDevicePaused = !isDevicePaused
    }

    // ========================================
    // Pairing Code State
    // ========================================
    var pairingCode by mutableStateOf("")
        private set

    companion object {
        /**
         * Creates a mock ViewModel instance for @Preview composables.
         */
        fun createMock(): HomeSyncViewModel {
            return HomeSyncViewModel()
        }
    }
}
