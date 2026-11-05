package com.example.shoplist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import java.io.File

data class Item(
    val id: Long, val name: String, val desc: String = "", val images: List<String> = emptyList(),
    val qty: String = "1", val active: Boolean = true, val created: Long, val activity: Long
)

val SORTS = listOf("Alphabetical", "Last activity", "Creation time")

class Repo(val ctx: Context) {
    val items = mutableStateListOf<Item>()
    private val prefs = ctx.getSharedPreferences("p", 0)
    var sortA by mutableIntStateOf(prefs.getInt("sa", 2)); private set
    var sortH by mutableIntStateOf(prefs.getInt("sh", 2)); private set
    var ascA by mutableStateOf(prefs.getBoolean("aa", false)); private set
    var ascH by mutableStateOf(prefs.getBoolean("ah", false)); private set
    private val db = File(ctx.filesDir, "db.json")

    init { if (db.exists()) runCatching { items.addAll(parse(JSONArray(db.readText()), false)) } }

    fun setSort(actual: Boolean, v: Int) {
        if (actual) sortA = v else sortH = v
        prefs.edit().putInt(if (actual) "sa" else "sh", v).apply()
    }
    fun setAsc(actual: Boolean, v: Boolean) {
        if (actual) ascA = v else ascH = v
        prefs.edit().putBoolean(if (actual) "aa" else "ah", v).apply()
    }
    fun delete(id: Long) {
        items.firstOrNull { it.id == id }?.let { i ->
            i.images.forEach { file(it).delete() }
            items.remove(i); save()
        }
    }
    fun file(n: String) = File(ctx.filesDir, n)

    private fun json(withImg: Boolean) = JSONArray().also { a -> items.forEach { i ->
        a.put(JSONObject().apply {
            put("id", i.id); put("name", i.name); put("desc", i.desc); put("qty", i.qty)
            put("active", i.active); put("created", i.created); put("activity", i.activity)
            put(if (withImg) "images64" else "images", JSONArray().also { arr -> i.images.forEach { n ->
                arr.put(if (withImg) Base64.encodeToString(file(n).readBytes(), Base64.NO_WRAP) else n) } })
        })
    } }

    private fun parse(a: JSONArray, imp: Boolean) = (0 until a.length()).map { k ->
        val o = a.getJSONObject(k)
        val imgs = mutableListOf<String>()
        fun arr(key: String) = o.optJSONArray(key)?.let { a2 -> (0 until a2.length()).map { a2.getString(it) } } ?: emptyList()
        if (imp) {
            val b64 = arr("images64") + listOfNotNull(o.optString("img64", "").ifEmpty { null })
            b64.forEach { b ->
                val n = "img_${System.nanoTime()}.jpg"
                file(n).writeBytes(Base64.decode(b, Base64.DEFAULT)); imgs.add(n)
            }
        } else {
            imgs.addAll(arr("images")); o.optString("image", "").ifEmpty { null }?.let { imgs.add(it) }
        }
        Item(o.getLong("id"), o.getString("name"), o.optString("desc"), imgs, o.optString("qty", "1"),
            o.optBoolean("active", true), o.getLong("created"), o.getLong("activity"))
    }

    private fun save() = db.writeText(json(false).toString())
    fun exportDb() = json(true).toString()
    fun importDb(text: String) {
        val a = JSONArray(text)
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("img_") }?.forEach { it.delete() }
        val list = parse(a, true)
        items.clear(); items.addAll(list); save()
    }

    fun add(name: String) {
        val now = System.currentTimeMillis(); var id = now
        while (items.any { it.id == id }) id++
        items.add(Item(id, name, created = now, activity = now)); save()
    }
    fun update(id: Long, f: (Item) -> Item) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) { items[i] = f(items[i]); save() }
    }
    fun toggle(id: Long) = update(id) { it.copy(active = !it.active, activity = System.currentTimeMillis()) }

    fun importImage(u: Uri): String? = runCatching {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, o) }
        val s = maxOf(1, maxOf(o.outWidth, o.outHeight) / 1024)
        var b = cr.openInputStream(u)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        }!!
        val rot = cr.openInputStream(u)!!.use { ExifInterface(it).rotationDegrees }
        if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        val n = "img_${System.nanoTime()}.jpg"
        file(n).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        n
    }.getOrNull()
}

fun sorted(l: List<Item>, m: Int, asc: Boolean): List<Item> {
    val r = when (m) {
        0 -> l.sortedBy { it.name.lowercase() }
        1 -> l.sortedBy { it.activity }
        else -> l.sortedBy { it.created }
    }
    return if (asc) r else r.reversed()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = Repo(applicationContext)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App(repo) } } }
    }
}

