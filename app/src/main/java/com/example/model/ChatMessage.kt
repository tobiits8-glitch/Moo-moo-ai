package com.example.model

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

fun ChatMessageRecord.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    sender = if (role == "user") ChatSender.USER else ChatSender.MOO_MOO,
    text = text,
    imagePath = imagePath,
    isError = isError,
    timestamp = createdAt?.toDate()?.time ?: System.currentTimeMillis()
)
