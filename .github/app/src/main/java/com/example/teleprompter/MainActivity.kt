package com.example.teleprompter

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0B0E13)
private val Card = Color(0xFF1A1F28)
private val Amber = Color(0xFFFFB547)
private val RecRed = Color(0xFFFF5A5A)
private val Muted = Color(0xFF9AA3B2)

data class Script(val id: Long, val title: String, val body: String)
data class Prefs(
    val font: Int = 34,
    val speed: Int = 4,
    val mirror: Boolean = false,
    val guide: Boolean = true,
    val keepOn: Boolean = true,
)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tp", Context.MODE_PRIVATE)

    fun scripts(): List<Script> {
        val raw = sp.getString("scripts", null) ?: return listOf(
            Script(
                1, "دەقی نموونە",
                "سڵاو و بەخێربێن. ئەمە دەقێکی نموونەیە.\n\nدەقی خۆت لێرە بنووسە یان دابنێ، پاشان دوگمەی دەستپێکردن دابگرە بۆ ئەوەی دەقەکە بە خۆی بخشێت.\n\nدەتوانیت لە هەمان کاتدا ڤیدیۆ تۆمار بکەیت."
            )
        )
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Script(o.getLong("id"), o.getString("title"), o.getString("body"))
        }
    }

    fun saveScripts(list: List<Script>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body)) }
        sp.edit().putString("scripts", arr.toString()).apply()
    }

    fun prefs() = Prefs(
        sp.getInt("font", 34), sp.getInt("speed", 4), sp.getBoolean("mirror", false),
        sp.getBoolean("guide", true), sp.getBoolean("keepOn", true)
    )

    fun savePrefs(p: Prefs) = sp.edit().putInt("font", p.font).putInt("speed", p.speed)
        .putBoolean("mirror", p.mirror).putBoolean("guide", p.guide).putBoolean("keepOn", p.keepOn).apply()
}

sealed interface Screen {
    data object Library : Screen
    data class Editor(val script: Script?) : Screen
    data class Reader(val script: Script) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Amber, background = Bg, surface = Card)) {
                    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
                    var scripts by remember { mutableStateOf(store.scripts()) }
                    var prefs by remember { mutableStateOf(store.prefs()) }

                    Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
                        when (val s = screen) {
                            Screen.Library -> LibraryScreen(
                                scripts,
                                onOpen = { screen = Screen.Reader(it) },
                                onEdit = { screen = Screen.Editor(it) },
                                onNew = { screen = Screen.Editor(null) },
                                onSettings = { screen = Screen.Settings },
                            )
                            is Screen.Editor -> EditorScreen(
                                s.script,
                                onSave = { saved ->
                                    scripts = if (scripts.any { it.id == saved.id }) scripts.map { if (it.id == saved.id) saved else it } else scripts + saved
                                    store.saveScripts(scripts); screen = Screen.Library
                                },
                                onDelete = { id ->
                                    scripts = scripts.filter { it.id != id }
                                    store.saveScripts(scripts); screen = Screen.Library
                                },
                                onBack = { screen = Screen.Library },
                            )
                            is Screen.Reader -> ReaderScreen(s.script, prefs) { screen = Screen.Library }
                            Screen.Settings -> SettingsScreen(prefs, { prefs = it; store.savePrefs(it) }) { screen = Screen.Library }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RoundButton(onClick: () -> Unit, size: Int = 56, color: Color = Card, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "گەڕانەوە", tint = Color.White)
            }
        }
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        actions()
    }
}

