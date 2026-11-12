package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.example.data.local.ChatDao
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.model.MooMooModelTier
import com.example.model.MooMooMood
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "MooMooChatRepo"
private const val STREAM_INACTIVITY_TIMEOUT_MS = 15_000L
private const val FALLBACK_STREAM_INACTIVITY_TIMEOUT_MS = 15_000L
private const val STREAM_TOTAL_TIMEOUT_MS = 60_000L
private const val UNARY_TIMEOUT_MS = 20_000L
private const val MAX_IMAGE_DIM = 1280

class MooMooChatRepository(
    private val context: Context,
    private val chatDao: ChatDao
) {
    private val firebaseAi by lazy { Firebase.ai }
    private val modelCache = ConcurrentHashMap<Triple<String, String, String>, GenerativeModel>()

    private val titleModel: GenerativeModel by lazy {
        firebaseAi.generativeModel(
            modelName = MooMooModelTier.FAST.modelName,
            generationConfig = generationConfig {
                temperature = 0.3f
                maxOutputTokens = 24
            }
        )
    }

    private fun buildSystemInstruction(mood: MooMooMood, customInstruction: String): Content {
        val trimmedCustom = customInstruction.trim()
        val instructionText = buildString {
            append(mood.systemPrompt)
            if (trimmedCustom.isNotEmpty()) {
                append("\n\nAdditional user preferences for Moo Moo:\n")
                append(trimmedCustom)
            }
        }
        return content { text(instructionText) }
    }

    private fun getOrCreateModel(
        tier: MooMooModelTier,
        mood: MooMooMood,
        customInstruction: String
    ): GenerativeModel {
        val cacheKey = Triple(tier.modelName, mood.id, customInstruction.trim())
        return modelCache.getOrPut(cacheKey) {
            firebaseAi.generativeModel(
                modelName = tier.modelName,
                generationConfig = generationConfig {
                    temperature = if (mood == MooMooMood.CODING) 0.35f else 0.75f
                },
                systemInstruction = buildSystemInstruction(mood, customInstruction)
            )
        }
    }

    fun observeConversations(): Flow<List<ConversationEntity>> =
        chatDao.observeConversations()

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        chatDao.observeMessages(conversationId)

    suspend fun getConversation(conversationId: String): ConversationEntity? =
        chatDao.getConversation(conversationId)

    suspend fun createConversation(title: String, mood: MooMooMood): ConversationEntity {
        val now = System.currentTimeMillis()
        val entity = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            moodId = mood.id,
            createdAt = now,
            updatedAt = now
        )
        chatDao.upsertConversation(entity)
        return entity
    }

    suspend fun updateConversationMood(conversationId: String, mood: MooMooMood) {
        chatDao.updateConversationMood(conversationId, mood.id, System.currentTimeMillis())
    }

    suspend fun deleteConversation(conversationId: String) {
        val messages = chatDao.getMessagesOnce(conversationId)
        withContext(Dispatchers.IO) {
            messages.mapNotNull { it.imagePath }.forEach { path ->
                runCatching { File(path).delete() }
            }
        }
        chatDao.deleteConversation(conversationId)
    }

    suspend fun saveMessage(
        conversationId: String,
        role: String,
        text: String,
        imagePath: String? = null,
        isError: Boolean = false
    ): MessageEntity {
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role,
            text = text,
            imagePath = imagePath,
            isError = isError,
            timestamp = now
        )
        chatDao.insertMessage(message)
        chatDao.touchConversation(conversationId, now)
        return message
    }

    suspend fun deleteMessage(messageId: String) {
        chatDao.deleteMessage(messageId)
    }

    suspend fun persistAttachment(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
            val originalBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@withContext null

            val orientedBitmap = rotateBitmapIfNeeded(uri, originalBitmap)
            val scaledBitmap = scaleBitmapDown(orientedBitmap, MAX_IMAGE_DIM)
            if (scaledBitmap !== orientedBitmap) {
                orientedBitmap.recycle()
            }
            if (orientedBitmap !== originalBitmap && !originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }

            val outFile = File(attachmentsDir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(outFile).use { fos ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }
            scaledBitmap.recycle()
            outFile.absolutePath
        }.onFailure { e ->
            Log.e(TAG, "Failed to persist attached photo", e)
        }.getOrNull()
    }

    private fun rotateBitmapIfNeeded(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private suspend fun loadBitmap(path: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            BitmapFactory.decodeFile(path)
        }.onFailure { e ->
            Log.e(TAG, "Failed to decode bitmap at $path", e)
        }.getOrNull()
    }

    private fun sanitizePriorHistory(priorMessages: List<MessageEntity>): List<MessageEntity> {
        val alternating = mutableListOf<MessageEntity>()
        var expectedRole = "user"
        for (msg in priorMessages.takeLast(20)) {
            if (msg.role == expectedRole) {
                alternating.add(msg)
                expectedRole = if (expectedRole == "user") "model" else "user"
            } else if (msg.role == "user" && alternating.isNotEmpty() && alternating.last().role == "user") {
                alternating[alternating.lastIndex] = msg
            }
        }
        if (alternating.lastOrNull()?.role == "user") {
            alternating.removeAt(alternating.lastIndex)
        }
        return alternating
    }

    private fun logAppCheckDiagnosticAsync() {
        runCatching {
            FirebaseAppCheck.getInstance().getAppCheckToken(false)
                .addOnSuccessListener { result ->
                    Log.d(TAG, "App Check token status: len=${result.token.length}")
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "App Check token async diagnostic warning", error)
                }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun tryStream(
        model: GenerativeModel,
        historyContents: List<Content>,
        userPromptContent: Content,
        inactivityTimeoutMs: Long,
        onChunk: (String) -> Unit
    ): String? {
        val accumulated = StringBuilder()
        var loggedFirstChunk = false
        val startMs = System.currentTimeMillis()
        val responseFlow = if (historyContents.isEmpty()) {
            model.generateContentStream(userPromptContent)
        } else {
            model.startChat(history = historyContents).sendMessageStream(userPromptContent)
        }
        return try {
            withTimeout(STREAM_TOTAL_TIMEOUT_MS) {
                responseFlow
                    .mapNotNull { chunk -> chunk.text?.takeIf { it.isNotEmpty() } }
                    .timeout(inactivityTimeoutMs.milliseconds)
                    .collect { piece ->
                        if (!loggedFirstChunk) {
                            loggedFirstChunk = true
                            Log.d(TAG, "First chunk arrived in ${System.currentTimeMillis() - startMs} ms")
                        }
                        accumulated.append(piece)
                        onChunk(accumulated.toString())
                    }
            }
            accumulated.toString().trim().takeIf { it.isNotEmpty() }
        } catch (e: TimeoutCancellationException) {
            val partial = accumulated.toString().trim()
            if (partial.isNotEmpty()) {
                Log.w(TAG, "Stream timed out after partial output; keeping streamed response.")
                partial
            } else {
                Log.w(TAG, "Stream timed out before first token after ${inactivityTimeoutMs}ms.")
                null
            }
        }
    }

    suspend fun streamReply(
        conversationId: String,
        modelTier: MooMooModelTier,
        mood: MooMooMood,
        customInstruction: String,
        onChunk: (String) -> Unit
    ): String {
        val allMessages = chatDao.getMessagesOnce(conversationId)
            .filter { !it.isError }

        val lastUserIndex = allMessages.indexOfLast { it.role == "user" }
        require(lastUserIndex >= 0) { "No user message found to reply to." }

        val priorMessages = sanitizePriorHistory(allMessages.take(lastUserIndex))
        val latestUserMessage = allMessages[lastUserIndex]

        val historyContents = mutableListOf<Content>()
        for (msg in priorMessages) {
            val bitmap = msg.imagePath?.let { loadBitmap(it) }
            val built = content(role = msg.role) {
                if (bitmap != null) {
                    image(bitmap)
                }
                if (msg.text.isNotBlank()) {
                    text(msg.text)
                } else if (bitmap != null) {
                    text("See attached image.")
                }
            }
            historyContents.add(built)
        }

        val latestBitmap = latestUserMessage.imagePath?.let { loadBitmap(it) }
        val userPromptContent = content(role = "user") {
            if (latestBitmap != null) {
                image(latestBitmap)
            }
            if (latestUserMessage.text.isNotBlank()) {
                text(latestUserMessage.text)
            } else {
                text("What do you see in this image? Help me out, Moo Moo.")
            }
        }

        logAppCheckDiagnosticAsync()

        val primaryModel = getOrCreateModel(modelTier, mood, customInstruction)
        Log.d(TAG, "Starting Moo Moo reply via ${modelTier.modelName} (history=${historyContents.size})")

        try {
            // 1. Primary streaming attempt
            val primaryStream = tryStream(
                model = primaryModel,
                historyContents = historyContents,
                userPromptContent = userPromptContent,
                inactivityTimeoutMs = STREAM_INACTIVITY_TIMEOUT_MS,
                onChunk = onChunk
            )
            if (primaryStream != null) return primaryStream

            // 2. If SMART streaming timed out, try FAST model streaming
            val allTurnContents = historyContents + userPromptContent
            if (modelTier != MooMooModelTier.FAST) {
                val fastModel = getOrCreateModel(MooMooModelTier.FAST, mood, customInstruction)
                Log.w(TAG, "Falling back to ${MooMooModelTier.FAST.modelName} streaming")
                val fastStream = tryStream(
                    model = fastModel,
                    historyContents = historyContents,
                    userPromptContent = userPromptContent,
                    inactivityTimeoutMs = FALLBACK_STREAM_INACTIVITY_TIMEOUT_MS,
                    onChunk = onChunk
                )
                if (fastStream != null) return fastStream
            }

            // 3. Unary non-streaming fallback (in case SSE stream buffering stalled)
            val fallbackModel = getOrCreateModel(MooMooModelTier.FAST, mood, customInstruction)
            Log.w(TAG, "Falling back to unary generateContent")
            val unaryText = try {
                withTimeout(UNARY_TIMEOUT_MS) {
                    fallbackModel.generateContent(allTurnContents).text?.trim().orEmpty()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Unary fallback also timed out", e)
                throw IllegalStateException("Moo Moo couldn't reach Gemini (network request timed out). Please check your connection and tap Try again.")
            }

            if (unaryText.isEmpty()) {
                throw IllegalStateException("Moo Moo returned an empty response. Please try again.")
            }
            onChunk(unaryText)
            return unaryText
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI chat failed", e)
            throw e
        }
    }

    suspend fun generateConversationTitle(
        conversationId: String,
        firstUserMessage: String
    ) {
        val fallback = firstUserMessage
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.take(36)
            ?.ifBlank { "New Chat" }
            ?: "New Chat"

        val title = try {
            withTimeout(10_000L) {
                val prompt = "Summarize this chat opener into a crisp 2 to 5 word title without quotes or trailing punctuation:\n\n$firstUserMessage"
                val response = titleModel.generateContent(prompt)
                response.text
                    ?.trim()
                    ?.replace("\"", "")
                    ?.take(42)
                    ?.ifBlank { fallback }
                    ?: fallback
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Using fallback conversation title", e)
            fallback
        }

        chatDao.updateConversationTitle(conversationId, title, System.currentTimeMillis())
    }
}
