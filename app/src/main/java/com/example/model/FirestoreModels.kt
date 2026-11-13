package com.example.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val moodId: String = MooMooMood.CLASSIC.id,
    val modelTierId: String = MooMooModelTier.SMART.id,
    val customInstruction: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ConversationRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val moodId: String = MooMooMood.CLASSIC.id,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val updatedAtMillis: Long
        get() = (updatedAt ?: createdAt)?.toDate()?.time ?: System.currentTimeMillis()
}

@IgnoreExtraProperties
data class ChatMessageRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val conversationId: String = "",
    val role: String = "user",
    val text: String = "",
    val imagePath: String? = null,
    val isError: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)
