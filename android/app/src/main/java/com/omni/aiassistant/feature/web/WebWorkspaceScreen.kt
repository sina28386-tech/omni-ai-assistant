package com.omni.aiassistant.feature.web

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.omni.aiassistant.R
import com.omni.aiassistant.core.ui.OmniSecondaryButton
import com.omni.aiassistant.core.ui.OmniSpacing
import com.omni.aiassistant.core.ui.ScreenHeader

private data class CreativeSite(@StringRes val name: Int, val url: String)

private val creativeSites = listOf(
    CreativeSite(R.string.web_workspace_higgsfield, "https://higgsfield.ai/"),
    CreativeSite(R.string.web_workspace_runway, "https://app.runwayml.com/"),
    CreativeSite(R.string.web_workspace_flow, "https://labs.google/fx/tools/flow"),
)

@Composable
fun WebWorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var launchFailed by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(OmniSpacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.xl),
        ) {
            item {
                Column {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.web_workspace_back))
                    }
                    ScreenHeader(
                        title = stringResource(R.string.web_workspace_title),
                        subtitle = stringResource(R.string.web_workspace_subtitle),
                    )
                }
            }
            item {
                Text(stringResource(R.string.web_workspace_session), style = MaterialTheme.typography.bodyMedium)
            }
            items(creativeSites.size) { index ->
                val site = creativeSites[index]
                OmniSecondaryButton(
                    text = stringResource(R.string.web_workspace_open, stringResource(site.name)),
                    onClick = {
                        launchFailed = false
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(site.url))
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            launchFailed = true
                        } catch (_: SecurityException) {
                            launchFailed = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (launchFailed) {
                item {
                    Text(
                        text = stringResource(R.string.web_workspace_launch_error),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.sm)) {
                    Text(stringResource(R.string.web_workspace_workflow_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.web_workspace_workflow), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.web_workspace_billing),
                        modifier = Modifier.padding(top = OmniSpacing.sm),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
