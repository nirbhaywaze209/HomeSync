package com.homesync.app.ui.screens

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.homesync.app.ui.theme.ParentCardSlate
import com.homesync.app.ui.theme.ParentElevatedSlate
import com.homesync.app.ui.theme.ParentPaleMint
import com.homesync.app.ui.theme.ParentSage
import com.homesync.app.ui.theme.ParentSlateTeal
import com.homesync.app.ui.theme.ParentTeal
import com.homesync.app.util.AuthManager
import com.homesync.app.util.ChildIdManager
import com.homesync.app.util.UserAccount
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onLoginSuccess: (name: String, email: String, age: Int, pairingCodeInput: String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // Ensure demo accounts are ready
    LaunchedEffect(Unit) {
        AuthManager.ensureDemoAccounts(context)
    }

    // 0 = Parent/Guardian, 1 = Child Space, 2 = Connect Child
    var selectedRoleTab by remember { mutableStateOf(0) }
    // For Parent: 0 = Sign In, 1 = Sign Up
    var isSignUpMode by remember { mutableStateOf(false) }

    // Prepopulate with last logged-in account if available
    val lastAccount = remember { AuthManager.getLastLoggedInAccount(context) }
    val savedChildren = remember { ChildIdManager.getAllSavedChildren(context) }
    val currentDeviceId = remember { ChildIdManager.getDeviceChildId(context) }

    // Form inputs
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(lastAccount?.email ?: "") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var childLinkedCode by remember { mutableStateOf("") }

    // Direct Connect Tab inputs
    var connectGuardianName by remember { mutableStateOf(lastAccount?.name ?: "Guardian") }
    var connectChildCode by remember { mutableStateOf(currentDeviceId) }

    // Child specific inputs
    var childName by remember { mutableStateOf("") }
    var childAgeText by remember { mutableStateOf("10") }

    // State indicators
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var resetEmailInput by remember { mutableStateOf("") }
    var newPasswordInput by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF0F172A))
                )
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // App Logo & Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color(0xFF0284C7), shape = RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "🏡", fontSize = 22.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "HomeSync",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "Family Safety & Habit Hub",
                            fontSize = 11.sp,
                            color = ParentPaleMint.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Role Selector Tabs (Parent vs Child vs Connect Child)
                TabRow(
                    selectedTabIndex = selectedRoleTab,
                    containerColor = ParentElevatedSlate,
                    contentColor = ParentTeal,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                ) {
                    Tab(
                        selected = selectedRoleTab == 0,
                        onClick = {
                            selectedRoleTab = 0
                            errorMessage = ""
                        },
                        text = {
                            Text(
                                text = "🛡️ Parent",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (selectedRoleTab == 0) ParentPaleMint else ParentPaleMint.copy(alpha = 0.6f)
                            )
                        }
                    )
                    Tab(
                        selected = selectedRoleTab == 1,
                        onClick = {
                            selectedRoleTab = 1
                            errorMessage = ""
                        },
                        text = {
                            Text(
                                text = "👦 Child",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (selectedRoleTab == 1) Color(0xFFF97316) else Color(0xFF94A3B8)
                            )
                        }
                    )
                    Tab(
                        selected = selectedRoleTab == 2,
                        onClick = {
                            selectedRoleTab = 2
                            errorMessage = ""
                        },
                        text = {
                            Text(
                                text = "🔗 Connect",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (selectedRoleTab == 2) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ==========================================
                // 1. PARENT / GUARDIAN AUTH VIEW
                // ==========================================
                if (selectedRoleTab == 0) {
                    // Sign In vs Sign Up Toggle Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ParentElevatedSlate, shape = RoundedCornerShape(10.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { isSignUpMode = false; errorMessage = "" },
                            color = if (!isSignUpMode) ParentTeal else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Sign In",
                                color = if (!isSignUpMode) Color.White else ParentPaleMint.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { isSignUpMode = true; errorMessage = "" },
                            color = if (isSignUpMode) ParentTeal else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Create Account",
                                color = if (isSignUpMode) Color.White else ParentPaleMint.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Sign Up: Full Name field
                    if (isSignUpMode) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Full Name", color = ParentPaleMint) },
                            leadingIcon = { Text("👤") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ParentTeal,
                                unfocusedBorderColor = ParentSage,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Email field
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email Address", color = ParentPaleMint) },
                        leadingIcon = { Text("✉️") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ParentTeal,
                            unfocusedBorderColor = ParentSage,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Password field
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password", color = ParentPaleMint) },
                        leadingIcon = { Text("🔒") },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(if (passwordVisible) "👁️" else "🙈", fontSize = 16.sp)
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (isSignUpMode) ImeAction.Next else ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ParentTeal,
                            unfocusedBorderColor = ParentSage,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Sign Up: Confirm Password field
                    if (isSignUpMode) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text("Confirm Password", color = ParentPaleMint) },
                            leadingIcon = { Text("🔒") },
                            trailingIcon = {
                                IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                    Text(if (confirmPasswordVisible) "👁️" else "🙈", fontSize = 16.sp)
                                }
                            },
                            visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ParentTeal,
                                unfocusedBorderColor = ParentSage,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Child Connect Field inside Parent Login (Both Sign In & Sign Up)
                    OutlinedTextField(
                        value = childLinkedCode,
                        onValueChange = { childLinkedCode = it.uppercase() },
                        label = { Text("🔗 Link Child Unique ID (e.g. HS-849201)", color = ParentPaleMint) },
                        leadingIcon = { Text("🆔") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ParentTeal,
                            unfocusedBorderColor = ParentSage,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Forgot Password Link (in Sign In mode)
                    if (!isSignUpMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                resetEmailInput = email
                                newPasswordInput = ""
                                showForgotPasswordDialog = true
                            }) {
                                Text(
                                    text = "Forgot Password?",
                                    fontSize = 11.sp,
                                    color = ParentPaleMint,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Error Message Display
                    AnimatedVisibility(visible = errorMessage.isNotBlank()) {
                        Surface(
                            color = Color(0x33EF4444),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "⚠️ $errorMessage",
                                    color = Color(0xFFF87171),
                                    fontSize = 12.sp
                                )
                                if (errorMessage.contains("already exists", ignoreCase = true)) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    TextButton(
                                        onClick = {
                                            isSignUpMode = false
                                            errorMessage = ""
                                        },
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("Click here to Sign In with this email", color = ParentPaleMint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Primary Auth Button
                    Button(
                        onClick = {
                            if (email.isBlank() || !email.contains("@")) {
                                errorMessage = "Please enter a valid email address."
                                return@Button
                            }
                            if (password.length < 4) {
                                errorMessage = "Password must be at least 4 characters."
                                return@Button
                            }

                            val cleanEmail = email.trim().lowercase()

                            if (isSignUpMode) {
                                if (name.isBlank()) {
                                    errorMessage = "Please enter your full name."
                                    return@Button
                                }
                                if (password != confirmPassword) {
                                    errorMessage = "Passwords do not match."
                                    return@Button
                                }

                                isLoading = true
                                errorMessage = ""

                                Log.i("HomeSyncAuth", "AUTH_LOGIN_START email=$cleanEmail provider=email_create")
                                FirebaseAuth.getInstance().createUserWithEmailAndPassword(cleanEmail, password)
                                    .addOnSuccessListener { authRes ->
                                        val user = authRes.user
                                        val uid = user?.uid ?: ""
                                        Log.i("HomeSyncAuth", "AUTH_LOGIN_SUCCESS uid=$uid provider=email_create")
                                        Log.i("HomeSyncAuth", "AUTH_FIREBASE_UID uid=$uid")

                                        val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                                            .setDisplayName(name.trim())
                                            .build()
                                        user?.updateProfile(profileUpdates)

                                        val activeChildCode = childLinkedCode.trim().uppercase()
                                        val newAccount = UserAccount(
                                            email = cleanEmail,
                                            password = "",
                                            name = name.trim(),
                                            role = "GUARDIAN",
                                            childCode = activeChildCode
                                        )
                                        AuthManager.registerAccount(context, newAccount)
                                        isLoading = false
                                        Toast.makeText(context, "Account created successfully! Welcome, ${name.trim()}", Toast.LENGTH_SHORT).show()
                                        onLoginSuccess(name.trim(), cleanEmail, 35, activeChildCode)
                                    }
                                    .addOnFailureListener { err ->
                                        isLoading = false
                                        Log.w("HomeSyncAuth", "AUTH_LOGIN_FAILED error=${err.message}")
                                        errorMessage = err.localizedMessage ?: "Failed to create account in Firebase."
                                    }
                            } else {
                                // ================= SIGN IN =================
                                isLoading = true
                                errorMessage = ""

                                Log.i("HomeSyncAuth", "AUTH_LOGIN_START email=$cleanEmail provider=email_password")
                                FirebaseAuth.getInstance().signInWithEmailAndPassword(cleanEmail, password)
                                    .addOnSuccessListener { authResult ->
                                        isLoading = false
                                        val user = authResult.user
                                        val uid = user?.uid ?: ""
                                        Log.i("HomeSyncAuth", "AUTH_LOGIN_SUCCESS uid=$uid provider=email_password")
                                        Log.i("HomeSyncAuth", "AUTH_FIREBASE_UID uid=$uid")

                                        val displayName = user?.displayName ?: name.ifBlank { cleanEmail.substringBefore("@") }
                                        val activeChildCode = childLinkedCode.trim().uppercase()
                                        val savedAcc = UserAccount(
                                            email = cleanEmail,
                                            password = "",
                                            name = displayName,
                                            role = "GUARDIAN",
                                            childCode = activeChildCode
                                        )
                                        AuthManager.registerAccount(context, savedAcc)
                                        Toast.makeText(context, "Welcome back, $displayName!", Toast.LENGTH_SHORT).show()
                                        onLoginSuccess(displayName, cleanEmail, 35, activeChildCode)
                                    }
                                    .addOnFailureListener { e ->
                                        isLoading = false
                                        Log.w("HomeSyncAuth", "AUTH_LOGIN_FAILED error=${e.message}")
                                        val msg = e.localizedMessage ?: "Sign in failed"
                                        errorMessage = if (msg.contains("no user", ignoreCase = true) || msg.contains("user-not-found", ignoreCase = true)) {
                                            "No account found with email $cleanEmail. Please click 'Create Account' above to sign up."
                                        } else if (msg.contains("password", ignoreCase = true) || msg.contains("wrong-password", ignoreCase = true) || msg.contains("invalid-credential", ignoreCase = true)) {
                                            "Incorrect password for $cleanEmail. Please try again or click 'Forgot Password?'."
                                        } else {
                                            msg
                                        }
                                    }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ParentTeal, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        } else {
                            Text(
                                text = if (isSignUpMode) "Create Guardian Account" else "Sign In to Guardian Dashboard",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(text = "── OR ──", fontSize = 11.sp, color = ParentPaleMint.copy(alpha = 0.7f))

                    Spacer(modifier = Modifier.height(12.dp))

                    // Google Sign-In Button
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                Log.i("HomeSyncAuth", "AUTH_LOGIN_START provider=google_guardian")
                                performGoogleSignIn(
                                    context = context,
                                    onSuccess = { googleEmail, googleName ->
                                        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                                        Log.i("HomeSyncAuth", "AUTH_LOGIN_SUCCESS uid=$uid provider=google_guardian")
                                        Log.i("HomeSyncAuth", "AUTH_FIREBASE_UID uid=$uid")
                                        val activeChildCode = childLinkedCode.trim().uppercase()
                                        val googleAccount = UserAccount(
                                            email = googleEmail,
                                            password = "",
                                            name = googleName,
                                            role = "GUARDIAN",
                                            childCode = activeChildCode
                                        )
                                        AuthManager.registerAccount(context, googleAccount)
                                        Toast.makeText(context, "Welcome, $googleName!", Toast.LENGTH_SHORT).show()
                                        onLoginSuccess(googleName, googleEmail, 35, activeChildCode)
                                    },
                                    onError = { err ->
                                        Log.w("HomeSyncAuth", "AUTH_LOGIN_FAILED error=$err")
                                        errorMessage = err
                                    }
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ParentElevatedSlate, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            text = "Continue with Google",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

                // ==========================================
                // 2. CHILD PROFILE ENTRY VIEW
                // ==========================================
                if (selectedRoleTab == 1) {
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "👦 Child Space Login",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF97316),
                                    fontSize = 15.sp
                                )
                                Surface(
                                    color = Color(0x33F97316),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable {
                                        ChildIdManager.copyIdToClipboard(context, currentDeviceId)
                                    }
                                ) {
                                    Text(
                                        text = "ID: $currentDeviceId 📋",
                                        color = Color(0xFFF97316),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Share your Unique ID with your parents to link your safety map!",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 1. Google Sign-In Button for Child
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                Log.i("HomeSyncAuth", "AUTH_LOGIN_START provider=google_child")
                                performGoogleSignIn(
                                    context = context,
                                    onSuccess = { googleEmail, googleName ->
                                        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                                        Log.i("HomeSyncAuth", "AUTH_LOGIN_SUCCESS uid=$uid provider=google_child")
                                        Log.i("HomeSyncAuth", "AUTH_FIREBASE_UID uid=$uid")
                                        val finalChildId = if (childLinkedCode.isNotBlank()) childLinkedCode.trim().uppercase() else currentDeviceId
                                        val childAccount = UserAccount(
                                            email = googleEmail,
                                            password = "",
                                            name = googleName,
                                            role = "CHILD",
                                            childCode = finalChildId
                                        )
                                        AuthManager.registerAccount(context, childAccount)
                                        Toast.makeText(context, "Welcome, $googleName!", Toast.LENGTH_SHORT).show()
                                        onLoginSuccess(googleName, googleEmail, 10, finalChildId)
                                    },
                                    onError = { err ->
                                        Log.w("HomeSyncAuth", "AUTH_LOGIN_FAILED error=$err")
                                        errorMessage = err
                                    }
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ParentElevatedSlate, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "🌐", fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Continue with Google (Child)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(text = "── OR ──", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Child Name
                    OutlinedTextField(
                        value = childName,
                        onValueChange = { childName = it },
                        label = { Text("Child's First Name", color = Color(0xFF94A3B8)) },
                        leadingIcon = { Text("👦") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFF97316),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Child Age
                    OutlinedTextField(
                        value = childAgeText,
                        onValueChange = { childAgeText = it },
                        label = { Text("Age (e.g. 6 - 15)", color = Color(0xFF94A3B8)) },
                        leadingIcon = { Text("🎂") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFF97316),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Unique Child ID Display/Input
                    OutlinedTextField(
                        value = if (childLinkedCode.isNotBlank()) childLinkedCode else currentDeviceId,
                        onValueChange = { childLinkedCode = it.uppercase() },
                        label = { Text("Unique Child ID (Auto-Assigned)", color = Color(0xFF94A3B8)) },
                        leadingIcon = { Text("🆔") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFF97316),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    AnimatedVisibility(visible = errorMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "⚠️ $errorMessage", color = Color(0xFFF87171), fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            val parsedAge = childAgeText.toIntOrNull() ?: 10
                            val validChildAge = parsedAge.coerceIn(1, 15)
                            val finalChildId = if (childLinkedCode.isNotBlank()) childLinkedCode.trim().uppercase() else currentDeviceId
                            val currentAuth = FirebaseAuth.getInstance().currentUser

                            if (currentAuth != null) {
                                val uid = currentAuth.uid
                                Log.i("HomeSyncAuth", "AUTH_FIREBASE_UID uid=$uid")
                                val finalName = if (childName.isNotBlank()) childName.trim() else currentAuth.displayName ?: "Child"
                                onLoginSuccess(finalName, currentAuth.email ?: "", validChildAge, finalChildId)
                            } else {
                                isLoading = true
                                Log.i("HomeSyncAuth", "AUTH_LOGIN_START provider=google_child_space")
                                coroutineScope.launch {
                                    performGoogleSignIn(
                                        context = context,
                                        onSuccess = { googleEmail, googleName ->
                                            isLoading = false
                                            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                                            Log.i("HomeSyncAuth", "AUTH_LOGIN_SUCCESS uid=$uid provider=google_child_space")
                                            Log.i("HomeSyncAuth", "AUTH_FIREBASE_UID uid=$uid")
                                            val finalName = if (childName.isNotBlank()) childName.trim() else googleName
                                            val childAccount = UserAccount(
                                                email = googleEmail,
                                                password = "",
                                                name = finalName,
                                                role = "CHILD",
                                                childCode = finalChildId
                                            )
                                            AuthManager.registerAccount(context, childAccount)
                                            Toast.makeText(context, "Welcome, $finalName!", Toast.LENGTH_SHORT).show()
                                            onLoginSuccess(finalName, googleEmail, validChildAge, finalChildId)
                                        },
                                        onError = { err ->
                                            isLoading = false
                                            Log.w("HomeSyncAuth", "AUTH_LOGIN_FAILED error=$err")
                                            errorMessage = "Google sign-in required to enter Child Space: $err"
                                        }
                                    )
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        } else {
                            Text(
                                text = "🚀 Enter Child Space & Arcade",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // ==========================================
                // 3. 🔗 DIRECT CHILD CONNECT TAB
                // ==========================================
                if (selectedRoleTab == 2) {
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "🔗 Direct Child Connect & Sync",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4ADE80),
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Enter any child's Unique ID to immediately pair, track safe zones, and manage habit routines.",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Guardian Display Name
                    OutlinedTextField(
                        value = connectGuardianName,
                        onValueChange = { connectGuardianName = it },
                        label = { Text("Your Name (Guardian)", color = Color(0xFF94A3B8)) },
                        leadingIcon = { Text("🛡️") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4ADE80),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Child Pairing Code
                    OutlinedTextField(
                        value = connectChildCode,
                        onValueChange = { connectChildCode = it.uppercase() },
                        label = { Text("Child's Unique ID (e.g. HS-849201)", color = Color(0xFF4ADE80)) },
                        leadingIcon = { Text("🆔") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4ADE80),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Discovered / Saved Children on this Device
                    if (savedChildren.isNotEmpty()) {
                        Text(
                            text = "Discovered Child Devices:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            savedChildren.take(2).forEach { (cName, cId) ->
                                Surface(
                                    color = Color(0xFF064E3B),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            connectChildCode = cId
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = "👦", fontSize = 12.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Column {
                                            Text(text = cName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Text(text = cId, color = Color(0xFF4ADE80), fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (connectChildCode.isBlank()) {
                                errorMessage = "Please enter a Child Unique ID."
                                return@Button
                            }
                            val guardianDisplayName = connectGuardianName.ifBlank { "Guardian" }
                            val cleanCode = connectChildCode.trim().uppercase()
                            val currentAuth = FirebaseAuth.getInstance().currentUser
                            if (currentAuth != null) {
                                val uid = currentAuth.uid
                                Log.i("HomeSyncAuth", "AUTH_FIREBASE_UID uid=$uid")
                                Toast.makeText(context, "Connected to Child: $cleanCode! 🚀", Toast.LENGTH_SHORT).show()
                                onLoginSuccess(guardianDisplayName, currentAuth.email ?: "", 35, cleanCode)
                            } else {
                                Log.i("HomeSyncAuth", "AUTH_LOGIN_START provider=google_direct_connect")
                                coroutineScope.launch {
                                    performGoogleSignIn(
                                        context = context,
                                        onSuccess = { googleEmail, googleName ->
                                            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                                            Log.i("HomeSyncAuth", "AUTH_LOGIN_SUCCESS uid=$uid provider=google_direct_connect")
                                            Log.i("HomeSyncAuth", "AUTH_FIREBASE_UID uid=$uid")
                                            val finalName = if (connectGuardianName.isNotBlank() && connectGuardianName != "Guardian") connectGuardianName else googleName
                                            Toast.makeText(context, "Connected to Child: $cleanCode! 🚀", Toast.LENGTH_SHORT).show()
                                            onLoginSuccess(finalName, googleEmail, 35, cleanCode)
                                        },
                                        onError = { err ->
                                            Log.w("HomeSyncAuth", "AUTH_LOGIN_FAILED error=$err")
                                            errorMessage = "Google sign-in required to connect child: $err"
                                        }
                                    )
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            text = "🔗 Connect Child & Open Safety Map",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // ==========================================
                // ⚡ 1-TAP DEMO SHORTCUTS
                // ==========================================
                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Empty spacer
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }

    // Reset / Update Password Dialog
    if (showForgotPasswordDialog) {
        Dialog(onDismissRequest = { showForgotPasswordDialog = false }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ParentCardSlate),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Reset or Update Password",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enter your registered email and new password below to instantly update your password.",
                        fontSize = 12.sp,
                        color = ParentPaleMint.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = resetEmailInput,
                        onValueChange = { resetEmailInput = it },
                        label = { Text("Registered Email", color = ParentPaleMint) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ParentTeal,
                            unfocusedBorderColor = ParentSage,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newPasswordInput,
                        onValueChange = { newPasswordInput = it },
                        label = { Text("New Password (min 4 chars)", color = ParentPaleMint) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ParentTeal,
                            unfocusedBorderColor = ParentSage,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showForgotPasswordDialog = false },
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ParentTeal),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ParentPaleMint),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = ParentPaleMint)
                        }
                        Button(
                            onClick = {
                                if (resetEmailInput.isBlank() || !resetEmailInput.contains("@")) {
                                    Toast.makeText(context, "Please enter a valid email.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                val cleanEmail = resetEmailInput.trim().lowercase()
                                try {
                                    FirebaseAuth.getInstance().sendPasswordResetEmail(cleanEmail)
                                        .addOnSuccessListener {
                                            Toast.makeText(context, "Password reset email sent to $cleanEmail. Please check your inbox.", Toast.LENGTH_LONG).show()
                                            showForgotPasswordDialog = false
                                        }
                                        .addOnFailureListener { e ->
                                            Toast.makeText(context, "Failed to send reset email: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                        }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ParentTeal, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Send Reset Link", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

suspend fun performGoogleSignIn(
    context: Context,
    onSuccess: (email: String, name: String) -> Unit,
    onError: (String) -> Unit
) {
    Log.i("HomeSyncAuth", "AUTH_GOOGLE_LOGIN_START")
    val credentialManager = CredentialManager.create(context)
    val webClientId = "136088147782-3l40o3qe9tsh6len59j310f7ntjqkqnp.apps.googleusercontent.com"

    try {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    } catch (_: Exception) {}

    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setAutoSelectEnabled(false)
        .setServerClientId(webClientId)
        .build()

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    try {
        val result = credentialManager.getCredential(context = context, request = request)
        val credential = result.credential

        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val selectedEmail = googleIdTokenCredential.id
            Log.i("HomeSyncAuth", "AUTH_GOOGLE_ACCOUNT_SELECTED email=$selectedEmail")

            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            FirebaseAuth.getInstance().signInWithCredential(firebaseCredential)
                .addOnSuccessListener { authResult ->
                    val user = authResult.user
                    val uid = user?.uid ?: FirebaseAuth.getInstance().currentUser?.uid ?: ""
                    val email = user?.email ?: FirebaseAuth.getInstance().currentUser?.email ?: selectedEmail
                    Log.i("HomeSyncAuth", "AUTH_GOOGLE_LOGIN_SUCCESS uid=$uid")
                    Log.i("HomeSyncAuth", "AUTH_FIREBASE_UID uid=$uid")
                    Log.i("HomeSyncAuth", "AUTH_FIREBASE_EMAIL email=$email")
                    onSuccess(email, user?.displayName ?: googleIdTokenCredential.displayName ?: "User")
                }
                .addOnFailureListener { e ->
                    Log.w("HomeSyncAuth", "AUTH_LOGIN_FAILED error=${e.message}")
                    onError(e.localizedMessage ?: "Firebase Sign-In failed")
                }
        } else {
            Log.w("HomeSyncAuth", "AUTH_LOGIN_FAILED reason=unsupported_credential")
            onError("Unsupported credential type")
        }
    } catch (e: Exception) {
        Log.w("HomeSyncAuth", "AUTH_LOGIN_FAILED exception=${e.message}")
        onError("Google Sign-In canceled or unsupported on this device.")
    }
}