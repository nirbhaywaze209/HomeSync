package com.homesync.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.homesync.app.util.ChildRewardsManager
import com.homesync.app.util.ChildStats
import kotlinx.coroutines.delay
import kotlin.random.Random

// ----------------------------------------------------
// BLITZ ITEM DEFINITIONS
// ----------------------------------------------------
enum class BlitzItemType(
    val icon: String,
    val label: String,
    val points: Int,
    val coins: Int,
    val isHazard: Boolean
) {
    APPLE("🍎", "+10 Pts", 10, 1, false),
    WATER("💧", "+15 Pts", 15, 2, false),
    BOOK("📚", "+20 Pts", 20, 2, false),
    PLANT("🪴", "+15 Pts", 15, 2, false),
    STAR("⭐", "+30 Pts", 30, 3, false),
    COIN("🪙", "+10 Coins!", 10, 10, false),
    DIAMOND("💎", "+50 Pts!", 50, 5, false),
    FRENZY("🔥", "+100 FRENZY!", 100, 10, false),
    SCREENTIME("⏰", "-15 Pts", -15, 0, true),
    BOMB("💣", "-25 Pts", -25, 0, true)
}

data class ScoreToast(
    val id: Long,
    val text: String,
    val isPositive: Boolean
)

@Composable
fun HabitArcadeGame(
    childId: String,
    childName: String,
    initialTab: Int = 0,
    onBackToHome: () -> Unit,
    onRewardsUpdated: (ChildStats) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(initialTab.coerceIn(0, 1)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF1E1B4B), Color(0xFF311042))
                )
            )
            .padding(14.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onBackToHome,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("⬅️ Exit Arcade", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Surface(
                color = Color(0x33FBBF24),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFBBF24))
            ) {
                Text(
                    text = "🎮 GAMIFIED ARCADE",
                    color = Color(0xFFFBBF24),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2 Clean Game Mode Selector
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF1E293B),
            contentColor = Color(0xFF38BDF8),
            modifier = Modifier.clip(RoundedCornerShape(16.dp))
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("⭐ Star Blitz Arcade", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("🧠 Memory Match", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (selectedTab == 0) {
            StarBlitzEngine(childId = childId, onRewardsClaimed = onRewardsUpdated)
        } else {
            EcoMemoryProGame(childId = childId, onRewardsClaimed = onRewardsUpdated)
        }
    }
}

// ====================================================
// GAME 1: ⭐ HABIT STAR BLITZ (FAST TAP ARCADE)
// ====================================================
@Composable
fun StarBlitzEngine(
    childId: String,
    onRewardsClaimed: (ChildStats) -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }
    var timeLeft by remember { mutableIntStateOf(30) }
    var score by remember { mutableIntStateOf(0) }
    var coinsEarned by remember { mutableIntStateOf(0) }
    var streak by remember { mutableIntStateOf(0) }
    var maxStreak by remember { mutableIntStateOf(0) }
    var showGameOverDialog by remember { mutableStateOf(false) }
    var activeToast by remember { mutableStateOf<ScoreToast?>(null) }

    val gridSlots = remember { mutableStateListOf<BlitzItemType?>().apply { repeat(9) { add(null) } } }

    val multiplier = when {
        streak >= 10 -> 5
        streak >= 5 -> 3
        streak >= 3 -> 2
        else -> 1
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            timeLeft = 30
            score = 0
            coinsEarned = 0
            streak = 0
            maxStreak = 0
            while (timeLeft > 0 && isPlaying) {
                delay(1000L)
                timeLeft--
            }
            if (isPlaying) {
                isPlaying = false
                showGameOverDialog = true
            }
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val allTypes = BlitzItemType.values()
            while (isPlaying) {
                val speedDelay = (650L - (30 - timeLeft) * 12L).coerceAtLeast(300L)
                val activeCount = Random.nextInt(2, 4)

                val nextSlots = Array<BlitzItemType?>(9) { null }
                val chosenIndices = (0 until 9).shuffled().take(activeCount)
                for (idx in chosenIndices) {
                    nextSlots[idx] = allTypes[Random.nextInt(allTypes.size)]
                }
                for (i in 0 until 9) {
                    gridSlots[i] = nextSlots[i]
                }

                delay(speedDelay)
            }
            for (i in 0 until 9) gridSlots[i] = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ArcadeStatusBar(score = score, timeLeft = timeLeft, coins = coinsEarned, combo = "${multiplier}x 🔥")

        Spacer(modifier = Modifier.height(10.dp))

        if (!isPlaying && !showGameOverDialog) {
            ArcadeStartCard(
                title = "⭐ Habit Star Blitz",
                icon = "⭐",
                description = "Fast tap arcade game!\nTap healthy habit items (🍎 💧 📚 ⭐ 🪙 💎 🔥) as speed accelerates!\nAvoid screen traps (⏰ 💣) to build 5x Combo Fire Streaks!",
                onStart = { isPlaying = true }
            )
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(gridSlots) { index, item ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    if (item == null) Color(0xFF1E293B)
                                    else if (item.isHazard) Color(0xFF451A1A)
                                    else Color(0xFF064E3B)
                                )
                                .border(
                                    width = if (item != null && !item.isHazard) 2.dp else 0.dp,
                                    color = if (item != null && !item.isHazard) Color(0xFF4ADE80) else Color.Transparent,
                                    shape = RoundedCornerShape(18.dp)
                                )
                                .clickable(enabled = item != null) {
                                    item?.let { clickedItem ->
                                        if (clickedItem.isHazard) {
                                            score = (score + clickedItem.points).coerceAtLeast(0)
                                            streak = 0
                                            activeToast = ScoreToast(System.currentTimeMillis(), "TRAP! ${clickedItem.label}", false)
                                        } else {
                                            val addedScore = clickedItem.points * multiplier
                                            val addedCoins = clickedItem.coins * (if (multiplier > 2) 2 else 1)
                                            score += addedScore
                                            coinsEarned += addedCoins
                                            streak++
                                            if (streak > maxStreak) maxStreak = streak
                                            activeToast = ScoreToast(System.currentTimeMillis(), "+$addedScore COMBO! 🔥", true)
                                        }
                                        gridSlots[index] = null
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (item != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = item.icon, fontSize = 36.sp)
                                    Text(text = item.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (item.isHazard) Color(0xFFF87171) else Color(0xFF4ADE80))
                                }
                            }
                        }
                    }
                }

                activeToast?.let { toast ->
                    Text(
                        text = toast.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (toast.isPositive) Color(0xFF4ADE80) else Color(0xFFEF4444),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        if (showGameOverDialog) {
            ArcadeGameOverDialog(
                title = "STAR BLITZ COMPLETE!",
                score = score,
                maxStreak = maxStreak,
                coinsEarned = coinsEarned,
                childId = childId,
                onDismiss = { showGameOverDialog = false },
                onPlayAgain = { isPlaying = true; showGameOverDialog = false },
                onRewardsClaimed = onRewardsClaimed
            )
        }
    }
}

