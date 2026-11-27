package com.fintrack.app.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.client.engine.HttpClientEngine
import kotlinx.serialization.json.Json

/** Project settings from BuildConfig (local.properties). Both empty = sync not configured. */
data class SupabaseConfig(val url: String, val publishableKey: String) {
    val isConfigured: Boolean get() = url.isNotBlank() && publishableKey.isNotBlank()
}

/** The Supabase client, or null when the build has no Supabase configuration. */
class SupabaseAccess(val client: SupabaseClient?) {
    companion object {
        /**
         * Logging is off: supabase-kt would otherwise log requests, which can contain amounts,
         * descriptions and tokens. [sessionManager] and [codeVerifierCache] null = the library's
         * default app-private storage (SharedPreferences, set up by App Startup); tests pass
         * in-memory ones.
         */
        fun create(
            config: SupabaseConfig,
            engine: HttpClientEngine,
            sessionManager: SessionManager? = null,
            codeVerifierCache: CodeVerifierCache? = null,
            lifecycleCallbacks: Boolean = true,
        ): SupabaseAccess {
            if (!config.isConfigured) return SupabaseAccess(null)
            val client = createSupabaseClient(config.url, config.publishableKey) {
                defaultLogLevel = LogLevel.NONE
                httpEngine = engine
                defaultSerializer = KotlinXSerializer(Json { ignoreUnknownKeys = true; encodeDefaults = true })
                install(Auth) {
                    if (sessionManager != null) this.sessionManager = sessionManager
                    if (codeVerifierCache != null) this.codeVerifierCache = codeVerifierCache
                    enableLifecycleCallbacks = lifecycleCallbacks
                }
                install(Postgrest)
            }
            return SupabaseAccess(client)
        }
    }
}
