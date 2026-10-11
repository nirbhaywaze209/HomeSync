package com.homesync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.homesync.app.util.AvatarItem
import com.homesync.app.util.ChildRewardsManager
import com.homesync.app.util.ChildStats

@Composable
fun RewardsShopDialog(
    childId: String,
    currentStats: ChildStats,
    familyId: String = "",
    onStatsUpdated: (ChildStats) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf(currentStats) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🛍️ Rewards Shop",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Text(text = "✕", fontSize = 18.sp, color = Color.Gray)
                    }
                }

                // Balance Badge
                Surface(
                    color = Color(0xFF334155),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Your Coin Balance:",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "🪙 ${stats.coins}",
                                color = Color(0xFFFBBF24),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    text = "Unlock avatar companions to display on your dashboard!",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // List of Avatars
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(ChildRewardsManager.AVAILABLE_AVATARS) { avatar ->
                        val isEquipped = stats.equippedAvatar.id == avatar.id
                        val isUnlocked = stats.unlockedAvatarIds.contains(avatar.id)
                        val canAfford = stats.coins >= avatar.costCoins

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isEquipped) Color(0xFF0F766E) else Color(0xFF0F172A)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Avatar Icon & Details
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(Color(0xFF334155), shape = CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = avatar.icon, fontSize = 24.sp)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = avatar.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color.White
                                        )
                                        Text(
                                            text = avatar.description,
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }

                                // Action Button
                                when {
                                    isEquipped -> {
                                        Surface(
                                            color = Color(0xFF14B8A6),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "Equipped ✔️",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                    isUnlocked -> {
                                        Button(
                                            onClick = {
                                                ChildRewardsManager.unlockAndEquipAvatarOnCloud(context, familyId, childId, avatar) { success, _ ->
                                                    if (success) {
                                                        val updated = ChildRewardsManager.getStats(context, childId)
                                                        stats = updated
                                                        onStatsUpdated(updated)
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Equip", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    else -> {
                                        Button(
                                            onClick = {
                                                ChildRewardsManager.unlockAndEquipAvatarOnCloud(context, familyId, childId, avatar) { success, _ ->
                                                    if (success) {
                                                        val updated = ChildRewardsManager.getStats(context, childId)
                                                        stats = updated
                                                        onStatsUpdated(updated)
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (canAfford) Color(0xFFF59E0B) else Color(0xFF475569)
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "🪙 ${avatar.costCoins}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (canAfford) Color.Black else Color.LightGray
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close Shop", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
