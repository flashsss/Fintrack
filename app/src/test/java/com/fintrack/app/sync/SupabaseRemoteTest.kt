package com.fintrack.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fintrack.app.data.local.entity.TransactionEntity
import com.fintrack.app.data.local.entity.WalletEntity
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.TransactionSource
import com.fintrack.app.domain.model.TransactionType
import com.fintrack.app.domain.model.WalletType
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant

/** The real supabase-kt client against a mock HTTP engine: requests sent and errors mapped. */
@RunWith(AndroidJUnit4::class)
class SupabaseRemoteTest {

    private val config = SupabaseConfig("https://example.supabase.co", "sb_publishable_test")
    private val requests = mutableListOf<HttpRequestData>()
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("no response set") }

    private val engine = MockEngine { request ->
        requests += request
        handler(request)
    }
    private val access = SupabaseAccess.create(config, engine, MemorySessionManager(), MemoryCodeVerifierCache(), lifecycleCallbacks = false)
    private val auth = AuthRepository(access)
    private val remote = SupabaseRemoteSource(access)

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val userId = "6f1c1b7e-0000-4000-8000-000000000001"
    private val session = """{"access_token":"access-1","token_type":"bearer","expires_in":3600,"refresh_token":"refresh-1",
        "user":{"id":"$userId","aud":"authenticated","email":"saya@example.com"}}"""

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, json)

    private suspend fun signIn() {
        handler = { ok(session) }
        assertEquals(SignInResult.SUCCESS, auth.signIn("saya@example.com", "rahasia"))
    }

    private val wallet = WalletEntity("11111111-1111-1111-1111-111111111111", "BCA", WalletType.BANK, 0, "IDR", true, 1, 2, null, SyncStatus.PENDING)

    @Test
    fun `sign in posts the password grant with the publishable key and exposes the user`() = runBlocking {
        signIn()
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/auth/v1/token", request.url.encodedPath)
        assertEquals("password", request.url.parameters["grant_type"])
        assertEquals("sb_publishable_test", request.headers["apikey"])
        val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
        assertEquals("saya@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals(AuthState.SignedIn(userId, "saya@example.com"), auth.state.first { it is AuthState.SignedIn })
        assertEquals(userId, auth.currentUserId())
    }

    @Test
    fun `wrong password and no network are told apart`() = runBlocking {
        handler = { respond("""{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""", HttpStatusCode.BadRequest, json) }
        assertEquals(SignInResult.INVALID_CREDENTIALS, auth.signIn("saya@example.com", "salah"))
        handler = { throw IOException("offline") }
        assertEquals(SignInResult.NETWORK, auth.signIn("saya@example.com", "rahasia"))
        assertEquals(null, auth.currentUserId())
    }

    @Test
    fun `nothing is sent before sign in`() = runBlocking {
        try {
            remote.upsert(SyncTable.WALLETS, listOf(SyncRecord.Wallet(wallet)))
            fail("expected Unauthorized")
        } catch (expected: RemoteSyncException.Unauthorized) {
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `upsert merges on (user_id, id) with the user's token and never sends user_id`() = runBlocking {
        signIn()
        handler = { respond("", HttpStatusCode.Created, json) }
        remote.upsert(SyncTable.WALLETS, listOf(SyncRecord.Wallet(wallet)))
        val request = requests.last()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/rest/v1/wallets", request.url.encodedPath)
        assertEquals("user_id,id", request.url.parameters["on_conflict"])
        assertTrue(request.headers.getAll("Prefer").orEmpty().joinToString().contains("resolution=merge-duplicates"))
        assertEquals("Bearer access-1", request.headers[HttpHeaders.Authorization])
        val row = Json.parseToJsonElement(String(request.body.toByteArray())).jsonArray.single().jsonObject
        assertEquals("BCA", row["name"]!!.jsonPrimitive.content)
        assertTrue(row.keys.none { it == "user_id" || it == "server_updated_at" })
    }

    @Test
    fun `pull filters by cursor, orders stably and pages`() = runBlocking {
        signIn()
        handler = {
            ok("""[{"user_id":"$userId","id":"t-1","wallet_id":"w-1","destination_wallet_id":null,"category_id":"c-1",
                "type":"EXPENSE","amount":25000,"transaction_date":5,"description":"Kopi","merchant":null,"source":"MANUAL",
                "import_batch_id":null,"external_id":null,"created_at":1,"updated_at":2,"deleted_at":null,
                "server_updated_at":"2026-11-27T03:51:12.123456+00:00"}]""")
        }
        val since = Instant.parse("2026-11-27T03:00:00Z")
        val rows = remote.pull(SyncTable.TRANSACTIONS, since, offset = 500, limit = 500)
        val request = requests.last()
        assertEquals("/rest/v1/transactions", request.url.encodedPath)
        assertEquals("gte.2026-11-27T03:00:00Z", request.url.parameters["server_updated_at"])
        assertEquals("server_updated_at.asc.nullslast,id.asc.nullslast", request.url.parameters["order"]) // both columns are NOT NULL
        val paging = (request.url.parameters["offset"] to request.url.parameters["limit"])
        val rangeHeader = request.headers["Range"]
        assertTrue("paging $paging / $rangeHeader", paging == ("500" to "500") || rangeHeader == "500-999")

        val row = rows.single()
        assertEquals(Instant.parse("2026-11-27T03:51:12.123456Z"), row.serverUpdatedAt)
        val entity = (row.record as SyncRecord.Transaction).entity
        assertEquals(TransactionEntity("t-1", "w-1", null, "c-1", TransactionType.EXPENSE, 25_000, 5, "Kopi", null,
            TransactionSource.MANUAL, null, null, 1, 2, null, SyncStatus.SYNCED), entity)
    }

    @Test
    fun `server and network failures map to retry, re-login or refusal`() = runBlocking {
        signIn()
        val record = listOf(SyncRecord.Wallet(wallet))
        suspend fun outcome(response: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): String {
            handler = response
            return try {
                remote.upsert(SyncTable.WALLETS, record)
                "ok"
            } catch (error: RemoteSyncException) {
                error::class.simpleName!!
            }
        }
        val pgError = """{"code":"23514","message":"new row violates check constraint","details":null,"hint":null}"""
        assertEquals("Rejected", outcome { respond(pgError, HttpStatusCode.BadRequest, json) })
        assertEquals("Rejected", outcome { respond(pgError.replace("23514", "42501"), HttpStatusCode.Forbidden, json) })
        assertEquals("Unavailable", outcome { respond("""{"message":"down"}""", HttpStatusCode.ServiceUnavailable, json) })
        assertEquals("Unavailable", outcome { throw IOException("offline") })
        assertEquals("Unauthorized", outcome { respond("""{"code":"PGRST301","message":"JWT expired"}""", HttpStatusCode.Unauthorized, json) })
    }

    @Test
    fun `sign out clears the session even when the server cannot be reached`() = runBlocking {
        signIn()
        handler = { throw IOException("offline") }
        auth.signOut()
        assertEquals(null, auth.currentUserId())
        assertEquals(AuthState.SignedOut, auth.state.first { it !is AuthState.Loading })
    }
}
