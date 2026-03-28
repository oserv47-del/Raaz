@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.gfxtool

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.gfxtool.ui.theme.*
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

// ────────────────────────────────────────────────────────────────────────────────────────────────────
//  Preferences Manager
// ────────────────────────────────────────────────────────────────────────────────────────────────────
class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("GFX_PREFS", Context.MODE_PRIVATE)
    fun getBool(key: String, default: Boolean = false): Boolean = prefs.getBoolean(key, default)
    fun setBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
}

// ────────────────────────────────────────────────────────────────────────────────────────────────────
//  Data Model
// ────────────────────────────────────────────────────────────────────────────────────────────────────
data class FeatureItem(
    val emoji               : String,
    val title               : String,
    val subtitle            : String,
    val color               : Color,
    val key                 : String,
    val downloadUrl         : String,
    val fileName            : String,
    val targetPath          : String, 
    val isZip               : Boolean = false, 
    val targetFolderName    : String = "" // Defines what folder to delete when turned OFF
)

// ────────────────────────────────────────────────────────────────────────────────────────────────────
//  Activity
// ────────────────────────────────────────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    private var shizukuRunning    by mutableStateOf(false)
    private var shizukuPermission by mutableStateOf(false)
    lateinit var prefs: PrefsManager

    // Target Directories Base Paths
    private val bgmiFilesDir = "/storage/emulated/0/Android/data/com.pubg.imobile/files"
    private val bgmiShadowTrackerDir = "$bgmiFilesDir/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra"
    private val bgmiSavedDir = "$bgmiShadowTrackerDir/Saved"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(this)

        Shizuku.addBinderReceivedListener { checkShizuku() }
        Shizuku.addBinderDeadListener    { checkShizuku() }

        setContent {
            val isExtraDark = remember { mutableStateOf(prefs.getBool("extra_dark", true)) }
            
            GfxToolTheme(isExtraDark = isExtraDark.value) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GfxApp(isExtraDark)
                }
            }
        }
        checkShizuku()
    }

    private fun checkShizuku() {
        shizukuRunning    = Shizuku.pingBinder()
        shizukuPermission = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }
    }

    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    //  ZIP Extractor Utility
    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    private fun unzipFile(zipFile: File, targetDirectory: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDirectory, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    //  Core Engine: SMART CACHE DOWNLOAD & INSTANT APPLY LOGIC
    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    private suspend fun downloadAndApplyFile(
        context: Context, 
        item: FeatureItem, 
        enable: Boolean,
        onComplete: (Boolean, String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val targetDirPath = item.targetPath
                
                // Using externalMediaDirs so Shizuku can read it without Scoped Storage restrictions!
                val mediaDirs = context.externalMediaDirs
                val cacheDir = if (mediaDirs.isNotEmpty()) {
                    File(mediaDirs[0], "GFX_Smart_Cache")
                } else {
                    File(context.getExternalFilesDir(null), "GFX_Smart_Cache")
                }
                
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val appLocalFile = File(cacheDir, item.fileName)
                val extractDir = File(cacheDir, "${item.fileName}_extracted")

                if (enable) {
                    // STEP 1: SMART DOWNLOAD (Only if not already downloaded)
                    if (!appLocalFile.exists()) {
                        withContext(Dispatchers.Main) { 
                            Toast.makeText(context, "Downloading data once, please wait...", Toast.LENGTH_SHORT).show() 
                        }
                        val url = URL(item.downloadUrl)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 10000
                        connection.connect()

                        connection.inputStream.use { input ->
                            FileOutputStream(appLocalFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }

                    if (item.isZip) {
                        // STEP 2: SMART EXTRACT (Only if not already extracted)
                        if (!extractDir.exists() || extractDir.listFiles()?.isEmpty() == true) {
                            withContext(Dispatchers.Main) { 
                                Toast.makeText(context, "Extracting for the first time...", Toast.LENGTH_SHORT).show() 
                            }
                            extractDir.mkdirs()
                            unzipFile(appLocalFile, extractDir)
                        } else {
                            // Instant apply indication for user
                            withContext(Dispatchers.Main) { 
                                Toast.makeText(context, "Applying instantly from cache...", Toast.LENGTH_SHORT).show() 
                            }
                        }
                        
                        // STEP 3: INSTANT COPY FROM CACHE
                        val shizukuCmd = "mkdir -p '$targetDirPath' && cp -rf '${extractDir.absolutePath}/.' '$targetDirPath/'"
                        val success = runShizukuCommand(shizukuCmd)
                        
                        // We DO NOT delete extractDir anymore. It stays in cache for instant future use!
                        if (success) {
                            withContext(Dispatchers.Main) { onComplete(true, "${item.title} Applied Successfully!") }
                        } else {
                            withContext(Dispatchers.Main) { onComplete(false, "Failed to apply ZIP Folder.") }
                        }
                    } else {
                        // NORMAL FILE INSTANT APPLY
                        withContext(Dispatchers.Main) { 
                            if (appLocalFile.exists()) Toast.makeText(context, "Applying instantly...", Toast.LENGTH_SHORT).show() 
                        }
                        val shizukuCmd = "mkdir -p '$targetDirPath' && cp -f '${appLocalFile.absolutePath}' '$targetDirPath/${item.fileName}'"
                        val success = runShizukuCommand(shizukuCmd)
                        if (success) {
                            withContext(Dispatchers.Main) { onComplete(true, "${item.title} Applied Successfully!") }
                        } else {
                            withContext(Dispatchers.Main) { onComplete(false, "Failed to copy file.") }
                        }
                    }
                } else {
                    // STEP 4: REMOVE LOGIC
                    val removeName = if (item.targetFolderName.isNotEmpty()) item.targetFolderName else item.fileName
                    val shizukuCmd = "rm -rf '$targetDirPath/$removeName'"
                    runShizukuCommand(shizukuCmd)
                    withContext(Dispatchers.Main) { onComplete(true, "${item.title} Removed!") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete(false, "Error: ${e.message}") }
            }
        }
    }

    private fun runShizukuCommand(command: String): Boolean {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as java.lang.Process
            
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val errorStream = process.errorStream.bufferedReader().readText()
                println("Shizuku Error: $errorStream") 
                false
            } else {
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    //  UI Components
    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    @Composable
    fun GfxApp(isExtraDark: MutableState<Boolean>) {
        val drawerState  = rememberDrawerState(DrawerValue.Closed)
        val scope        = rememberCoroutineScope()
        var showSettings by remember { mutableStateOf(false) }
        var selectedPage by remember { mutableStateOf("Home") }

        ModalNavigationDrawer(
            drawerState   = drawerState,
            drawerContent = {
                DrawerContent(selectedPage) { page ->
                    selectedPage = page
                    scope.launch { drawerState.close() }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val pulse by rememberInfiniteTransition().animateFloat(
                                    initialValue = 0.7f, targetValue = 1.3f,
                                    animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse)
                                )
                                Box(Modifier.size(10.dp).scale(pulse).clip(CircleShape).background(Color(0xFF00FF41)))
                                Spacer(Modifier.width(8.dp))
                                Text("GFX PRO TOOL", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = Color(0xFF00FF41))
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                            }
                        },
                        actions = {
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.smallTopAppBarColors(containerColor = Color.Transparent)
                    )
                },
                containerColor = Color.Transparent
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when (selectedPage) {
                        "About" -> AboutPage()
                        "Help"  -> HelpPage()
                        else    -> GfxUI()
                    }
                }
            }
        }

        if (showSettings) {
            SettingsDialog(isExtraDark) { showSettings = false }
        }
    }

    @Composable
    fun GfxUI() {
        val context = LocalContext.current
        val view = LocalView.current
        val scope = rememberCoroutineScope()
        var loadingKey by remember { mutableStateOf("") }

        val requestPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) prefs.setBool("notifications", true)
        }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        val feature1 = FeatureItem(
            emoji = "📁", title = "WALL HACK", subtitle = "STATUS - ACTIVE", 
            color = Color(0xFFFFFF00), key = "ProgramBinaryCache.zip", 
            downloadUrl = "https://github.com/raax00/RAAZ/releases/download/Binary/ProgramBinaryCache.zip", 
            fileName = "ProgramBinaryCache.zip", targetPath = bgmiFilesDir, 
            isZip = true, targetFolderName = "ProgramBinaryCache"
        )
        val feature2 = FeatureItem(
            emoji = "🚀", title = "SMOOTH - NO LAG", subtitle = "Ultra Smooth Gameplay", 
            color = Color(0xFF00FF41), key = "fps120", 
            downloadUrl = "https://github.com/rajamransri-blip/IslamicAppData/raw/refs/heads/main/Active.sav", 
            fileName = "Active.sav", targetPath = "$bgmiSavedDir/SaveGames", 
            isZip = false, targetFolderName = "Active.sav"
        )

        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SlideInCard(delayMs = 0) { ShizukuStatusCard() }
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF00FF41)))
                    Spacer(Modifier.width(8.dp))
                    Text("PERFORMANCE TWEAKS", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
                Spacer(Modifier.height(10.dp))

                // Feature 1
                var isEnabled1 by remember { mutableStateOf(prefs.getBool(feature1.key)) }
                SlideInCard(delayMs = 80) {
                    FeatureCard(item = feature1, isEnabled = isEnabled1, isLoading = loadingKey == feature1.key) { checked ->
                        if (!shizukuPermission) { Toast.makeText(context, "Grant Shizuku Permission First!", Toast.LENGTH_SHORT).show(); return@FeatureCard }
                        if (prefs.getBool("haptic_feedback", true)) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        loadingKey = feature1.key
                        scope.launch {
                            downloadAndApplyFile(context, feature1, checked) { success, msg ->
                                loadingKey = ""; if (success) { isEnabled1 = checked; prefs.setBool(feature1.key, checked) }
                                if (prefs.getBool("notifications", true)) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // Feature 2
                var isEnabled2 by remember { mutableStateOf(prefs.getBool(feature2.key)) }
                SlideInCard(delayMs = 160) {
                    FeatureCard(item = feature2, isEnabled = isEnabled2, isLoading = loadingKey == feature2.key) { checked ->
                        if (!shizukuPermission) { Toast.makeText(context, "Grant Shizuku Permission First!", Toast.LENGTH_SHORT).show(); return@FeatureCard }
                        if (prefs.getBool("haptic_feedback", true)) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        loadingKey = feature2.key
                        scope.launch {
                            downloadAndApplyFile(context, feature2, checked) { success, msg ->
                                loadingKey = ""; if (success) { isEnabled2 = checked; prefs.setBool(feature2.key, checked) }
                                if (prefs.getBool("notifications", true)) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // Feature 3: AIM MOD (SMART EXPANDABLE CARD)
                SlideInCard(delayMs = 240) {
                    AntiAliasingCard(
                        context = context, view = view, scope = scope, 
                        bgmiShadowTrackerDir = bgmiShadowTrackerDir
                    )
                }

                Spacer(Modifier.height(30.dp))
                TelegramJoinCard()
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    //  SMART AIM MOD EXPANDABLE CARD
    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    @Composable
    fun AntiAliasingCard(context: Context, view: android.view.View, scope: CoroutineScope, bgmiShadowTrackerDir: String) {
        var isExpanded by remember { mutableStateOf(false) }
        var isSetupEnabled by remember { mutableStateOf(prefs.getBool("aa_setup_content1")) }
        var isApplyEnabled by remember { mutableStateOf(prefs.getBool("aa_apply_content")) }
        var isSetupLoading by remember { mutableStateOf(false) }

        val aaFeature = FeatureItem(
            emoji = "✨", title = "AIM MOD", subtitle = "Tap to expand and configure", 
            color = Color(0xFF00CFFF), key = "antialias_smart", 
            downloadUrl = "https://github.com/raax00/RAAZ/releases/download/Binary/Content1.zip", 
            fileName = "Content1.zip", targetPath = bgmiShadowTrackerDir, 
            isZip = true, targetFolderName = "Content1"
        )

        val cardBg = Color(0xFF13131A)
        val glowAlpha by animateFloatAsState(targetValue = if (isExpanded || isApplyEnabled) 1f else 0f, tween(400))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .drawBehind {
                    if (isExpanded || isApplyEnabled) {
                        drawRoundRect(
                            color = aaFeature.color.copy(alpha = 0.18f * glowAlpha),
                            cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                .background(Brush.linearGradient(if (isApplyEnabled) listOf(cardBg, aaFeature.color.copy(alpha = 0.10f)) else listOf(cardBg, cardBg)))
                .border(1.dp, if (isApplyEnabled) aaFeature.color.copy(alpha = 0.50f) else Color(0xFF2A2A3A), RoundedCornerShape(16.dp))
                .animateContentSize()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(aaFeature.color.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) { Text(aaFeature.emoji, fontSize = 20.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(aaFeature.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text(aaFeature.subtitle, color = Color.Gray, fontSize = 12.sp)
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand", tint = Color.Gray
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF0D0D14)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        
                        // Switch 1: SETUP
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("Step 1: Setup Options", color = Color(0xFFFFD700), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                if (isSetupLoading) {
                                    LinearProgressIndicator(modifier = Modifier.width(100.dp).padding(top = 4.dp).height(2.dp), color = Color(0xFFFFD700))
                                } else {
                                    Text("Downloads & pastes as Content1", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                            Switch(
                                checked = isSetupEnabled,
                                onCheckedChange = { checked ->
                                    if (!shizukuPermission) { Toast.makeText(context, "Need Shizuku!", Toast.LENGTH_SHORT).show(); return@Switch }
                                    if (prefs.getBool("haptic_feedback", true)) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    
                                    if (!checked && isApplyEnabled) {
                                        Toast.makeText(context, "Turn off Apply Mod first!", Toast.LENGTH_SHORT).show()
                                        return@Switch
                                    }

                                    isSetupLoading = true
                                    scope.launch {
                                        downloadAndApplyFile(context, aaFeature, checked) { success, msg ->
                                            isSetupLoading = false
                                            if (success) { isSetupEnabled = checked; prefs.setBool("aa_setup_content1", checked) }
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !isSetupLoading,
                                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFFFD700))
                            )
                        }

                        Divider(color = Color(0xFF2A2A3A), modifier = Modifier.padding(vertical = 10.dp))

                        // Switch 2: ENABLE MOD
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("Step 2: Enable Mod", color = Color(0xFF00FF41), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("Renames Content1 to Content", color = Color.Gray, fontSize = 11.sp)
                            }
                            Switch(
                                checked = isApplyEnabled,
                                onCheckedChange = { checked ->
                                    if (!shizukuPermission) { Toast.makeText(context, "Need Shizuku!", Toast.LENGTH_SHORT).show(); return@Switch }
                                    if (prefs.getBool("haptic_feedback", true)) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    
                                    if (checked && !isSetupEnabled) {
                                        Toast.makeText(context, "Please turn ON Setup first!", Toast.LENGTH_SHORT).show()
                                        return@Switch
                                    }

                                    if (checked) {
                                        // RENAME LOGIC (ON)
                                        val cmd = "mv '$bgmiShadowTrackerDir/Content1' '$bgmiShadowTrackerDir/Content'"
                                        val success = runShizukuCommand(cmd)
                                        if (success) {
                                            isApplyEnabled = true
                                            prefs.setBool("aa_apply_content", true)
                                            Toast.makeText(context, "Mod Activated!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Failed to apply Mod", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        // DELETE LOGIC (OFF) - Deletes Content folder & Resets Setup switch
                                        val cmd = "rm -rf '$bgmiShadowTrackerDir/Content'"
                                        val success = runShizukuCommand(cmd)
                                        if (success) {
                                            isApplyEnabled = false
                                            isSetupEnabled = false // Sync switch 1 to off
                                            prefs.setBool("aa_apply_content", false)
                                            prefs.setBool("aa_setup_content1", false)
                                            Toast.makeText(context, "Mod Deleted & Disabled!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Failed to delete Mod", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF00FF41))
                            )
                        }
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    //  Standard Feature Card
    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    @Composable
    fun FeatureCard(item: FeatureItem, isEnabled: Boolean, isLoading: Boolean, onToggle: (Boolean) -> Unit) {
        val glowAlpha by animateFloatAsState(targetValue = if (isEnabled) 1f else 0f, tween(400))
        val cardAlpha by animateFloatAsState(targetValue = if (isEnabled) 1f else 0.75f, tween(300))
        val cardBg = Color(0xFF13131A)
        val cardBorder = Color(0xFF2A2A3A)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .drawBehind {
                    if (isEnabled) {
                        drawRoundRect(
                            color = item.color.copy(alpha = 0.18f * glowAlpha),
                            cornerRadius = CornerRadius(16.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                .background(Brush.linearGradient(if (isEnabled) listOf(cardBg, item.color.copy(alpha = 0.10f)) else listOf(cardBg, cardBg)))
                .border(1.dp, if (isEnabled) item.color.copy(alpha = 0.50f) else cardBorder, RoundedCornerShape(16.dp))
                .clickable(enabled = !isLoading) { onToggle(!isEnabled) }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(item.color.copy(alpha = if (isEnabled) 0.20f else 0.08f)),
                    contentAlignment = Alignment.Center
                ) { Text(item.emoji, fontSize = 20.sp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Color.White.copy(alpha = cardAlpha), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.width(130.dp).height(3.dp).clip(RoundedCornerShape(2.dp)), color = item.color, trackColor = cardBorder)
                    } else {
                        Text(item.subtitle, color = if (isEnabled) item.color.copy(0.85f) else Color.Gray, fontSize = 12.sp)
                    }
                }
                Switch(
                    checked = isEnabled, onCheckedChange = { onToggle(it) }, enabled = !isLoading,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = item.color, uncheckedTrackColor = Color(0xFF39393D))
                )
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    //  Telegram Button Design
    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    @Composable
    fun TelegramJoinCard() {
        val context = LocalContext.current
        val pulseAnimation by rememberInfiniteTransition().animateFloat(
            initialValue = 0.98f, targetValue = 1.02f,
            animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(pulseAnimation)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1E88E5), Color(0xFF1565C0))))
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/raaxrr")) 
                    context.startActivity(intent)
                }
                .padding(2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF13131A).copy(alpha = 0.3f))
                    .padding(vertical = 16.dp, horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Send, contentDescription = "Telegram", tint = Color.White, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Join Our Telegram Channel", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Text("Get latest updates & configs", color = Color(0xFFBBDEFB), fontSize = 12.sp)
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    //  Settings & Drawer System
    // ────────────────────────────────────────────────────────────────────────────────────────────────────
    @Composable
    fun SettingsDialog(isExtraDark: MutableState<Boolean>, onDismiss: () -> Unit) {
        val context = LocalContext.current
        var notifs   by remember { mutableStateOf(prefs.getBool("notifications", true)) }
        var haptics  by remember { mutableStateOf(prefs.getBool("haptic_feedback", true)) }
        var extraDrk by remember { mutableStateOf(prefs.getBool("extra_dark", true)) }

        Dialog(onDismissRequest = onDismiss) {
            Card(
                shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A28)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, null, tint = Color(0xFF00FF41), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, null, tint = Color.Gray) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Divider(color = Color(0xFF2A2A3A))
                    Spacer(Modifier.height(8.dp))

                    SettingToggle("🔔 Notifications", "Show apply/remove alerts", notifs, Color(0xFF00FF41)) { notifs = it; prefs.setBool("notifications", it) }
                    SettingToggle("🌘 Extra Dark Mode", "Deeper background theme", extraDrk, Color(0xFFBB86FC)) { extraDrk = it; isExtraDark.value = it; prefs.setBool("extra_dark", it) }
                    SettingToggle("📳 Haptic Feedback", "Vibrate on toggle", haptics, Color(0xFFFF6D00)) { haptics = it; prefs.setBool("haptic_feedback", it) }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val mediaDirs = context.externalMediaDirs
                            val cacheDir = if (mediaDirs.isNotEmpty()) File(mediaDirs[0], "GFX_Smart_Cache") else File(context.getExternalFilesDir(null), "GFX_Smart_Cache")
                            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                            Toast.makeText(context, "Downloaded Cache Cleared!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1A1A)), border = BorderStroke(1.dp, Color(0xFFFF6D00).copy(0.5f))
                    ) {
                        Text("Clear Downloaded Cache", color = Color(0xFFFF6D00), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    fun SettingToggle(title: String, subtitle: String, checked: Boolean, color: Color, onChange: (Boolean) -> Unit) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = color, uncheckedTrackColor = Color(0xFF39393D)))
        }
    }

    @Composable
    fun ShizukuStatusCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (shizukuRunning && shizukuPermission) Color(0xFF00FF41).copy(0.1f) else Color(0xFFFF6D00).copy(0.1f)),
            border = BorderStroke(1.dp, if (shizukuRunning && shizukuPermission) Color(0xFF00FF41).copy(0.5f) else Color(0xFFFF6D00).copy(0.5f))
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = if (shizukuRunning && shizukuPermission) Color(0xFF00FF41) else Color(0xFFFF6D00))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Shizuku Status", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(if (shizukuRunning && shizukuPermission) "Running & Permission Granted" else "Not Running or Denied", color = if (shizukuRunning && shizukuPermission) Color(0xFF00FF41) else Color(0xFFFF6D00), fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    fun SlideInCard(delayMs: Int, content: @Composable () -> Unit) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(delayMs.toLong()); visible = true }
        AnimatedVisibility(visible = visible, enter = slideInVertically(initialOffsetY = { 50 }, animationSpec = tween(500)) + fadeIn(tween(500))) { content() }
    }

    @Composable fun AboutPage() { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("GFX PRO v2.0", color = Color(0xFF00FF41)) } }
    @Composable fun HelpPage() { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Need Shizuku to copy files.", color = Color(0xFF00CFFF)) } }

    @Composable 
    fun DrawerContent(selected: String, onNavigate: (String) -> Unit) { 
        ModalDrawerSheet(
            drawerContainerColor = Color(0xFF0D0D14),
            modifier = Modifier.width(280.dp)
        ) {
            Spacer(Modifier.height(40.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1A2A1A), Color(0xFF0D1A2A))))
                    .border(1.dp, Color(0xFF00FF41).copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text("⚡ GFX PRO", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF00FF41))
                    Text("BGMI / PUBG Optimizer", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    Text("v2.0 — Shizuku Powered", fontSize = 11.sp, color = Color(0xFF00CFFF))
                }
            }
            Spacer(Modifier.height(20.dp))
            Divider(color = Color(0xFF2A2A3A))
            Spacer(Modifier.height(8.dp))

            data class NavItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val color: Color)
            val items = listOf(
                NavItem(Icons.Default.Home,  "Home",  Color(0xFF00FF41)),
                NavItem(Icons.Default.Info,  "About", Color(0xFF00CFFF)),
                NavItem(Icons.Default.Build, "Help",  Color(0xFFBB86FC)) 
            )
            items.forEach { item ->
                val isSel = selected == item.label
                NavigationDrawerItem(
                    icon     = { Icon(item.icon, null, tint = if (isSel) item.color else Color.Gray) },
                    label    = { Text(item.label, color = if (isSel) item.color else Color.LightGray, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                    selected = isSel,
                    onClick  = { onNavigate(item.label) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    colors   = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor   = item.color.copy(alpha = 0.12f),
                        unselectedContainerColor = Color.Transparent
                    )
                )
            }
            Spacer(Modifier.weight(1f))
            Divider(color = Color(0xFF2A2A3A))
            Text("⚠️  For educational use only", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(24.dp))
        }
    }
}
