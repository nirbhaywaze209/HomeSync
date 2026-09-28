package com.homesync.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.homesync.app.ui.theme.ParentBorder
import com.homesync.app.ui.theme.ParentCardBg
import com.homesync.app.ui.theme.ParentDarkBg
import com.homesync.app.ui.theme.ParentEmerald
import com.homesync.app.ui.theme.ParentPrimary
import com.homesync.app.ui.theme.ParentSurfaceElevated
import com.homesync.app.ui.theme.ParentTeal
import com.homesync.app.ui.theme.ParentTextMuted
import com.homesync.app.ui.theme.ParentTextPrimary
import com.homesync.app.ui.theme.ParentTextSecondary
import com.homesync.app.util.AdultTask
import com.homesync.app.util.AdultTaskCategory
import com.homesync.app.util.AdultTaskManager
import com.homesync.app.util.TaskPriority

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdultTasksCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var tasks by remember { mutableStateOf(AdultTaskManager.getTasks(context)) }
    var selectedCategory by remember { mutableStateOf<AdultTaskCategory?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val summary = remember(tasks) { AdultTaskManager.getSummary(tasks) }
    val filteredTasks = remember(tasks, selectedCategory) {
        if (selectedCategory == null) tasks else tasks.filter { it.category == selectedCategory }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ParentCardBg),
        border = BorderStroke(1.dp, ParentBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ListAlt,
                        contentDescription = "Tasks",
                        tint = ParentPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Adult Tasks & Household",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = ParentTextPrimary
                        )
                        Text(
                            text = "${summary.completed} of ${summary.total} Completed • ${summary.pending} Pending",
                            fontSize = 12.sp,
                            color = ParentTextSecondary
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Reset button
                    IconButton(
                        onClick = {
                            tasks = AdultTaskManager.resetAllTasks(context)
                            Toast.makeText(context, "Daily tasks reset", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Daily Tasks",
                            tint = ParentTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Add Task Button
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = ParentPrimary, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Task", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Progress Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LinearProgressIndicator(
                    progress = { summary.completionPercent },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = ParentPrimary,
                    trackColor = ParentSurfaceElevated
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${(summary.completionPercent * 100).toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ParentPrimary
                )
            }

            if (summary.urgentCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Color(0x22EF4444),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Color(0x44EF4444))
                ) {
                    Text(
                        text = "${summary.urgentCount} High Priority Task${if (summary.urgentCount > 1) "s" else ""} remaining",
                        color = Color(0xFFF87171),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Category Filter Chips
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChipItem(
                    label = "All (${tasks.size})",
                    isSelected = selectedCategory == null,
                    onClick = { selectedCategory = null }
                )
                AdultTaskCategory.values().forEach { category ->
                    val count = tasks.count { it.category == category }
                    FilterChipItem(
                        label = "${category.displayName} ($count)",
                        isSelected = selectedCategory == category,
                        onClick = {
                            selectedCategory = if (selectedCategory == category) null else category
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Task List
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (filteredTasks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No tasks in this category",
                            color = ParentTextMuted,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    filteredTasks.forEach { task ->
                        AdultTaskItemRow(
                            task = task,
                            onToggle = {
                                tasks = AdultTaskManager.toggleTask(context, task.id)
                            },
                            onDelete = {
                                tasks = AdultTaskManager.deleteTask(context, task.id)
                                Toast.makeText(context, "Task deleted", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // Add Task Dialog
    if (showAddDialog) {
        AddAdultTaskDialog(
            onDismiss = { showAddDialog = false },
            onAddTask = { title, category, priority, timeEstimate ->
                tasks = AdultTaskManager.addTask(
                    context = context,
                    title = title,
                    category = category,
                    priority = priority,
                    timeEstimate = timeEstimate
                )
                showAddDialog = false
                Toast.makeText(context, "Task added", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun FilterChipItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) ParentPrimary else ParentSurfaceElevated,
        border = BorderStroke(1.dp, if (isSelected) ParentPrimary else ParentBorder),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color.Black else ParentTextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun AdultTaskItemRow(
    task: AdultTask,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = if (task.isCompleted) ParentSurfaceElevated.copy(alpha = 0.4f) else ParentSurfaceElevated,
        border = BorderStroke(1.dp, ParentBorder),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox Circle
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (task.isCompleted) ParentEmerald else Color.Transparent)
                    .then(
                        if (!task.isCompleted) Modifier.background(Color.Transparent, CircleShape)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (task.isCompleted) ParentEmerald else Color.Transparent,
                    border = BorderStroke(1.5.dp, if (task.isCompleted) ParentEmerald else ParentBorder),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (task.isCompleted) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Completed",
                                tint = Color.Black,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Task info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (task.isCompleted) ParentTextMuted else ParentTextPrimary,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Category badge
                    Text(
                        text = task.category.displayName,
                        fontSize = 10.sp,
                        color = ParentTextSecondary
                    )

                    // Priority badge
                    val priorityColor = Color(task.priority.colorHex)
                    Surface(
                        color = priorityColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = task.priority.label,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = priorityColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }

                    // Time estimate
                    if (task.timeEstimate.isNotBlank()) {
                        Text(
                            text = task.timeEstimate,
                            fontSize = 10.sp,
                            color = ParentTextMuted
                        )
                    }
                }
            }

            // Delete button for custom tasks or completed tasks
            if (task.isCustom || task.isCompleted) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete task",
                        tint = ParentTextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddAdultTaskDialog(
    onDismiss: () -> Unit,
    onAddTask: (title: String, category: AdultTaskCategory, priority: TaskPriority, timeEstimate: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(AdultTaskCategory.HOUSEHOLD) }
    var selectedPriority by remember { mutableStateOf(TaskPriority.MEDIUM) }
    var timeEstimate by remember { mutableStateOf("15m") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = ParentCardBg,
            border = BorderStroke(1.dp, ParentBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Add Household Task",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = ParentTextPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Description", color = ParentTextSecondary) },
                    placeholder = { Text("e.g. Schedule dentist, Buy groceries", color = ParentTextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ParentTextPrimary,
                        unfocusedTextColor = ParentTextPrimary,
                        focusedBorderColor = ParentPrimary,
                        unfocusedBorderColor = ParentBorder
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Category:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ParentTextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AdultTaskCategory.values().forEach { cat ->
                        FilterChipItem(
                            label = cat.displayName,
                            isSelected = selectedCategory == cat,
                            onClick = { selectedCategory = cat }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Priority:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ParentTextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskPriority.values().forEach { prio ->
                        val isSelected = selectedPriority == prio
                        Surface(
                            color = if (isSelected) Color(prio.colorHex) else ParentSurfaceElevated,
                            border = BorderStroke(1.dp, if (isSelected) Color(prio.colorHex) else ParentBorder),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedPriority = prio }
                        ) {
                            Text(
                                text = prio.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else ParentTextSecondary,
                                modifier = Modifier.padding(vertical = 6.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = timeEstimate,
                    onValueChange = { timeEstimate = it },
                    label = { Text("Time Estimate (e.g. 15m, 30m, 1h)", color = ParentTextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ParentTextPrimary,
                        unfocusedTextColor = ParentTextPrimary,
                        focusedBorderColor = ParentPrimary,
                        unfocusedBorderColor = ParentBorder
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = ParentTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isNotBlank()) {
                                onAddTask(title, selectedCategory, selectedPriority, timeEstimate)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ParentPrimary, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        enabled = title.isNotBlank()
                    ) {
                        Text("Add Task", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
