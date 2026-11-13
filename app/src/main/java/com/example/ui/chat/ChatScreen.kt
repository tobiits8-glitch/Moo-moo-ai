package com.example.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.model.ChatMessage
import com.example.model.ChatSender
import com.example.model.MooMooModelTier
import com.example.model.MooMooMood
import com.example.model.defaultStarterPrompts
import com.example.ui.components.MarkdownText
import com.example.ui.components.MooMooCustomizeSheet
import com.example.ui.components.MooMooDrawerContent
import com.example.ui.components.moodIcon
import com.example.ui.theme.MooMooTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val activeConversation by viewModel.activeConversation.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val composerText by viewModel.composerText.collectAsStateWithLifecycle()
    val pendingImageUri by viewModel.pendingImageUri.collectAsStateWithLifecycle()
    val generationState by viewModel.generationState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val prefs by viewModel.userPreferences.collectAsStateWithLifecycle()
    val cloudError by viewModel.cloudError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showCustomizeSheet by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(cloudError) {
        val message = cloudError
        if (!message.isNullOrBlank()) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.dismissCloudError()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.attachImage(uri)
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isExpandedWidth = maxWidth >= 840.dp

        val drawerContent: @Composable () -> Unit = {
            MooMooDrawerContent(
                conversations = conversations,
                activeConversationId = activeConversationId,
                searchQuery = searchQuery,
                userDisplayName = viewModel.userDisplayName,
                userEmail = viewModel.userEmail,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onNewChat = {
                    viewModel.startNewChat()
                    if (!isExpandedWidth) scope.launch { drawerState.close() }
                },
                onSelectConversation = { id ->
                    viewModel.selectConversation(id)
                    if (!isExpandedWidth) scope.launch { drawerState.close() }
                },
                onDeleteConversation = viewModel::deleteConversation,
                onOpenCustomize = {
                    if (!isExpandedWidth) scope.launch { drawerState.close() }
                    showCustomizeSheet = true
                },
                onSignOut = {
                    if (!isExpandedWidth) scope.launch { drawerState.close() }
                    onSignOut()
                }
            )
        }

        val chatBody: @Composable () -> Unit = {
            ChatMainPane(
                conversationTitle = activeConversation?.title,
                messages = messages,
                generationState = generationState,
                composerText = composerText,
                pendingImageUri = pendingImageUri,
                selectedModelTier = prefs.modelTier,
                selectedMood = prefs.mood,
                isExpandedWidth = isExpandedWidth,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onNewChat = viewModel::startNewChat,
                onOpenCustomize = { showCustomizeSheet = true },
                onSelectModelTier = viewModel::setModelTier,
                onSelectMood = viewModel::setMood,
                onComposerTextChange = viewModel::updateComposerText,
                onPickImage = {
                    runCatching {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }.onFailure {
                        Toast.makeText(
                            context,
                            "No photo picker available on this device.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onClearPendingImage = viewModel::clearPendingImage,
                onSendMessage = { viewModel.sendMessage() },
                onStarterPromptClick = { prompt -> viewModel.sendMessage(prompt) },
                onStopGeneration = viewModel::cancelGeneration,
                onRegenerateReply = viewModel::regenerateLastReply
            )
        }

        if (isExpandedWidth) {
            PermanentNavigationDrawer(
                drawerContent = drawerContent,
                content = chatBody
            )
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = drawerContent,
                content = chatBody
            )
        }
    }

    if (showCustomizeSheet) {
        MooMooCustomizeSheet(
            selectedModelTier = prefs.modelTier,
            selectedMood = prefs.mood,
            customInstruction = prefs.customInstruction,
            themeMode = prefs.themeMode,
            onSelectModelTier = viewModel::setModelTier,
            onSelectMood = viewModel::setMood,
            onCustomInstructionChange = viewModel::setCustomInstruction,
            onSelectThemeMode = viewModel::setThemeMode,
            onDismiss = { showCustomizeSheet = false }
        )
    }
}

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    composerText: String,
    onComposerTextChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    modifier: Modifier = Modifier,
    conversationTitle: String? = null,
    selectedModelTier: MooMooModelTier = MooMooModelTier.SMART,
    selectedMood: MooMooMood = MooMooMood.CLASSIC,
    generationState: GenerationUiState = GenerationUiState.Idle,
    pendingImageUri: Uri? = null,
    onOpenDrawer: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onOpenCustomize: () -> Unit = {},
    onSelectModelTier: (MooMooModelTier) -> Unit = {},
    onSelectMood: (MooMooMood) -> Unit = {},
    onPickImage: () -> Unit = {},
    onClearPendingImage: () -> Unit = {},
    onStarterPromptClick: (String) -> Unit = {},
    onStopGeneration: () -> Unit = {},
    onRegenerateReply: () -> Unit = {}
) {
    ChatMainPane(
        conversationTitle = conversationTitle,
        messages = messages,
        generationState = generationState,
        composerText = composerText,
        pendingImageUri = pendingImageUri,
        selectedModelTier = selectedModelTier,
        selectedMood = selectedMood,
        isExpandedWidth = false,
        onOpenDrawer = onOpenDrawer,
        onNewChat = onNewChat,
        onOpenCustomize = onOpenCustomize,
        onSelectModelTier = onSelectModelTier,
        onSelectMood = onSelectMood,
        onComposerTextChange = onComposerTextChange,
        onPickImage = onPickImage,
        onClearPendingImage = onClearPendingImage,
        onSendMessage = onSendMessage,
        onStarterPromptClick = onStarterPromptClick,
        onStopGeneration = onStopGeneration,
        onRegenerateReply = onRegenerateReply,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatMainPane(
    conversationTitle: String?,
    messages: List<ChatMessage>,
    generationState: GenerationUiState,
    composerText: String,
    pendingImageUri: Uri?,
    selectedModelTier: MooMooModelTier,
    selectedMood: MooMooMood,
    isExpandedWidth: Boolean,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenCustomize: () -> Unit,
    onSelectModelTier: (MooMooModelTier) -> Unit,
    onSelectMood: (MooMooMood) -> Unit,
    onComposerTextChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onClearPendingImage: () -> Unit,
    onSendMessage: () -> Unit,
    onStarterPromptClick: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onRegenerateReply: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isGenerating = generationState is GenerationUiState.Loading ||
        generationState is GenerationUiState.Streaming

    var showModelMenu by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var speakingMessageId by remember { mutableStateOf<String?>(null) }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var pendingSpeakMessage by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun stopSpeaking() {
        pendingSpeakMessage = null
        ttsEngine?.stop()
        speakingMessageId = null
    }

    fun toggleSpeak(messageId: String, text: String) {
        if (speakingMessageId == messageId) {
            stopSpeaking()
            return
        }
        speakingMessageId = messageId
        val existing = ttsEngine
        if (existing != null && ttsReady) {
            existing.speak(text, TextToSpeech.QUEUE_FLUSH, null, messageId)
            return
        }
        pendingSpeakMessage = messageId to text
        if (existing == null) {
            runCatching {
                var created: TextToSpeech? = null
                created = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        runCatching { created?.language = Locale.getDefault() }
                        mainHandler.post {
                            ttsReady = true
                            val pending = pendingSpeakMessage
                            if (pending != null && speakingMessageId == pending.first) {
                                pendingSpeakMessage = null
                                runCatching {
                                    created?.speak(
                                        pending.second,
                                        TextToSpeech.QUEUE_FLUSH,
                                        null,
                                        pending.first
                                    )
                                }
                            }
                        }
                    } else {
                        mainHandler.post {
                            pendingSpeakMessage = null
                            speakingMessageId = null
                            Toast.makeText(
                                context,
                                "Text-to-speech is not available on this phone.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            if (speakingMessageId == utteranceId) {
                                speakingMessageId = null
                            }
                        }
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        mainHandler.post {
                            if (speakingMessageId == utteranceId) {
                                speakingMessageId = null
                            }
                        }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post {
                            if (speakingMessageId == utteranceId) {
                                speakingMessageId = null
                            }
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            if (speakingMessageId == utteranceId) {
                                speakingMessageId = null
                            }
                        }
                    }
                })
                ttsEngine = created
            }.onFailure {
                pendingSpeakMessage = null
                speakingMessageId = null
                Toast.makeText(
                    context,
                    "Text-to-speech is not available on this phone.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
            ttsEngine = null
        }
    }

    val showScrollToBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) false
            else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible < total - 2
            }
        }
    }

    val isNearBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) true
            else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2
            }
        }
    }

    val streamingLength = (generationState as? GenerationUiState.Streaming)?.text?.length ?: 0
    LaunchedEffect(messages.size, generationState is GenerationUiState.Loading) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0 && isNearBottom) {
            listState.animateScrollToItem(total - 1)
        }
    }

    LaunchedEffect(streamingLength) {
        if (streamingLength > 0 && isNearBottom) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                listState.scrollToItem(total - 1, scrollOffset = Int.MAX_VALUE)
            }
        }
    }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
            MaterialTheme.colorScheme.background
        )
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.94f)
                ),
                navigationIcon = {
                    if (!isExpandedWidth) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("open_drawer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Menu,
                                contentDescription = stringResource(R.string.cd_open_drawer)
                            )
                        }
                    }
                },
                title = {
                    Box {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { showModelMenu = true }
                                .testTag("model_selector_pill")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.img_moomoo_icon_fg),
                                    contentDescription = stringResource(R.string.cd_moomoo_avatar),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                )
                                Column(horizontalAlignment = Alignment.Start) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = selectedModelTier.label,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            maxLines = 1
                                        )
                                        Icon(
                                            imageVector = Icons.Rounded.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.cd_switch_model),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    if (!conversationTitle.isNullOrBlank()) {
                                        Text(
                                            text = conversationTitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 170.dp)
                                        )
                                    }
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false }
                        ) {
                            MooMooModelTier.entries.forEach { tier ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = tier.label,
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                text = tier.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (tier == MooMooModelTier.FAST) {
                                                Icons.Rounded.Bolt
                                            } else {
                                                Icons.Rounded.AutoAwesome
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    trailingIcon = {
                                        if (tier == selectedModelTier) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    },
                                    onClick = {
                                        onSelectModelTier(tier)
                                        showModelMenu = false
                                    },
                                    modifier = Modifier.testTag("menu_model_${tier.id}")
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenCustomize,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("customize_moomoo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = stringResource(R.string.cd_customize_moomoo)
                        )
                    }
                    if (messages.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                stopSpeaking()
                                onNewChat()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("top_new_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddComment,
                                contentDescription = stringResource(R.string.cd_new_chat)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgGradient)
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 760.dp),
                contentAlignment = Alignment.Center
            ) {
                val showWelcome = messages.isEmpty() && generationState is GenerationUiState.Idle
                Crossfade(
                    targetState = showWelcome,
                    animationSpec = tween(durationMillis = 220),
                    label = "welcome_chat_crossfade"
                ) { isWelcome ->
                    if (isWelcome) {
                        MooMooWelcomeView(
                            selectedMood = selectedMood,
                            onSelectMood = onSelectMood,
                            onStarterPromptClick = onStarterPromptClick
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("chat_message_list"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            items(
                                items = messages,
                                key = { it.id },
                                contentType = { if (it.isFromUser) "user_message" else "model_message" }
                            ) { message ->
                                val isLastMessage = message.id == messages.lastOrNull()?.id && !isGenerating
                                val isSpeaking = speakingMessageId == message.id
                                ChatMessageBubble(
                                    message = message,
                                    showRegenerate = isLastMessage && (!message.isFromUser || message.isError),
                                    isSpeaking = isSpeaking,
                                    onToggleSpeak = { toggleSpeak(message.id, message.text) },
                                    onRegenerate = onRegenerateReply,
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(durationMillis = 180),
                                        fadeOutSpec = tween(durationMillis = 140),
                                        placementSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                )
                            }

                            when (generationState) {
                                is GenerationUiState.Loading -> {
                                    item(key = "streaming_loading", contentType = "loading") {
                                        MooMooThinkingBubble(
                                            modifier = Modifier.animateItem(
                                                fadeInSpec = tween(durationMillis = 160),
                                                fadeOutSpec = tween(durationMillis = 120)
                                            )
                                        )
                                    }
                                }

                                is GenerationUiState.Streaming -> {
                                    item(key = "streaming_active", contentType = "streaming") {
                                        MooMooStreamingBubble(text = generationState.text)
                                    }
                                }

                                else -> Unit
                            }
                        }
                    }
                }

                this@Column.AnimatedVisibility(
                    visible = showScrollToBottom,
                    enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.85f, animationSpec = tween(160)),
                    exit = fadeOut(tween(130)) + scaleOut(targetScale = 0.85f, animationSpec = tween(130)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 12.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                val total = listState.layoutInfo.totalItemsCount
                                if (total > 0) listState.animateScrollToItem(total - 1)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("scroll_to_bottom_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowDownward,
                            contentDescription = stringResource(R.string.cd_scroll_to_bottom)
                        )
                    }
                }
            }

            ChatComposerBar(
                text = composerText,
                pendingImageUri = pendingImageUri,
                isGenerating = isGenerating,
                onTextChange = onComposerTextChange,
                onPickImage = onPickImage,
                onClearPendingImage = onClearPendingImage,
                onSend = onSendMessage,
                onStop = onStopGeneration,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun MooMooWelcomeView(
    selectedMood: MooMooMood,
    onSelectMood: (MooMooMood) -> Unit,
    onStarterPromptClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 2.dp,
            modifier = Modifier.size(104.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_moomoo_mascot),
                contentDescription = stringResource(R.string.cd_moomoo_mascot),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp)
        )

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MooMooMood.entries.forEach { mood ->
                val selected = mood == selectedMood
                FilterChip(
                    selected = selected,
                    onClick = { onSelectMood(mood) },
                    label = {
                        Text(
                            text = mood.displayName,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = moodIcon(mood),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("welcome_mood_chip_${mood.id}")
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 340.dp) 1 else 2
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                defaultStarterPrompts.chunked(columns).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { starter ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { onStarterPromptClick(starter.prompt) }
                                    .testTag("starter_prompt_${starter.title.lowercase().replace(" ", "_")}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = starter.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = starter.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    showRegenerate: Boolean,
    isSpeaking: Boolean,
    onToggleSpeak: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedToast = stringResource(R.string.copied_toast)

    if (message.isFromUser) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(
                    topStart = 22.dp,
                    topEnd = 22.dp,
                    bottomStart = 22.dp,
                    bottomEnd = 6.dp
                ),
                modifier = Modifier.widthIn(max = 520.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    message.imagePath?.let { path ->
                        val imageRequest = remember(path, context) {
                            ImageRequest.Builder(context)
                                .data(File(path))
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = stringResource(R.string.cd_attached_image),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                    }
                    if (message.text.isNotBlank()) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    } else {
        // ChatGPT-style clean full-width assistant response with Moo Moo badge & action bar
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_moomoo_icon_fg),
                    contentDescription = stringResource(R.string.cd_moomoo_avatar),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (message.isError) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                MarkdownText(
                    markdown = message.text,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 34.dp)
                )
            }

            Row(
                modifier = Modifier.padding(start = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!message.isError) {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(message.text))
                            Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.cd_copy_message),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onToggleSpeak,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) {
                                Icons.Rounded.StopCircle
                            } else {
                                Icons.AutoMirrored.Rounded.VolumeUp
                            },
                            contentDescription = stringResource(
                                if (isSpeaking) R.string.cd_stop_speaking else R.string.cd_speak_message
                            ),
                            tint = if (isSpeaking) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            runCatching {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, message.text)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, null))
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    "Unable to share on this device.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.cd_share_message),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (showRegenerate) {
                    TextButton(
                        onClick = onRegenerate,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("regenerate_reply_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cd_regenerate_reply),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (message.isError) stringResource(R.string.error_retry) else "Regenerate",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MooMooStreamingBubble(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_moomoo_icon_fg),
                contentDescription = stringResource(R.string.cd_moomoo_avatar),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        MarkdownText(
            markdown = "$text ▍",
            textColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 34.dp)
        )
    }
}

@Composable
private fun MooMooThinkingBubble(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_moomoo_icon_fg),
            contentDescription = stringResource(R.string.cd_moomoo_avatar),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
        )
        Row(
            modifier = Modifier.alpha(pulse),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = stringResource(R.string.moomoo_thinking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatComposerBar(
    text: String,
    pendingImageUri: Uri?,
    isGenerating: Boolean,
    onTextChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onClearPendingImage: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 3.dp,
        modifier = modifier.animateContentSize(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            AnimatedVisibility(visible = pendingImageUri != null) {
                pendingImageUri?.let { uri ->
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp, top = 6.dp, bottom = 4.dp)
                            .size(84.dp)
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = stringResource(R.string.cd_attached_image),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(26.dp)
                                .clickable(onClick = onClearPendingImage)
                                .testTag("remove_attachment_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.cd_remove_attachment),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onPickImage,
                    enabled = !isGenerating,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("attach_image_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = stringResource(R.string.cd_attach_image),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.hint_ask_moomoo),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (!isGenerating && (text.isNotBlank() || pendingImageUri != null)) {
                                onSend()
                            }
                        }
                    ),
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field")
                )

                val canSend = text.isNotBlank() || pendingImageUri != null
                val sendBg by animateColorAsState(
                    targetValue = if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    animationSpec = tween(durationMillis = 160),
                    label = "send_button_bg"
                )
                val sendFg by animateColorAsState(
                    targetValue = if (canSend) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 160),
                    label = "send_button_fg"
                )

                AnimatedContent(
                    targetState = isGenerating,
                    transitionSpec = {
                        (fadeIn(tween(140)) + scaleIn(initialScale = 0.88f, animationSpec = tween(140)))
                            .togetherWith(fadeOut(tween(120)) + scaleOut(targetScale = 0.88f, animationSpec = tween(120)))
                    },
                    label = "send_stop_transition"
                ) { generating ->
                    if (generating) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(CircleShape)
                                .clickable(onClick = onStop)
                                .testTag("stop_generation_button")
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = stringResource(R.string.cd_stop_generation),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = sendBg,
                            contentColor = sendFg,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(CircleShape)
                                .clickable(enabled = canSend, onClick = onSend)
                                .testTag("send_message_button")
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = stringResource(R.string.cd_send_message),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreview() {
    MooMooTheme {
        ChatScreen(
            messages = listOf(
                ChatMessage(
                    id = "1",
                    sender = ChatSender.USER,
                    text = "Can you explain how Kotlin Flows work?"
                ),
                ChatMessage(
                    id = "2",
                    sender = ChatSender.MOO_MOO,
                    text = "A **Kotlin Flow** is a cold asynchronous data stream that emits values sequentially.\n\n- **Cold**: Code inside `flow { ... }` doesn't run until collected.\n- **Composable**: Transform streams with `map`, `filter`, and `combine`."
                )
            ),
            composerText = "",
            onComposerTextChange = {},
            onSendMessage = {}
        )
    }
}
