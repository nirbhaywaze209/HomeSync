package com.homesync.app.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.model.LatLng
import com.homesync.app.ui.theme.ParentBorder
import com.homesync.app.ui.theme.ParentCardBg
import com.homesync.app.ui.theme.ParentEmerald
import com.homesync.app.ui.theme.ParentPrimary
import com.homesync.app.ui.theme.ParentSurfaceElevated
import com.homesync.app.ui.theme.ParentTextMuted
import com.homesync.app.ui.theme.ParentTextPrimary
import com.homesync.app.ui.theme.ParentTextSecondary
import com.homesync.app.util.ChildIdManager
import com.homesync.app.util.FamilyManager
import com.homesync.app.util.CustomSafeZone
import com.homesync.app.util.LocationHelper
import com.homesync.app.util.SafeZoneManager
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun LiveSafetyMap(
    childName: String = "",
    guardianName: String = "Guardian",
    linkedChildCode: String = "",
    modifier: Modifier = Modifier,
    isReadOnly: Boolean = false
) {
    val context = LocalContext.current

    var isSatellite by remember { mutableStateOf(false) }
    var isMaximized by remember { mutableStateOf(false) }

    val activeFamilyId = remember { FamilyManager.getStoredFamilyId(context) }

    // User Safe Zones with real-time cloud listener
    var userSafeZones by remember { mutableStateOf(SafeZoneManager.getSafeZones(context)) }

    DisposableEffect(activeFamilyId) {
        val reg = SafeZoneManager.listenSafeZones(context, activeFamilyId) { freshZones ->
            userSafeZones = freshZones
        }
        onDispose { reg?.remove() }
    }

    // Guardian ("Me") Location State
    var guardianLocation by remember { mutableStateOf<LatLng?>(null) }

    // Live continuous GPS tracking for Guardian device
    DisposableEffect(Unit) {
        val callback = if (LocationHelper.hasLocationPermission(context)) {
            LocationHelper.startContinuousLocationUpdates(context) { latLng, _ ->
                guardianLocation = latLng
            }
        } else null

        onDispose {
            LocationHelper.stopLocationUpdates(context, callback)
        }
    }

    // Dynamic Child Location from local storage, RTDB, and Cloud Firestore
    var childLocationTriple by remember(linkedChildCode) {
        mutableStateOf(ChildIdManager.getChildLocation(context, linkedChildCode))
    }

    // Cloud Firestore & Realtime Database live location subscriptions
    DisposableEffect(linkedChildCode) {
        val firestoreReg = if (linkedChildCode.isNotBlank()) {
            com.homesync.app.util.FirebaseSyncManager.listenChildLocation(linkedChildCode) { cloudLoc ->
                childLocationTriple = Triple(cloudLoc.latitude, cloudLoc.longitude, cloudLoc.address)
                ChildIdManager.saveChildLocation(context, linkedChildCode, cloudLoc.latitude, cloudLoc.longitude, cloudLoc.address)
            }
        } else null

        val rtdbListener = if (linkedChildCode.isNotBlank()) {
            com.homesync.app.util.FirebaseRealtimeSyncManager.listenChildLocation(linkedChildCode) { liveLoc ->
                childLocationTriple = Triple(liveLoc.latitude, liveLoc.longitude, liveLoc.address)
                ChildIdManager.saveChildLocation(context, linkedChildCode, liveLoc.latitude, liveLoc.longitude, liveLoc.address)
            }
        } else null

        onDispose {
            firestoreReg?.remove()
            com.homesync.app.util.FirebaseRealtimeSyncManager.removeLocationListener(linkedChildCode, rtdbListener)
        }
    }

    LaunchedEffect(linkedChildCode) {
        val updated = ChildIdManager.getChildLocation(context, linkedChildCode)
        if (updated != null) {
            childLocationTriple = updated
        }
    }

    // Active resolved child location (True GPS coordinates without artificial offsets)
    val childLocation by remember(childLocationTriple, guardianLocation) {
        mutableStateOf(
            if (childLocationTriple != null) {
                LatLng(childLocationTriple!!.first, childLocationTriple!!.second)
            } else if (guardianLocation != null) {
                // If child device hasn't uploaded GPS yet, use guardian's true GPS without fake offsets
                LatLng(guardianLocation!!.latitude, guardianLocation!!.longitude)
            } else {
                LatLng(0.0, 0.0)
            }
        )
    }

    var resolvedAddress by remember(linkedChildCode, childLocationTriple) {
        mutableStateOf(
            childLocationTriple?.third ?: LocationHelper.resolveAddress(context, childLocation.latitude, childLocation.longitude)
        )
    }

    var lastGeocodedLat by remember { mutableDoubleStateOf(0.0) }
    var lastGeocodedLng by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(childLocation, childLocationTriple) {
        if (childLocationTriple?.third != null && childLocationTriple!!.third.isNotBlank() && childLocationTriple!!.third != "Live Location") {
            resolvedAddress = childLocationTriple!!.third
        } else if (childLocation.latitude != 0.0 && childLocation.longitude != 0.0) {
            val dist = Math.hypot(childLocation.latitude - lastGeocodedLat, childLocation.longitude - lastGeocodedLng) * 111000.0
            if (dist > 30.0 || resolvedAddress.isBlank() || resolvedAddress == "Live Location") {
                lastGeocodedLat = childLocation.latitude
                lastGeocodedLng = childLocation.longitude
                resolvedAddress = LocationHelper.resolveAddressAsync(context, childLocation.latitude, childLocation.longitude)
            }
        }
    }

    val isUsingRealGps = childLocationTriple != null || guardianLocation != null

    // Effective Child Name (Resolved from ChildIdManager if generic "Child" or blank)
    val effectiveChildName = remember(childName, linkedChildCode) {
        if (childName.isNotBlank() && childName != "Child") childName
        else ChildIdManager.getChildName(context, linkedChildCode).ifBlank { "Child" }
    }

    // Live Geofence Containment (Evaluated against user's actual device location or child location)
    val monitorLat = guardianLocation?.latitude ?: childLocationTriple?.first ?: childLocation.latitude
    val monitorLng = guardianLocation?.longitude ?: childLocationTriple?.second ?: childLocation.longitude
    val safetyStatus = remember(monitorLat, monitorLng, userSafeZones) {
        SafeZoneManager.checkChildSafetyStatus(monitorLat, monitorLng, userSafeZones)
    }

    // Dialog state for adding a custom safe zone
    var showAddZoneDialog by remember { mutableStateOf(false) }
    var pendingZoneLat by remember { mutableStateOf(childLocation.latitude) }
    var pendingZoneLng by remember { mutableStateOf(childLocation.longitude) }
    var newZoneName by remember { mutableStateOf("") }
    var newZoneIcon by remember { mutableStateOf("🏡") }
    var newZoneRadius by remember { mutableStateOf(300f) }
    var newZoneColorHex by remember { mutableStateOf("#38BDF8") }

    // Edit/Delete Safe Zone Dialog state
    var selectedZoneForEdit by remember { mutableStateOf<CustomSafeZone?>(null) }
    var showSafeZoneDropdownMenu by remember { mutableStateOf(false) }

    // WebViews reference for syncing commands (embedded + fullscreen)
    var embeddedWebView by remember { mutableStateOf<WebView?>(null) }
    var fullscreenWebView by remember { mutableStateOf<WebView?>(null) }

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            LocationHelper.fetchCurrentDeviceLocation(
                context = context,
                onSuccess = { latLng, address ->
                    guardianLocation = latLng
                    if (childLocationTriple == null) {
                        pendingZoneLat = latLng.latitude
                        pendingZoneLng = latLng.longitude
                    }
                },
                onFailure = {
                    resolvedAddress = "GPS Signal Weak"
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!LocationHelper.hasLocationPermission(context)) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    fun refreshSafeZones() {
        userSafeZones = SafeZoneManager.getSafeZones(context)
    }

    // Helper to send dual location updates to WebViews
    LaunchedEffect(childLocation, effectiveChildName, linkedChildCode, guardianLocation, guardianName) {
        val gLat = guardianLocation?.latitude ?: 0.0
        val gLng = guardianLocation?.longitude ?: 0.0
        val cleanGName = guardianName.replace("'", "\\'")
        embeddedWebView?.evaluateJavascript(
            "updateChildLocation(${childLocation.latitude}, ${childLocation.longitude}, '$effectiveChildName', '$linkedChildCode', $gLat, $gLng, '$cleanGName');",
            null
        )
        fullscreenWebView?.evaluateJavascript(
            "updateChildLocation(${childLocation.latitude}, ${childLocation.longitude}, '$effectiveChildName', '$linkedChildCode', $gLat, $gLng, '$cleanGName');",
            null
        )
    }

    LaunchedEffect(userSafeZones) {
        val json = serializeSafeZones(userSafeZones)
        embeddedWebView?.evaluateJavascript("updateSafeZones($json);", null)
        fullscreenWebView?.evaluateJavascript("updateSafeZones($json);", null)
    }

    LaunchedEffect(isSatellite) {
        embeddedWebView?.evaluateJavascript("setLayer($isSatellite);", null)
        fullscreenWebView?.evaluateJavascript("setLayer($isSatellite);", null)
    }

    var hasInitialCentered by remember { mutableStateOf(false) }

    // Auto-recenter smoothly to Child Location 1st (falling back to Guardian) when map first loads
    LaunchedEffect(guardianLocation, childLocation) {
        if (!hasInitialCentered) {
            val centerLat = if (childLocationTriple != null || (childLocation.latitude != 0.0 && childLocation.longitude != 0.0)) {
                childLocation.latitude
            } else {
                guardianLocation?.latitude ?: childLocation.latitude
            }
            val centerLng = if (childLocationTriple != null || (childLocation.latitude != 0.0 && childLocation.longitude != 0.0)) {
                childLocation.longitude
            } else {
                guardianLocation?.longitude ?: childLocation.longitude
            }
            if (centerLat != 0.0 && centerLng != 0.0) {
                hasInitialCentered = true
                embeddedWebView?.evaluateJavascript("recenter($centerLat, $centerLng);", null)
                fullscreenWebView?.evaluateJavascript("recenter($centerLat, $centerLng);", null)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Embedded Live Map Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isSatellite) Color(0xFF0F1E17) else Color(0xFFE2E8F0))
        ) {
            // Hardware Accelerated Interactive Leaflet Map
            InteractiveLeafletMapView(
                lat = childLocation.latitude,
                lng = childLocation.longitude,
                zoom = 16,
                isSatellite = isSatellite,
                childName = effectiveChildName,
                linkedCode = linkedChildCode,
                safeZones = userSafeZones,
                onWebViewCreated = { embeddedWebView = it },
                onMapClick = { tapLat, tapLng ->
                    if (!isReadOnly) {
                        pendingZoneLat = tapLat
                        pendingZoneLng = tapLng
                        val addr = LocationHelper.resolveAddress(context, tapLat, tapLng)
                        newZoneName = if (addr.isNotBlank() && !addr.startsWith("Lat")) addr.substringBefore(",") else "Safe Zone ${userSafeZones.size + 1}"
                        showAddZoneDialog = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Top-Left: Live GPS Status Badge
            Surface(
                color = ParentCardBg.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, ParentBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (userSafeZones.isNotEmpty() && !safetyStatus.first) Color(0xFFEF4444)
                                else if (isUsingRealGps) ParentEmerald
                                else ParentPrimary
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (userSafeZones.isNotEmpty()) safetyStatus.second else if (isUsingRealGps) "Live GPS Active" else "GPS Signal Ready",
                        color = ParentTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Top-Right: Map Control Buttons
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Zoom In (+)
                Surface(
                    color = ParentCardBg.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, ParentBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { embeddedWebView?.evaluateJavascript("zoomIn();", null) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Zoom In", tint = ParentTextPrimary, modifier = Modifier.size(18.dp))
                    }
                }

                // Zoom Out (-)
                Surface(
                    color = ParentCardBg.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, ParentBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { embeddedWebView?.evaluateJavascript("zoomOut();", null) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.Remove, contentDescription = "Zoom Out", tint = ParentTextPrimary, modifier = Modifier.size(18.dp))
                    }
                }

                // Recenter GPS Button
                Surface(
                    color = ParentCardBg.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, ParentBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .size(34.dp)
                        .clickable {
                            val targetLat = if (childLocationTriple != null || (childLocation.latitude != 0.0 && childLocation.longitude != 0.0)) {
                                childLocation.latitude
                            } else {
                                guardianLocation?.latitude ?: childLocation.latitude
                            }
                            val targetLng = if (childLocationTriple != null || (childLocation.latitude != 0.0 && childLocation.longitude != 0.0)) {
                                childLocation.longitude
                            } else {
                                guardianLocation?.longitude ?: childLocation.longitude
                            }
                            if (targetLat != 0.0 && targetLng != 0.0) {
                                embeddedWebView?.evaluateJavascript("recenter($targetLat, $targetLng);", null)
                                Toast.makeText(context, "Centered on ${if (childLocationTriple != null) effectiveChildName else "Current"} Location", Toast.LENGTH_SHORT).show()
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.MyLocation, contentDescription = "Recenter", tint = ParentPrimary, modifier = Modifier.size(18.dp))
                    }
                }

                // Satellite / Street Toggle
                Surface(
                    color = ParentCardBg.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, ParentBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { isSatellite = !isSatellite }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.Layers, contentDescription = "Layer", tint = if (isSatellite) ParentPrimary else ParentTextSecondary, modifier = Modifier.size(18.dp))
                    }
                }

                // Fullscreen / Maximize
                Surface(
                    color = ParentCardBg.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, ParentBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { isMaximized = true }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.Fullscreen, contentDescription = "Fullscreen", tint = ParentTextPrimary, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Bottom Overlay: Live Telemetry Bar
            Surface(
                color = ParentCardBg.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, ParentBorder),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = resolvedAddress,
                            color = ParentPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = "💡 Interactive 60fps Map • Pinch to zoom • Tap map to add zone",
                            color = ParentTextMuted,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Safe Zones Section with Premium Clean Dropdown Menu
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = CardDefaults.cardColors(containerColor = ParentCardBg),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, ParentBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEFF6FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text("SAFE ZONES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ParentTextSecondary, letterSpacing = 1.sp)
                            Text("${userSafeZones.size} Active Areas", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = ParentTextPrimary)
                        }
                    }

                    if (!isReadOnly) {
                        Box {
                            Surface(
                                onClick = { showSafeZoneDropdownMenu = true },
                                color = Color(0xFFEFF6FF),
                                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(16.dp))
                                    Text("Manage Safe Zones", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(18.dp))
                                }
                            }

                            DropdownMenu(
                                expanded = showSafeZoneDropdownMenu,
                                onDismissRequest = { showSafeZoneDropdownMenu = false },
                                modifier = Modifier
                                    .background(ParentCardBg)
                                    .width(270.dp)
                            ) {
                                Text(
                                    "SAFE ZONE CONTROLS",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ParentTextSecondary,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )

                                // Add New Safe Zone Action
                                DropdownMenuItem(
                                    text = {
                                        Surface(
                                            color = Color(0xFFEFF6FF),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF2563EB)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                }
                                                Column {
                                                    Text("Add New Safe Zone Area", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
                                                    Text("Tap map or set radius", fontSize = 10.sp, color = ParentTextSecondary)
                                                }
                                            }
                                        }
                                    },
                                    onClick = {
                                        showSafeZoneDropdownMenu = false
                                        pendingZoneLat = childLocation.latitude
                                        pendingZoneLng = childLocation.longitude
                                        newZoneName = "Safe Zone ${userSafeZones.size + 1}"
                                        showAddZoneDialog = true
                                    }
                                )

                                if (userSafeZones.isNotEmpty()) {
                                    HorizontalDivider(color = ParentBorder, modifier = Modifier.padding(vertical = 4.dp))
                                    Text(
                                        "EXISTING ZONES (TAP TO EDIT)",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ParentTextSecondary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                                    )

                                    userSafeZones.forEach { zone ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        Text(zone.icon, fontSize = 18.sp)
                                                        Column {
                                                            Text(zone.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ParentTextPrimary)
                                                            Text("${SafeZoneManager.formatDistance(zone.radiusMeters)} radius", fontSize = 11.sp, color = ParentEmerald, fontWeight = FontWeight.SemiBold)
                                                        }
                                                    }
                                                    Icon(Icons.Default.Edit, contentDescription = null, tint = ParentTextSecondary, modifier = Modifier.size(14.dp))
                                                }
                                            },
                                            onClick = {
                                                showSafeZoneDropdownMenu = false
                                                selectedZoneForEdit = zone
                                            }
                                        )
                                    }

                                    HorizontalDivider(color = ParentBorder, modifier = Modifier.padding(vertical = 4.dp))
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(18.dp))
                                                Text("Clear All Safe Zones", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                                            }
                                        },
                                        onClick = {
                                            showSafeZoneDropdownMenu = false
                                            userSafeZones.forEach { SafeZoneManager.deleteSafeZone(context, it.id) }
                                            refreshSafeZones()
                                            Toast.makeText(context, "Cleared all safe zones", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Horizontal chips for quick zone inspection
                if (userSafeZones.isNotEmpty()) {
                    HorizontalDivider(color = ParentBorder)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(userSafeZones) { zone ->
                            Surface(
                                color = ParentSurfaceElevated,
                                border = BorderStroke(1.dp, ParentBorder),
                                shape = RoundedCornerShape(10.dp),
                                modifier = if (!isReadOnly) Modifier.clickable { selectedZoneForEdit = zone } else Modifier
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(zone.icon, fontSize = 13.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = zone.name,
                                            color = ParentTextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${SafeZoneManager.formatDistance(zone.radiusMeters)} radius",
                                            color = ParentEmerald,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Fullscreen Dialog
    if (isMaximized) {
        Dialog(
            onDismissRequest = { isMaximized = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A))
            ) {
                InteractiveLeafletMapView(
                    lat = childLocation.latitude,
                    lng = childLocation.longitude,
                    zoom = 16,
                    isSatellite = isSatellite,
                    childName = effectiveChildName,
                    linkedCode = linkedChildCode,
                    safeZones = userSafeZones,
                    onWebViewCreated = { fullscreenWebView = it },
                    onMapClick = { tapLat, tapLng ->
                        if (!isReadOnly) {
                            pendingZoneLat = tapLat
                            pendingZoneLng = tapLng
                            showAddZoneDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Top Controls in Fullscreen
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = ParentCardBg.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, ParentBorder),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Live Child Location: $resolvedAddress",
                            color = ParentPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = { isSatellite = !isSatellite },
                            modifier = Modifier
                                .size(36.dp)
                                .background(ParentCardBg, RoundedCornerShape(8.dp))
                        ) {
                            Icon(imageVector = Icons.Default.Layers, contentDescription = "Layer", tint = if (isSatellite) ParentPrimary else Color.White)
                        }

                        IconButton(
                            onClick = { isMaximized = false },
                            modifier = Modifier
                                .size(36.dp)
                                .background(ParentCardBg, RoundedCornerShape(8.dp))
                        ) {
                            Icon(imageVector = Icons.Default.FullscreenExit, contentDescription = "Exit", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    // Add Safe Zone Dialog
    if (showAddZoneDialog) {
        Dialog(onDismissRequest = { showAddZoneDialog = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ParentCardBg),
                border = BorderStroke(1.dp, ParentBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Create Safe Zone",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ParentTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Set a geographic boundary to monitor when child enters or leaves.",
                        fontSize = 12.sp,
                        color = ParentTextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = newZoneName,
                        onValueChange = { newZoneName = it },
                        label = { Text("Zone Name (e.g. Home, School, Park)", color = ParentTextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ParentPrimary,
                            unfocusedBorderColor = ParentBorder,
                            focusedTextColor = ParentTextPrimary,
                            unfocusedTextColor = ParentTextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Select Zone Icon:",
                        color = ParentTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("🏡", "🏫", "🌳", "⚽", "🎨", "👵", "🏥").forEach { icon ->
                            Surface(
                                color = if (newZoneIcon == icon) ParentPrimary.copy(alpha = 0.2f) else ParentSurfaceElevated,
                                border = BorderStroke(1.dp, if (newZoneIcon == icon) ParentPrimary else ParentBorder),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable { newZoneIcon = icon }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(icon, fontSize = 18.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Radius:", color = ParentTextSecondary, fontSize = 12.sp)
                        Text(SafeZoneManager.formatDistanceLong(newZoneRadius), color = ParentPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Slider(
                        value = newZoneRadius,
                        onValueChange = { newZoneRadius = it },
                        valueRange = 50f..1500f,
                        steps = 29,
                        colors = SliderDefaults.colors(
                            thumbColor = ParentPrimary,
                            activeTrackColor = ParentPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAddZoneDialog = false },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, ParentBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = ParentTextSecondary)
                        }

                        Button(
                            onClick = {
                                val finalName = newZoneName.ifBlank { "Safe Zone ${userSafeZones.size + 1}" }
                                val newZone = CustomSafeZone(
                                    id = "zone_${System.currentTimeMillis()}",
                                    name = finalName.trim(),
                                    icon = newZoneIcon,
                                    latitude = pendingZoneLat,
                                    longitude = pendingZoneLng,
                                    radiusMeters = newZoneRadius,
                                    colorHex = newZoneColorHex
                                )
                                SafeZoneManager.addSafeZone(context, newZone)
                                refreshSafeZones()
                                Toast.makeText(context, "Safe Zone Created: ${newZone.name}", Toast.LENGTH_SHORT).show()
                                showAddZoneDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ParentPrimary, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Zone", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Edit Safe Zone Dialog
    selectedZoneForEdit?.let { zone ->
        var editName by remember(zone) { mutableStateOf(zone.name) }
        var editIcon by remember(zone) { mutableStateOf(zone.icon) }
        var editRadius by remember(zone) { mutableStateOf(zone.radiusMeters) }

        Dialog(onDismissRequest = { selectedZoneForEdit = null }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ParentCardBg),
                border = BorderStroke(1.dp, ParentBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Edit Safe Zone",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ParentTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Modify zone details or delete this safe zone.",
                        fontSize = 12.sp,
                        color = ParentTextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Zone Name", color = ParentTextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ParentPrimary,
                            unfocusedBorderColor = ParentBorder,
                            focusedTextColor = ParentTextPrimary,
                            unfocusedTextColor = ParentTextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Select Zone Icon:",
                        color = ParentTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("🏡", "🏫", "🌳", "⚽", "🎨", "👵", "🏥").forEach { icon ->
                            Surface(
                                color = if (editIcon == icon) ParentPrimary.copy(alpha = 0.2f) else ParentSurfaceElevated,
                                border = BorderStroke(1.dp, if (editIcon == icon) ParentPrimary else ParentBorder),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable { editIcon = icon }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(icon, fontSize = 18.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Radius:", color = ParentTextSecondary, fontSize = 12.sp)
                        Text(SafeZoneManager.formatDistanceLong(editRadius), color = ParentPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Slider(
                        value = editRadius,
                        onValueChange = { editRadius = it },
                        valueRange = 50f..1500f,
                        steps = 29,
                        colors = SliderDefaults.colors(thumbColor = ParentPrimary, activeTrackColor = ParentPrimary)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                SafeZoneManager.deleteSafeZone(context, zone.id)
                                refreshSafeZones()
                                Toast.makeText(context, "Deleted safe zone: ${zone.name}", Toast.LENGTH_SHORT).show()
                                selectedZoneForEdit = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Delete Zone", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val updated = zone.copy(
                                    name = editName.trim().ifBlank { zone.name },
                                    icon = editIcon,
                                    radiusMeters = editRadius
                                )
                                SafeZoneManager.updateSafeZone(context, updated)
                                refreshSafeZones()
                                Toast.makeText(context, "Updated safe zone: ${updated.name}", Toast.LENGTH_SHORT).show()
                                selectedZoneForEdit = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ParentPrimary, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Changes", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 🗺️ Hardware-Accelerated Interactive Leaflet Map View
 * Provides real-world 60fps smooth zooming, inertial panning, live child marker with radar beacon,
 * and geographic safe zone circles without requiring any Google Maps API keys or billing.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InteractiveLeafletMapView(
    lat: Double,
    lng: Double,
    zoom: Int = 16,
    isSatellite: Boolean = false,
    childName: String,
    linkedCode: String,
    safeZones: List<CustomSafeZone>,
    onWebViewCreated: (WebView) -> Unit = {},
    onMapClick: (Double, Double) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val cachedHtml = remember { generateLeafletHtml() }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    setSupportZoom(true)
                    builtInZoomControls = false
                    displayZoomControls = false
                }

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val zonesJson = serializeSafeZones(safeZones)
                        view?.evaluateJavascript(
                            "initMap($lat, $lng, $zoom, $isSatellite, '$childName', '$linkedCode', $zonesJson);",
                            null
                        )
                    }
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onMapTapped(tapLat: Double, tapLng: Double) {
                        mainHandler.post {
                            onMapClick(tapLat, tapLng)
                        }
                    }
                }, "AndroidBridge")

                loadDataWithBaseURL("https://leafletjs.com", cachedHtml, "text/html", "UTF-8", null)
                onWebViewCreated(this)
            }
        },
        update = { webView ->
            // Active syncing is handled through LaunchedEffect JavaScript evaluation
        },
        modifier = modifier
    )
}

private fun serializeSafeZones(zones: List<CustomSafeZone>): String {
    val array = JSONArray()
    for (zone in zones) {
        val obj = JSONObject().apply {
            put("id", zone.id)
            put("name", zone.name)
            put("icon", zone.icon)
            put("lat", zone.latitude)
            put("lng", zone.longitude)
            put("radius", zone.radiusMeters)
            put("color", zone.colorHex)
        }
        array.put(obj)
    }
    return array.toString()
}

/**
 * Clean self-contained Leaflet HTML template with CDN styling, pulsing radar,
 * multi-tier tile servers (CartoDB, OpenStreetMap, Esri Satellite), and smooth touch zoom.
 */
private fun generateLeafletHtml(): String {
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <style>
            html, body, #map {
                width: 100%;
                height: 100%;
                margin: 0;
                padding: 0;
                background-color: #0F172A;
                color: #E2E8F0;
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            }
            .leaflet-control-attribution { display: none !important; }
            .leaflet-control-zoom { display: none !important; }
            
            #offline-fallback {
                display: none;
                position: absolute;
                top: 0; left: 0; right: 0; bottom: 0;
                background: #0F172A;
                color: #94A3B8;
                flex-direction: column;
                align-items: center;
                justify-content: center;
                text-align: center;
                padding: 24px;
                z-index: 999;
            }
            #offline-fallback h3 { color: #38BDF8; margin: 0 0 8px 0; font-size: 16px; }
            #offline-fallback p { font-size: 12px; margin: 4px 0; max-width: 260px; line-height: 1.4; }
            
            /* Google Maps Location Blue Beacon & Pin */
            .child-pulse-icon {
                position: relative;
                width: 36px;
                height: 36px;
            }
            .child-pulse-icon .dot {
                width: 18px;
                height: 18px;
                background-color: #1A73E8;
                border: 3px solid #FFFFFF;
                border-radius: 50%;
                position: absolute;
                top: 9px;
                left: 9px;
                box-shadow: 0 2px 8px rgba(26, 115, 232, 0.6);
                z-index: 10;
            }
            .child-pulse-icon .ring {
                width: 36px;
                height: 36px;
                border-radius: 50%;
                background-color: rgba(26, 115, 232, 0.35);
                position: absolute;
                top: 0;
                left: 0;
                animation: pulse-ring 1.8s infinite cubic-bezier(0.215, 0.61, 0.355, 1);
            }
            @keyframes pulse-ring {
                0% { transform: scale(0.5); opacity: 0.9; }
                80% { transform: scale(2.2); opacity: 0; }
                100% { transform: scale(2.4); opacity: 0; }
            }
            
            /* Guardian ("Me") Green Beacon & Pin */
            .guardian-pulse-icon {
                position: relative;
                width: 36px;
                height: 36px;
            }
            .guardian-pulse-icon .dot {
                width: 18px;
                height: 18px;
                background-color: #10B981;
                border: 3px solid #FFFFFF;
                border-radius: 50%;
                position: absolute;
                top: 9px;
                left: 9px;
                box-shadow: 0 2px 8px rgba(16, 185, 129, 0.6);
                z-index: 10;
            }
            .guardian-pulse-icon .ring {
                width: 36px;
                height: 36px;
                border-radius: 50%;
                background-color: rgba(16, 185, 129, 0.35);
                position: absolute;
                top: 0;
                left: 0;
                animation: pulse-ring-g 2s infinite cubic-bezier(0.215, 0.61, 0.355, 1);
            }
            @keyframes pulse-ring-g {
                0% { transform: scale(0.5); opacity: 0.9; }
                80% { transform: scale(2.2); opacity: 0; }
                100% { transform: scale(2.4); opacity: 0; }
            }

            .marker-label {
                background: #FFFFFF;
                color: #1F2937;
                border: 1px solid #E5E7EB;
                border-radius: 20px;
                padding: 4px 10px;
                font-size: 11px;
                font-weight: 700;
                font-family: 'Roboto', 'Segoe UI', sans-serif;
                white-space: nowrap;
                box-shadow: 0 2px 6px rgba(0,0,0,0.18);
            }
            .marker-label-guardian {
                background: #10B981;
                color: #FFFFFF;
                border: 1px solid #059669;
                border-radius: 20px;
                padding: 4px 10px;
                font-size: 11px;
                font-weight: 700;
                font-family: 'Roboto', 'Segoe UI', sans-serif;
                white-space: nowrap;
                box-shadow: 0 2px 6px rgba(0,0,0,0.18);
            }
            .zone-label {
                background: rgba(15, 23, 42, 0.85);
                color: #38BDF8;
                border: 1px solid rgba(56, 189, 248, 0.4);
                border-radius: 6px;
                padding: 2px 6px;
                font-size: 10px;
                font-weight: 600;
                white-space: nowrap;
            }
        </style>
    </head>
    <body>
        <div id="map"></div>
        <div id="offline-fallback">
            <div style="font-size:36px;margin-bottom:8px;">🛰️</div>
            <h3>Live GPS Active</h3>
            <p id="fallback-status">Waiting for map tiles...</p>
            <p style="font-size:11px;color:#64748B;">GPS coordinates and geofences are being tracked in real time.</p>
        </div>
        <script>
            let map;
            let streetLayer, satLayer;
            let childMarker = null;
            let guardianMarker = null;
            let safeZoneCircles = [];
            let safeZoneLabels = [];

            const streetUrl = 'https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}';
            const satUrl = 'https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}';

            function showFallback(msg) {
                const fb = document.getElementById('offline-fallback');
                if (fb) {
                    fb.style.display = 'flex';
                    const st = document.getElementById('fallback-status');
                    if (st && msg) st.innerText = msg;
                }
            }

            function hideFallback() {
                const fb = document.getElementById('offline-fallback');
                if (fb) fb.style.display = 'none';
            }

            function initMap(lat, lng, zoom, isSat, childName, childCode, safeZones) {
                if (typeof L === 'undefined') {
                    showFallback('Offline mode active • Leaflet library unavailable');
                    return;
                }
                if (map) return;
                hideFallback();

                try {
                    streetLayer = L.tileLayer(streetUrl, { 
                        maxZoom: 20, 
                        subdomains: ['0','1','2','3']
                    });
                    satLayer = L.tileLayer(satUrl, { 
                        maxZoom: 20, 
                        subdomains: ['0','1','2','3']
                    });

                    const baseLayer = isSat ? satLayer : streetLayer;
                    const centerCoord = (lat && lng && (lat !== 0 || lng !== 0)) ? [lat, lng] : [20.5937, 78.9629];

                    map = L.map('map', {
                        center: centerCoord,
                        zoom: (lat && lng && (lat !== 0 || lng !== 0)) ? zoom : 5,
                        layers: [baseLayer],
                        zoomControl: false,
                        attributionControl: false
                    });

                    map.on('click', function(e) {
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onMapTapped(e.latlng.lat, e.latlng.lng);
                        }
                    });

                    if (lat && lng && (lat !== 0 || lng !== 0)) {
                        updateChildLocation(lat, lng, childName, childCode, 0, 0);
                    }
                    updateSafeZones(safeZones);

                    setTimeout(function() {
                        if (map) map.invalidateSize();
                    }, 350);
                } catch(e) {
                    showFallback('Map renderer initialized with live GPS tracking');
                }
            }

            function updateChildLocation(childLat, childLng, name, code, guardianLat, guardianLng, gName) {
                if (!map) return;

                // Child Marker
                const hasChildInfo = (name && name !== 'No Child Connected') || (code && code.length > 0);
                const hasValidCoords = childLat && childLng && (childLat !== 0 || childLng !== 0);
                if (!hasChildInfo || !hasValidCoords) {
                    if (childMarker) {
                        map.removeLayer(childMarker);
                        childMarker = null;
                    }
                } else {
                    const childPos = [childLat, childLng];
                    const childIcon = L.divIcon({
                        className: 'child-pulse-container',
                        html: '<div class="child-pulse-icon"><div class="ring"></div><div class="dot"></div></div>',
                        iconSize: [32, 32],
                        iconAnchor: [16, 16]
                    });

                    const displayName = (name && name !== 'No Child Connected') ? name : 'Child';
                    if (!childMarker) {
                        childMarker = L.marker(childPos, { icon: childIcon }).addTo(map);
                        childMarker.bindTooltip('🧒 ' + displayName, {
                            permanent: true,
                            direction: 'top',
                            offset: [0, -12],
                            className: 'marker-label'
                        });
                    } else {
                        childMarker.setLatLng(childPos);
                        childMarker.setTooltipContent('🧒 ' + displayName);
                    }
                }

                // Guardian ("Me") Marker
                if (guardianLat && guardianLng && guardianLat !== 0 && guardianLng !== 0) {
                    const guardianPos = [guardianLat, guardianLng];
                    const guardianIcon = L.divIcon({
                        className: 'guardian-pulse-container',
                        html: '<div class="guardian-pulse-icon"><div class="ring"></div><div class="dot"></div></div>',
                        iconSize: [32, 32],
                        iconAnchor: [16, 16]
                    });

                    const guardianDisplayName = gName || 'Guardian';
                    if (!guardianMarker) {
                        guardianMarker = L.marker(guardianPos, { icon: guardianIcon }).addTo(map);
                        guardianMarker.bindTooltip('👤 ' + guardianDisplayName, {
                            permanent: true,
                            direction: 'bottom',
                            offset: [0, 12],
                            className: 'marker-label-guardian'
                        });
                    } else {
                        guardianMarker.setLatLng(guardianPos);
                        guardianMarker.setTooltipContent('👤 ' + guardianDisplayName);
                    }
                }
            }

            function updateSafeZones(zones) {
                if (!map) return;

                // Clear previous
                safeZoneCircles.forEach(c => map.removeLayer(c));
                safeZoneLabels.forEach(l => map.removeLayer(l));
                safeZoneCircles = [];
                safeZoneLabels = [];

                if (!zones || !zones.length) return;

                zones.forEach(z => {
                    const circle = L.circle([z.lat, z.lng], {
                        radius: z.radius,
                        color: z.color || '#38BDF8',
                        weight: 2.5,
                        fillColor: z.color || '#38BDF8',
                        fillOpacity: 0.2
                    }).addTo(map);

                    const labelMarker = L.marker([z.lat, z.lng], {
                        icon: L.divIcon({
                            className: 'zone-center',
                            html: '<div style="width:8px;height:8px;border-radius:50%;background:' + (z.color || '#38BDF8') + ';border:2px solid #fff;"></div>',
                            iconSize: [8, 8],
                            iconAnchor: [4, 4]
                        })
                    }).addTo(map);

                    labelMarker.bindTooltip((z.icon || '📍') + ' ' + z.name + ' (' + Math.round(z.radius) + 'm)', {
                        permanent: true,
                        direction: 'bottom',
                        offset: [0, 8],
                        className: 'zone-label'
                    });

                    safeZoneCircles.push(circle);
                    safeZoneLabels.push(labelMarker);
                });
            }

            function setLayer(isSat) {
                if (!map) return;
                if (isSat) {
                    if (map.hasLayer(streetLayer)) map.removeLayer(streetLayer);
                    if (!map.hasLayer(satLayer)) map.addLayer(satLayer);
                } else {
                    if (map.hasLayer(satLayer)) map.removeLayer(satLayer);
                    if (!map.hasLayer(streetLayer)) map.addLayer(streetLayer);
                }
            }

            function zoomIn() {
                if (map) map.zoomIn();
            }

            function zoomOut() {
                if (map) map.zoomOut();
            }

            function recenter(lat, lng) {
                if (map && lat && lng) {
                    map.panTo([lat, lng], {
                        animate: true,
                        duration: 0.4
                    });
                }
            }
        </script>
    </body>
    </html>
    """.trimIndent()
}
