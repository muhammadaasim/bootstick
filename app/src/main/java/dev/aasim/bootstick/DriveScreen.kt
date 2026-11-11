@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.aasim.bootstick

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun DriveScreen(s: AppState, usb: Usb, pad: PaddingValues, goTo: (Int) -> Unit) {
    val ctx = LocalContext.current
    var showOther by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Image?>(null) }
    var deleting by remember { mutableStateOf<Image?>(null) }
    var unpacking by remember { mutableStateOf<Image?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { s.open(it, goTo) } }
    val free = remember(s.images) { StatFs((Downloader.sdDir()?.takeIf { Downloader.toSd } ?: Environment.getDataDirectory()).path).availableBytes }

    fun tap(img: Image) {
        if (img.kind == "compressed") unpacking = img else s.offer = img
    }
    fun act(img: Image, action: String) {
        when (action) {
            "check" -> { s.checkImage(img); goTo(2) }
            "unpack" -> unpacking = img
            "rename" -> renaming = img
            else -> deleting = img
        }
    }

    PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = s::refresh, modifier = Modifier.padding(pad).fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            s.busy?.let { item { Busy(it) } }
            item { Hero(s, usb, goTo) }
            if (s.supported) {
                // Firmware dumps (boot.img and similar) are rarely what you want to boot, so they start hidden.
                val (main, other) = s.images.partition { it.kind != "other" }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            SectionTitle(
                                stringResource(R.string.your_images),
                                listOfNotNull(
                                    stringResource(R.string.tap_to_mount).takeIf { main.isNotEmpty() },
                                    stringResource(R.string.free_space, size(ctx, free)),
                                ).joinToString(" · "),
                            )
                        }
                        TextButton({ pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.mount_a_file)) }
                    }
                }
                if (main.isEmpty() && !s.refreshing) item { EmptyLibrary(goTo) }
                items(main, key = { it.path }) { ImageCard(it, it.path in s.mounted, { tap(it) }) { a -> act(it, a) } }
                if (other.isNotEmpty()) {
                    item {
                        TextButton({ showOther = !showOther }) {
                            Text(if (showOther) stringResource(R.string.hide_other) else pluralStringResource(R.plurals.show_other, other.size, other.size))
                        }
                    }
                    if (showOther) items(other, key = { it.path }) { ImageCard(it, it.path in s.mounted, { tap(it) }) { a -> act(it, a) } }
                }
            }
        }
    }

    renaming?.let { img ->
        var name by remember(img) { mutableStateOf(img.name) }
        val ok = name.isNotBlank() && '/' !in name && isImageName(name) && name != img.name
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                    supportingText = { Text(stringResource(R.string.rename_hint)) },
                )
            },
            confirmButton = { TextButton({ renaming = null; s.rename(img, name) }, enabled = ok) { Text(stringResource(R.string.rename)) } },
            dismissButton = { TextButton({ renaming = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    deleting?.let { img ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_title, img.name)) },
            text = { Text(stringResource(R.string.delete_body, size(ctx, img.size))) },
            confirmButton = { TextButton({ deleting = null; s.delete(img) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    unpacking?.let { img ->
        AlertDialog(
            onDismissRequest = { unpacking = null },
            title = { Text(stringResource(R.string.unpack_title, img.name)) },
            text = { Text(stringResource(R.string.unpack_body, unpackedName(img.name))) },
            confirmButton = { TextButton({ unpacking = null; s.unpack(img) }) { Text(stringResource(R.string.unpack)) } },
            dismissButton = { TextButton({ unpacking = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
fun Busy(label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The big status card at the top: what's mounted, or what's stopping BootStick from mounting. */
@Composable
private fun Hero(s: AppState, usb: Usb, goTo: (Int) -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val i = s.info
    val wanted = Engine.wanted(ctx)
    when {
        i == null -> HeroCard(c.surfaceContainerHigh, c.onSurface, painterResource(R.drawable.ic_usb), stringResource(R.string.checking_phone)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        !i.hasRoot -> HeroCard(
            c.secondaryContainer, c.onSecondaryContainer, rememberVectorPainter(Icons.Filled.Lock), stringResource(R.string.root_needed),
            stringResource(s.rootApp?.steps ?: R.string.root_steps_generic),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.rootApp?.let { app ->
                    Button({ ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.let(ctx::startActivity) }) { Text(stringResource(R.string.open_app, app.name)) }
                }
                OutlinedButton(s::refresh) { Text(stringResource(R.string.check_again)) }
            }
            WithoutRoot(goTo)
        }

        !s.supported -> HeroCard(
            c.errorContainer, c.onErrorContainer, rememberVectorPainter(Icons.Filled.Warning),
            stringResource(R.string.cant_act_as_drive), reasonText(i.values["reason"]),
        ) {
            OutlinedButton({ goTo(2) }) { Text(stringResource(R.string.open_diagnostics)) }
            WithoutRoot(goTo)
        }

        s.mounted.isNotEmpty() -> {
            val luns = s.luns
            HeroCard(
                c.primaryContainer, c.onPrimaryContainer, painterResource(R.drawable.ic_bootstick),
                if (luns.size == 1) luns[0].path.substringAfterLast('/') else pluralStringResource(R.plurals.drives, luns.size, luns.size),
                overline = stringResource(R.string.mounted), live = true,
            ) {
                luns.forEachIndexed { n, l ->
                    // The kernel forgets the path of a drive the computer ejected; the saved list still has it.
                    val path = l.path.ifEmpty { wanted.getOrNull(n)?.path.orEmpty() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (luns.size > 1) Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill(stringResource(modeLabel(l.mode)), c.primary, c.onPrimary)
                                if (l.path.isEmpty()) Pill(stringResource(R.string.ejected_by_computer))
                            }
                        }
                        if (luns.size > 1 && path.isNotEmpty()) TextButton({ s.remove(path) }) { Text(stringResource(R.string.remove)) }
                    }
                }
                Pill(stringResource(usb.label))
                Text(stringResource(if (usb == Usb.Computer) R.string.computer_sees_it else R.string.plug_in))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(s::eject) { Text(stringResource(if (luns.size > 1) R.string.eject_all else R.string.eject)) }
                    TextButton({ goTo(2) }) { Text(stringResource(R.string.how_to_boot)) }
                }
                AutoMountSwitch(s)
            }
        }

        wanted.isNotEmpty() -> HeroCard(
            c.tertiaryContainer, c.onTertiaryContainer, painterResource(R.drawable.ic_bootstick),
            wanted.joinToString(", ") { it.path.substringAfterLast('/') }, overline = stringResource(R.string.ejected_by_computer),
            body = stringResource(R.string.goes_back_in),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(s::mountLast) { Text(stringResource(R.string.reinsert_now)) }
                OutlinedButton(s::eject) { Text(stringResource(R.string.eject)) }
            }
            AutoMountSwitch(s)
        }

        else -> HeroCard(
            c.surfaceContainerHigh, c.onSurface, painterResource(R.drawable.ic_usb), stringResource(R.string.ready),
            body = stringResource(R.string.ready_body),
        ) { Pill(stringResource(usb.label)) }
    }
}

@Composable
private fun AutoMountSwitch(s: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.auto_mount), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.auto_mount_hint), style = MaterialTheme.typography.bodySmall)
        }
        Switch(s.autoMount, { s.autoMount = it })
    }
}

@Composable
private fun HeroCard(
    container: Color,
    content: Color,
    icon: Painter,
    title: String,
    body: String? = null,
    overline: String? = null,
    live: Boolean = false,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    // A live drive glows: the card fades from its color into the surface, and the overline gets a pulsing dot.
    val glow = if (live) Brush.verticalGradient(listOf(container, MaterialTheme.colorScheme.surfaceContainer)) else SolidColor(container)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = content)) {
        Column(Modifier.background(glow).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(icon, content.copy(alpha = 0.12f), content, 56.dp)
                Column(Modifier.weight(1f)) {
                    overline?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (live) LiveDot()
                            Text(it, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            body?.let { Text(it) }
            actions()
        }
    }
}

/** An image in the list: tap to mount (or unpack), and a menu for the rest. [onAction] gets check, unpack, rename or delete. */
@Composable
private fun ImageCard(img: Image, mounted: Boolean, onClick: () -> Unit, onAction: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (mounted) c.primaryContainer else c.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(kindIcon(img.kind), c.secondaryContainer, c.onSecondaryContainer, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(img.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(stringResource(kindLabel(img.kind)), stringResource(R.string.bios_only).takeIf { img.biosOnly }, size(ctx, img.size)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                )
                Text(where(ctx, img.path), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (mounted) Pill(stringResource(R.string.mounted), c.primary, c.onPrimary)
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options, img.name), tint = c.onSurfaceVariant) }
                DropdownMenu(menu, { menu = false }) {
                    val items = listOf(
                        (if (img.kind == "compressed") "unpack" else "check") to (if (img.kind == "compressed") R.string.unpack else R.string.check_title),
                        "rename" to R.string.rename,
                        "delete" to R.string.delete,
                    )
                    items.forEach { (a, label) ->
                        DropdownMenuItem(
                            { Text(stringResource(label)) },
                            { menu = false; onAction(a) },
                            // A mounted file is open in the kernel; moving or deleting it would pull the drive out from under the computer.
                            enabled = !(mounted && (a == "rename" || a == "delete")),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(goTo: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.surfaceContainerLow)) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(painterResource(R.drawable.ic_bootstick), c.secondaryContainer, c.onSecondaryContainer, 64.dp)
            Text(stringResource(R.string.no_images), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.no_images_body), textAlign = TextAlign.Center, color = c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ goTo(1) }) { Text(stringResource(R.string.tab_get)) }
                OutlinedButton({ goTo(2) }) { Text(stringResource(R.string.without_root_blank)) }
            }
        }
    }
}

/** A small teal dot that breathes, to say "this is happening now". */
@Composable
private fun LiveDot() {
    val alpha by rememberInfiniteTransition().animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    )
    Box(Modifier.size(10.dp).graphicsLayer { this.alpha = alpha }.background(MaterialTheme.colorScheme.primary, CircleShape))
}
