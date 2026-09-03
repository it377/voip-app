package com.ucmtelnyx.app

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Message(
    val id: String,
    val direction: String, // "inbound" | "outbound"
    val to: String?,
    @Json(name = "from") val fromNumber: String?,
    val text: String?,
    val media: List<String> = emptyList(),
    val status: String,
    val timestamp: String,
)

@JsonClass(generateAdapter = true)
data class ConversationSummary(
    val number: String,
    val lastMessage: Message?,
    val count: Int,
)

@JsonClass(generateAdapter = true)
data class ConversationDetail(
    val number: String,
    val messages: List<Message>,
)

@JsonClass(generateAdapter = true)
data class WsEnvelope(
    val type: String,
    val message: Message?,
)

@JsonClass(generateAdapter = true)
data class Me(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val canMessage: Boolean = false,
    val isAdmin: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class SessionResponse(
    val authenticated: Boolean = false,
    val user: Me? = null,
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    val ok: Boolean = false,
    val user: Me? = null,
    val error: String? = null,
)

/** What the server hands back so the phone can register with no manual setup. */
@JsonClass(generateAdapter = true)
data class SipConfig(
    val domain: String = "",
    val wssUrl: String = "",
    val sipPort: Int = 5061,
    val sipTransport: String = "tls",
    val extension: String = "",
    val password: String = "",
) {
    val isComplete: Boolean
        get() = domain.isNotBlank() && extension.isNotBlank() && password.isNotBlank()
}

@JsonClass(generateAdapter = true)
data class SendMessageResponse(
    val ok: Boolean = false,
    val message: Message? = null,
    val error: String? = null,
)
