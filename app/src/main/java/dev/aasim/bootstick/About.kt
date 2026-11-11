@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.aasim.bootstick

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

const val APP_PAGE = "https://aasim.site/apps/bootstick"
const val REPO = "https://github.com/muhammadaasim/bootstick"
private const val EMAIL = "muhammadaasimsoomro@gmail.com"

@Composable
fun AboutScreen(s: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val links = LocalUriHandler.current
    val c = MaterialTheme.colorScheme
    val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    BackHandler(onBack = onBack)

    fun email() {
        val body = "\n\n---\nBootStick $version\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
        val uri = Uri.parse("mailto:$EMAIL?subject=" + Uri.encode(res.getString(R.string.feedback_subject)) + "&body=" + Uri.encode(body))
        runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, uri)) }
            .onFailure { s.error = res.getString(R.string.no_email_app, EMAIL) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppIcon(Modifier.size(96.dp))
                    Wordmark(MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.version, version.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Text(
                        stringResource(R.string.about_tagline),
                        textAlign = TextAlign.Center, color = c.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        Button({ links.openUri(APP_PAGE) }) { Text(stringResource(R.string.app_page)) }
                        val shareText = stringResource(R.string.share_text, APP_PAGE)
                        val shareTitle = stringResource(R.string.share_app)
                        OutlinedButton({
                            ctx.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText),
                                shareTitle,
                            ))
                        }) { Text(stringResource(R.string.share)) }
                        OutlinedButton({ links.openUri(REPO) }) { Text("GitHub") }
                    }
                }
            }

            item {
                Section(stringResource(R.string.what_you_need)) {
                    // What this phone has, checked live, then the things only the user can check.
                    Need(
                        true, stringResource(R.string.need_android),
                        stringResource(R.string.need_android_detail, Build.VERSION.RELEASE),
                    )
                    Need(
                        s.rooted, stringResource(R.string.need_root),
                        when {
                            !s.rooted -> stringResource(R.string.need_root_no)
                            s.rootApp != null -> stringResource(R.string.need_root_through, s.rootApp.name)
                            else -> stringResource(R.string.need_root_yes)
                        },
                    )
                    Need(
                        if (s.rooted) s.supported else null, stringResource(R.string.need_kernel),
                        when {
                            s.supported -> stringResource(R.string.need_kernel_yes)
                            s.rooted -> reasonText(s.info?.values?.get("reason"))
                            else -> stringResource(R.string.need_kernel_later)
                        },
                    )
                    Need(null, stringResource(R.string.need_cable), stringResource(R.string.need_cable_detail))
                    Need(null, stringResource(R.string.need_computer), stringResource(R.string.need_computer_detail))
                }
            }

            item {
                Section(stringResource(R.string.which_phones)) {
                    Text(stringResource(R.string.which_phones_body))
                    Text(stringResource(R.string.tested_on), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.tested_on_detail))
                    Text(stringResource(R.string.wont_work), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.wont_work_detail))
                    Text(stringResource(R.string.tried_another), color = c.onSurfaceVariant)
                    OutlinedButton(::email) { Text(stringResource(R.string.send_feedback)) }
                }
            }

            item {
                Section(stringResource(R.string.made_by)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Surface(shape = CircleShape, color = c.primary, contentColor = c.onPrimary, modifier = Modifier.size(56.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("MA", style = MaterialTheme.typography.titleLarge) }
                        }
                        Column {
                            Text("Muhammad Aasim", style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.author_role), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    }
                    Text(stringResource(R.string.origin))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ links.openUri("https://aasim.site") }) { Text(stringResource(R.string.portfolio)) }
                        OutlinedButton({ links.openUri("https://github.com/muhammadaasim") }) { Text("GitHub") }
                        OutlinedButton({ links.openUri("https://www.linkedin.com/in/dev-aasim/") }) { Text("LinkedIn") }
                        OutlinedButton(::email) { Text(stringResource(R.string.email)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** One requirement: [ok] true = this phone has it, false = it doesn't, null = can't be checked from here. */
@Composable
private fun Need(ok: Boolean?, title: String, detail: String) {
    val c = MaterialTheme.colorScheme
    val (icon, tint: Color) = when (ok) {
        true -> Icons.Filled.CheckCircle to c.primary
        false -> Icons.Filled.Warning to c.error
        null -> Icons.Filled.Info to c.onSurfaceVariant
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}
