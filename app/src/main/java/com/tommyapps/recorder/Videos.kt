package com.tommyapps.recorder

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/* ---------- Folder helpers ---------- */

/** "primary:Movies/x" -> "Main storage: Movies/x", "raw:/storage/emulated/0/x" -> "Main storage: x" */
fun prettyFolder(uriStr: String): String {
    if (uriStr.isEmpty()) return "Main storage: Movies/ScreenSnap"
    val id = try { DocumentsContract.getTreeDocumentId(Uri.parse(uriStr)) } catch (_: Exception) { return uriStr }
    val volume = id.substringBefore(':')
    val path = id.substringAfter(':', "")
    return when {
        volume == "primary" -> "Main storage: " + path.ifEmpty { "/" }
        volume == "raw" && path.startsWith("/storage/emulated/0") ->
            "Main storage: " + path.removePrefix("/storage/emulated/0").removePrefix("/").ifEmpty { "/" }
        volume == "raw" -> "Storage: $path"
        else -> "SD card: " + path.ifEmpty { "/" }
    }
}

fun folderLabel(): String =
    if (Prefs.saveUri.isEmpty()) "Main storage: Movies/ScreenSnap (default)" else prettyFolder(Prefs.saveUri)

fun saveFolder(ctx: Context, uri: Uri) {
    ctx.contentResolver.takePersistableUriPermission(
        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
    Prefs.saveUri = uri.toString()
}

/* ---------- Finding the videos and GIFs ---------- */

class VideoItem(val uri: Uri, val name: String, val modified: Long, val size: Long, val isGif: Boolean)

fun mimeOf(v: VideoItem) = if (v.isGif) "image/gif" else "video/mp4"

fun loadVideos(ctx: Context): List<VideoItem> {
    val out = mutableListOf<VideoItem>()
    try {
        val custom = Prefs.saveUri
        when {
            custom.isNotEmpty() -> {
                val tree = Uri.parse(custom)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val cols = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE
                )
                ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        val gif = mime == "image/gif" || name.endsWith(".gif", ignoreCase = true)
                        val mp4 = mime == "video/mp4" || name.endsWith(".mp4", ignoreCase = true)
                        if (gif || mp4) {
                            out += VideoItem(
                                DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                                name, c.getLong(3), c.getLong(4), gif
                            )
                        }
                    }
                }
            }
            Build.VERSION.SDK_INT >= 29 -> {
                val vCol = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                ctx.contentResolver.query(
                    vCol,
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.SIZE),
                    "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND ${MediaStore.Video.Media.MIME_TYPE} = ?",
                    arrayOf("Movies/ScreenSnap/", "video/mp4"), null
                )?.use { c ->
                    while (c.moveToNext()) {
                        out += VideoItem(
                            ContentUris.withAppendedId(vCol, c.getLong(0)),
                            c.getString(1) ?: "video.mp4", c.getLong(2) * 1000, c.getLong(3), false
                        )
                    }
                }
                val iCol = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                ctx.contentResolver.query(
                    iCol,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.SIZE),
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?",
                    arrayOf("Pictures/ScreenSnap/", "image/gif"), null
                )?.use { c ->
                    while (c.moveToNext()) {
                        out += VideoItem(
                            ContentUris.withAppendedId(iCol, c.getLong(0)),
                            c.getString(1) ?: "image.gif", c.getLong(2) * 1000, c.getLong(3), true
                        )
                    }
                }
            }
            else -> { // Android 7-9: the app's own Movies folder
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                dir?.listFiles { f: File -> f.extension.equals("mp4", true) || f.extension.equals("gif", true) }?.forEach { f ->
                    out += VideoItem(
                        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f),
                        f.name, f.lastModified(), f.length(), f.extension.equals("gif", true)
                    )
                }
            }
        }
    } catch (_: Exception) { }
    return out.sortedByDescending { it.modified }
}

/* ---------- Date headers ---------- */

