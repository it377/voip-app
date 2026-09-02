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
data class SendMessageResponse(
    val ok: Boolean = false,
    val message: Message? = null,
    val error: String? = null,
)
