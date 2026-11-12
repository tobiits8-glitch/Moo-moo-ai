package com.example

import com.example.data.local.MessageEntity
import com.example.model.ChatMessage
import com.example.model.ChatSender
import com.example.model.MooMooModelTier
import com.example.model.MooMooMood
import com.example.model.toChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTest {

    @Test
    fun `user message maps to ChatSender USER`() {
        val entity = MessageEntity(
            id = "m1",
            conversationId = "c1",
            role = "user",
            text = "Hello Moo Moo!",
            timestamp = 1_000L
        )

        val message: ChatMessage = entity.toChatMessage()

        assertEquals("m1", message.id)
        assertEquals(ChatSender.USER, message.sender)
        assertTrue(message.isFromUser)
        assertEquals("Hello Moo Moo!", message.text)
    }

    @Test
    fun `model message maps to ChatSender MOO_MOO`() {
        val entity = MessageEntity(
            id = "m2",
            conversationId = "c1",
            role = "model",
            text = "Moo-velous to meet you!",
            timestamp = 2_000L
        )

        val message: ChatMessage = entity.toChatMessage()

        assertEquals("m2", message.id)
        assertEquals(ChatSender.MOO_MOO, message.sender)
        assertFalse(message.isFromUser)
        assertEquals("Moo-velous to meet you!", message.text)
    }

    @Test
    fun `model tiers map to supported Gemini 3 models with safe fallback`() {
        assertEquals("gemini-3.8-flash", MooMooModelTier.SMART.modelName)
        assertEquals("gemini-3.5-flash-lite", MooMooModelTier.FAST.modelName)
        assertEquals(MooMooModelTier.FAST, MooMooModelTier.fromId("fast"))
        assertEquals(MooMooModelTier.SMART, MooMooModelTier.fromId("unknown"))
    }

    @Test
    fun `assistant modes resolve cleanly with CLASSIC fallback`() {
        assertEquals(MooMooMood.CODING, MooMooMood.fromId("coding"))
        assertEquals(MooMooMood.CLASSIC, MooMooMood.fromId("nonexistent"))
    }
}