private fun dayKey(ms: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

/** "Today", "Yesterday", or e.g. "Mon, Oct 27" (with the year if it isn't this year). */
fun dateLabel(ctx: Context, ms: Long): String {
    val k = dayKey(ms)
    val now = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when (k) {
        dayKey(now.timeInMillis) -> "Today"
        dayKey(yesterday.timeInMillis) -> "Yesterday"
        else -> {
            var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
            if (k / 1000 != now.get(Calendar.YEAR)) flags = flags or DateUtils.FORMAT_SHOW_YEAR
            DateUtils.formatDateTime(ctx, ms, flags)
        }
    }
}

sealed class ListEntry(val key: String) {
    class Header(val label: String, day: Int) : ListEntry("header-$day")
    class Item(val v: VideoItem) : ListEntry(v.uri.toString())
}

fun buildEntries(ctx: Context, list: List<VideoItem>): List<ListEntry> = buildList {
    var last = -1
    for (v in list) {
        val k = dayKey(v.modified)
        if (k != last) { add(ListEntry.Header(dateLabel(ctx, v.modified), k)); last = k }
        add(ListEntry.Item(v))
    }
}

/* ---------- Actions ---------- */

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

fun openItem(ctx: Context, v: VideoItem) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(v.uri, mimeOf(v)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: Exception) { toast(ctx, "No app found to open this") }
}

fun editItem(ctx: Context, v: VideoItem) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_EDIT).setDataAndType(v.uri, mimeOf(v))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        )
    } catch (_: Exception) { toast(ctx, "No editing app found on this device") }
}

fun shareItems(ctx: Context, items: List<VideoItem>) {
    if (items.isEmpty()) return
    try {
        val uris = ArrayList(items.map { it.uri })
        val types = items.map { mimeOf(it) }.distinct()
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.type = if (types.size == 1) types[0] else "*/*"
        val clip = ClipData.newRawUri("", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "Share"))
    } catch (_: Exception) { toast(ctx, "Couldn't share") }
}

/* ---------- Selection state ---------- */

class VideosState {
    var videos by mutableStateOf<List<VideoItem>?>(null)
    var selected by mutableStateOf(setOf<String>())
    var reload by mutableIntStateOf(0)
    val selectedItems: List<VideoItem> get() = videos.orEmpty().filter { it.uri.toString() in selected }
}

/* ---------- Top pill (Record | Videos) ---------- */