// ====================================================
// GAME 2: 🧠 ECO MEMORY PRO (PAIR FLIPPER)
// ====================================================
data class ProMemoryCard(
    val id: Int,
    val icon: String,
    val label: String,
    var isFaceUp: Boolean = false,
    var isMatched: Boolean = false
)

@Composable
fun EcoMemoryProGame(
    childId: String,
    onRewardsClaimed: (ChildStats) -> Unit
) {
    val context = LocalContext.current
    var isProMode by remember { mutableStateOf(false) }

    val baseIcons = listOf(
        Pair("🍎", "Apple"), Pair("💧", "Water"), Pair("📚", "Reading"),
        Pair("🪴", "Plants"), Pair("🚲", "Cycling"), Pair("🧹", "Clean"),
        Pair("🛡️", "Shield"), Pair("🔒", "Secure")
    )

    fun createDeck(pro: Boolean): List<ProMemoryCard> {
        val selectedIcons = if (pro) baseIcons else baseIcons.take(6)
        val deck = mutableListOf<ProMemoryCard>()
        var idCounter = 0
        for (item in selectedIcons) {
            deck.add(ProMemoryCard(idCounter++, item.first, item.second))
            deck.add(ProMemoryCard(idCounter++, item.first, item.second))
        }
        return deck.shuffled()
    }

    var cards by remember { mutableStateOf(createDeck(isProMode)) }
    val flippedIndices = remember { mutableStateListOf<Int>() }
    var movesCount by remember { mutableIntStateOf(0) }
    var matchedPairs by remember { mutableIntStateOf(0) }
    var showWinDialog by remember { mutableStateOf(false) }

    val totalPairs = if (isProMode) 8 else 6

    LaunchedEffect(flippedIndices.size) {
        if (flippedIndices.size == 2) {
            val firstIdx = flippedIndices[0]
            val secondIdx = flippedIndices[1]
            val card1 = cards[firstIdx]
            val card2 = cards[secondIdx]

            delay(500L)

            if (card1.icon == card2.icon) {
                cards = cards.mapIndexed { idx, c ->
                    if (idx == firstIdx || idx == secondIdx) c.copy(isMatched = true, isFaceUp = true) else c
                }
                matchedPairs++
                if (matchedPairs == totalPairs) {
                    showWinDialog = true
                }
            } else {
                cards = cards.mapIndexed { idx, c ->
                    if (idx == firstIdx || idx == secondIdx) c.copy(isFaceUp = false) else c
                }
            }
            flippedIndices.clear()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !isProMode,
                        onClick = {
                            isProMode = false
                            cards = createDeck(false)
                            movesCount = 0
                            matchedPairs = 0
                        },
                        label = { Text("Normal 3x4", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = isProMode,
                        onClick = {
                            isProMode = true
                            cards = createDeck(true)
                            movesCount = 0
                            matchedPairs = 0
                        },
                        label = { Text("Pro 4x4 🌟", fontSize = 11.sp) }
                    )
                }

                Text("Pairs: $matchedPairs/$totalPairs | Moves: $movesCount", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isProMode) 4 else 3),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(cards) { index, card ->
                val isVisible = card.isFaceUp || card.isMatched

                Box(
                    modifier = Modifier
                        .aspectRatio(0.85f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (card.isMatched) Color(0xFF047857)
                            else if (isVisible) Color(0xFF1E293B)
                            else Color(0xFF4338CA)
                        )
                        .clickable(enabled = !isVisible && flippedIndices.size < 2) {
                            cards = cards.mapIndexed { idx, c ->
                                if (idx == index) c.copy(isFaceUp = true) else c
                            }
                            flippedIndices.add(index)
                            movesCount++
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isVisible) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = card.icon, fontSize = if (isProMode) 24.sp else 30.sp)
                            Text(text = card.label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else {
                        Text(text = "❓", fontSize = if (isProMode) 22.sp else 26.sp)
                    }
                }
            }
        }

        if (showWinDialog) {
            val rankTitle = when {
                movesCount <= totalPairs + 3 -> "⭐⭐⭐ S-RANK MEMORY MASTER!"
                movesCount <= totalPairs + 7 -> "⭐⭐ A-RANK GREAT JOB!"
                else -> "⭐ B-RANK COMPLETED!"
            }
            val rewardXp = if (isProMode) 150 else 100
            val rewardCoins = if (movesCount <= totalPairs + 3) 60 else 40

            Dialog(onDismissRequest = { showWinDialog = false }) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "🏆🎉", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = rankTitle, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFBBF24), textAlign = TextAlign.Center)
                        Text(text = "Completed in $movesCount moves!", fontSize = 13.sp, color = Color(0xFF94A3B8))

                        Spacer(modifier = Modifier.height(16.dp))

                        Surface(
                            color = Color(0xFF0F172A),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("EARNED XP", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                                    Text("⭐ +$rewardXp", fontSize = 20.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("EARNED COINS", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                                    Text("🪙 +$rewardCoins", fontSize = 20.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                val updatedStats = ChildRewardsManager.addRewards(
                                    context = context,
                                    childId = childId,
                                    earnedPoints = rewardXp,
                                    earnedCoins = rewardCoins
                                )
                                onRewardsClaimed(updatedStats)
                                showWinDialog = false
                                cards = createDeck(isProMode)
                                movesCount = 0
                                matchedPairs = 0
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("Claim 🪙 $rewardCoins Coins & ⭐ $rewardXp XP", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------
// SHARED HELPER COMPONENTS
// ----------------------------------------------------
@Composable
private fun ArcadeStatusBar(score: Int, timeLeft: Int, coins: Int, combo: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SCORE", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                Text("$score", fontSize = 18.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.ExtraBold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TIME", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                Text("${timeLeft}s", fontSize = 18.sp, color = if (timeLeft <= 5) Color(0xFFEF4444) else Color.White, fontWeight = FontWeight.ExtraBold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("COINS", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                Text("🪙 $coins", fontSize = 18.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.ExtraBold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("COMBO", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                Text(combo, fontSize = 18.sp, color = Color(0xFF4ADE80), fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun ArcadeStartCard(title: String, icon: String, description: String, onStart: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = icon, fontSize = 56.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = description, fontSize = 13.sp, color = Color(0xFF94A3B8), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(0.85f).height(52.dp)
            ) {
                Text("🚀 START BLITZ GAME (30s)", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
            }
        }
    }
}

@Composable
private fun ArcadeGameOverDialog(
    title: String,
    score: Int,
    maxStreak: Int,
    coinsEarned: Int,
    childId: String,
    onDismiss: () -> Unit,
    onPlayAgain: () -> Unit,
    onRewardsClaimed: (ChildStats) -> Unit
) {
    val context = LocalContext.current
    val totalXpEarned = (score / 2).coerceAtLeast(20)
    val totalCoins = (coinsEarned + (score / 15)).coerceAtLeast(10)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "🏆🎉", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Final Score: $score | Max Streak: ${maxStreak}x", fontSize = 13.sp, color = Color(0xFF94A3B8))

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("EARNED XP", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                            Text("⭐ +$totalXpEarned", fontSize = 20.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("EARNED COINS", fontSize = 10.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                            Text("🪙 +$totalCoins", fontSize = 20.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        val updatedStats = ChildRewardsManager.addRewards(
                            context = context,
                            childId = childId,
                            earnedPoints = totalXpEarned,
                            earnedCoins = totalCoins,
                            gameScore = score
                        )
                        onRewardsClaimed(updatedStats)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("🎉 Claim Rewards & Bank Coins 🪙", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = onPlayAgain) {
                    Text("Play Again 🔄", color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
