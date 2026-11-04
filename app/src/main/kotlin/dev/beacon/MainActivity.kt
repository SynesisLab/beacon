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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
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
import androidx.compose.runtime.getValue
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
    @javax.inject.Inject
    lateinit var session: dev.beacon.core.data.SessionStore

    @OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        setContent {
            val windowSizeClass = androidx.compose.material3.windowsizeclass.calculateWindowSizeClass(this)
            // Appearance (user request 2026-11-03): Settings → Appearance forces
            // Light/Dark or follows the system; DataStore rides live.
            val settings by session.settings.collectAsState(
                initial = dev.beacon.core.data.ServerSettings(),
            )
            val darkTheme = when (val mode = settings.themeMode) {
                dev.beacon.core.data.ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
                dev.beacon.core.data.ThemeMode.Light -> false
                dev.beacon.core.data.ThemeMode.Dark -> true
            }
            BeaconTheme(darkTheme = darkTheme) {
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
                // Transitions ("better transitions" user request): the forward
                // push fades-in w/ a slight right-to-left slide; the BACK gesture
                // (predictive) previews a scale-down + slide-out — Claude-app calm.
                enterTransition = {
                    androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    ) + androidx.compose.animation.slideInHorizontally(
                        androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    ) { fullWidth -> fullWidth / 8 }
                },
                exitTransition = {
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))
                },
                popEnterTransition = {
                    androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    ) + androidx.compose.animation.slideInHorizontally(
                        androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    ) { fullWidth -> -fullWidth / 10 }
                },
                popExitTransition = {
                    // The predictive-back shape: scale-down + slide right.
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) +
                        androidx.compose.animation.scaleOut(
                            animationSpec = androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            targetScale = 0.92f,
                        ) +
                        androidx.compose.animation.slideOutHorizontally(
                            androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        ) { fullWidth -> fullWidth / 6 }
                },
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
                            setMfaCodeDraft = vm::setMfaCodeDraft,
                            setMfaRecoveryMode = vm::setMfaRecoveryMode,
                            submitMfaCode = vm::submitMfaCode,
                            submitMfaEnrollConfirm = vm::submitMfaEnrollConfirm,
                            continueAfterRecoveryCodes = vm::continueAfterRecoveryCodes,
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
                        onThemeMode = vm::setThemeMode,
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
                    route = "folder/{id}/{name}/chats",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    val writeVm: dev.beacon.feature.surfaces.SurfaceWriteViewModel = hiltViewModel()
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        writeVm.folderChats(entry.arguments?.getString("id").orEmpty())
                    }
                    dev.beacon.feature.surfaces.FolderChatsScreen(
                        writeModel = writeVm,
                        folderName = android.net.Uri.decode(entry.arguments?.getString("name").orEmpty()),
                        onBack = { nav.popBackStack() },
                        onOpenChat = { chatId -> nav.navigate("chat/$chatId") },
                    )
                }
                composable(
                    route = "note/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    val writeVm: dev.beacon.feature.surfaces.SurfaceWriteViewModel = hiltViewModel()
                    dev.beacon.feature.surfaces.NoteEditorScreen(
                        writeModel = writeVm,
                        noteId = entry.arguments?.getString("id"),
                        title = "",
                        onBack = { nav.popBackStack() },
                        onCreated = { }, // list refresh = surface reload on back
                        onSaved = { nav.popBackStack() },
                    )
                }
                composable("note/new") { _ ->
                    val writeVm: dev.beacon.feature.surfaces.SurfaceWriteViewModel = hiltViewModel()
                    dev.beacon.feature.surfaces.NoteEditorScreen(
                        writeModel = writeVm,
                        noteId = null,
                        title = "",
                        onBack = { nav.popBackStack() },
                        onCreated = { id -> nav.navigate("note/$id") { popUpTo("note/new") { inclusive = true } } },
                        onSaved = { nav.popBackStack() },
                    )
                }
                composable(
                    route = "channel/{id}/{name}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    val writeVm: dev.beacon.feature.surfaces.SurfaceWriteViewModel = hiltViewModel()
                    dev.beacon.feature.surfaces.ChannelChatScreen(
                        writeModel = writeVm,
                        channelId = entry.arguments?.getString("id").orEmpty(),
                        channelName = android.net.Uri.decode(entry.arguments?.getString("name").orEmpty()),
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
                composable(route = "terminal") { _ ->
                    val vm: dev.beacon.feature.terminal.TerminalViewModel = hiltViewModel()
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val save = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream"),
                    ) { uri ->
                        vm.pendingDownload?.let { (bytes, _) ->
                            uri?.let {
                                runCatching {
                                    context.contentResolver.openOutputStream(it)?.use { out ->
                                        bytes.inputStream().copyTo(out)
                                    }
                                }
                            }
                            vm.pendingDownload = null
                        }
                    }
                    dev.beacon.feature.terminal.TerminalScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onSaveDownload = { bytes, name ->
                            vm.pendingDownload = bytes to name
                            save.launch(name)
                        },
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
    val writeVm: dev.beacon.feature.surfaces.SurfaceWriteViewModel = hiltViewModel()
    val state = vm.state.collectAsStateWithLifecycle().value
    // Re-fetch on every resume of the backstack entry: returning from a note
    // editor / folder detail shows fresh data (M4 fix: stale lists).
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    when (key) {
        "folders" -> dev.beacon.feature.surfaces.FoldersScreen(
            writeModel = writeVm,
            loaderState = state,
            onBack = onBack,
            onOpenFolderChats = { id ->
                val folder = state.folders.firstOrNull { it.id == id }
                nav.navigate("folder/$id/${android.net.Uri.encode(folder?.name ?: "")}/chats")
            },
            onChanged = { vm.refresh() },
        )
        "notes" -> dev.beacon.feature.surfaces.NotesScreen(
            writeModel = writeVm,
            loaderState = state,
            onBack = onBack,
            onOpenNote = { note -> nav.navigate("note/$note") },
            onNewNote = { nav.navigate("note/new") },
            onChanged = { vm.refresh() },
        )
        "memories" -> dev.beacon.feature.surfaces.MemoriesScreen(
            writeModel = writeVm,
            loaderState = state,
            onBack = onBack,
            onChanged = { vm.refresh() },
        )
        "channels" -> dev.beacon.feature.surfaces.ListSurfaceScreen(
            title = "Channels",
            items = state.channels,
            loading = state.loading,
            error = state.error,
            rowKey = { it.id },
            onBack = onBack,
            onItem = { channel ->
                nav.navigate(
                    "channel/${channel.id}/${android.net.Uri.encode(channel.name)}",
                )
            }
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
                // The file viewer is a real surface since M2-3; mime from the
                // row's meta.content_type (files.py:54) so images render natively.
                nav.navigate(
                    "file/${android.net.Uri.encode(file.id)}/${android.net.Uri.encode(file.filename)}/" +
                        android.net.Uri.encode(file.meta?.content_type ?: "application/octet-stream"),
                )
            },
        ) { file ->
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                androidx.compose.material3.Text(file.filename, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
        }
        "workspace" -> dev.beacon.feature.surfaces.WorkspaceScreen(
            onBack = onBack,
            onPrompt = { prompt ->
                dev.beacon.feature.chat.ShareSeed.pending = promptContent(prompt)
                nav.navigate(ROUTE_CHAT_NEW)
            },
        )
        else -> onBack()
    }
}

private fun promptContent(prompt: dev.beacon.core.api.PromptRow): String = prompt.content.orEmpty()

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

    // M4-d3: the model's ask_user interaction rides the socket; render it as
    // a native dialog on top of the chat surface.
    val inputPrompt by vm.inputPrompt.collectAsStateWithLifecycle()
    inputPrompt?.let { prompt ->
        dev.beacon.feature.chat.InputPromptDialog(
            prompt = prompt,
            onAnswer = vm::answerInput,
            onCancel = vm::cancelInput,
        )
    }

    // M4: a held NON-ask_user tool call — approve runs it, reject answers the
    // model with the rejection (both via the resolve route; tool_approval.py).
    val toolApproval by vm.toolApproval.collectAsStateWithLifecycle()
    toolApproval?.let { request ->
        dev.beacon.feature.chat.ToolApprovalDialog(
            request = request,
            onApprove = vm::approveToolCall,
            onReject = vm::rejectToolCall,
        )
    }

    val surfacesVm: dev.beacon.feature.surfaces.SurfaceViewModel = hiltViewModel()
    // Fold-aware note (agent.md queue): on expanded widths the chats panel will
    // be a PERMANENT two-pane surface instead of the modal drawer — deferred here
    // pending a careful extraction of the surface body; the window-size class
    // plumbing (permanentSidebar) is ready.

    // M4: the interceptor raises session.authExpired on any credentialed 401 —
    // surface a re-auth banner instead of silently-empty screens (2026-11-03:
    // the expired token 401'd EVERYTHING behind /api/config).
    val authExpired by vm.authExpired.collectAsStateWithLifecycle()

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(
                // Scheme-driven (Appearance): the drawer follows the forced mode.
                drawerContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                ChatsDrawer(
                    listViewModel = drawerVm,
                    surfaces = surfacesVm,
                    onOpenChat = { chat -> nav.navigate("chat/${chat.chatId}") { launchSingleTop = true } },
                    // pop+push: launchSingleTop would dedup against the CURRENT
                    // chat/new entry (its VM keeps the old engine state — the
                    // button "did nothing"); a fresh entry every tap instead.
                    onNewChat = {
                        nav.navigate(ROUTE_CHAT_NEW) {
                            popUpTo(ROUTE_CHAT_NEW) { inclusive = true; saveState = false }
                        }
                    },
                    onOpenSettings = { nav.navigate(ROUTE_SETTINGS) },
                    onOpenTerminal = { nav.navigate("terminal") },
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
                    // launchSingleTop = a no-op while on chat/new; reset instead
                    // (bug 2026-11-03: the +New chat button did nothing there).
                    val alreadyNew = nav.currentBackStackEntry?.destination?.route == ROUTE_CHAT_NEW
                    if (alreadyNew) vm.resetForNewChat() else {
                        nav.navigate(ROUTE_CHAT_NEW) { launchSingleTop = true }
                    }
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
                onShareChat = vm::shareChatLink,
                onCopyMarkdown = vm::copyChatAsMarkdown,
                onCopyJson = vm::copyChatAsJson,
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
                // The Files TAB is the terminal files surface now (user request
                // 2026-11-03); the OWUI user-files list keeps its viewer route.
                onOpenSurfaceTab = { key ->
                    if (key == "files") nav.navigate("terminal") else nav.navigate("surface/$key")
                },
                onAnswerInput = vm::answerInput,
                onCancelInput = vm::cancelInput,
            ),
            showModelPicker = pickerOpen,
            onDismissPicker = { pickerOpen = false },
            onPickModel = { id ->
                vm.chooseModel(id)
                pickerOpen = false
            },
            onSetDefault = { id -> vm.chooseModel(id) },
        )
        if (authExpired) {
            SessionExpiredBanner(
                onSignInAgain = {
                    vm.signOutNow()
                    nav.navigate(ROUTE_ONBOARDING) { popUpTo(0) }
                },
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
            )
        }
        }
    }
}

/** M4: dead-credential banner — intercepts the empty-looking surfaces. */
@Composable
private fun SessionExpiredBanner(onSignInAgain: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        color = dev.beacon.ui.theme.CoralDeep,
        modifier = modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    "Session expired",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                androidx.compose.material3.Text(
                    "Your login expired — sign in again to reconnect.",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
            androidx.compose.material3.TextButton(onClick = onSignInAgain) {
                androidx.compose.material3.Text("Sign in", color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}