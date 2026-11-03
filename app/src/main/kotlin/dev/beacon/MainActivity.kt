package dev.beacon

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.beacon.core.data.local.ChatEntity
import dev.beacon.feature.chat.ChatsDrawer
import dev.beacon.feature.chat.ChatActions
import dev.beacon.feature.chat.ChatScreen
import dev.beacon.feature.chat.ChatViewModel
import dev.beacon.feature.home.HomeViewModel
import dev.beacon.feature.onboarding.OnboardingActions
import dev.beacon.feature.onboarding.OnboardingScreen
import dev.beacon.feature.onboarding.OnboardingViewModel
import dev.beacon.feature.settings.SettingsScreen
import dev.beacon.feature.settings.SettingsViewModel
import dev.beacon.ui.theme.BeaconTheme

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_CHAT_NEW = "chat/new"
private const val ROUTE_CHAT = "chat/{chatId}"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        setContent {
            val windowSizeClass = androidx.compose.material3.windowsizeclass.calculateWindowSizeClass(this)
            BeaconTheme {
                val wide = windowSizeClass.widthSizeClass != androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact
                BeaconNavHost(permanentSidebar = wide)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * §9.3 share-target: shared text → a new chat with the draft pre-filled.
     * When no instance is onboarded yet, the share text is dropped (honest).
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                dev.beacon.feature.chat.ShareSeed.pending = text
                dev.beacon.feature.chat.ShareSeed.requestCount.value++
                intent.removeExtra(android.content.Intent.EXTRA_TEXT)
            }
        } else if (intent?.action == "dev.beacon.NEW_CHAT") {
            dev.beacon.feature.chat.ShareSeed.requestCount.value++
        }
    }
}

