package com.example.model

import com.example.data.local.MessageEntity

enum class ChatSender {
    USER,
    MOO_MOO
}

data class ChatMessage(
    val id: String,
    val sender: ChatSender,
    val text: String,
    val imagePath: String? = null,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isFromUser: Boolean
        get() = sender == ChatSender.USER
}

fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    sender = if (role == "user") ChatSender.USER else ChatSender.MOO_MOO,
    text = text,
    imagePath = imagePath,
    isError = isError,
    timestamp = timestamp
)
