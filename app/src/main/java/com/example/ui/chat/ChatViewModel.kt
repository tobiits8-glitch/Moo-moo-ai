package com.example.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.ConversationEntity
import com.example.data.local.MooMooDatabase
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.MooMooChatRepository
import com.example.model.ChatMessage
import com.example.model.ChatSender
import com.example.model.MooMooModelTier
import com.example.model.MooMooMood
import com.example.model.toChatMessage
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val preferencesRepository: UserPreferencesRepository
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

    private var activeGenerationJob: Job? = null

    val userPreferences: StateFlow<UserPreferences> = preferencesRepository.preferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences()
        )

    val conversations: StateFlow<List<ConversationEntity>> =
        combine(
            chatRepository.observeConversations(),
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

    val activeConversation: StateFlow<ConversationEntity?> =
        combine(
            chatRepository.observeConversations(),
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
                if (id == null) flowOf(emptyList())
                else chatRepository.observeMessages(id).map { entities ->
                    entities.map { it.toChatMessage() }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

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
            chatRepository.deleteConversation(conversationId)
        }
    }

    fun setMood(mood: MooMooMood) {
        viewModelScope.launch {
            preferencesRepository.setMood(mood)
            _activeConversationId.value?.let { id ->
                chatRepository.updateConversationMood(id, mood)
            }
        }
    }

    fun setModelTier(tier: MooMooModelTier) {
        viewModelScope.launch {
            preferencesRepository.setModelTier(tier)
        }
    }

    fun setCustomInstruction(instruction: String) {
        viewModelScope.launch {
            preferencesRepository.setCustomInstruction(instruction)
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
            val conversationId = _activeConversationId.value ?: run {
                val initialTitle = rawText.ifBlank { "Photo chat" }.take(36)
                val created = chatRepository.createConversation(initialTitle, prefs.mood)
                _activeConversationId.value = created.id
                created.id
            }

            val savedImagePath = imageUri?.let { chatRepository.persistAttachment(it) }
            val userText = rawText.ifBlank { "What's in this image?" }

            chatRepository.saveMessage(
                conversationId = conversationId,
                role = "user",
                text = userText,
                imagePath = savedImagePath
            )

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
                chatRepository.deleteMessage(lastMessage.id)
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
                chatRepository.saveMessage(
                    conversationId = conversationId,
                    role = "model",
                    text = partial
                )
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
            chatRepository.saveMessage(
                conversationId = conversationId,
                role = "model",
                text = msg,
                isError = true
            )
            _generationState.value = GenerationUiState.Error(msg)
            false
        }
    }

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = MooMooDatabase.getInstance(appContext)
            val repo = MooMooChatRepository(appContext, db.chatDao())
            val prefs = UserPreferencesRepository(appContext)
            return ChatViewModel(repo, prefs) as T
        }
    }
}