@Composable
fun LibraryScreen(scripts: List<Script>, onOpen: (Script) -> Unit, onEdit: (Script) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column {
            TopBar("دەقەکانم", null) {
                IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "ڕێکخستنەکان", tint = Color.White)
                }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(scripts, key = { it.id }) { s ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onOpen(s) }.padding(16.dp).heightIn(min = 56.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(s.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(s.body, color = Muted, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onEdit(s) }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "دەستکاری", tint = Amber)
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew, containerColor = Amber, contentColor = Bg,
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("دەقی نوێ", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun EditorScreen(script: Script?, onSave: (Script) -> Unit, onDelete: (Long) -> Unit, onBack: () -> Unit) {
    var title by remember { mutableStateOf(script?.title ?: "") }
    var body by remember { mutableStateOf(script?.body ?: "") }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(if (script == null) "دەقی نوێ" else "دەستکاری", onBack) {
            if (script != null) IconButton(onClick = { onDelete(script.id) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "سڕینەوە", tint = RecRed)
            }
            IconButton(
                onClick = { onSave(Script(script?.id ?: System.currentTimeMillis(), title.ifBlank { "بێ ناونیشان" }, body)) },
                modifier = Modifier.size(48.dp)
            ) { Icon(Icons.Default.Check, contentDescription = "پاشەکەوتکردن", tint = Amber) }
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("ناونیشان") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        OutlinedTextField(
            value = body, onValueChange = { body = it }, label = { Text("دەق") },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp)
        )
    }
}

@Composable
fun SettingsScreen(prefs: Prefs, onChange: (Prefs) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar("ڕێکخستنەکان", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp)) {
                Text("قەبارەی نووسین: ${prefs.font}", color = Color.White, fontSize = 17.sp)
                Slider(prefs.font.toFloat(), { onChange(prefs.copy(font = it.toInt())) }, valueRange = 20f..64f)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp)) {
                Text("خێرایی خشان: ${prefs.speed}", color = Color.White, fontSize = 17.sp)
                Slider(prefs.speed.toFloat(), { onChange(prefs.copy(speed = it.toInt())) }, valueRange = 1f..10f)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(horizontal = 16.dp)) {
                SwitchRow("دۆخی ئاوێنەیی", prefs.mirror) { onChange(prefs.copy(mirror = it)) }
                SwitchRow("هێڵی ڕێنمایی", prefs.guide) { onChange(prefs.copy(guide = it)) }
                SwitchRow("ڕێگەنەدان بە کوژانەوەی شاشە", prefs.keepOn) { onChange(prefs.copy(keepOn = it)) }
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun ReaderScreen(script: Script, prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as LifecycleOwner
    BackHandler(onBack = onBack)

    val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    var hasPerm by remember { mutableStateOf(perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> hasPerm = r.values.all { it } }
    LaunchedEffect(Unit) { if (!hasPerm) launcher.launch(perms) }

    val view = LocalView.current
    DisposableEffect(prefs.keepOn) {
        view.keepScreenOn = prefs.keepOn
        onDispose { view.keepScreenOn = false }
    }

    // Camera
    val previewView = remember { PreviewView(ctx) }
    var front by remember { mutableStateOf(true) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    LaunchedEffect(hasPerm, front) {
        if (!hasPerm) return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(ctx).get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        val vc = VideoCapture.withOutput(recorder)
        provider.unbindAll()
        provider.bindToLifecycle(
            owner,
            if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
            preview, vc
        )
        videoCapture = vc
    }
    DisposableEffect(Unit) { onDispose { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } }

    // Recording
    var recording by remember { mutableStateOf<Recording?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    val isRec = recording != null
    LaunchedEffect(isRec) {
        seconds = 0
        while (isRec) { delay(1000); seconds++ }
    }
    fun startRecording() {
        val vc = videoCapture ?: return
        val name = "Teleprompter_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Teleprompter")
        }
        val opts = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        recording = vc.output.prepareRecording(ctx, opts).withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    recording = null
                    val msg = if (ev.hasError()) "تۆمارکردن سەرکەوتوو نەبوو" else "ڤیدیۆ لە گەلەری پاشەکەوت کرا"
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }
    }
    DisposableEffect(Unit) { onDispose { recording?.stop() } }

    // Scrolling
    val scroll = rememberScrollState()
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(prefs.speed.toFloat()) }
    var font by remember { mutableIntStateOf(prefs.font) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            scroll.scrollBy(speed * 20f * dt)
            if (scroll.value >= scroll.maxValue) { playing = false; break }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "گەڕانەوە", tint = Color.White)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(script.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isRec) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(RecRed))
                    Spacer(Modifier.width(6.dp))
                    Text("تۆمارکردن %02d:%02d".format(seconds / 60, seconds % 60), color = Color.White, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.size(48.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (hasPerm) AndroidView({ previewView }, Modifier.fillMaxSize())
            else Text("ڕێگە بدە بە کامێرا و مایکرۆفۆن", color = Muted, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = !playing).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(180.dp))
                Text(
                    script.body, color = Color.White, fontSize = font.sp, lineHeight = (font * 1.6f).sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = if (prefs.mirror) -1f else 1f }
                )
                Spacer(Modifier.height(500.dp))
            }
            if (prefs.guide) Box(Modifier.fillMaxWidth().padding(top = 180.dp).height(2.dp).background(Amber.copy(alpha = 0.7f)))
        }

        Column(Modifier.fillMaxWidth().background(Color(0xFF12161D)).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("خێرایی", color = Muted, fontSize = 16.sp)
                Slider(speed, { speed = it }, valueRange = 1f..10f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(speed.toInt().toString(), color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                RoundButton({ font = (font + 4).coerceAtMost(72) }) { Text("A+", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                RoundButton({ playing = !playing }, 72, Amber) {
                    if (playing) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp, 26.dp).background(Bg)); Box(Modifier.size(8.dp, 26.dp).background(Bg))
                    } else Icon(Icons.Default.PlayArrow, contentDescription = "دەستپێکردن", tint = Bg, modifier = Modifier.size(38.dp))
                }
                RoundButton({ if (isRec) recording?.stop() else startRecording() }, 72, Color.Transparent) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White)) {}
                    Box(
                        Modifier.size(60.dp).clip(CircleShape).background(Bg).padding(if (isRec) 18.dp else 10.dp)
                            .clip(if (isRec) RoundedCornerShape(6.dp) else CircleShape).background(RecRed)
                    )
                }
                RoundButton({ if (!isRec) front = !front }) {
                    Icon(Icons.Default.Refresh, contentDescription = "گۆڕینی کامێرا", tint = Color.White)
                }
            }
        }
    }
}
