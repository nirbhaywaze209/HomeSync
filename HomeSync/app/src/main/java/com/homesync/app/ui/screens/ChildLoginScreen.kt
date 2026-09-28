package com.homesync.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homesync.app.ui.theme.ChildLoginTheme
import com.homesync.app.ui.theme.PastelBlueLight
import com.homesync.app.ui.theme.PastelCardBg
import com.homesync.app.ui.theme.PastelGreenBg
import com.homesync.app.ui.theme.PastelGreenMedium
import com.homesync.app.ui.theme.PastelTextDark
import com.homesync.app.ui.theme.PastelTextMuted
import com.homesync.app.ui.theme.PastelYellowSoft

@Composable
fun ChildLoginScreen(
    pairingCode: String = "HS-123456",
    onScanQrCode: () -> Unit = {},
    onNeedHelp: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    if (showQrScannerDialog) {
        AlertDialog(
            onDismissRequest = { showQrScannerDialog = false },
            title = { Text("📷 Parent QR Code Scanner", fontWeight = FontWeight.Bold, color = PastelTextDark) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Point your camera at the QR code displayed on your parent's phone.", color = PastelTextMuted, fontSize = 14.sp)
                    Surface(
                        modifier = Modifier.size(180.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = PastelYellowSoft
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("📱 [ QR Camera Scanner Active ]", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = PastelTextDark)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showQrScannerDialog = false
                        Toast.makeText(context, "Pairing successful! Code matched.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PastelGreenMedium)
                ) {
                    Text("Simulate QR Scan Success")
                }
            },
            dismissButton = {
                TextButton(onClick = { showQrScannerDialog = false }) {
                    Text("Cancel", color = PastelTextMuted)
                }
            },
            containerColor = PastelCardBg
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("❓ Pairing Help", fontWeight = FontWeight.Bold, color = PastelTextDark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Open HomeSync on your Parent/Guardian device.", fontWeight = FontWeight.SemiBold, color = PastelTextDark)
                    Text("2. Go to 'Pair Child' and type the 6-digit code shown on this screen ($pairingCode).", color = PastelTextMuted)
                    Text("3. Once paired, your daily missions and games will sync instantly!", color = PastelTextMuted)
                }
            },
            confirmButton = {
                Button(onClick = { showHelpDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = PastelGreenMedium)) {
                    Text("Got it!")
                }
            },
            containerColor = PastelCardBg
        )
    }

    ChildLoginTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(PastelGreenBg, PastelBlueLight)
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Section
                Text(
                    text = "Family Adventures",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PastelTextDark,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "HomeSync",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = PastelTextMuted,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Adventure-themed emoji illustration
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("☀️", fontSize = 36.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("🏔️", fontSize = 32.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("🌲", fontSize = 32.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("🌳", fontSize = 32.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("🦊", fontSize = 32.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("🐰", fontSize = 32.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("🦋", fontSize = 32.sp)
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Middle Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = PastelCardBg),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Ask a grown-up to scan or enter this code:",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PastelTextMuted,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = PastelYellowSoft,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = pairingCode,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = PastelTextDark,
                                textAlign = TextAlign.Center,
                                letterSpacing = 2.sp,
                                modifier = Modifier.padding(vertical = 16.dp, horizontal = 16.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "This code connects you to your family",
                            fontSize = 12.sp,
                            color = PastelTextMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Bottom Section
                OutlinedButton(
                    onClick = {
                        onScanQrCode()
                        showQrScannerDialog = true
                    },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(2.dp, PastelGreenMedium),
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = "Scan QR Code",
                        tint = PastelTextDark,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Scan Parent's QR Code",
                        color = PastelTextDark,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                TextButton(
                    onClick = {
                        onNeedHelp()
                        showHelpDialog = true
                    }
                ) {
                    Text(
                        text = "Need help?",
                        color = PastelTextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewChildLoginScreen() {
    ChildLoginScreen()
}
