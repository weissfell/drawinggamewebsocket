package de.weissbeb.data.models

import de.weissbeb.other.Constants

/**
 * single chat message user sends to room
 */
data class ChatMessage(
    val from: String,
    val roomName: String,
    val message: String,
    val timestamp: Long
) : BaseModel(Constants.TYPE_CHAT_MESSAGE)