@Composable
private fun BeaconNavHost(permanentSidebar: Boolean) {
    val main: MainViewModel = hiltViewModel()
    val settings by main.settings.collectAsStateWithLifecycle()

    when (val snapshot = settings) {
        null -> Surface(Modifier.fillMaxSize()) { } // DataStore hydration flash
        else -> key(snapshot.isOnboarded) {
            val nav = rememberNavController()
            // §9.3 share-target / shortcut: jump to a fresh chat when requested.
            LaunchedEffect(Unit) {
                dev.beacon.feature.chat.ShareSeed.requestCount.collect { count ->
                    if (count > 0 && snapshot.isOnboarded) {
                        nav.navigate("chat/new") { launchSingleTop = true }
                    }
                }
            }
            NavHost(
                navController = nav,
                // Boot straight into a fresh chat surface; chats live in the drawer
                // (user request + interface copy 2026-11-02).
                startDestination = if (snapshot.isOnboarded) ROUTE_CHAT_NEW else ROUTE_ONBOARDING,
            ) {
                composable(ROUTE_ONBOARDING) {
                    val vm: OnboardingViewModel = hiltViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    LaunchedEffectOnce { vm.onFinished = { navPopToChatHome(nav) } }
                    OnboardingScreen(
                        state = state,
                        actions = OnboardingActions(
                            setUrlDraft = vm::setUrlDraft,
                            probeServer = vm::probeServer,
                            setAuthMode = vm::setAuthMode,
                            setApiKeyDraft = vm::setApiKeyDraft,
                            submitApiKey = vm::submitApiKey,
                            setEmailDraft = vm::setEmailDraft,
                            setPasswordDraft = vm::setPasswordDraft,
                            submitPassword = vm::submitPassword,
                            chooseModel = vm::chooseModel,
                            finish = vm::finish,
                        ),
                    )
                }
                // Boot surface: a fresh chat (chats live in the drawer).
                composable(ROUTE_CHAT_NEW) {
                    BeaconChatSurface(nav, chatIdArg = "new", permanentSidebar)
                }
                composable(
                    route = ROUTE_CHAT,
                    arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
                ) { entry ->
                    BeaconChatSurface(nav, chatIdArg = entry.arguments?.getString("chatId"), permanentSidebar)
                }
                composable(ROUTE_SETTINGS) {
                    val vm: SettingsViewModel = hiltViewModel()
                    SettingsScreen(
                        state = vm.state.collectAsStateWithLifecycle().value,
                        onSignOut = vm::signOut,
                        onClose = { nav.popBackStack() },
                    )
                }
                // M4-d1 drawer surfaces (read-only): folders / notes / channels /
                // files / workspace.
                composable(
                    route = "surface/{key}",
                    arguments = listOf(navArgument("key") { type = NavType.StringType }),
                ) { entry ->
                    SurfaceRoute(
                        nav = nav,
                        key = entry.arguments?.getString("key").orEmpty(),
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    route = "file/{fileId}/{name}/{mime}?server={server}&path={path}",
                    arguments = listOf(
                        navArgument("fileId") { type = NavType.StringType; defaultValue = "" },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" },
                        navArgument("mime") { type = NavType.StringType; defaultValue = "" },
                        navArgument("server") { type = NavType.StringType; defaultValue = "" },
                        navArgument("path") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    val fileVm: dev.beacon.feature.chat.FileViewerViewModel = hiltViewModel()
                    dev.beacon.feature.chat.FileViewerScreen(
                        filesApi = fileVm.api,
                        terminalsApi = fileVm.terminals,
                        fileId = android.net.Uri.decode(entry.arguments?.getString("fileId").orEmpty()),
                        name = android.net.Uri.decode(entry.arguments?.getString("name").orEmpty()),
                        mime = android.net.Uri.decode(entry.arguments?.getString("mime").orEmpty()),
                        terminalServerId = entry.arguments?.getString("server")
                            ?.takeIf { it.isNotBlank() },
                        terminalPath = entry.arguments?.getString("path")
                            ?.takeIf { it.isNotBlank() }?.let { android.net.Uri.decode(it) },
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchedEffectOnce(block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

/** The M4-d1 read-only surfaces (folders/notes/channels/files/workspace). */
@Composable
private fun SurfaceRoute(
    nav: androidx.navigation.NavController,
    key: String,
    onBack: () -> Unit,
) {
    val vm: dev.beacon.feature.surfaces.SurfaceViewModel = hiltViewModel()
    val state = vm.state.collectAsStateWithLifecycle().value
    when (key) {
        "folders" -> dev.beacon.feature.surfaces.ListSurfaceScreen(
            title = "Folders",
            items = state.folders,
            loading = state.loading,
            error = state.error,
            rowKey = { it.name + it.id },
            onBack = onBack,
            onItem = { }, // folder chats: M4-d2
        ) { folder ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                androidx.compose.material3.Text(folder.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                if (folder.unread_count > 0) {
                    androidx.compose.material3.Text(
                        "${folder.unread_count} new",
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        "notes" -> dev.beacon.feature.surfaces.ListSurfaceScreen(
            title = "Notes",
            items = state.notes,
            loading = state.loading,
            error = state.error,
            rowKey = { it.id },
            onBack = onBack,
            onItem = { }, // note view/edit: M4-d2
        ) { note ->
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                androidx.compose.material3.Text(note.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                if (note.is_pinned == true) {
                    androidx.compose.material3.Text("Pinned", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                }
            }
        }
        "channels" -> dev.beacon.feature.surfaces.ListSurfaceScreen(
            title = "Channels",
            items = state.channels,
            loading = state.loading,
            error = state.error,
            rowKey = { it.id },
            onBack = onBack,
            onItem = { }, // channel chat: M4-d2 (socket-dependent)
        ) { channel ->
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                androidx.compose.material3.Text(channel.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                channel.description?.let {
                    androidx.compose.material3.Text(
                        it,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }
        "files" -> dev.beacon.feature.surfaces.ListSurfaceScreen(
            title = "Files",
            items = state.files,
            loading = state.loading,
            error = state.error,
            rowKey = { it.id },
            onBack = onBack,
            onItem = { file ->
                // The file viewer is a real surface since M2-3.
                nav.navigate("file/${android.net.Uri.encode(file.id)}/${android.net.Uri.encode(file.filename)}/${android.net.Uri.encode("application/octet-stream")}")
            },
        ) { file ->
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                androidx.compose.material3.Text(file.filename, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
        }
        "workspace" -> dev.beacon.feature.surfaces.ListSurfaceScreen(
            title = "Workspace",
            items = state.prompts,
            loading = state.loading,
            error = state.error,
            rowKey = { (it.id ?: it.command) },
            onBack = onBack,
            onItem = { }, // prompt-send: M4-d2
            header = {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    androidx.compose.material3.Text("Prompts", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    state.tools.take(3).forEach { tool ->
                        androidx.compose.material3.Text("• ${tool.name}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                    state.knowledge.take(3).forEach { knowledge ->
                        androidx.compose.material3.Text("• ${knowledge.name}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
        ) { prompt ->
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                androidx.compose.material3.Text(prompt.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                androidx.compose.material3.Text("/${prompt.command}", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else -> onBack()
    }
}

private fun navPopToChatHome(nav: androidx.navigation.NavController) {
    nav.navigate(ROUTE_CHAT_NEW) {
        popUpTo(ROUTE_ONBOARDING) { inclusive = true }
    }
}

/**
 * The chat surface, Claude-app-style (interface copy 2026-11-02): one screen
 * wrapped by the chats drawer; the boot/new surface flips the nav icon to the
 * hamburger (the drawer home), opened chats show the back affordance.
 */
@Composable
private fun BeaconChatSurface(
    nav: androidx.navigation.NavController,
    chatIdArg: String?,
    permanentSidebar: Boolean,
) {
    val drawerVm: HomeViewModel = hiltViewModel()
    val vm: ChatViewModel = hiltViewModel()
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed,
    )
    val drawerScope = androidx.compose.runtime.rememberCoroutineScope()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val docPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.attach(name = dev.beacon.core.data.contentQueryName(context, uri), mime = "", uri = uri)
        }
    }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startDictation() }

    val surfacesVm: dev.beacon.feature.surfaces.SurfaceViewModel = hiltViewModel()
    // Fold-aware note (agent.md queue): on expanded widths the chats panel will
    // be a PERMANENT two-pane surface instead of the modal drawer — deferred here
    // pending a careful extraction of the surface body; the window-size class
    // plumbing (permanentSidebar) is ready.

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet {
                ChatsDrawer(
                    listViewModel = drawerVm,
                    surfaces = surfacesVm,
                    onOpenChat = { chat -> nav.navigate("chat/${chat.chatId}") { launchSingleTop = true } },
                    onNewChat = { nav.navigate(ROUTE_CHAT_NEW) { launchSingleTop = true; popUpTo(ROUTE_CHAT_NEW) { inclusive = false } } },
                    onOpenSettings = { nav.navigate(ROUTE_SETTINGS) },
                    onOpenSurface = { key -> nav.navigate("surface/$key") },
                    onClose = { drawerScope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        ChatScreen(
            state = vm.ui.collectAsStateWithLifecycle().value,
            actions = ChatActions(
                onDraft = vm::setDraft,
                onSend = vm::send,
                onStop = vm::stop,
                onBack = { nav.popBackStack() },
                onOpenModelPicker = { pickerOpen = true },
                onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                onNewChat = {
                    nav.navigate(ROUTE_CHAT_NEW) { launchSingleTop = true }
                },
                onAttach = { docPicker.launch(arrayOf("*/*")) },
                onRemoveAttachment = vm::removeAttachment,
                onToggleWebSearch = vm::toggleWebSearch,
                onToggleImageGeneration = vm::toggleImageGeneration,
                onToggleCodeInterpreter = vm::toggleCodeInterpreter,
                onToggleTemp = vm::toggleTempMode,
                onMicTap = {
                    if (vm.micPermissionGranted()) {
                        vm.startDictation()
                    } else {
                        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onMicStop = { vm.stopDictation() },
                onReadAloud = { id, text -> vm.readAloud(id, text) },
                onRegenerate = { vm.regenerate() },
                onRate = { id, rating -> vm.rate(id, rating) },
                onShare = { _, text -> vm.share(text) },
                onFlickRefetch = vm::flickRefetch,
                onAppResume = vm::onAppResume,
                onClearUpdatedPill = vm::clearUpdatedPill,
                onOpenFile = { fileId, name, mime, terminalServer, terminalPath ->
                    val base = "file/${android.net.Uri.encode(fileId)}/${android.net.Uri.encode(name)}/${android.net.Uri.encode(mime)}"
                    val suffix = buildString {
                        if (terminalServer != null) append("?server=${android.net.Uri.encode(terminalServer)}")
                        if (terminalPath != null) append(
                            (if (terminalServer == null) "?" else "&") + "path=${android.net.Uri.encode(terminalPath)}",
                        )
                    }
                    nav.navigate(base + suffix)
                },
            ),
            showModelPicker = pickerOpen,
            onDismissPicker = { pickerOpen = false },
            onPickModel = { id ->
                vm.chooseModel(id)
                pickerOpen = false
            },
            onSetDefault = { id -> vm.chooseModel(id) },
        )
    }
}