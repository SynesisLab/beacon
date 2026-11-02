package dev.beacon.di

import android.content.Context
import androidx.datastore.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.beacon.core.data.ServerSettings
import dev.beacon.core.data.createSettingsDataStore
import dev.beacon.core.network.AuthInterceptor
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkGraph {
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * One client for REST + SSE (agent.md §3). Same-origin credential attachment
     * lives in [AuthInterceptor]. trustUserCa (opt-in, agent.md §5.5) re-applies
     * on process restart in M0 (flagged in docs/decisions.md ADR-0004).
     */
    @Provides
    @Singleton
    fun okHttp(auth: AuthInterceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(auth)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<ServerSettings> =
        createSettingsDataStore(context)
}