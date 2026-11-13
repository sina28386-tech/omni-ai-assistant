package com.omni.aiassistant.ui

import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.omni.aiassistant.R
import com.omni.aiassistant.core.security.LocalSecurityIncident
import com.omni.aiassistant.core.ui.AppErrorDialog
import com.omni.aiassistant.core.wake.WakeCommandBus
import com.omni.aiassistant.core.ui.Hairline
import com.omni.aiassistant.core.ui.OmniRadius
import com.omni.aiassistant.core.ui.OmniSpacing
import com.omni.aiassistant.core.ui.OmniTheme
import com.omni.aiassistant.domain.model.AppLanguage
import com.omni.aiassistant.feature.chat.ChatScreen
import com.omni.aiassistant.feature.conversations.ConversationsScreen
import com.omni.aiassistant.feature.conversations.ConversationsViewModel
import com.omni.aiassistant.feature.devices.DevicesHub
import com.omni.aiassistant.feature.hermes.HermesScreen
import com.omni.aiassistant.feature.home.HomeDashboard
import com.omni.aiassistant.feature.providers.ProvidersScreen
import com.omni.aiassistant.feature.settings.SettingsScreen
import com.omni.aiassistant.feature.settings.SettingsViewModel
import com.omni.aiassistant.feature.voice.VoiceScreen
import com.omni.aiassistant.feature.bots.BotsScreen
import com.omni.aiassistant.feature.web.WebWorkspaceScreen
import org.koin.androidx.compose.koinViewModel

private data class TopDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    /** Routes that belong to this destination even when they are not tabs. */
    val children: Set<String> = emptySet(),
)

