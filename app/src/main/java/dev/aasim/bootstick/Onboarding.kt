package dev.aasim.bootstick

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val PAGES = 3

/** First-run walkthrough: what BootStick does, how to use it, and a live root and phone check. */
@Composable
fun Onboarding(s: AppState, onDone: () -> Unit) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            HorizontalPager(pager, Modifier.weight(1f)) { p ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                ) {
                    when (p) {
                        0 -> Welcome()
                        1 -> HowItWorks()
                        else -> RootCheck(s)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ if (page == 0) onDone() else scope.launch { pager.animateScrollToPage(page - 1) } }) {
                    Text(stringResource(if (page == 0) R.string.skip else R.string.back))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                    repeat(PAGES) { i ->
                        Box(
                            Modifier.padding(4.dp).size(if (i == page) 10.dp else 8.dp).clip(CircleShape)
                                .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
                Button({ if (page == PAGES - 1) onDone() else scope.launch { pager.animateScrollToPage(page + 1) } }) {
                    Text(stringResource(if (page == PAGES - 1) R.string.get_started else R.string.next))
                }
            }
        }
    }
}

@Composable
private fun Welcome() {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AppIcon(Modifier.size(128.dp))
    }
    Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        stringResource(R.string.welcome_body),
        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = c.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Point(Icons.Filled.CheckCircle, c.primary, stringResource(R.string.welcome_point1))
        Point(Icons.Filled.CheckCircle, c.primary, stringResource(R.string.welcome_point2))
        Point(Icons.Filled.CheckCircle, c.primary, stringResource(R.string.welcome_point3))
    }
}

@Composable
private fun HowItWorks() {
    Text(stringResource(R.string.how_it_works), style = MaterialTheme.typography.headlineMedium)
    Step(1, stringResource(R.string.step1_title), stringResource(R.string.step1_body))
    Step(2, stringResource(R.string.step2_title), stringResource(R.string.step2_body))
    Step(3, stringResource(R.string.step3_title), stringResource(R.string.step3_body))
}

/** Live check, refreshed whenever the user comes back (for example from granting root in KernelSU). */
@Composable
private fun RootCheck(s: AppState) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { s.refresh() }
    val i = s.info
    when {
        i == null -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Text(stringResource(R.string.checking_root), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        s.supported -> {
            Verdict(Icons.Filled.CheckCircle, c.primary, stringResource(R.string.all_set), stringResource(R.string.all_set_body))
        }
        i.hasRoot -> {
            Verdict(Icons.Filled.Warning, c.error, stringResource(R.string.root_but_no_drive), reasonText(i.values["reason"]))
            NoRootList()
        }
        else -> {
            Verdict(
                Icons.Filled.Lock, c.secondary, stringResource(R.string.give_root),
                stringResource(R.string.give_root_body) + " " + stringResource(s.rootApp?.steps ?: R.string.root_steps_generic),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.rootApp?.let { app ->
                    Button({ ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.let(ctx::startActivity) }) { Text(stringResource(R.string.open_app, app.name)) }
                }
                OutlinedButton(s::refresh, enabled = !s.refreshing) { Text(stringResource(if (s.refreshing) R.string.checking else R.string.check_again)) }
            }
            NoRootList()
        }
    }
}

@Composable
private fun NoRootList() {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.without_it), style = MaterialTheme.typography.titleSmall)
        Point(Icons.Filled.CheckCircle, c.onSurfaceVariant, stringResource(R.string.without_it_1))
        Point(Icons.Filled.CheckCircle, c.onSurfaceVariant, stringResource(R.string.without_it_2))
        Point(Icons.Filled.CheckCircle, c.onSurfaceVariant, stringResource(R.string.without_it_3))
    }
}

@Composable
private fun Verdict(icon: ImageVector, tint: Color, title: String, body: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        IconBadge(rememberVectorPainter(icon), tint.copy(alpha = 0.12f), tint, 96.dp)
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Point(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Step(n: Int, title: String, body: String) {
    val c = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = CircleShape, color = c.primary, contentColor = c.onPrimary, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("%d".format(n), style = MaterialTheme.typography.titleMedium) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
    }
}