@Composable
fun App(r: Repo) {
    var tab by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<Long?>(null) }
    var edit by remember { mutableStateOf<Long?>(null) }
    var full by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    BackHandler(full != null || view != null || edit != null) {
        if (full != null) full = null else if (edit != null) edit = null else view = null
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        when {
            edit != null -> EditCard(r, edit!!) { edit = null }
            view != null -> ViewCard(r, view!!, { view = null }, { edit = view }, { full = view!! to it })
            else -> {
                Row(Modifier.fillMaxWidth().height(44.dp)) {
                    listOf("Actual", "History").forEachIndexed { i, t ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable { tab = i }
                            .background(if (tab == i) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                            Alignment.Center) { Text(t, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) }
                    }
                    Box(Modifier.width(52.dp).fillMaxHeight().clickable { tab = 2 }
                        .background(if (tab == 2) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                        Alignment.Center) { Icon(Icons.Default.Settings, "Settings") }
                }
                HorizontalDivider()
                if (tab == 2) Settings(r) else ListScreen(r, tab == 0) { view = it }
            }
        }
    }
    full?.let { (fid, idx) ->
        val fname = r.items.firstOrNull { it.id == fid }?.images?.getOrNull(idx)
        if (fname != null) Box(Modifier.fillMaxSize().background(Color.Black)) { ZoomImage(r, fname) { full = null } }
    }
    }
}

