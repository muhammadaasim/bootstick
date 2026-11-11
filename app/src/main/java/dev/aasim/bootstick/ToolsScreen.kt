@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.aasim.bootstick

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ToolsScreen(s: AppState, pad: PaddingValues, replayIntro: () -> Unit, openAbout: () -> Unit) {
    var diag by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.padding(pad).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        s.busy?.let { item { Busy(it) } }
        item { CheckCard(s) }
        item { BlankCard(s) }
        item { BootGuide() }
        item {
            ToolLink(
                stringResource(R.string.diagnostics),
                stringResource(if (s.rooted) R.string.diagnostics_hint else R.string.needs_root),
                enabled = s.rooted,
            ) { diag = true }
        }
        item { ToolLink(stringResource(R.string.intro_again), stringResource(R.string.intro_again_hint)) { replayIntro() } }
        item { ToolLink(stringResource(R.string.about_title), stringResource(R.string.about_hint)) { openAbout() } }
    }
    if (diag) Diagnostics { diag = false }
}

@Composable
private fun ToolCard(icon: Painter, title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(icon, c.secondaryContainer, c.onSecondaryContainer, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

/**
 * Identify an image, suggest a mode, verify its checksum, and unpack it if it's packed. Reads through the file picker
 * (or through root, for images from the Drive tab), so it works without root.
 */
@Composable
private fun CheckCard(s: AppState) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val cr = ctx.contentResolver
    val scope = rememberCoroutineScope()
    var src by remember { mutableStateOf<Source?>(null) }
    var hash by remember { mutableStateOf<Pair<String, String>?>(null) } // algorithm to hex
    var progress by remember { mutableLongStateOf(-1L) } // bytes hashed or unpacked so far, while working
    var job by remember { mutableStateOf<Job?>(null) }
    var expected by rememberSaveable { mutableStateOf("") }
    var doing by remember { mutableIntStateOf(R.string.computing_n) } // what progress counts

    fun load(new: Source) {
        job?.cancel()
        src = new; hash = null; progress = -1L; expected = ""
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { io { inspect(cr, uri) to realPath(cr, uri) } }
                .onSuccess { (f, path) -> load(Source(f, path) { cr.openInputStream(uri) ?: throw java.io.IOException(f.name) }) }
                .onFailure { s.error = res.getString(R.string.err_read, "", it.message) }
        }
    }
    LaunchedEffect(s.check) { s.check?.let { load(it); s.check = null } }

    // Without root, a packed image is unpacked into a file the user picks.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { out ->
        val f = src ?: return@rememberLauncherForActivityResult
        out ?: return@rememberLauncherForActivityResult
        doing = R.string.unpacking_n
        job = scope.launch {
            progress = 0L
            try {
                withContext(Dispatchers.IO) {
                    cr.openOutputStream(out)!!.use { o -> f.open().use { input -> unpack(f.file.name, input, o) { progress = it; ensureActive() } } }
                }
                s.say(R.string.unpacked, unpackedName(f.file.name))
                s.refresh()
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(cr, out) }
                if (e is CancellationException) throw e
                s.error = res.getString(R.string.err_unpack, e.message)
            } finally {
                progress = -1L
            }
        }
    }

    ToolCard(rememberVectorPainter(Icons.Filled.Search), stringResource(R.string.check_title), stringResource(R.string.check_hint)) {
        OutlinedButton({ pick.launch(arrayOf("*/*")) }) { Text(stringResource(if (src == null) R.string.choose_file else R.string.choose_another)) }
        val source = src ?: return@ToolCard
        val f = source.file
        // Root can mount or unpack it in place when it's a real file; otherwise unpacking goes through a picked file.
        val path = source.path?.takeIf { s.supported }
        HorizontalDivider()
        Text(f.name, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(kindLabel(f.kind)))
            if (f.size > 0) Pill(size(ctx, f.size))
        }
        Text(stringResource(kindHint(f.kind)))
        if (f.biosOnly) Text(stringResource(R.string.bios_only_hint), color = MaterialTheme.colorScheme.error)

        val working = progress >= 0
        if (f.kind == "compressed") {
            if (!working) Button({
                if (path != null) s.unpack(Image(path, f.size, f.kind, false)) else save.launch(unpackedName(f.name))
            }) { Text(stringResource(R.string.unpack)) }
        } else {
            Text(stringResource(R.string.mount_it_as, stringResource(modeLabel(suggestedFor(f.name, f.kind)))), fontWeight = FontWeight.SemiBold)
            if (path != null) Button({ s.offer = Image(path, f.size, f.kind, f.biosOnly) }) { Text(stringResource(R.string.mount)) }
        }

        OutlinedTextField(
            expected, { expected = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.paste_checksum)) },
            supportingText = { Text(stringResource(R.string.paste_checksum_hint)) },
            singleLine = true,
        )
        // Accepts a bare hash, a `sha256sum` line ("<hash>  file.iso") or a BSD one ("SHA256 (file.iso) = <hash>").
        val want = hashesIn(expected).firstOrNull()
        val algo = want?.let(::algoFor) ?: "SHA-256"
        when {
            working -> {
                LinearProgressIndicator({ if (f.size > 0) progress.toFloat() / f.size else 0f }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(doing, size(ctx, progress)),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton({ job?.cancel(); progress = -1L }) { Text(stringResource(R.string.stop)) }
                }
            }
            hash?.first != algo -> OutlinedButton({
                doing = R.string.computing_n
                job = scope.launch {
                    progress = 0L
                    try {
                        val hex = withContext(Dispatchers.IO) { source.open().use { input -> digest(input, algo) { progress = it; ensureActive() } } }
                        hash = algo to hex
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        s.error = res.getString(R.string.err_read, f.name, e.message)
                    } finally {
                        progress = -1L
                    }
                }
            }) { Text(stringResource(R.string.compute, algo)) }
            else -> {
                val (a, h) = hash!!
                Text(a, style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(h, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                TextButton({
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(a, h))
                }) { Text(stringResource(R.string.copy)) }
                if (want != null) {
                    val ok = want == h
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Text(stringResource(if (ok) R.string.checksum_match else R.string.checksum_mismatch))
                    }
                }
            }
        }
    }
}

