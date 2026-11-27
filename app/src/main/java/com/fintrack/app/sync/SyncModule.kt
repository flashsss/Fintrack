package com.fintrack.app.sync

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.WorkManager
import com.fintrack.app.BuildConfig
import com.fintrack.app.data.local.dao.SyncDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.jan.supabase.auth.SettingsCodeVerifierCache
import io.github.jan.supabase.auth.SettingsSessionManager
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

private val Context.syncDataStore by preferencesDataStore(name = "sync_state")

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    fun provideConfig(): SupabaseConfig = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)

    /**
     * Session tokens live in app-private SharedPreferences (not backed up: allowBackup=false).
     * Passed explicitly instead of relying on the library's App Startup initializer.
     */
    @Provides
    @Singleton
    fun provideAccess(config: SupabaseConfig, @ApplicationContext context: Context): SupabaseAccess = createAccess(config, context)

    /** [lifecycleCallbacks] (session refresh on app foreground) is only turned off by tests. */
    fun createAccess(config: SupabaseConfig, context: Context, lifecycleCallbacks: Boolean = true): SupabaseAccess {
        val settings = SharedPreferencesSettings(context.getSharedPreferences("supabase_auth", Context.MODE_PRIVATE))
        return SupabaseAccess.create(
            config, OkHttp.create(), SettingsSessionManager(settings), SettingsCodeVerifierCache(settings), lifecycleCallbacks,
        )
    }

    @Provides
    @Singleton
    fun provideStateStore(@ApplicationContext context: Context): SyncStateStore = SyncStateStore(context.syncDataStore)

    @Provides
    fun provideCursorStore(store: SyncStateStore): SyncCursorStore = store

    @Provides
    fun provideSessionUser(auth: AuthRepository): SessionUser = auth

    @Provides
    fun provideRemote(remote: SupabaseRemoteSource): RemoteSyncSource = remote

    @Provides
    @Singleton
    fun provideScheduler(@ApplicationContext context: Context): SyncScheduler = SyncScheduler(WorkManager.getInstance(context))

    @Provides
    @Singleton
    fun provideTrigger(auth: AuthRepository, syncDao: SyncDao, scheduler: SyncScheduler): SyncTrigger =
        SyncTrigger(auth.state, syncDao.observePendingCount(), scheduler)
}
