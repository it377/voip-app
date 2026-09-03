package com.ucmtelnyx.app

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap

private class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host] ?: emptyList()
}

class BackendException(message: String) : Exception(message)

/**
 * Talks to the same Flask backend the web app uses (login, messages, live
 * WebSocket push). `baseUrl` must be the server's public URL, e.g.
 * "https://voip.example.com" - unlike the browser app this isn't same-origin,
 * so it has to be entered once in Settings.
 */
class BackendClient(private var baseUrl: String) {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val cookieJar = MemoryCookieJar()
    private val client = OkHttpClient.Builder().cookieJar(cookieJar).build()

    private val jsonMedia = "application/json".toMediaType()

    fun updateBaseUrl(url: String) {
        baseUrl = url.trimEnd('/')
    }

    private fun url(path: String) = "$baseUrl$path"

    suspend fun login(username: String, password: String): Result<Me> = withContext(Dispatchers.IO) {
        try {
            val body = moshi.adapter(Map::class.java)
                .toJson(mapOf("username" to username, "password" to password))
            val request = Request.Builder()
                .url(url("/api/login"))
                .post(body.toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { response ->
                val json = response.body?.string() ?: "{}"
                val parsed = moshi.adapter(LoginResponse::class.java).fromJson(json)
                if (!response.isSuccessful || parsed?.user == null) {
                    Result.failure(
                        BackendException(parsed?.error ?: "Sign in failed (${response.code})")
                    )
                } else {
                    Result.success(parsed.user)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Returns the signed-in user, or null if this device has no valid session. */
    suspend fun session(): Me? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url("/api/session")).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = response.body?.string() ?: return@withContext null
                val parsed = moshi.adapter(SessionResponse::class.java).fromJson(json)
                if (parsed?.authenticated == true) parsed.user else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The extension an admin assigned to this account, merged with the shared PBX
     * settings - this is what replaces typing SIP details into the app.
     */
    suspend fun sipConfig(): Result<SipConfig> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url("/api/sip-config")).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        BackendException("Could not load your extension (${response.code})")
                    )
                }
                val json = response.body?.string() ?: "{}"
                val config = moshi.adapter(SipConfig::class.java).fromJson(json)
                    ?: return@withContext Result.failure(BackendException("Malformed SIP config"))
                Result.success(config)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url("/api/logout"))
                .post("".toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().close()
        }
        Unit
    }

    suspend fun listConversations(): List<ConversationSummary> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url("/api/messages")).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw BackendException("Failed to load messages (${response.code})")
            val json = response.body?.string() ?: "[]"
            val type = Types.newParameterizedType(List::class.java, ConversationSummary::class.java)
            moshi.adapter<List<ConversationSummary>>(type).fromJson(json) ?: emptyList()
        }
    }

    suspend fun getConversation(number: String): ConversationDetail = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(number, "UTF-8")
        val request = Request.Builder().url(url("/api/messages/$encoded")).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw BackendException("Failed to load conversation (${response.code})")
            val json = response.body?.string() ?: "{}"
            moshi.adapter(ConversationDetail::class.java).fromJson(json)
                ?: ConversationDetail(number, emptyList())
        }
    }

    suspend fun sendMessage(to: String, text: String): Result<Message> = withContext(Dispatchers.IO) {
        try {
            val body = moshi.adapter(Map::class.java).toJson(mapOf("to" to to, "text" to text))
            val request = Request.Builder()
                .url(url("/api/messages/send"))
                .post(body.toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { response ->
                val json = response.body?.string() ?: "{}"
                val parsed = moshi.adapter(SendMessageResponse::class.java).fromJson(json)
                if (!response.isSuccessful || parsed?.message == null) {
                    return@withContext Result.failure(
                        BackendException(parsed?.error ?: "Send failed (${response.code})")
                    )
                }
                Result.success(parsed.message)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Opens the live-updates WebSocket. Call close() on the returned WebSocket when done. */
    fun connectWebSocket(onMessage: (Message) -> Unit, onFailure: (Throwable) -> Unit): WebSocket {
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/ws"
        val request = Request.Builder().url(wsUrl).build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val envelope = moshi.adapter(WsEnvelope::class.java).fromJson(text) ?: return
                if (envelope.type == "message" && envelope.message != null) {
                    onMessage(envelope.message)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                onFailure(t)
            }
        })
    }
}
