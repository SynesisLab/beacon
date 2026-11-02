package dev.beacon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.beacon.feature.home.HomeActions
import dev.beacon.feature.home.HomeScreen
import dev.beacon.feature.home.HomeViewModel
import dev.beacon.feature.onboarding.OnboardingActions
import dev.beacon.feature.onboarding.OnboardingScreen
import dev.beacon.feature.onboarding.OnboardingViewModel
import dev.beacon.feature.settings.SettingsScreen
import dev.beacon.feature.settings.SettingsViewModel
import dev.beacon.ui.theme.BeaconTheme

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
        null -> Surface(Modifier.fillMaxSize()) { // DataStore hydration flash; sub-frame in practice
        }
        else -> key(snapshot.isOnboarded) {
            val nav = rememberNavController()
            NavHost(
                navController = nav,
                startDestination = if (snapshot.isOnboarded) ROUTE_HOME else ROUTE_ONBOARDING,
            ) {
                composable(ROUTE_ONBOARDING) {
                    val vm: OnboardingViewModel = hiltViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    LaunchedEffect(vm) {
                        vm.onFinished = {
                            nav.navigate(ROUTE_HOME) {
                                popUpTo(ROUTE_ONBOARDING) { inclusive = true }
                            }
                        }
                    }
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
                    val state by vm.state.collectAsStateWithLifecycle()
                    HomeScreen(
                        state = state,
                        actions = HomeActions(
                            refresh = vm::refresh,
                            openSettings = { nav.navigate(ROUTE_SETTINGS) },
                            chooseModel = vm::choose,
                        ),
                    )
                }
                composable(ROUTE_SETTINGS) {
                    val vm: SettingsViewModel = hiltViewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    SettingsScreen(
                        state = state,
                        onSignOut = vm::signOut,
                        onClose = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"