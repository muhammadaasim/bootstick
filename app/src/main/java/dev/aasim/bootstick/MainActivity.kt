@file:OptIn(ExperimentalMaterial3Api::class)

package dev.aasim.bootstick

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Launcher shortcuts (res/xml/shortcuts.xml). */
const val ACTION_MOUNT_LAST = "dev.aasim.bootstick.MOUNT_LAST"
const val ACTION_EJECT = "dev.aasim.bootstick.EJECT"

class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    /** An intent to act on once the UI is up: a file to open, a link to download, a shortcut. */
    var pending by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pending = intent
        enableEdgeToEdge()
        setContent { BootStickTheme { App(this, resumes) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pending = intent
    }

    // Re-check on every return: root may have just been granted, the computer may have ejected the disk.
    override fun onResume() {
        super.onResume()
        resumes++
    }
}

/** Returns true if already granted; otherwise asks and returns false. */
fun ComponentActivity.askPermission(p: String): Boolean {
    if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true
    requestPermissions(arrayOf(p), 0)
    return false
}

suspend fun <T> io(block: () -> T) = withContext(Dispatchers.IO) { block() }

/** A file for Tools → Check an image: what it is, its real path if root can reach it, and how to read it. */
class Source(val file: PickedFile, val path: String?, val open: () -> InputStream)

/** Everything the screens share: what the engine reported, the image list, and the actions on them. */
class AppState(val activity: ComponentActivity, private val scope: CoroutineScope) {
    private val ctx: Context = activity
    var info by mutableStateOf<Output?>(null)
        private set
    var images by mutableStateOf(emptyList<Image>())
        private set
    // A count, not a flag: overlapping jobs (a mount during an unpack) keep the bar up until the last one ends.
    private var running by mutableIntStateOf(0)
    private var label by mutableStateOf("")
    val busy get() = label.takeIf { running > 0 }
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** A file to open in Tools → Check an image. */
    var check by mutableStateOf<Source?>(null)

    /** A link for Get ISOs → Download from a link, shared from another app; it waits for the user to tap Download. */
    var link by mutableStateOf("")

    /** An image waiting in the mount sheet. */
    var offer by mutableStateOf<Image?>(null)
    val snackbar = SnackbarHostState()
    val rootApp = installedRootApp(ctx)
    private var auto by mutableStateOf(Engine.autoMount(ctx))

    /** Mount the images again when the phone restarts. */
    var autoMount: Boolean
        get() = auto
        set(on) {
            Engine.setAutoMount(ctx, on)
            auto = on
        }

    val rooted get() = info?.hasRoot == true
    val supported get() = rooted && info?.values?.get("backend") in setOf("configfs", "legacy")

    /** One USB drive per LUN; it can only show one image at a time. */
    val multi get() = info?.values?.get("backend") == "configfs"

    /** The drives as the kernel has them now. A path is empty once the computer has ejected that drive. */
    val luns get() = info?.luns.orEmpty()
    val mounted get() = luns.map { it.path }.filter { it.isNotEmpty() }.toSet()

    fun say(@StringRes id: Int, vararg args: Any) {
        val message = ctx.getString(id, *args)
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun work(label: String, block: suspend () -> Unit) {
        scope.launch {
            running++
            this@AppState.label = label
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                running--
            }
        }
    }

    fun refresh(scan: Boolean = true) {
        scope.launch {
            refreshing = scan
            try { reload(scan) } finally { refreshing = false }
        }
    }

    private suspend fun reload(scan: Boolean = true) {
        val i = io { Engine.run(ctx, "info") }
        info = i
        if (!supported) return
        if (Engine.wanted(ctx).isNotEmpty()) {
            // A saved mount the kernel no longer has means the phone restarted. Unless it should mount again by
            // itself, forget it; otherwise make sure the service is up to watch it (and mount it, if it's gone).
            if (i.luns.all { it.path.isEmpty() } && i.values["linked"] != "1" && !Engine.autoMount(ctx)) {
                Engine.forget(ctx)
                MountService.stop(ctx)
            } else {
                MountService.start(ctx)
            }
        }
        if (!scan) return
        val order = listOf("hybrid", "iso", "disk", "blank", "compressed", "other")
        images = io { Engine.scan(ctx) }.sortedWith(compareBy({ order.indexOf(it.kind) }, { it.name.lowercase() }))
    }

    /** Mounts [path] in [mode]: in place of what's mounted, or as another drive alongside it when [add]. */
    fun mount(path: String, mode: String, add: Boolean = false) {
        val now = Engine.wanted(ctx)
        attach(
            when {
                now.any { it.path == path } -> now.map { if (it.path == path) Mount(path, mode) else it }
                add -> now + Mount(path, mode)
                else -> listOf(Mount(path, mode))
            },
        )
    }

    /** Takes one image out and leaves the others mounted. */
    fun remove(path: String) {
        val rest = Engine.wanted(ctx).filter { it.path != path }
        if (rest.isEmpty()) eject() else attach(rest)
    }

