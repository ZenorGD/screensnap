package com.tommyapps.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, RecorderService::class.java)
                        .setAction(RecorderService.ACTION_START)
                        .putExtra("code", r.resultCode)
                        .putExtra("data", data)
                )
                moveTaskToBack(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        enableEdgeToEdge()
        setContent { AppTheme { App(::toggle) } }
    }

    private fun toggle() {
        if (RecorderService.state.value == RecorderService.IDLE) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        }
    }
}

/* ---------- Theme (Material 3 Expressive) ---------- */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (Prefs.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(
            primary = Color(0xFFBBA6FF), onPrimary = Color(0xFF2A0F9E),
            primaryContainer = Color(0xFF4A2FD0), onPrimaryContainer = Color(0xFFE6DEFF),
            error = Color(0xFFFF6B6B)
        )
        else -> lightColorScheme(
            primary = Color(0xFF5B3DF5), primaryContainer = Color(0xFFE6DEFF),
            onPrimaryContainer = Color(0xFF1A0063), error = Color(0xFFE53935)
        )
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

/* ---------- Helpers ---------- */
fun hasMic(c: Context) =
    ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
fun hasNotif(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

fun hasWriteSettings(c: Context) =
    Settings.System.canWrite(c)

/** Turns the floating menu on/off. Takes effect straight away if a recording is running. */
fun setFloatingMenu(ctx: Context, on: Boolean) {
    if (on && !Settings.canDrawOverlays(ctx)) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        return
    }
    Prefs.overlay = on
    if (RecorderService.state.value != RecorderService.IDLE) {
        val i = Intent(ctx, OverlayService::class.java)
        if (on) ctx.startService(i) else ctx.stopService(i)
    }
}


/* ---------- Navigation with animations + predictive back ---------- */
@Composable
fun App(onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val state by RecorderService.state.collectAsState()
    val nav = rememberNavController()
    val start = remember {
        when {
            !Prefs.onboarded -> "welcome"
            !hasNotif(ctx) -> "permissions"      // the required permission was revoked
            else -> "main"
        }
    }
    val finishSetup: () -> Unit = {
        Prefs.onboarded = true
        nav.navigate("main") { popUpTo(nav.graph.findStartDestination().id) { inclusive = true } }
    }

    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = { slideInHorizontally(tween(350, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(300)) },
        exitTransition = { slideOutHorizontally(tween(350, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(250)) },
        popEnterTransition = { slideInHorizontally(tween(350, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(300)) },
        popExitTransition = { slideOutHorizontally(tween(350, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(250)) }
    ) {
        composable("welcome") { WelcomeScreen { nav.navigate("permissions") } }
        composable("permissions") {
            PermissionsScreen("Continue") { if (Prefs.onboarded) finishSetup() else nav.navigate("folder") }
        }
        composable("folder") { FolderScreen(finishSetup) }
        composable("main") { MainScreen(state, onToggle) { nav.navigate("settings") } }
        composable("settings") { SettingsScreen { nav.popBackStack() } }
    }
}

/* ---------- Welcome ---------- */
@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    val wave by rememberInfiniteTransition(label = "wave").animateFloat(
        -15f, 15f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w"
    )
    // Draw the emoji into an image once. Animating a big emoji *text* can flicker on some devices,
    // animating an image is smooth.
    val emoji = remember {
        val size = 400
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.8f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        android.graphics.Canvas(bmp).drawText("👋", size / 2f, y, paint)
        bmp.asImageBitmap()
    }
    Scaffold { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    emoji, null,
                    Modifier.size(160.dp).graphicsLayer { rotationZ = wave; transformOrigin = TransformOrigin(0.7f, 0.9f) }
                )
                Spacer(Modifier.height(24.dp))
                Text("Welcome to ScreenSnap", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text("Simple, smooth screen recording", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            Button(onContinue, Modifier.fillMaxWidth().height(64.dp)) { Text("Continue") }
        }
    }
}

/* ---------- Permissions ---------- */
@Composable
fun PermissionsScreen(buttonLabel: String, onNext: () -> Unit) {
    val ctx = LocalContext.current
    var mic by remember { mutableStateOf(hasMic(ctx)) }
    var notif by remember { mutableStateOf(hasNotif(ctx)) }
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var micDenied by remember { mutableStateOf(false) }
    var notifDenied by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        mic = hasMic(ctx); notif = hasNotif(ctx); overlay = Settings.canDrawOverlays(ctx)
        onPauseOrDispose { }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mic = it; micDenied = !it }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = it; notifDenied = !it }
    fun appSettings() = ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    )

    Scaffold { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(24.dp)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Permissions", style = MaterialTheme.typography.displaySmall)
                Text("Only the required one is needed to start recording.", style = MaterialTheme.typography.bodyLarge)
                PermCard("Notifications", "Pause and stop controls while you record", true, notif, notifDenied) {
                    if (notifDenied) appSettings()
                    else if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                PermCard("Microphone", "Record your voice or device audio", false, mic, micDenied) {
                    if (micDenied) appSettings() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                PermCard("Display over other apps", "Floating controls and the pen", false, overlay, false) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onNext, Modifier.fillMaxWidth().height(64.dp), enabled = notif) { Text(buttonLabel) }
        }
    }
}

