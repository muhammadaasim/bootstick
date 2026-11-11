@file:OptIn(ExperimentalMaterial3Api::class)

package dev.aasim.bootstick

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.FileInputStream

@Composable
fun GetScreen(s: AppState, pad: PaddingValues, goTo: (Int) -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val catalog = Catalog.distros
    val shown = catalog.filter { d ->
        (group == null || d.group == group) &&
            (query.isBlank() || d.name.contains(query, true) || d.note.contains(query, true) || d.group.contains(query, true))
    }

    LazyColumn(
        Modifier.padding(pad).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        s.busy?.let { item { Busy(it) } }
        if (Downloader.tasks.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.your_downloads), stringResource(R.string.your_downloads_hint)) }
            items(Downloader.tasks, key = { it.id }) { t ->
                TaskCard(
                    t, canMount = s.supported,
                    onMount = { s.open(Uri.fromFile(t.file), goTo) },
                    onCheck = {
                        scope.launch {
                            runCatching { io { inspect(ctx.contentResolver, Uri.fromFile(t.file)) } }
                                .onSuccess { f -> s.check = Source(f, t.file.path) { FileInputStream(t.file) }; goTo(2) }
                                .onFailure { s.error = res.getString(R.string.err_read, t.name, it.message) }
                        }
                    },
                )
            }
        }
        item {
            SectionTitle(
                stringResource(R.string.catalog_title),
                stringResource(if (Downloader.toSd) R.string.catalog_hint_sd else R.string.catalog_hint, catalog.size),
            )
        }
        item {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_hint)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.clear)) } },
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(group == null, { group = null }, { Text(stringResource(R.string.all)) }) }
                items(Catalog.groups) { g -> FilterChip(group == g, { group = g }, { Text(groupLabel(g)?.let { stringResource(it) } ?: g) }) }
            }
        }
        if (shown.isEmpty()) item { Text(stringResource(R.string.nothing_matches), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown, key = { it.name }) { d -> DistroCard(d) { s.download(d) } }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.from_link), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        s.link, { s.link = it.trim() }, Modifier.fillMaxWidth(),
                        placeholder = { Text("https://…/image.iso") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        // Cleartext traffic is blocked for apps targeting API 28+, so plain http:// would only fail later.
                        supportingText = { Text(stringResource(R.string.must_be_https)) },
                    )
                    Button({ s.download(null, s.link); s.link = "" }, enabled = s.link.startsWith("https://")) { Text(stringResource(R.string.download)) }
                }
            }
        }
        item { DownloadSettings() }
    }
}

@Composable
private fun DownloadSettings() {
    val sd = remember { Downloader.sdDir() }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.download_settings), style = MaterialTheme.typography.titleSmall)
            Setting(stringResource(R.string.wifi_only), stringResource(R.string.wifi_only_hint), Downloader.wifiOnly) { Downloader.wifiOnly = it }
            if (sd != null) Setting(stringResource(R.string.save_to_sd), stringResource(R.string.save_to_sd_hint), Downloader.toSd) { Downloader.toSd = it }
        }
    }
}

@Composable
private fun Setting(title: String, hint: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, set)
    }
}

@Composable
private fun DistroCard(d: Distro, onDownload: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(painterResource(R.drawable.ic_disc), c.tertiaryContainer, c.onTertiaryContainer, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleSmall)
                Text(d.note, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Text(groupLabel(d.group)?.let { stringResource(it) } ?: d.group, style = MaterialTheme.typography.labelSmall, color = c.primary)
            }
            FilledTonalIconButton(onDownload) { Icon(painterResource(R.drawable.ic_download), stringResource(R.string.download_x, d.name)) }
        }
    }
}

@Composable
private fun TaskCard(t: Task, canMount: Boolean, onMount: () -> Unit, onCheck: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val pct = if (t.total > 0) (t.done * 100 / t.total).toInt() else 0
    // Cancelling deletes what's downloaded, which can be gigabytes, so it asks first.
    var confirmCancel by remember { mutableStateOf(false) }
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text(stringResource(R.string.cancel_download_title)) },
        text = { Text(stringResource(R.string.cancel_download_body, t.name, size(ctx, t.done))) },
        confirmButton = { TextButton({ confirmCancel = false; Downloader.cancel(t) }) { Text(stringResource(R.string.cancel_and_delete)) } },
        dismissButton = { TextButton({ confirmCancel = false }) { Text(stringResource(R.string.keep_downloading)) } },
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (t.status == "done") {
                    IconButton({ Downloader.forget(t) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove_from_list)) }
                } else {
                    IconButton({ confirmCancel = true }) { Icon(Icons.Filled.Close, stringResource(R.string.cancel_and_delete)) }
                }
            }
            when (t.status) {
                "done" -> {}
                "finishing" -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> if (t.total > 0) LinearProgressIndicator({ t.done.toFloat() / t.total }, Modifier.fillMaxWidth())
                else if (t.status == "running" || t.status == "queued") LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val sizes = if (t.total > 0) stringResource(R.string.x_of_y, size(ctx, t.done), size(ctx, t.total)) else size(ctx, t.done)
            val line = when (t.status) {
                "queued" -> stringResource(R.string.dl_starting)
                "running" -> listOfNotNull(
                    "$pct%", sizes, t.speed.takeIf { it > 0 }?.let { stringResource(R.string.per_second, size(ctx, it)) },
                    if (t.speed > 0 && t.total > 0) eta((t.total - t.done) / t.speed) else null,
                ).joinToString(" · ")
                "paused" -> stringResource(R.string.dl_paused_at, pct, sizes)
                "failed" -> stringResource(R.string.dl_stopped_at, pct)
                "finishing" -> stringResource(R.string.dl_finishing)
                else -> stringResource(R.string.dl_downloaded, size(ctx, t.total))
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            t.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (t.status == "failed") c.error else c.onSurfaceVariant) }
            if (t.status == "done") CheckResult(t)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (t.status) {
                    "running", "queued" -> OutlinedButton({ Downloader.pause(t) }) { Text(stringResource(R.string.pause)) }
                    "paused" -> FilledTonalButton({ Downloader.start(t) }) { Text(stringResource(R.string.resume)) }
                    "failed" -> FilledTonalButton({ Downloader.start(t) }) { Text(stringResource(R.string.retry)) }
                    "finishing" -> {}
                    else -> if (t.check == "bad") {
                        Button({ Downloader.again(t) }) { Text(stringResource(R.string.download_again)) }
                    } else {
                        if (canMount && t.file.exists() && !isPacked(t.name)) Button(onMount) { Text(stringResource(R.string.mount)) }
                        OutlinedButton(onCheck) { Text(stringResource(R.string.check)) }
                    }
                }
            }
        }
    }
}

/** How a finished download compared with its published checksum. */
@Composable
private fun CheckResult(t: Task) {
    val c = MaterialTheme.colorScheme
    val (ok, text) = when (t.check) {
        "ok" -> true to stringResource(R.string.dl_check_ok, t.algo.orEmpty())
        "bad" -> false to stringResource(R.string.dl_check_bad)
        "none" -> null to stringResource(R.string.dl_check_none)
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ok != null) Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, Modifier.size(18.dp), tint = if (ok) c.primary else c.error)
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (ok == false) c.error else c.onSurfaceVariant)
    }
}

@Composable
private fun eta(seconds: Long) = when {
    seconds < 60 -> stringResource(R.string.eta_under_minute)
    seconds < 3600 -> stringResource(R.string.eta_minutes, seconds / 60)
    else -> stringResource(R.string.eta_hours, seconds / 3600, seconds % 3600 / 60)
}