@Composable
fun TopPill(selected: Int, onSelect: (Int) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
        Row(Modifier.padding(4.dp)) {
            listOf("Record", "Videos").forEachIndexed { i, label ->
                val sel = selected == i
                val bg by animateColorAsState(
                    if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "pill"
                )
                Box(
                    Modifier.clip(CircleShape).background(bg).clickable { onSelect(i) }
                        .padding(horizontal = 28.dp, vertical = 10.dp),
                    Alignment.Center
                ) {
                    Text(
                        label, style = MaterialTheme.typography.titleSmall,
                        color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/* ---------- Selection bar (replaces the pill while items are selected) ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(vs: VideosState, onGifs: (List<VideoItem>) -> Unit) {
    val ctx = LocalContext.current
    val items = vs.selectedItems
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("${vs.selected.size} selected") },
        navigationIcon = {
            IconButton({ vs.selected = emptySet() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Unselect all") }
        },
        actions = {
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = {
                        menu = false
                        items.firstOrNull()?.let { openItem(ctx, it) }
                        vs.selected = emptySet()
                    })
                    DropdownMenuItem(text = { Text("Share") }, onClick = {
                        menu = false
                        shareItems(ctx, items)
                        vs.selected = emptySet()
                    })
                    val vids = items.filter { !it.isGif }
                    if (vids.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(if (vs.selected.size > 1) "Save as GIFs" else "Save as GIF") },
                            onClick = { menu = false; onGifs(vids) }
                        )
                    }
                }
            }
        }
    )
}

/* ---------- Videos tab ---------- */

@Composable
fun VideosTab(state: Int, vs: VideosState, onGifs: (List<VideoItem>) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    LaunchedEffect(tick, vs.reload, Prefs.saveUri, state == RecorderService.IDLE) {
        val list = withContext(Dispatchers.IO) { loadVideos(ctx) }
        vs.videos = list
        vs.selected = vs.selected.intersect(list.map { it.uri.toString() }.toSet())
    }
    val selecting = vs.selected.isNotEmpty()
    fun toggle(key: String) {
        vs.selected = if (key in vs.selected) vs.selected - key else vs.selected + key
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = vs.videos
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    "No videos yet", Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium
                )
                else -> {
                    val entries = remember(list, tick) { buildEntries(ctx, list) }
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(entries, key = { it.key }) { e ->
                            when (e) {
                                is ListEntry.Header -> Text(
                                    e.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.animateItem().padding(start = 8.dp, top = 8.dp)
                                )
                                is ListEntry.Item -> {
                                    val v = e.v
                                    VideoRow(
                                        v, selected = e.key in vs.selected, selecting = selecting,
                                        onClick = { if (selecting) toggle(e.key) else openItem(ctx, v) },
                                        onLongClick = { toggle(e.key) },
                                        onGif = { onGifs(listOf(v)) },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Shows all MP4 videos and GIFs in the selected output folder.", style = MaterialTheme.typography.bodyMedium)
                    Text(folderLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (Prefs.saveUri.isEmpty() && Build.VERSION.SDK_INT >= 29) {
                        Text(
                            "GIFs: Main storage: Pictures/ScreenSnap", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRow(
    v: VideoItem, selected: Boolean, selecting: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onGif: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val meta = remember(v) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(v.modified)) +
                " · " + Formatter.formatShortFileSize(ctx, v.size)
    }
    val shape = MaterialTheme.shapes.extraLarge
    Card(
        modifier = modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Thumb(v, Modifier.size(width = 120.dp, height = 68.dp))
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = Color(0xCC000000)
                ) {
                    Text(
                        if (v.isGif) "GIF" else "MP4",
                        Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall, color = Color.White
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selecting) {
                Checkbox(selected, null)
            } else {
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; openItem(ctx, v) })
                        DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; shareItems(ctx, listOf(v)) })
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editItem(ctx, v) })
                        if (!v.isGif) DropdownMenuItem(text = { Text("Save as GIF") }, onClick = { menu = false; onGif() })
                    }
                }
            }
        }
    }
}

@Composable
fun Thumb(v: VideoItem, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, v.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val raw: Bitmap? = if (v.isGif) {
                    ctx.contentResolver.openInputStream(v.uri)?.use { BitmapFactory.decodeStream(it) }
                } else {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(ctx, v.uri)
                    val f = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    r.release()
                    f
                }
                raw?.let { Bitmap.createScaledBitmap(it, 320, (320f * it.height / it.width).toInt().coerceAtLeast(1), true) }
            } catch (_: Exception) { null }
        }
    }
    Box(
        modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        Alignment.Center
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(painterResource(R.drawable.ic_play), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ---------- Setup: choose the folder ---------- */

@Composable
fun FolderScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) saveFolder(ctx, uri)
    }
    Scaffold { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Where should videos go?", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Pick the folder ScreenSnap saves your recordings to. The Videos tab shows the MP4 videos and GIFs in this folder.",
                    style = MaterialTheme.typography.bodyLarge
                )
                ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                    ListItem(
                        headlineContent = { Text("Save location") },
                        supportingContent = { Text(folderLabel()) },
                        trailingContent = { FilledTonalButton({ picker.launch(null) }) { Text("Choose") } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
                Text("You can change this any time in Settings.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Button(onFinish, Modifier.fillMaxWidth().height(64.dp)) { Text("Finish") }
        }
    }
}