@Composable
fun PermCard(title: String, desc: String, required: Boolean, granted: Boolean, denied: Boolean, onGrant: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (required) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            if (required) "Required" else "Optional",
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            supportingContent = { Text(desc) },
            trailingContent = {
                if (granted) {
                    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape), Alignment.Center) {
                        Icon(Icons.Default.Check, "Granted", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                } else {
                    FilledTonalButton(onGrant) { Text(if (denied) "Settings" else "Grant") }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

/* ---------- Main screen: pill + tabs + GIF progress ---------- */
private class GifProgress(val index: Int, val total: Int, val fraction: Float)

@Composable
fun MainScreen(state: Int, onToggle: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val vs = remember { VideosState() }
    var progress by remember { mutableStateOf<GifProgress?>(null) }
    var gifJob by remember { mutableStateOf<Job?>(null) }
    val selecting = tab == 1 && vs.selected.isNotEmpty()
    BackHandler(selecting) { vs.selected = emptySet() }

    val makeGifs: (List<VideoItem>) -> Unit = { list ->
        vs.selected = emptySet()
        gifJob = scope.launch {
            var done = 0
            try {
                list.forEachIndexed { i, item ->
                    progress = GifProgress(i + 1, list.size, 0f)
                    val ok = try {
                        withContext(Dispatchers.IO) {
                            GifMaker.convert(ctx, item) { f -> progress = GifProgress(i + 1, list.size, f) }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        false
                    }
                    if (ok) done++
                }
                Toast.makeText(
                    ctx,
                    if (done > 0) "Saved $done GIF${if (done > 1) "s" else ""} ✅" else "Couldn't create the GIF",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                progress = null
                vs.reload++
            }
        }
    }

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "topbar"
            ) { sel ->
                if (sel) {
                    SelectionBar(vs, makeGifs)
                } else {
                    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp), Alignment.Center) {
                        TopPill(tab) { tab = it; vs.selected = emptySet() }
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it * dir / 4 } + fadeIn(tween(250))) togetherWith
                            (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it * dir / 4 } + fadeOut(tween(150)))
                },
                label = "tabs"
            ) { t ->
                if (t == 0) RecordTab(state, onToggle, onSettings) else VideosTab(state, vs, makeGifs)
            }
        }
    }

    progress?.let { p ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (p.total > 1) "Creating GIF ${p.index} of ${p.total}" else "Creating GIF") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Uses the first ${GifMaker.MAX_SECONDS} seconds, ${GifMaker.FPS} FPS, ${GifMaker.MAX_WIDTH} px wide.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton({ gifJob?.cancel() }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RecordTab(state: Int, onToggle: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    val rec = state != RecorderService.IDLE

    // Spins while recording, freezes on pause, resumes from the same angle
    val angle = remember { Animatable(0f) }
    LaunchedEffect(state == RecorderService.RECORDING) {
        if (state == RecorderService.RECORDING) {
            while (true) angle.animateTo(angle.value + 360f, tween(14000, easing = LinearEasing))
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ScreenSnap", style = MaterialTheme.typography.headlineLarge)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { rotationZ = angle.value }
                        .background(MaterialTheme.colorScheme.primaryContainer, cookie)
                )
                Box(Modifier.size(180.dp).clip(CircleShape).clickable(onClick = onToggle), Alignment.Center) {
                    Icon(
                        painterResource(if (rec) R.drawable.ic_stop else R.drawable.ic_record),
                        if (rec) "Stop recording" else "Start recording",
                        Modifier.size(120.dp), tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Text(
            when (state) { RecorderService.RECORDING -> "Recording…"; RecorderService.PAUSED -> "Paused"; else -> "Tap to start recording" },
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        FilterChip(
            selected = Prefs.overlay,
            onClick = { setFloatingMenu(ctx, !Prefs.overlay) },
            label = { Text(if (Prefs.overlay) "Floating menu: On" else "Floating menu: Off") },
            leadingIcon = {
                Icon(if (Prefs.overlay) Icons.Default.Check else Icons.Default.Close, null, Modifier.size(18.dp))
            }
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onSettings, Modifier.fillMaxWidth().height(64.dp)) {
            Icon(Icons.Default.SettingsIcon, null)
            Spacer(Modifier.width(8.dp))
            Text("Settings", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/* ---------- Settings ---------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var writeSettingsOk by remember {
        mutableStateOf(hasWriteSettings(ctx))
    }

    var enableTouchesAfterPermission by remember {
        mutableStateOf(false)
    }

    LifecycleResumeEffect(Unit) {
        writeSettingsOk = hasWriteSettings(ctx)

        if (writeSettingsOk && enableTouchesAfterPermission) {
            Prefs.showTouches = true
        }

        enableTouchesAfterPermission = false

        onPauseOrDispose { }
    }

    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) saveFolder(ctx, uri)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val audioOptions = buildList {
        add("None" to 0); add("Mic" to 1)
        if (Build.VERSION.SDK_INT >= 29) { add("Device" to 2); add("Both" to 3) }
    }



    Scaffold(
        Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                scrollBehavior = scroll
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Section("Video") {
                Choice("Resolution", listOf("Native" to 0, "480p" to 480, "720p" to 720, "1080p" to 1080), Prefs.height) { Prefs.height = it }
                Choice("Frame rate", listOf("24 FPS" to 24, "30 FPS" to 30, "60 FPS" to 60), Prefs.fps) { Prefs.fps = it }
                Text("Bit rate: ${Prefs.bitrate} Mbps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Slider(Prefs.bitrate.toFloat(), { Prefs.bitrate = it.roundToInt() }, valueRange = 1f..40f, steps = 38)
            }
            Section("Audio") {
                Choice("Audio source", audioOptions, Prefs.audio) {
                    Prefs.audio = it
                    if (it != 0 && !hasMic(ctx)) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                Text(
                    "Device audio records sound playing on your phone (Android 10+). Some apps block it.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Section("Storage") {
                ListItem(
                    headlineContent = { Text("Save location") },
                    supportingContent = { Text(folderLabel()) },
                    trailingContent = { FilledTonalButton({ folder.launch(null) }) { Text("Change") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                if (Prefs.saveUri.isNotEmpty()) TextButton({ Prefs.saveUri = "" }) { Text("Reset to default") }
            }
            Section("Appearance") {
                Choice("App theme", listOf("System" to 0, "Light" to 1, "Dark" to 2), Prefs.theme) { Prefs.theme = it }
            }
            Section("Recording") {
                SwitchRow(
                    "Show touches",
                    if (writeSettingsOk) {
                        "Highlight taps in the recording"
                    } else {
                        "Allow system settings access to highlight taps"
                    },
                    Prefs.showTouches && writeSettingsOk
                ) { on ->
                    if (on && !writeSettingsOk) {
                        enableTouchesAfterPermission = true

                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    } else {
                        Prefs.showTouches = on
                    }
                }
            }
            Section("Floating controls") {
                SwitchRow("Floating menu", "Pause, stop, pen and erase while recording", Prefs.overlay) { on ->
                    setFloatingMenu(ctx, on)
                }
                Text("Overlay opacity: ${Prefs.overlayAlpha}%", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Slider(Prefs.overlayAlpha.toFloat(), { Prefs.overlayAlpha = it.roundToInt() }, valueRange = 30f..100f)
                Text(
                    "Tip: tap the pen to draw, tap it again to choose a colour, tap once more to turn it off.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text("ScreenSnap 1.0.0 · Developer: Tommy Apps",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        Card(
            Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) { Column(Modifier.padding(16.dp), content = content) }
    }
}

@Composable
fun <T> Choice(title: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (label, v) ->
                SegmentedButton(selected == v, { onSelect(v) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
            }
        }
    }
}

/** A switch with a tick (on) or cross (off) on the thumb, like Pixel settings. */
@Composable
fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) = ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(desc) },
    trailingContent = {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            thumbContent = {
                Icon(
                    if (checked) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        )
    },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
)