package com.example.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.unwrapFirestoreException
import com.example.data.repository.MooMooChatRepository
import com.example.model.ChatMessage
import com.example.model.ChatSender
import com.example.model.ConversationRecord
import com.example.model.MooMooModelTier
import com.example.model.MooMooMood
import com.example.model.toChatMessage
import com.example.ui.theme.ThemeMode
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "ChatViewModel"

sealed interface GenerationUiState {
    data object Idle : GenerationUiState
    data object Loading : GenerationUiState
    data class Streaming(val text: String) : GenerationUiState
    data class Error(val message: String) : GenerationUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val chatRepository: MooMooChatRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val currentUserId: String,
    val userDisplayName: String,
    val userEmail: String,
    private val userPhotoUrl: String?,
    val isGuest: Boolean = false
) : ViewModel() {

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _pendingImageUri = MutableStateFlow<Uri?>(null)
    val pendingImageUri: StateFlow<Uri?> = _pendingImageUri.asStateFlow()

    private val _generationState = MutableStateFlow<GenerationUiState>(GenerationUiState.Idle)
    val generationState: StateFlow<GenerationUiState> = _generationState.asStateFlow()

    private val _cloudError = MutableStateFlow<String?>(null)
    val cloudError: StateFlow<String?> = _cloudError.asStateFlow()

    private var activeGenerationJob: Job? = null
    private var customInstructionSyncJob: Job? = null

    private val remoteUserProfile = if (isGuest) {
        flowOf(null)
    } else {
        chatRepository.observeUserProfile(currentUserId)
            .catch { error ->
                val firestoreError = error.unwrapFirestoreException()
                if (firestoreError == null && error is CancellationException) throw error
                Log.w(TAG, "Error observing user profile", firestoreError ?: error)
                emit(null)
            }
    }

    val userPreferences: StateFlow<UserPreferences> = combine(
        preferencesRepository.preferences,
        remoteUserProfile
    ) { localPrefs, profile ->
        if (profile == null) {
            localPrefs
        } else {
            localPrefs.copy(
                mood = MooMooMood.fromId(profile.moodId),
                modelTier = MooMooModelTier.fromId(profile.modelTierId),
                customInstruction = profile.customInstruction
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences()
    )

    private val allConversations: StateFlow<List<ConversationRecord>> =
        chatRepository.observeConversations(currentUserId)
            .catch { error ->
                val firestoreError = error.unwrapFirestoreException()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing conversations", rootError)
                _cloudError.value = rootError.localizedMessage ?: "Failed to sync chat history."
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val conversations: StateFlow<List<ConversationRecord>> =
        combine(
            allConversations,
            _searchQuery
        ) { items, query ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) items
            else items.filter { it.title.contains(trimmed, ignoreCase = true) }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val activeConversation: StateFlow<ConversationRecord?> =
        combine(
            allConversations,
            _activeConversationId
        ) { items, activeId ->
            items.firstOrNull { it.id == activeId }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val messages: StateFlow<List<ChatMessage>> =
        _activeConversationId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(emptyList())
                } else {
                    chatRepository.observeMessages(currentUserId, id)
                        .map { records -> records.map { it.toChatMessage() } }
                        .catch { error ->
                            val firestoreError = error.unwrapFirestoreException()
                            if (firestoreError == null && error is CancellationException) throw error
                            val rootError = firestoreError ?: error
                            Log.w(TAG, "Error observing messages", rootError)
                            _cloudError.value = rootError.localizedMessage ?: "Failed to load messages."
                            emit(emptyList())
                        }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    init {
        if (isGuest) {
            viewModelScope.launch {
                chatRepository.initGuestStoreIfNeeded()
            }
        } else {
            syncProfileOnLaunch()
        }
    }

    private fun syncProfileOnLaunch() {
        viewModelScope.launch {
            val prefs = userPreferences.value
            runCatching {
                chatRepository.syncUserProfile(
                    displayName = userDisplayName,
                    email = userEmail,
                    photoUrl = userPhotoUrl,
                    defaultMood = prefs.mood,
                    defaultTier = prefs.modelTier,
                    defaultCustomInstruction = prefs.customInstruction
                )
            }.onFailure { error ->
                Log.w(TAG, "Unable to sync user profile to Firestore", error)
            }
        }
    }

    fun dismissCloudError() {
        _cloudError.value = null
    }

    fun updateComposerText(text: String) {
        _composerText.value = text
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun attachImage(uri: Uri?) {
        _pendingImageUri.value = uri
    }

    fun clearPendingImage() {
        _pendingImageUri.value = null
    }

    fun selectConversation(conversationId: String) {
        if (_activeConversationId.value == conversationId) return
        cancelGeneration()
        _activeConversationId.value = conversationId
        _generationState.value = GenerationUiState.Idle
    }

    fun startNewChat() {
        cancelGeneration()
        _activeConversationId.value = null
        _generationState.value = GenerationUiState.Idle
        _pendingImageUri.value = null
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            if (_activeConversationId.value == conversationId) {
                cancelGeneration()
                _activeConversationId.value = null
                _generationState.value = GenerationUiState.Idle
            }
            runCatching {
                chatRepository.deleteConversation(conversationId)
            }.onFailure { error ->
                _cloudError.value = error.localizedMessage ?: "Unable to delete conversation."
            }
        }
    }

    fun setMood(mood: MooMooMood) {
        viewModelScope.launch {
            preferencesRepository.setMood(mood)
            if (!isGuest) {
                val current = userPreferences.value
                runCatching {
                    chatRepository.updateAssistantPreferences(
                        mood = mood,
                        modelTier = current.modelTier,
                        customInstruction = current.customInstruction
                    )
                }
            }
            _activeConversationId.value?.let { id ->
                runCatching {
                    chatRepository.updateConversationMood(id, mood)
                }
            }
        }
    }

    fun setModelTier(tier: MooMooModelTier) {
        viewModelScope.launch {
            preferencesRepository.setModelTier(tier)
            if (!isGuest) {
                val current = userPreferences.value
                runCatching {
                    chatRepository.updateAssistantPreferences(
                        mood = current.mood,
                        modelTier = tier,
                        customInstruction = current.customInstruction
                    )
                }
            }
        }
    }

    fun setCustomInstruction(instruction: String) {
        viewModelScope.launch {
            preferencesRepository.setCustomInstruction(instruction)
        }
        if (!isGuest) {
            customInstructionSyncJob?.cancel()
            customInstructionSyncJob = viewModelScope.launch {
                delay(450L)
                val current = userPreferences.value
                runCatching {
                    chatRepository.updateAssistantPreferences(
                        mood = current.mood,
                        modelTier = current.modelTier,
                        customInstruction = instruction
                    )
                }
            }
        }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setThemeMode(themeMode)
        }
    }

    fun sendMessage(overridePrompt: String? = null) {
        val rawText = (overridePrompt ?: _composerText.value).trim()
        val imageUri = if (overridePrompt == null) _pendingImageUri.value else null

        if (rawText.isEmpty() && imageUri == null) return
        if (_generationState.value is GenerationUiState.Loading ||
            _generationState.value is GenerationUiState.Streaming
        ) {
            return
        }

        if (overridePrompt == null) {
            _composerText.value = ""
            _pendingImageUri.value = null
        }

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            val prefs = userPreferences.value
            val isNewConversation = _activeConversationId.value == null
            val conversationId = try {
                _activeConversationId.value ?: run {
                    val initialTitle = rawText.ifBlank { "Photo chat" }.take(36)
                    val created = chatRepository.createConversation(initialTitle, prefs.mood)
                    _activeConversationId.value = created.id
                    created.id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error creating conversation", e)
                _cloudError.value = e.localizedMessage ?: "Unable to start chat."
                return@launch
            }

            val savedImagePath = imageUri?.let { chatRepository.persistAttachment(it) }
            val userText = rawText.ifBlank { "What's in this image?" }

            try {
                chatRepository.saveMessage(
                    conversationId = conversationId,
                    role = "user",
                    text = userText,
                    imagePath = savedImagePath
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error saving message", e)
                _cloudError.value = e.localizedMessage ?: "Unable to save message."
                return@launch
            }

            val succeeded = streamModelResponse(
                conversationId = conversationId,
                modelTier = prefs.modelTier,
                mood = prefs.mood,
                customInstruction = prefs.customInstruction
            )
            if (isNewConversation && succeeded) {
                launch {
                    chatRepository.generateConversationTitle(conversationId, userText)
                }
            }
        }
    }

    fun regenerateLastReply() {
        val conversationId = _activeConversationId.value ?: return
        if (_generationState.value is GenerationUiState.Loading ||
            _generationState.value is GenerationUiState.Streaming
        ) {
            return
        }

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            val currentMessages = messages.value
            val lastMessage = currentMessages.lastOrNull()
            if (lastMessage != null && (lastMessage.sender == ChatSender.MOO_MOO || lastMessage.isError)) {
                runCatching {
                    chatRepository.deleteMessage(conversationId, lastMessage.id)
                }
            }
            val prefs = userPreferences.value
            streamModelResponse(
                conversationId = conversationId,
                modelTier = prefs.modelTier,
                mood = prefs.mood,
                customInstruction = prefs.customInstruction
            )
        }
    }

    fun cancelGeneration() {
        val partial = (_generationState.value as? GenerationUiState.Streaming)?.text
        activeGenerationJob?.cancel()
        activeGenerationJob = null
        val conversationId = _activeConversationId.value
        if (!partial.isNullOrBlank() && conversationId != null) {
            viewModelScope.launch {
                runCatching {
                    chatRepository.saveMessage(
                        conversationId = conversationId,
                        role = "model",
                        text = partial
                    )
                }
            }
        }
        _generationState.value = GenerationUiState.Idle
    }

    private suspend fun streamModelResponse(
        conversationId: String,
        modelTier: MooMooModelTier,
        mood: MooMooMood,
        customInstruction: String
    ): Boolean {
        _generationState.value = GenerationUiState.Loading
        var lastEmitMs = 0L
        return try {
            val finalReply = chatRepository.streamReply(
                conversationId = conversationId,
                modelTier = modelTier,
                mood = mood,
                customInstruction = customInstruction
            ) { accumulated ->
                val now = android.os.SystemClock.uptimeMillis()
                if (lastEmitMs == 0L || now - lastEmitMs >= 32L) {
                    lastEmitMs = now
                    _generationState.value = GenerationUiState.Streaming(accumulated)
                }
            }
            chatRepository.saveMessage(
                conversationId = conversationId,
                role = "model",
                text = finalReply
            )
            _generationState.value = GenerationUiState.Idle
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error generating Moo Moo reply", e)
            val msg = e.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "Moo Moo ran into a snag reaching Gemini. Please try again."
            runCatching {
                chatRepository.saveMessage(
                    conversationId = conversationId,
                    role = "model",
                    text = msg,
                    isError = true
                )
            }
            _generationState.value = GenerationUiState.Error(msg)
            false
        }
    }

    class Factory(
        private val appContext: Context,
        private val currentUserId: String,
        private val userDisplayName: String,
        private val userEmail: String,
        private val userPhotoUrl: String?,
        private val isGuest: Boolean = false
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repo = if (isGuest) {
                MooMooChatRepository.createGuest(appContext)
            } else {
                val databaseId = appContext.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                MooMooChatRepository(appContext, db)
            }
            val prefs = UserPreferencesRepository(appContext)
            return ChatViewModel(
                chatRepository = repo,
                preferencesRepository = prefs,
                currentUserId = currentUserId,
                userDisplayName = userDisplayName,
                userEmail = userEmail,
                userPhotoUrl = userPhotoUrl,
                isGuest = isGuest
            ) as T
        }
    }
}