    fun mountLast() {
        val last = Engine.last(ctx)
        if (last.isEmpty()) say(R.string.nothing_mounted_before) else attach(last)
    }

    private fun attach(list: List<Mount>) {
        // Asked here, when it's obviously useful: the notification is where you eject from.
        if (Build.VERSION.SDK_INT >= 33) activity.askPermission(Manifest.permission.POST_NOTIFICATIONS)
        work(ctx.getString(R.string.busy_mounting, list.joinToString(", ") { it.path.substringAfterLast('/') })) {
            val r = io { Engine.attach(ctx, list) }
            if (r.ok) {
                MountService.start(ctx)
                if (list.size == 1) say(R.string.ready_one, ctx.getString(modeLabel(list[0].mode)))
                else say(R.string.ready_many, list.size)
            } else {
                error = r.error
            }
            reload(scan = false)
        }
    }

    fun eject() = work(ctx.getString(R.string.busy_ejecting)) {
        val r = io { Engine.detach(ctx) }
        MountService.stop(ctx)
        if (r.ok) say(R.string.ejected) else error = r.error
        reload(scan = false)
    }

    fun download(distro: Distro?, url: String = "") {
        if (Build.VERSION.SDK_INT < 30 && !activity.askPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            error = ctx.getString(R.string.err_storage_permission)
            return
        }
        work(distro?.let { ctx.getString(R.string.busy_finding, it.name) } ?: ctx.getString(R.string.busy_finding_file)) {
            val r = distro?.let {
                try {
                    io { resolve(it) }
                } catch (e: Exception) {
                    throw Exception(ctx.getString(R.string.err_find_latest, it.name, e.message), e)
                }
            } ?: Resolved(url, url.substringBefore('?').substringAfterLast('/').ifEmpty { "download.iso" }, null)
            if (Downloader.tasks.any { it.url == r.url && it.status == "done" && it.file.exists() }) {
                say(R.string.already_downloaded, r.name)
                return@work
            }
            if (Build.VERSION.SDK_INT >= 33) activity.askPermission(Manifest.permission.POST_NOTIFICATIONS)
            Downloader.add(r.url, r.name, r.page)
            say(R.string.downloading, r.name)
        }
    }

    /**
     * Opens a picked or shared file: into the mount sheet if root can mount it, otherwise into Check an image
     * (packed images, and phones without root). [goTo] switches tabs.
     */
    fun open(uri: Uri, goTo: (Int) -> Unit) = work(ctx.getString(R.string.busy_reading)) {
        // Opened from another app right at launch, before the first root check has come back.
        if (info == null) reload(scan = false)
        val cr = ctx.contentResolver
        val f = io { inspect(cr, uri) }
        val path = io { realPath(cr, uri) }
        if (supported && f.kind != "compressed") {
            if (path == null) throw Exception(ctx.getString(R.string.err_no_path, f.name))
            offer = Image(path, f.size, f.kind, f.biosOnly)
            goTo(0)
        } else {
            check = Source(f, path) { cr.openInputStream(uri) ?: throw java.io.IOException("Can't open ${f.name}") }
            goTo(2)
        }
    }

    /** Opens an image the scan found in Check an image, reading it through root. */
    fun checkImage(img: Image) {
        check = Source(PickedFile(img.name, img.size, img.kind, img.biosOnly), img.path) { Engine.read(ctx, img.path) }
    }

    fun delete(img: Image) = work(ctx.getString(R.string.busy_deleting, img.name)) {
        val r = io { Engine.run(ctx, "rm", img.path) }
        if (r.ok) say(R.string.deleted, img.name) else error = r.error
        reload()
    }

    fun rename(img: Image, name: String) = work(ctx.getString(R.string.busy_renaming, img.name)) {
        val r = io { Engine.run(ctx, "mv", img.path, name.trim()) }
        if (!r.ok) error = r.error
        reload()
    }

    /** Unpacks a packed image next to itself, through root, since it's usually another app's file. */
    fun unpack(img: Image) = work(ctx.getString(R.string.busy_unpacking, img.name, size(ctx, 0))) {
        val out = img.path.substringBeforeLast('/') + "/" + unpackedName(img.name)
        var shown = 0L
        io {
            Engine.put(ctx, out) { o ->
                Engine.read(ctx, img.path).use { input ->
                    unpack(img.name, input, o) { n ->
                        if (n - shown > 16 * MIB) { shown = n; label = ctx.getString(R.string.busy_unpacking, img.name, size(ctx, n)) }
                    }
                }
            }
        }
        say(R.string.unpacked, out.substringAfterLast('/'))
        reload()
    }

