package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.example.R
import com.example.ai.configureAppCheck
import com.example.ai.ensureFirebaseInitialized
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.example.model.ChatMessageRecord
import com.example.model.ConversationRecord
import com.example.model.MooMooModelTier
import com.example.model.MooMooMood
import com.example.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "MooMooChatRepo"
private const val STREAM_INACTIVITY_TIMEOUT_MS = 15_000L
private const val FALLBACK_STREAM_INACTIVITY_TIMEOUT_MS = 15_000L
private const val STREAM_TOTAL_TIMEOUT_MS = 60_000L
private const val UNARY_TIMEOUT_MS = 20_000L
private const val MAX_IMAGE_DIM = 1280

class MooMooChatRepository private constructor(
    private val appContext: Context,
    private val db: FirebaseFirestore?,
    private val auth: FirebaseAuth?,
    private val guestStore: GuestChatStore?
) {
    constructor(
        appContext: Context,
        db: FirebaseFirestore,
        auth: FirebaseAuth = Firebase.auth
    ) : this(
        appContext = appContext.applicationContext ?: appContext,
        db = db,
        auth = auth,
        guestStore = null
    )

    constructor(context: Context) : this(
        appContext = context.applicationContext ?: context,
        db = FirebaseFirestore.getInstance(
            (context.applicationContext ?: context).getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth,
        guestStore = null
    )

    companion object {
        fun createGuest(context: Context): MooMooChatRepository {
            val appCtx = context.applicationContext ?: context
            return MooMooChatRepository(
                appContext = appCtx,
                db = null,
                auth = null,
                guestStore = GuestChatStore(appCtx)
            )
        }
    }

    private val isGuestMode: Boolean
        get() = guestStore != null

    private val firebaseAi: FirebaseAI by lazy {
        ensureFirebaseReadyOrThrow()
        Firebase.ai
    }
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

    private fun requireDb(): FirebaseFirestore =
        checkNotNull(db) { "Firestore is unavailable in Guest mode." }

    private fun requireUserId(): String {
        return auth?.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun userDocRef(userId: String) = requireDb().collection("users").document(userId)

    private fun conversationsCollection(userId: String) =
        userDocRef(userId).collection("conversations")

    private fun messagesCollection(userId: String, conversationId: String) =
        conversationsCollection(userId).document(conversationId).collection("messages")

    suspend fun initGuestStoreIfNeeded() {
        guestStore?.initIfNeeded()
    }

    private fun ensureFirebaseReadyOrThrow() {
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            configureAppCheck(appContext, null)
        }
        if (!ensureFirebaseInitialized(appContext)) {
            throw IllegalStateException(
                "Firebase is not configured in this APK build (missing google-services.json). " +
                    "Ensure google-services.json is included before building."
            )
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> {
        if (isGuestMode) return flowOf(null)
        val docRef = userDocRef(userId)
        return docRef.snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        UserProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth?.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.GET, docRef.path)
                }
                throw error
            }
    }

    suspend fun syncUserProfile(
        displayName: String,
        email: String,
        photoUrl: String?,
        defaultMood: MooMooMood,
        defaultTier: MooMooModelTier,
        defaultCustomInstruction: String
    ) {
        if (isGuestMode) return
        val uid = requireUserId()
        val docRef = userDocRef(uid)
        try {
            val existing = docRef.get().await()
            val safeName = displayName.trim().take(120)
            val safeEmail = email.trim().take(254)
            val safePhoto = photoUrl?.trim()?.takeIf { it.isNotEmpty() }?.take(500)

            if (!existing.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to safeName,
                    "email" to safeEmail,
                    "photoUrl" to safePhoto,
                    "moodId" to defaultMood.id,
                    "modelTierId" to defaultTier.id,
                    "customInstruction" to defaultCustomInstruction.trim().take(2000),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                docRef.set(payload).await()
            } else {
                val updates = mapOf(
                    "displayName" to safeName,
                    "email" to safeEmail,
                    "photoUrl" to safePhoto,
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                docRef.update(updates).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun updateAssistantPreferences(
        mood: MooMooMood,
        modelTier: MooMooModelTier,
        customInstruction: String
    ) {
        if (isGuestMode) return
        val uid = requireUserId()
        val docRef = userDocRef(uid)
        val updates = mapOf(
            "moodId" to mood.id,
            "modelTierId" to modelTier.id,
            "customInstruction" to customInstruction.trim().take(2000),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    fun observeConversations(userId: String): Flow<List<ConversationRecord>> {
        guestStore?.let { return it.observeConversations() }
        val colRef = conversationsCollection(userId)
        return colRef
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ConversationRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth?.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, colRef.path)
                }
                throw error
            }
    }

    fun observeMessages(userId: String, conversationId: String): Flow<List<ChatMessageRecord>> {
        guestStore?.let { return it.observeMessages(conversationId) }
        val colRef = messagesCollection(userId, conversationId)
        return colRef
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ChatMessageRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth?.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, colRef.path)
                }
                throw error
            }
    }

    suspend fun getConversation(userId: String, conversationId: String): ConversationRecord? {
        guestStore?.let { return it.getConversation(conversationId) }
        val docRef = conversationsCollection(userId).document(conversationId)
        return try {
            docRef.get().await().toObject(
                ConversationRecord::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    suspend fun createConversation(title: String, mood: MooMooMood): ConversationRecord {
        guestStore?.let { return it.createConversation(title, mood) }
        val uid = requireUserId()
        val convId = UUID.randomUUID().toString()
        val safeTitle = title.trim().ifBlank { "New Chat" }.take(120)
        val docRef = conversationsCollection(uid).document(convId)
        val payload = mapOf(
            "userId" to uid,
            "title" to safeTitle,
            "moodId" to mood.id,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            return ConversationRecord(
                id = convId,
                userId = uid,
                title = safeTitle,
                moodId = mood.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    suspend fun updateConversationMood(conversationId: String, mood: MooMooMood) {
        guestStore?.let {
            it.updateConversationMood(conversationId, mood)
            return
        }
        val uid = requireUserId()
        val docRef = conversationsCollection(uid).document(conversationId)
        try {
            docRef.update(
                mapOf(
                    "moodId" to mood.id,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun deleteConversation(conversationId: String) {
        guestStore?.let {
            it.deleteConversation(conversationId)
            return
        }
        val uid = requireUserId()
        val firestore = requireDb()
        val convRef = conversationsCollection(uid).document(conversationId)
        val msgsCol = messagesCollection(uid, conversationId)
        try {
            val msgSnapshots = msgsCol.get().await()
            val messages = msgSnapshots.toObjects(
                ChatMessageRecord::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            withContext(Dispatchers.IO) {
                messages.mapNotNull { it.imagePath }.forEach { path ->
                    runCatching { File(path).delete() }
                }
            }
            val msgRefs = msgSnapshots.documents.map { it.reference }
            msgRefs.chunked(400).forEach { chunk ->
                firestore.runBatch { batch ->
                    chunk.forEach { batch.delete(it) }
                }.await()
            }
            convRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, convRef.path)
            throw e
        }
    }

    suspend fun saveMessage(
        conversationId: String,
        role: String,
        text: String,
        imagePath: String? = null,
        isError: Boolean = false
    ): ChatMessageRecord {
        guestStore?.let { return it.saveMessage(conversationId, role, text, imagePath, isError) }
        val uid = requireUserId()
        val firestore = requireDb()
        val msgId = UUID.randomUUID().toString()
        val convRef = conversationsCollection(uid).document(conversationId)
        val msgRef = messagesCollection(uid, conversationId).document(msgId)
        val safeText = text.take(64_000)
        val safeImagePath = imagePath?.takeIf { it.isNotBlank() }?.take(500)
        val payload = mapOf(
            "userId" to uid,
            "conversationId" to conversationId,
            "role" to role,
            "text" to safeText,
            "imagePath" to safeImagePath,
            "isError" to isError,
            "createdAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }

        try {
            firestore.runBatch { batch ->
                batch.set(msgRef, payload)
                batch.update(convRef, mapOf("updatedAt" to FieldValue.serverTimestamp()))
            }.await()
            return ChatMessageRecord(
                id = msgId,
                userId = uid,
                conversationId = conversationId,
                role = role,
                text = safeText,
                imagePath = safeImagePath,
                isError = isError
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path)
            throw e
        }
    }

    suspend fun deleteMessage(conversationId: String, messageId: String) {
        guestStore?.let {
            it.deleteMessage(conversationId, messageId)
            return
        }
        val uid = requireUserId()
        val msgRef = messagesCollection(uid, conversationId).document(messageId)
        try {
            msgRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, msgRef.path)
            throw e
        }
    }

    private suspend fun getMessagesOnce(conversationId: String): List<ChatMessageRecord> {
        guestStore?.let { return it.getMessagesOnce(conversationId) }
        val uid = requireUserId()
        val colRef = messagesCollection(uid, conversationId)
        return try {
            colRef.orderBy("createdAt", Query.Direction.ASCENDING)
                .get()
                .await()
                .toObjects(
                    ChatMessageRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, colRef.path)
            throw e
        }
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

    suspend fun persistAttachment(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val attachmentsDir = File(appContext.filesDir, "attachments").apply { mkdirs() }
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }

            val sampleSize = computeInSampleSize(
                width = boundsOptions.outWidth,
                height = boundsOptions.outHeight,
                maxDim = MAX_IMAGE_DIM
            )
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val originalBitmap = appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
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

    private fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        var curW = width
        var curH = height
        while (maxOf(curW, curH) / 2 >= maxDim) {
            curW /= 2
            curH /= 2
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun rotateBitmapIfNeeded(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
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
        val file = File(path)
        if (!file.exists()) return@withContext null
        runCatching {
            BitmapFactory.decodeFile(path)
        }.onFailure { e ->
            Log.e(TAG, "Failed to decode bitmap at $path", e)
        }.getOrNull()
    }

    private fun sanitizePriorHistory(priorMessages: List<ChatMessageRecord>): List<ChatMessageRecord> {
        val alternating = mutableListOf<ChatMessageRecord>()
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
        val allMessages = getMessagesOnce(conversationId)
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

        if (!isNetworkAvailable()) {
            throw IllegalStateException(
                "You appear to be offline. Connect to Wi-Fi or mobile data and tap Try again."
            )
        }

        ensureFirebaseReadyOrThrow()
        logAppCheckDiagnosticAsync()

        val primaryModel = getOrCreateModel(modelTier, mood, customInstruction)
        Log.d(TAG, "Starting Moo Moo reply via ${modelTier.modelName} (history=${historyContents.size})")

        try {
            val primaryStream = tryStream(
                model = primaryModel,
                historyContents = historyContents,
                userPromptContent = userPromptContent,
                inactivityTimeoutMs = STREAM_INACTIVITY_TIMEOUT_MS,
                onChunk = onChunk
            )
            if (primaryStream != null) return primaryStream

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

        guestStore?.let {
            it.updateConversationTitle(conversationId, title)
            return
        }

        val uid = requireUserId()
        val docRef = conversationsCollection(uid).document(conversationId)
        try {
            docRef.update(
                mapOf(
                    "title" to title.take(120),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
        }
    }
}
