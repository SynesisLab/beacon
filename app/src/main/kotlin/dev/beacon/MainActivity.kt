package dev.beacon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.beacon.feature.chat.ChatActions
import dev.beacon.feature.chat.ChatScreen
import dev.beacon.feature.chat.ChatViewModel
import dev.beacon.feature.home.ChatListActions
import dev.beacon.feature.home.HomeScreen
import dev.beacon.feature.home.HomeViewModel
import dev.beacon.feature.onboarding.OnboardingActions
import dev.beacon.feature.onboarding.OnboardingScreen
import dev.beacon.feature.onboarding.OnboardingViewModel
import dev.beacon.feature.settings.SettingsScreen
import dev.beacon.feature.settings.SettingsViewModel
import dev.beacon.ui.theme.BeaconTheme

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_CHAT = "chat/{chatId}"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeaconTheme {
                BeaconNavHost()
            }
        }
    }
}

@Composable
private fun BeaconNavHost() {
    val main: MainViewModel = hiltViewModel()
    val settings by main.settings.collectAsStateWithLifecycle()

    when (val snapshot = settings) {
        null -> Surface(Modifier.fillMaxSize()) { } // DataStore hydration flash
        else -> key(snapshot.isOnboarded) {
            val nav = rememberNavController()
            NavHost(
                navController = nav,
                startDestination = if (snapshot.isOnboarded) ROUTE_HOME else ROUTE_ONBOARDING,
            ) {
                composable(ROUTE_ONBOARDING) {
                    val vm: OnboardingViewModel = hiltViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    LaunchedEffectOnce { vm.onFinished = { navPopToHome(nav) } }
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
                composable(ROUTE_HOME) {
                    val vm: HomeViewModel = hiltViewModel()
                    HomeScreen(
                        state = vm.ui.collectAsStateWithLifecycle().value,
                        actions = ChatListActions(
                            onNewChat = { nav.navigate("chat/new") },
                            onOpenChat = { chat -> nav.navigate("chat/${chat.chatId}") },
                            onRefresh = vm::refresh,
                            onOpenSettings = { nav.navigate(ROUTE_SETTINGS) },
                            onOpenModels = { nav.navigate(ROUTE_SETTINGS) }, // model workspace M4
                            onTogglePin = vm::togglePin,
                            onDelete = vm::delete,
                            onLoadNextPage = vm::loadNextPage,
                        ),
                    )
                }
                composable(
                    route = ROUTE_CHAT,
                    arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
                ) {
                    val vm: ChatViewModel = hiltViewModel()
                    var pickerOpen by rememberSaveable { mutableStateOf(false) }
                    val context = androidx.compose.ui.platform.LocalContext.current

                    // M3-d: the doc picker covers all types (incl. images).
                    val docPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        if (uri != null) {
                            vm.attach(
                                name = dev.beacon.core.data.contentQueryName(context, uri),
                                mime = "",
                                uri = uri,
                            )
                        }
                    }

                    ChatScreen(
                        state = vm.ui.collectAsStateWithLifecycle().value,
                        actions = ChatActions(
                            onDraft = vm::setDraft,
                            onSend = vm::send,
                            onStop = vm::stop,
                            onBack = { nav.popBackStack() },
                            onOpenModelPicker = { pickerOpen = true },
                            onAttach = {
                                docPicker.launch(arrayOf("*/*"))
                            },
                            onRemoveAttachment = vm::removeAttachment,
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
                    )
                }
                composable(ROUTE_SETTINGS) {
                    val vm: SettingsViewModel = hiltViewModel()
                    SettingsScreen(
                        state = vm.state.collectAsStateWithLifecycle().value,
                        onSignOut = vm::signOut,
                        onClose = { nav.popBackStack() },
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

private fun navPopToHome(nav: androidx.navigation.NavController) {
    nav.navigate(ROUTE_HOME) {
        popUpTo(ROUTE_ONBOARDING) { inclusive = true }
    }
}