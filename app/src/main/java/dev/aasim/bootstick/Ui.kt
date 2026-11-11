@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.aasim.bootstick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

enum class Usb(@StringRes val label: Int) {
    Computer(R.string.usb_computer),
    Power(R.string.usb_power),
    None(R.string.usb_none),
}

/** Live USB cable state, from the sticky USB_STATE broadcast. */
@Composable
fun rememberUsbState(): Usb {
    val ctx = LocalContext.current
    var usb by remember { mutableStateOf(Usb.None) }
    DisposableEffect(Unit) {
        fun read(i: Intent) = when {
            i.getBooleanExtra("configured", false) -> Usb.Computer
            i.getBooleanExtra("connected", false) -> Usb.Power
            else -> Usb.None
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { usb = read(i) }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(USB_STATE), ContextCompat.RECEIVER_EXPORTED)?.let { usb = read(it) }
        onDispose { ctx.unregisterReceiver(r) }
    }
    return usb
}

@Composable
fun kindIcon(kind: String): Painter =
    painterResource(if (kind == "hybrid" || kind == "iso") R.drawable.ic_disc else R.drawable.ic_usb)

fun where(ctx: Context, path: String) = path.substringBeforeLast('/')
    .replace(Regex("^/data/media/0/?"), "")
    .replace(Regex("^/storage/emulated/0/?"), "")
    .replace(Regex("^/mnt/media_rw/([^/]+)/?"), ctx.getString(R.string.sd_card) + "/")
    .ifEmpty { ctx.getString(R.string.internal_storage) }

fun size(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)

@Composable
fun reasonText(reason: String?) = when (reason) {
    "kernel_lacks_mass_storage" -> stringResource(R.string.reason_no_mass_storage)
    "no_configfs_usb_gadget" -> stringResource(R.string.reason_no_configfs)
    else -> stringResource(R.string.reason_other, reason.orEmpty())
}

/** A round tinted icon, used as the visual anchor of cards. */
@Composable
fun IconBadge(icon: Painter, container: Color, content: Color, size: Dp = 48.dp) {
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size / 2)) }
    }
}

@Composable
fun Pill(text: String, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** What still works without root, as shortcuts to the tabs that have it. */
@Composable
fun WithoutRoot(goTo: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.without_root_title), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip({ goTo(1) }, { Text(stringResource(R.string.without_root_download)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp)) })
            AssistChip({ goTo(2) }, { Text(stringResource(R.string.check_title)) })
            AssistChip({ goTo(2) }, { Text(stringResource(R.string.without_root_blank)) })
        }
    }
}

private val modeHints = listOf(
    "ro" to R.string.mode_ro_hint,
    "cdrom" to R.string.mode_cdrom_hint,
    "disk" to R.string.mode_disk_hint,
)

/** One choice in the mount sheet: a card with a radio button. */
@Composable
private fun Option(selected: Boolean, title: String, hint: String, pill: String? = null, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                    pill?.let { Pill(it) }
                }
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Pick how the computer should see an image, then mount it. With something else mounted, it either replaces it or
 * (when [multi]) goes in as another drive. Starts on the mode the image was last mounted in, else the suggested one.
 */
@Composable
fun MountSheet(
    img: Image, mounted: List<Mount>, multi: Boolean, lastMode: String?, onDismiss: () -> Unit, onMount: (mode: String, add: Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val allowed = modesFor(img.name)
    val suggested = suggestedFor(img.name, img.kind)
    val current = mounted.firstOrNull { it.path == img.path }
    val remembered = lastMode?.takeIf { it in allowed }
    var mode by remember { mutableStateOf(current?.mode ?: remembered ?: suggested) }
    val others = mounted.filter { it.path != img.path }
    var add by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(kindIcon(img.kind), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                Column(Modifier.weight(1f)) {
                    Text(img.name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                    Text(
                        listOfNotNull(stringResource(kindLabel(img.kind)), img.size.takeIf { it > 0 }?.let { size(ctx, it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (img.biosOnly) Text(stringResource(R.string.bios_only_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.sheet_how), style = MaterialTheme.typography.titleSmall)
            modeHints.filter { it.first in allowed }.forEach { (m, hint) ->
                Option(
                    mode == m, stringResource(modeLabel(m)), stringResource(hint),
                    when (m) {
                        suggested -> stringResource(R.string.suggested)
                        remembered -> stringResource(R.string.last_used)
                        else -> null
                    },
                ) { mode = m }
            }
            if ("disk" !in allowed) Text(
                stringResource(R.string.sheet_iso_readonly),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current == null && others.isNotEmpty() && multi) {
                val names = others.joinToString(", ") { it.path.substringAfterLast('/') }
                Text(stringResource(R.string.sheet_already, names), style = MaterialTheme.typography.titleSmall)
                Option(!add, stringResource(R.string.sheet_replace), stringResource(R.string.sheet_replace_hint)) { add = false }
                Option(add, stringResource(R.string.sheet_add), stringResource(R.string.sheet_add_hint)) { add = true }
            }
            Text(
                stringResource(R.string.sheet_reconnect),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button({ onMount(mode, add && current == null) }, Modifier.fillMaxWidth().height(52.dp)) {
                Text(
                    stringResource(
                        when {
                            current != null -> R.string.sheet_change_mode
                            add -> R.string.sheet_add_button
                            else -> R.string.mount
                        },
                    ),
                )
            }
        }
    }
}