@Composable
fun Pic(r: Repo, name: String?, mod: Modifier, sample: Int = 1, crop: ContentScale = ContentScale.Crop) {
    val bmp = remember(name) {
        name?.let { BitmapFactory.decodeFile(r.file(it).path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }
    }
    if (bmp != null) Image(bmp, null, mod, contentScale = crop)
    else Box(mod.background(Color(0x22888888)))
}

@Composable
fun ListScreen(r: Repo, active: Boolean, open: (Long) -> Unit) {
    var q by remember(active) { mutableStateOf("") }
    fun create() { if (q.isNotBlank()) { r.add(q.trim()); q = "" } }
    OutlinedTextField(
        q, { q = it }, Modifier.fillMaxWidth().padding(8.dp), singleLine = true,
        placeholder = { Text(if (active) "New" else "Search") },
        leadingIcon = if (active) null else ({ Icon(Icons.Default.Search, null) }),
        trailingIcon = { if (active) IconButton({ create() }) { Icon(Icons.Default.Add, "Add") } },
        keyboardOptions = KeyboardOptions(imeAction = if (active) ImeAction.Done else ImeAction.Search),
        keyboardActions = KeyboardActions(onDone = { create() })
    )
    var del by remember { mutableStateOf<Item?>(null) }
    val list = sorted(
        r.items.filter { it.active == active && (active || it.name.contains(q, true)) },
        if (active) r.sortA else r.sortH, if (active) r.ascA else r.ascH
    )
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { i ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton({ r.toggle(i.id) }) { Icon(if (active) Icons.Default.Check else Icons.Default.Add, if (active) "Done" else "Restore") }
                Text(i.name, Modifier.weight(1f).clickable { open(i.id) }.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
                Pic(r, i.images.firstOrNull(), Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)), sample = 4)
                Spacer(Modifier.width(8.dp))
                if (!active) {
                    IconButton({ del = i }, Modifier.width(56.dp)) { Icon(Icons.Default.Delete, "Delete") }
                } else {
                    BasicTextField(
                        i.qty, { v -> r.update(i.id) { it.copy(qty = v) } },
                        Modifier.width(56.dp).border(1.dp, Color.Gray, RoundedCornerShape(6.dp)).padding(8.dp),
                        singleLine = true, textStyle = TextStyle(textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
    del?.let { d ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("Delete permanently?") },
            text = { Text("\"${d.name}\" will be removed completely, including its picture.") },
            confirmButton = { TextButton({ r.delete(d.id); del = null }) { Text("Delete") } },
            dismissButton = { TextButton({ del = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun Arrow(icon: ImageVector, mod: Modifier, click: () -> Unit) {
    IconButton(click, mod.padding(4.dp).background(Color(0x66000000), CircleShape)) { Icon(icon, null, tint = Color.White) }
}

@Composable
fun ViewCard(r: Repo, id: Long, back: () -> Unit, edit: () -> Unit, onFull: (Int) -> Unit) {
    val item = r.items.firstOrNull { it.id == id } ?: return back()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            val n = item.images.size
            if (n > 0) {
                val pager = rememberPagerState { item.images.size }
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    HorizontalPager(pager, Modifier.fillMaxSize()) { p ->
                        Pic(r, item.images[p], Modifier.fillMaxSize().clickable { onFull(p) }, crop = ContentScale.Fit)
                    }
                    if (n > 1) {
                        val pg = pager.currentPage
                        if (pg > 0) Arrow(Icons.Default.ArrowBack, Modifier.align(Alignment.CenterStart)) {
                            scope.launch { pager.animateScrollToPage(pg - 1) }
                        }
                        if (pg < n - 1) Arrow(Icons.Default.ArrowForward, Modifier.align(Alignment.CenterEnd)) {
                            scope.launch { pager.animateScrollToPage(pg + 1) }
                        }
                        Text("${pg + 1}/$n", Modifier.align(Alignment.BottomCenter).padding(4.dp)
                            .background(Color(0x66000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(item.name, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(item.desc, style = MaterialTheme.typography.bodyLarge)
        }
        Button(edit, Modifier.fillMaxWidth().padding(16.dp)) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Edit") }
    }
}

@Composable
fun ZoomImage(r: Repo, name: String, close: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1) / 2; val my = size.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }
        .pointerInput(Unit) {
            detectTapGestures(onTap = { close() }, onDoubleTap = {
                if (scale > 1f) { scale = 1f; off = Offset.Zero } else scale = 2.5f
            })
        }
        .pointerInput(Unit) {
            detectTransformGestures { c, pan, zoom, _ ->
                val ns = (scale * zoom).coerceIn(1f, 6f)
                val cc = c - Offset(size.width / 2f, size.height / 2f)
                off = clamp((off - cc) * (ns / scale) + cc + pan, ns)
                scale = ns
            }
        }) {
        Pic(r, name, Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y
        }, crop = ContentScale.Fit)
    }
}

@Composable
fun EditCard(r: Repo, id: Long, done: () -> Unit) {
    val ctx = LocalContext.current
    val item = r.items.firstOrNull { it.id == id } ?: return done()
    val orig = remember { item.images }
    var name by remember { mutableStateOf(item.name) }
    var desc by remember { mutableStateOf(item.desc) }
    val images = remember { mutableStateListOf<String>().apply { addAll(item.images) } }
    var saved by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (!saved) images.filter { it !in orig }.forEach { r.file(it).delete() } } }
    var camUri by remember { mutableStateOf<Uri?>(null) }
    val gallery = rememberLauncherForActivityResult(GetMultipleContents()) { us -> us.forEach { u -> r.importImage(u)?.let { images.add(it) } } }
    val cam = rememberLauncherForActivityResult(TakePicture()) { ok -> if (ok) camUri?.let { r.importImage(it)?.let { n -> images.add(n) } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (images.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            images.toList().forEach { n ->
                Box {
                    Pic(r, n, Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)), sample = 2)
                    IconButton({ images.remove(n) }, Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).background(Color(0x99000000), CircleShape)) {
                        Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                val f = File(ctx.cacheDir, "cam.jpg")
                camUri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fp", f); cam.launch(camUri!!)
            }) { Text("Camera") }
            OutlinedButton({ gallery.launch("image/*") }) { Text("Add from files") }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("Description") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(done, Modifier.weight(1f)) { Text("Cancel") }
            Button({
                saved = true
                orig.filter { it !in images }.forEach { r.file(it).delete() }
                r.update(id) { it.copy(name = name.ifBlank { it.name }, desc = desc, images = images.toList()) }
                done()
            }, Modifier.weight(1f)) { Text("Save") }
        }
    }
}

@Composable
fun Settings(r: Repo) {
    val ctx = LocalContext.current
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    val exp = rememberLauncherForActivityResult(CreateDocument("application/json")) { u ->
        if (u != null) toast(if (runCatching {
                ctx.contentResolver.openOutputStream(u)!!.use { it.write(r.exportDb().toByteArray()) }
            }.isSuccess) "Exported" else "Export failed")
    }
    val imp = rememberLauncherForActivityResult(OpenDocument()) { u ->
        if (u != null) toast(if (runCatching {
                r.importDb(ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().decodeToString() })
            }.isSuccess) "Imported" else "Import failed")
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        SortPick("Actual sorting", r.sortA, r.ascA, { r.setSort(true, it) }) { r.setAsc(true, it) }
        Spacer(Modifier.height(16.dp))
        SortPick("History sorting", r.sortH, r.ascH, { r.setSort(false, it) }) { r.setAsc(false, it) }
        Spacer(Modifier.height(24.dp))
        Text("Database", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ exp.launch("shoplist.json") }) { Text("Export") }
            Button({ imp.launch(arrayOf("*/*")) }) { Text("Import (replaces data)") }
        }
    }
}

@Composable
fun SortPick(title: String, sel: Int, asc: Boolean, pick: (Int) -> Unit, setAsc: (Boolean) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    SORTS.forEachIndexed { i, n ->
        Row(Modifier.fillMaxWidth().clickable { pick(i) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(sel == i, { pick(i) }); Text(n)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(asc, { setAsc(true) }, { Text("Ascending") })
        FilterChip(!asc, { setAsc(false) }, { Text("Descending") })
    }
}
