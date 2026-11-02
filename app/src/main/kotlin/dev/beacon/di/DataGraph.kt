package dev.beacon.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.beacon.core.chat.ChatStore
import dev.beacon.core.data.ChatRepository
import dev.beacon.core.data.local.BeaconDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataGraph {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BeaconDatabase =
        Room.databaseBuilder(context, BeaconDatabase::class.java, "beacon.db")
            .fallbackToDestructiveMigration(false)
            .build()

    @Provides
    @Singleton
    fun chatStore(repository: ChatRepository): ChatStore = repository
}