private val units = listOf(R.string.unit_bytes to 1L, R.string.unit_mb to MB, R.string.unit_gb to GB)

/** Create a zero-filled (or FAT32-formatted) image wherever the user picks. No root needed; the root scan finds it afterwards. */
@Composable
private fun BlankCard(s: AppState) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    var name by rememberSaveable { mutableStateOf("blank.img") }
    var amount by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableLongStateOf(GB) }
    var fat32 by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }
    val presets = listOf(256 * MB, GB, 4 * GB, 16 * GB)
    val bytes = parseSize(amount, unit)
    val formattable = bytes != null && bytes >= MIN_FAT32

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val b = bytes ?: return@rememberLauncherForActivityResult
        val label = if (fat32 && formattable) name.substringBeforeLast('.') else null
        s.work(res.getString(R.string.busy_creating, size(ctx, b))) {
            progress = 0f
            try {
                io { createBlank(ctx.contentResolver, uri, b, label) { progress = it.toFloat() / b } }
                s.say(if (label != null) R.string.created_fat32 else R.string.created)
                s.refresh()
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
                val full = (e as? ErrnoException)?.errno == OsConstants.ENOSPC || e.message.orEmpty().contains("ENOSPC")
                throw if (full) Exception(res.getString(R.string.err_no_space_image, size(ctx, b))) else e
            } finally {
                progress = -1f
            }
        }
    }

    ToolCard(rememberVectorPainter(Icons.Filled.Add), stringResource(R.string.blank_title), stringResource(R.string.blank_hint)) {
        OutlinedTextField(name, { name = it.replace("/", "") }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.file_name)) }, singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { v ->
                FilterChip(bytes == v, {
                    if (v >= GB) { amount = (v / GB).toString(); unit = GB } else { amount = (v / MB).toString(); unit = MB }
                }, { Text(size(ctx, v)) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                amount, { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(16) },
                Modifier.weight(1f), label = { Text(stringResource(R.string.size)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = bytes == null,
            )
            units.forEach { (label, v) -> FilterChip(unit == v, { unit = v }, { Text(stringResource(label)) }) }
        }
        Text(
            when {
                bytes == null -> stringResource(R.string.size_invalid, size(ctx, MAX_IMAGE))
                // Bytes the user typed that don't fill a whole sector are rounded up, so say what they'll get.
                unit == 1L && bytes.toBigDecimal() != amount.replace(',', '.').toBigDecimalOrNull() ->
                    stringResource(R.string.size_rounded, "%,d".format(bytes))
                else -> stringResource(R.string.size_result, size(ctx, bytes), "%,d".format(bytes))
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (bytes == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.format_fat32), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(if (formattable || bytes == null) R.string.format_fat32_hint else R.string.format_fat32_small, size(ctx, MIN_FAT32)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(fat32 && formattable, { fat32 = it }, enabled = formattable)
        }
        if (progress >= 0) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
        Button({ create.launch(if ('.' in name) name else "$name.img") }, enabled = bytes != null && name.isNotBlank() && progress < 0) {
            Text(stringResource(R.string.choose_where))
        }
    }
}

@Composable
private fun BootGuide() {
    var open by rememberSaveable { mutableStateOf(false) }
    ToolCard(painterResource(R.drawable.ic_bootstick), stringResource(R.string.boot_title), stringResource(R.string.boot_hint)) {
        if (open) {
            Text(stringResource(R.string.boot_step1))
            Text(stringResource(R.string.boot_step2))
            listOf(
                "Dell, Lenovo, Acer, Toshiba, Gigabyte" to "F12",
                "HP" to stringResource(R.string.key_hp),
                "ASUS" to stringResource(R.string.key_asus),
                "MSI" to "F11",
                "Intel Mac" to stringResource(R.string.key_mac),
            ).forEach { (brand, key) ->
                Row(Modifier.padding(start = 16.dp)) {
                    Text(brand, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(key, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(stringResource(R.string.boot_step3))
            Text(stringResource(R.string.boot_trouble), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.boot_tip_windows))
            Text(stringResource(R.string.boot_tip_secure))
            Text(stringResource(R.string.boot_tip_cable))
            Text(stringResource(R.string.boot_tip_mac))
        }
        TextButton({ open = !open }) { Text(stringResource(if (open) R.string.show_less else R.string.show_steps)) }
    }
}

@Composable
private fun ToolLink(title: String, body: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Card(onClick, Modifier.fillMaxWidth(), enabled = enabled, colors = CardDefaults.cardColors(containerColor = c.surfaceContainerLow)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(rememberVectorPainter(Icons.Filled.Info), c.secondaryContainer, c.onSecondaryContainer, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun Diagnostics(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        report = "BootStick $version ($APP_PAGE)\n" + io { Engine.run(ctx, "diag").out }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diagnostics)) },
        text = {
            Text(
                report.ifEmpty { stringResource(R.string.collecting) },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            val title = stringResource(R.string.share_report)
            TextButton({
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), title))
            }) { Text(stringResource(R.string.share)) }
        },
        dismissButton = {
            TextButton({
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("BootStick report", report))
            }) { Text(stringResource(R.string.copy)) }
        },
    )
}