    /** Acts on an intent from outside: a file to open, a shared link, or a launcher shortcut. */
    fun handle(i: Intent, goTo: (Int) -> Unit) {
        when (i.action) {
            Intent.ACTION_VIEW -> i.data?.let { open(it, goTo) }
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { open(it, goTo); return }
                // Filled in rather than started, so another app can't start downloads on its own.
                Regex("https://\\S+").find(i.getStringExtra(Intent.EXTRA_TEXT).orEmpty())?.let {
                    link = it.value
                    goTo(1)
                }
            }
            ACTION_MOUNT_LAST -> mountLast()
            ACTION_EJECT -> eject()
        }
    }
}

@Composable
fun App(activity: MainActivity, resumes: Int) {
    val scope = rememberCoroutineScope()
    val s = remember { Downloader.init(activity); Catalog.init(activity); AppState(activity, scope) }
    val prefs = remember { activity.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var onboarded by remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var about by rememberSaveable { mutableStateOf(false) }
    var bySplash by rememberSaveable { mutableStateOf(true) }
    val usb = rememberUsbState()
    val usbNow by rememberUpdatedState(usb)

    LaunchedEffect(resumes) { s.refresh() }
    // A finished download should appear in the Drive tab without a manual refresh.
    LaunchedEffect(Unit) { snapshotFlow { Downloader.tasks.count { it.status == "done" } }.drop(1).collect { s.refresh() } }
    // Plugging in, unplugging, a computer ejecting the drive: show it without waiting for a manual refresh.
    LaunchedEffect(Unit) {
        snapshotFlow { usbNow to MountService.events }.drop(1).collectLatest { delay(1500); s.refresh(scan = false) }
    }
    LaunchedEffect(activity.pending, onboarded) {
        val i = activity.pending ?: return@LaunchedEffect
        if (!onboarded) return@LaunchedEffect
        activity.pending = null
        s.handle(i) { tab = it; about = false }
    }

    if (bySplash) {
        SplashScreen(onDone = { bySplash = false })
    } else if (!onboarded) {
        Onboarding(s) {
            prefs.edit().putBoolean("onboarded", true).apply()
            onboarded = true
        }
    } else if (about) {
        AboutScreen(s, onBack = { about = false })
    } else {
        val tabs = listOf(R.string.tab_drive, R.string.tab_get, R.string.tab_tools)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { if (tab == 0) Wordmark() else Text(stringResource(tabs[tab])) },
                    actions = { IconButton({ about = true }) { Icon(Icons.Filled.Info, stringResource(R.string.about_title)) } },
                )
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = {
                                when (i) {
                                    0 -> Icon(painterResource(R.drawable.ic_usb), null)
                                    1 -> Icon(painterResource(R.drawable.ic_download), null)
                                    else -> Icon(Icons.Filled.Build, null)
                                }
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(s.snackbar) },
        ) { pad ->
            when (tab) {
                0 -> DriveScreen(s, usb, pad, goTo = { tab = it })
                1 -> GetScreen(s, pad, goTo = { tab = it })
                else -> ToolsScreen(s, pad, replayIntro = { onboarded = false }, openAbout = { about = true })
            }
        }
    }

    s.offer?.let { img ->
        MountSheet(img, Engine.wanted(activity), s.multi, Engine.lastMode(activity, img.path), onDismiss = { s.offer = null }) { mode, add ->
            s.offer = null
            s.mount(img.path, mode, add)
            tab = 0
        }
    }

    s.error?.let {
        AlertDialog(
            onDismissRequest = { s.error = null },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(errorText(it)) },
            confirmButton = { TextButton({ s.error = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

/** Engine errors that are codes rather than sentences. */
@Composable
private fun errorText(e: String) = when (e) {
    "no_su" -> stringResource(R.string.err_no_su)
    else -> e
}

/** Shown for a second at launch, then dismissed automatically (or on tap). */
@Composable
private fun SplashScreen(onDone: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = 1.08f,
        animationSpec = repeatable(iterations = Int.MAX_VALUE, animation = tween(durationMillis = 1400)),
    )
    // Dismiss after a second; tapping skips early. Stopped when this leaves the tree.
    LaunchedEffect(Unit) {
        delay(1000)
        onDone()
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable { onDone() },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            AppIcon(Modifier.size(128.dp).graphicsLayer { scaleX = scale; scaleY = scale })
            Spacer(Modifier.height(28.dp))
            Wordmark(MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The launcher icon itself (ink tile, teal phone, amber arrow), for the splash and About. */
@Composable
fun AppIcon(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28)
    // The hairline keeps the ink tile visible on the dark background.
    Box(modifier.clip(shape).background(Color(0xFF0B1220)).border(1.dp, Color(0x3300BFA6), shape)) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
        // The foreground is drawn for a 108dp canvas whose outer third is masked by launchers; zoom past it.
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
    }
}

/** "Boot" in the text color, "Stick" in brand teal. The brand name isn't translated. */
@Composable
fun Wordmark(style: TextStyle = MaterialTheme.typography.titleLarge) {
    Text(
        buildAnnotatedString {
            append("Boot")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Stick") }
        },
        style = style.copy(fontWeight = FontWeight.Bold),
    )
}
