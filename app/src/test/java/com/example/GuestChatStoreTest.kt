package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.GuestChatStore
import com.example.model.MooMooMood
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GuestChatStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "moomoo_guest_chats.json").delete()
    }

    @Test
    fun `guest and developer credit strings are configured`() {
        assertEquals("Developed by Hemanth HS", context.getString(R.string.developer_credit))
        assertEquals("Continue as Guest", context.getString(R.string.auth_continue_guest))
        assertEquals("Guest", context.getString(R.string.guest_display_name))
    }

    @Test
    fun `guest chat store creates persists reloads and deletes conversations locally`() = runBlocking {
        val store = GuestChatStore(context)
        val conversation = store.createConversation("Hello Guest", MooMooMood.CLASSIC)
        val userMsg = store.saveMessage(
            conversationId = conversation.id,
            role = "user",
            text = "What is Kotlin?",
            imagePath = null,
            isError = false
        )
        store.saveMessage(
            conversationId = conversation.id,
            role = "model",
            text = "Kotlin is a modern language.",
            imagePath = null,
            isError = false
        )
        store.updateConversationTitle(conversation.id, "Kotlin Intro")
        store.updateConversationMood(conversation.id, MooMooMood.CODING)

        val reloadedStore = GuestChatStore(context)
        reloadedStore.initIfNeeded()

        val conversations = reloadedStore.observeConversations().first()
        assertEquals(1, conversations.size)
        assertEquals("Kotlin Intro", conversations.first().title)
        assertEquals(MooMooMood.CODING.id, conversations.first().moodId)

        val messages = reloadedStore.observeMessages(conversation.id).first()
        assertEquals(2, messages.size)
        assertEquals("What is Kotlin?", messages[0].text)
        assertEquals("Kotlin is a modern language.", messages[1].text)

        reloadedStore.deleteMessage(conversation.id, userMsg.id)
        assertEquals(1, reloadedStore.getMessagesOnce(conversation.id).size)

        reloadedStore.deleteConversation(conversation.id)
        assertTrue(reloadedStore.observeConversations().first().isEmpty())
        assertNull(reloadedStore.getConversation(conversation.id))
    }
}