private val TopDestinations = listOf(
    TopDestination("home", R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    TopDestination(
        "chats",
        R.string.nav_conversations,
        Icons.AutoMirrored.Outlined.Chat,
        Icons.AutoMirrored.Outlined.Chat,
    ),
    TopDestination("history", R.string.nav_history, Icons.Outlined.History, Icons.Filled.History),
    TopDestination(
        "devices",
        R.string.nav_devices,
        Icons.Outlined.Hub,
        Icons.Filled.Hub,
        children = setOf("providers", "hermes", "bots", "creative_web"),
    ),
    TopDestination("settings", R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
)

/** Routes that take over the full window and intentionally hide the tab bar. */
private val ImmersiveRoutes = setOf("voice")

@Composable
fun OmniApp() {
    val settingsViewModel: SettingsViewModel = koinViewModel()
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val securityError by LocalSecurityIncident.error.collectAsStateWithLifecycle()

    LaunchedEffect(settingsState.settings.language) {
        val tag = if (settingsState.settings.language == AppLanguage.PERSIAN) "fa" else "en"
        val desired = LocaleListCompat.forLanguageTags(tag)
        if (AppCompatDelegate.getApplicationLocales() != desired) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }

    OmniTheme(settingsState.settings.theme) {
        AppErrorDialog(securityError, LocalSecurityIncident::clear)
        val navController = rememberNavController()
        val conversationsViewModel: ConversationsViewModel = koinViewModel()
        val wakeCommands = WakeCommandBus.commands
        val backStack by navController.currentBackStackEntryAsState()
        val current = backStack?.destination
        val route = current?.route.orEmpty()
        val inChat = route.startsWith("chat/")
        val immersive = inChat || route in ImmersiveRoutes

        val newChat: () -> Unit = {
            conversationsViewModel.create { id -> navController.navigate("chat/$id") }
        }
        val startPrompt: (String) -> Unit = { prompt ->
            val trimmed = prompt.trim()
            if (trimmed.isEmpty()) {
                conversationsViewModel.create { id -> navController.navigate("chat/$id") }
            } else {
                val encoded = Uri.encode(trimmed)
                conversationsViewModel.create { id ->
                    navController.navigate("chat/$id?prefill=$encoded")
                }
            }
        }
        // A command that came from the wake word opens a chat and sends it
        // immediately, so the user only has to speak.
        val wakeCommand by wakeCommands.collectAsStateWithLifecycle()
        LaunchedEffect(wakeCommand?.id) {
            val command = wakeCommand ?: return@LaunchedEffect
            val encoded = Uri.encode(command.text)
            conversationsViewModel.create { id ->
                navController.navigate("chat/$id?prefill=$encoded&autoSend=true")
            }
            WakeCommandBus.consume()
        }
        val openChat: (String) -> Unit = { id -> navController.navigate("chat/$id") }
        val openTab: (String) -> Unit = { destination ->
            navController.navigate(destination) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            // Every screen owns its own status/navigation insets. The scaffold
            // only reserves space for the tab bar, which avoids the double
            // status-bar padding the previous V1 shell accumulated.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!immersive) {
                    OmniTabBar(
                        destinations = TopDestinations,
                        isSelected = { item ->
                            current?.hierarchy?.any { it.route == item.route } == true ||
                                route in item.children
                        },
                        onNavigate = { item -> openTab(item.route) },
                    )
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
                enterTransition = {
                    fadeIn(tween(220)) + slideInVertically(tween(220)) { height -> height / 48 }
                },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = {
                    fadeIn(tween(220)) + slideInVertically(tween(220)) { height -> height / 48 }
                },
                popExitTransition = { fadeOut(tween(140)) },
            ) {
                composable("home") {
                    HomeDashboard(
                        onNewChat = newChat,
                        onStartPrompt = startPrompt,
                        onOpenChat = openChat,
                        onOpenAllChats = { openTab("chats") },
                        onOpenDevices = { openTab("devices") },
                        onOpenProviders = { navController.navigate("providers") },
                        onOpenVoice = { navController.navigate("voice") },
                        onOpenBots = { navController.navigate("bots") },
                        onSubmitPrompt = { text ->
                            conversationsViewModel.create { id ->
                                navController.navigate("chat/$id?prefill=${Uri.encode(text)}&autoSend=true")
                            }
                        },
                    )
                }
                composable("chats") {
                    ConversationsScreen(
                        viewModel = conversationsViewModel,
                        onOpen = openChat,
                        onNew = newChat,
                    )
                }
                composable("history") {
                    ConversationsScreen(
                        viewModel = conversationsViewModel,
                        onOpen = openChat,
                        onNew = newChat,
                        titleRes = R.string.nav_history,
                        lockedArchived = true,
                    )
                }
                composable("devices") {
                    DevicesHub(
                        onOpenHermes = { navController.navigate("hermes") },
                        onOpenProviders = { navController.navigate("providers") },
                        onFindFile = {
                            val prefill = Uri.encode(
                                settingsState.settings.language.let { language ->
                                    if (language == AppLanguage.PERSIAN) "فایل‌ها: " else "Find files: "
                                },
                            )
                            conversationsViewModel.create { id ->
                                navController.navigate("chat/$id?prefill=$prefill")
                            }
                        },
                        onOpenBots = { navController.navigate("bots") },
                        onOpenWebWorkspace = { navController.navigate("creative_web") },
                    )
                }
                composable("hermes") {
                    HermesScreen(onOpenSettings = { openTab("settings") })
                }
                composable("providers") { ProvidersScreen() }
                composable("creative_web") { WebWorkspaceScreen(onBack = { navController.popBackStack() }) }
                composable("bots") {
                    BotsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenChat = openChat,
                        onOpenProviders = { navController.navigate("providers") },
                        onOpenDevices = { openTab("devices") },
                    )
                }
                composable("voice") {
                    VoiceScreen(
                        onOpenConversation = { id, text ->
                            navController.navigate("chat/$id?prefill=${Uri.encode(text)}&autoSend=true") {
                                popUpTo("voice") { inclusive = true }
                            }
                        },
                        onClose = { navController.popBackStack() },
                    )
                }
                composable("settings") { SettingsScreen() }
                composable(
                    "chat/{id}?prefill={prefill}&autoSend={autoSend}",
                    arguments = listOf(
                        navArgument("prefill") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("autoSend") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { entry ->
                    val id = requireNotNull(entry.arguments?.getString("id"))
                    val prefill = entry.arguments?.getString("prefill").orEmpty()
                    val autoSend = entry.arguments?.getBoolean("autoSend") ?: false
                    ChatScreen(
                        conversationId = id,
                        initialDraft = prefill,
                        autoSend = autoSend,
                        onBack = { navController.popBackStack() },
                        onOpenProviders = { navController.navigate("providers") },
                    )
                }
            }
        }
    }
}

/**
 * Quiet instrument-style tab bar. It deliberately does not use Material's
 * NavigationBar: no tonal lift, no pill, no elevation. Selection is expressed
 * by ember colour, the filled icon variant and the accessibility `selected`
 * state, never by colour alone.
 */
@Composable
private fun OmniTabBar(
    destinations: List<TopDestination>,
    isSelected: (TopDestination) -> Boolean,
    onNavigate: (TopDestination) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    // At large accessibility font scales the labels no longer fit next to the
    // icons on a 320 dp screen. The bar collapses to icons and keeps the
    // label as the accessibility name instead of truncating it.
    val showLabels = fontScale < 1.5f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Hairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(horizontal = OmniSpacing.xs, vertical = OmniSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                destinations.forEach { item ->
                    val selected = isSelected(item)
                    val label = stringResource(item.labelRes)
                    val tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(OmniRadius.small))
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onNavigate(item) },
                            )
                            .semantics {
                                this.selected = selected
                                contentDescription = label
                            }
                            .padding(horizontal = 2.dp, vertical = OmniSpacing.sm),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        if (showLabels) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = tint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
