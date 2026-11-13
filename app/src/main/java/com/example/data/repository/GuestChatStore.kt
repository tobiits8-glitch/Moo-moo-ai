package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.model.ChatMessageRecord
import com.example.model.ConversationRecord
import com.example.model.MooMooMood
import com.google.firebase.Timestamp
import java.io.File
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val GUEST_TAG = "GuestChatStore"
private const val GUEST_USER_ID = "guest_local"
private const val GUEST_FILE_NAME = "moomoo_guest_chats.json"

/**
 * Local-only persistence for Guest mode so users can chat with Moo Moo AI
 * without signing in or touching Cloud Firestore.
 */
class GuestChatStore(private val appContext: Context) {
    private val mutex = Mutex()
    private val conversationsState = MutableStateFlow<List<ConversationRecord>>(emptyList())
    private val messagesState = MutableStateFlow<Map<String, List<ChatMessageRecord>>>(emptyMap())
    private var loaded = false

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) {
                loadFromDiskLocked()
            }
            loaded = true
        }
    }

    private fun loadFromDiskLocked() {
        val file = File(appContext.filesDir, GUEST_FILE_NAME)
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val convArray = root.optJSONArray("conversations") ?: JSONArray()
            val loadedConvs = mutableListOf<ConversationRecord>()
            for (i in 0 until convArray.length()) {
                val obj = convArray.getJSONObject(i)
                val createdMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                val updatedMs = obj.optLong("updatedAtMs", createdMs)
                loadedConvs.add(
                    ConversationRecord(
                        id = obj.getString("id"),
                        userId = GUEST_USER_ID,
                        title = obj.getString("title"),
                        moodId = obj.optString("moodId", MooMooMood.CLASSIC.id),
                        createdAt = Timestamp(Date(createdMs)),
                        updatedAt = Timestamp(Date(updatedMs))
                    )
                )
            }

            val msgMapObj = root.optJSONObject("messages") ?: JSONObject()
            val loadedMsgs = mutableMapOf<String, List<ChatMessageRecord>>()
            val keys = msgMapObj.keys()
            while (keys.hasNext()) {
                val convId = keys.next()
                val arr = msgMapObj.getJSONArray(convId)
                val list = mutableListOf<ChatMessageRecord>()
                for (j in 0 until arr.length()) {
                    val m = arr.getJSONObject(j)
                    val createdMs = m.optLong("createdAtMs", System.currentTimeMillis())
                    list.add(
                        ChatMessageRecord(
                            id = m.getString("id"),
                            userId = GUEST_USER_ID,
                            conversationId = convId,
                            role = m.getString("role"),
                            text = m.getString("text"),
                            imagePath = m.optString("imagePath", "").takeIf { it.isNotEmpty() },
                            isError = m.optBoolean("isError", false),
                            createdAt = Timestamp(Date(createdMs))
                        )
                    )
                }
                loadedMsgs[convId] = list
            }

            conversationsState.value = loadedConvs.sortedByDescending { it.updatedAtMillis }
            messagesState.value = loadedMsgs
        }.onFailure { error ->
            Log.w(GUEST_TAG, "Failed to read guest chats; starting fresh", error)
        }
    }

    private fun saveToDiskLocked() {
        runCatching {
            val root = JSONObject()
            val convArray = JSONArray()
            conversationsState.value.forEach { conv ->
                convArray.put(
                    JSONObject().apply {
                        put("id", conv.id)
                        put("title", conv.title)
                        put("moodId", conv.moodId)
                        put("createdAtMs", conv.createdAt?.toDate()?.time ?: System.currentTimeMillis())
                        put("updatedAtMs", conv.updatedAtMillis)
                    }
                )
            }
            root.put("conversations", convArray)

            val msgMapObj = JSONObject()
            messagesState.value.forEach { (convId, msgs) ->
                val arr = JSONArray()
                msgs.forEach { msg ->
                    arr.put(
                        JSONObject().apply {
                            put("id", msg.id)
                            put("role", msg.role)
                            put("text", msg.text)
                            if (!msg.imagePath.isNullOrBlank()) {
                                put("imagePath", msg.imagePath)
                            }
                            put("isError", msg.isError)
                            put("createdAtMs", msg.createdAt?.toDate()?.time ?: System.currentTimeMillis())
                        }
                    )
                }
                msgMapObj.put(convId, arr)
            }
            root.put("messages", msgMapObj)

            File(appContext.filesDir, GUEST_FILE_NAME).writeText(root.toString())
        }.onFailure { error ->
            Log.w(GUEST_TAG, "Failed to persist guest chats", error)
        }
    }

    fun observeConversations(): Flow<List<ConversationRecord>> =
        conversationsState.map { list ->
            list.sortedByDescending { it.updatedAtMillis }
        }

    fun observeMessages(conversationId: String): Flow<List<ChatMessageRecord>> =
        messagesState.map { map ->
            map[conversationId].orEmpty()
        }

    suspend fun initIfNeeded() {
        ensureLoaded()
    }

    suspend fun getConversation(conversationId: String): ConversationRecord? {
        ensureLoaded()
        return conversationsState.value.firstOrNull { it.id == conversationId }
    }

    suspend fun getMessagesOnce(conversationId: String): List<ChatMessageRecord> {
        ensureLoaded()
        return messagesState.value[conversationId].orEmpty()
    }

    suspend fun createConversation(title: String, mood: MooMooMood): ConversationRecord {
        ensureLoaded()
        return mutex.withLock {
            val now = Timestamp.now()
            val conv = ConversationRecord(
                id = UUID.randomUUID().toString(),
                userId = GUEST_USER_ID,
                title = title.trim().ifBlank { "New Chat" }.take(120),
                moodId = mood.id,
                createdAt = now,
                updatedAt = now
            )
            conversationsState.value = listOf(conv) + conversationsState.value
            withContext(Dispatchers.IO) { saveToDiskLocked() }
            conv
        }
    }

    suspend fun updateConversationMood(conversationId: String, mood: MooMooMood) {
        ensureLoaded()
        mutex.withLock {
            val now = Timestamp.now()
            conversationsState.value = conversationsState.value.map { conv ->
                if (conv.id == conversationId) conv.copy(moodId = mood.id, updatedAt = now) else conv
            }.sortedByDescending { it.updatedAtMillis }
            withContext(Dispatchers.IO) { saveToDiskLocked() }
        }
    }

    suspend fun updateConversationTitle(conversationId: String, title: String) {
        ensureLoaded()
        mutex.withLock {
            val now = Timestamp.now()
            val safeTitle = title.trim().ifBlank { "New Chat" }.take(120)
            conversationsState.value = conversationsState.value.map { conv ->
                if (conv.id == conversationId) conv.copy(title = safeTitle, updatedAt = now) else conv
            }.sortedByDescending { it.updatedAtMillis }
            withContext(Dispatchers.IO) { saveToDiskLocked() }
        }
    }

    suspend fun deleteConversation(conversationId: String) {
        ensureLoaded()
        mutex.withLock {
            val removedMsgs = messagesState.value[conversationId].orEmpty()
            withContext(Dispatchers.IO) {
                removedMsgs.mapNotNull { it.imagePath }.forEach { path ->
                    runCatching { File(path).delete() }
                }
            }
            conversationsState.value = conversationsState.value.filterNot { it.id == conversationId }
            messagesState.value = messagesState.value - conversationId
            withContext(Dispatchers.IO) { saveToDiskLocked() }
        }
    }

    suspend fun saveMessage(
        conversationId: String,
        role: String,
        text: String,
        imagePath: String?,
        isError: Boolean
    ): ChatMessageRecord {
        ensureLoaded()
        return mutex.withLock {
            val now = Timestamp.now()
            val record = ChatMessageRecord(
                id = UUID.randomUUID().toString(),
                userId = GUEST_USER_ID,
                conversationId = conversationId,
                role = role,
                text = text.take(64_000),
                imagePath = imagePath?.takeIf { it.isNotBlank() }?.take(500),
                isError = isError,
                createdAt = now
            )
            val currentMap = messagesState.value.toMutableMap()
            currentMap[conversationId] = currentMap[conversationId].orEmpty() + record
            messagesState.value = currentMap

            conversationsState.value = conversationsState.value.map { conv ->
                if (conv.id == conversationId) conv.copy(updatedAt = now) else conv
            }.sortedByDescending { it.updatedAtMillis }

            withContext(Dispatchers.IO) { saveToDiskLocked() }
            record
        }
    }

    suspend fun deleteMessage(conversationId: String, messageId: String) {
        ensureLoaded()
        mutex.withLock {
            val currentMap = messagesState.value.toMutableMap()
            val updated = currentMap[conversationId].orEmpty().filterNot { it.id == messageId }
            currentMap[conversationId] = updated
            messagesState.value = currentMap
            withContext(Dispatchers.IO) { saveToDiskLocked() }
        }
    }
}
