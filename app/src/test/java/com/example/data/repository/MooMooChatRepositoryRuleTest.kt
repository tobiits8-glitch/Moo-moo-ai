package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.model.MooMooModelTier
import com.example.model.MooMooMood
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MooMooChatRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun syncProfileAndCreateConversation_authenticatedOwner_succeedsAndStreams() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = MooMooChatRepository(appContext, firestore, auth)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.syncUserProfile(
                displayName = "Alice",
                email = ALICE_EMAIL,
                photoUrl = null,
                defaultMood = MooMooMood.CLASSIC,
                defaultTier = MooMooModelTier.SMART,
                defaultCustomInstruction = ""
            )
            repository.updateAssistantPreferences(
                mood = MooMooMood.CODING,
                modelTier = MooMooModelTier.FAST,
                customInstruction = "Keep answers concise."
            )
        }

        val profile = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeUserProfile(uid).first { it != null }
        }
        assertNotNull(profile)
        assertEquals("coding", profile?.moodId)
        assertEquals("fast", profile?.modelTierId)

        val created = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createConversation("Kotlin Flow Question", MooMooMood.CODING)
        }
        assertTrue(created.id.isNotBlank())

        val userMessage = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveMessage(
                conversationId = created.id,
                role = "user",
                text = "How does snapshots() work?"
            )
        }

        val conversations = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeConversations(uid).first { list -> list.any { it.id == created.id } }
        }
        assertTrue(conversations.any { it.id == created.id })

        val messages = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMessages(uid, created.id).first { list -> list.any { it.id == userMessage.id } }
        }
        assertEquals(1, messages.size)
        assertEquals("How does snapshots() work?", messages.first().text)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.deleteConversation(created.id)
        }
    }

    @Test
    fun observeAndWriteConversation_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = MooMooChatRepository(appContext, firestore, auth)
        val aliceConv = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createConversation("Alice Private Chat", MooMooMood.CLASSIC)
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = MooMooChatRepository(appContext, firestore, auth)

        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                bobRepo.getConversation(aliceUid, aliceConv.id)
            }
            fail("Expected cross-user getConversation to fail with PERMISSION_DENIED")
        } catch (e: Exception) {
            val fsException = e.unwrapFirestoreException()
            assertNotNull("Expected FirebaseFirestoreException, got $e", fsException)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsException?.code)
        }

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeConversations(aliceUid).first()
            }
            fail("Expected cross-user observeConversations to fail with PERMISSION_DENIED")
        } catch (e: Exception) {
            val fsException = e.unwrapFirestoreException()
            assertNotNull("Expected FirebaseFirestoreException, got $e", fsException)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsException?.code)
        }
    }

    @Test
    fun observeConversations_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        auth.signOut()
        val repo = MooMooChatRepository(appContext, firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeConversations(aliceUid).first()
            }
            fail("Expected unauthenticated observeConversations to fail with PERMISSION_DENIED")
        } catch (e: Exception) {
            val fsException = e.unwrapFirestoreException()
            assertNotNull("Expected FirebaseFirestoreException, got $e", fsException)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsException?.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice@test.com"
        const val BOB_EMAIL = "bob@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
