package com.homesync.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * WhatsApp-style Profile Avatar with circular frame, camera badge overlay for editable avatars,
 * and tap actions to open full-screen viewer or camera.
 */
@Composable
fun WhatsAppProfileAvatar(
    bitmap: Bitmap?,
    name: String,
    size: Dp = 80.dp,
    isEditable: Boolean = false,
    backgroundColor: Color = Color(0xFF075E54), // WhatsApp dark green
    badgeColor: Color = Color(0xFF25D366), // WhatsApp bright green
    onClick: () -> Unit = {},
    onCameraClick: () -> Unit = onClick,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomEnd
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(backgroundColor)
                .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "$name Profile Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val initial = name.trim().take(1).uppercase().ifBlank { "H" }
                Text(
                    text = initial,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.42f).sp
                )
            }
        }

        if (isEditable) {
            Surface(
                onClick = onCameraClick,
                shape = CircleShape,
                color = badgeColor,
                border = BorderStroke(2.dp, Color.White),
                shadowElevation = 4.dp,
                modifier = Modifier.size((size.value * 0.36f).coerceAtLeast(26f).dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.CameraAlt,
                        contentDescription = "Edit Profile Picture",
                        tint = Color.White,
                        modifier = Modifier.size((size.value * 0.20f).coerceAtLeast(14f).dp)
                    )
                }
            }
        }
    }
}

/**
 * WhatsApp-style Full Screen Profile Picture Viewer Dialog with pinch-to-zoom, pan,
 * top action bar, and edit options.
 */
@Composable
fun WhatsAppProfileViewerDialog(
    bitmap: Bitmap?,
    title: String = "Profile Photo",
    isEditable: Boolean = false,
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit = {},
    onChooseGallery: () -> Unit = {},
    onRemovePhoto: () -> Unit = {}
) {
    var showEditModal by remember { mutableStateOf(false) }

    // Gesture Transformation state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // High-Res Image Canvas with Pinch-Zoom & Panning
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.8f, 5.0f)
                            if (scale > 1f) {
                                val maxX = (size.width * (scale - 1)) / 2f
                                val maxY = (size.height * (scale - 1)) / 2f
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                                )
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(160.dp)
                        )
                        Text(
                            text = "No profile photo set",
                            color = Color.LightGray,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }

            // Top WhatsApp Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isEditable) {
                    IconButton(onClick = { showEditModal = true }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Edit Profile Photo",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }

    // WhatsApp-style Profile Photo Edit Options Modal
    if (showEditModal) {
        WhatsAppPhotoOptionsModal(
            hasPhoto = (bitmap != null),
            onDismiss = { showEditModal = false },
            onTakePhoto = {
                showEditModal = false
                onTakePhoto()
            },
            onChooseGallery = {
                showEditModal = false
                onChooseGallery()
            },
            onRemovePhoto = {
                showEditModal = false
                onRemovePhoto()
            }
        )
    }
}

/**
 * WhatsApp-style Profile Photo Action Options Bottom Dialog
 */
@Composable
fun WhatsAppPhotoOptionsModal(
    hasPhoto: Boolean,
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseGallery: () -> Unit,
    onRemovePhoto: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Profile photo", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTakePhoto() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFDCFCE7),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(22.dp))
                        }
                    }
                    Column {
                        Text("Camera", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF0F172A))
                        Text("Take a new picture", fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChooseGallery() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFDBEAFE),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(22.dp))
                        }
                    }
                    Column {
                        Text("Gallery", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF0F172A))
                        Text("Choose from photos", fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                }

                if (hasPhoto) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRemovePhoto() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFFEE2E2),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(22.dp))
                            }
                        }
                        Column {
                            Text("Remove photo", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFFDC2626))
                            Text("Reset to default initials avatar", fontSize = 12.sp, color = Color(0xFF64748B))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp)
    )
}
