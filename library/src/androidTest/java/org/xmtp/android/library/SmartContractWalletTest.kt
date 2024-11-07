package org.xmtp.android.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.xmtp.android.library.libxmtp.Message
import org.xmtp.android.library.messages.PrivateKey
import org.xmtp.android.library.messages.PrivateKeyBuilder
import org.xmtp.android.library.messages.walletAddress

@RunWith(AndroidJUnit4::class)
class SmartContractWalletTest {
    companion object {
        private lateinit var davonSCW: FakeSCWWallet
        private lateinit var davonSCWClient: Client
        private lateinit var eriSCW: FakeSCWWallet
        private lateinit var eriSCWClient: Client
        private lateinit var options: ClientOptions
        private lateinit var boV3Wallet: PrivateKeyBuilder
        private lateinit var boV3: PrivateKey
        private lateinit var boV3Client: Client

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            val key = byteArrayOf(
                0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
                0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
                0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F
            )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            options = ClientOptions(
                ClientOptions.Api(XMTPEnvironment.LOCAL, false),
                appContext = context,
                dbEncryptionKey = key
            )

            // EOA
            boV3Wallet = PrivateKeyBuilder()
            boV3 = boV3Wallet.getPrivateKey()
            boV3Client = runBlocking {
                Client().create(
                    account = boV3Wallet,
                    options = options
                )
            }

            // SCW
            davonSCW = FakeSCWWallet.generate(ANVIL_TEST_PRIVATE_KEY_1)
            davonSCWClient = runBlocking {
                Client().create(
                    account = davonSCW,
                    options = options
                )
            }

            // SCW
            eriSCW = FakeSCWWallet.generate(ANVIL_TEST_PRIVATE_KEY_2)
            eriSCWClient = runBlocking {
                Client().create(
                    account = eriSCW,
                    options = options
                )
            }
        }
    }

    @Test
    fun testCanBuildASCW() {
        val davonSCWClient2 = runBlocking {
            Client().build(
                address = davonSCW.address,
                options = options
            )
        }

        assertEquals(davonSCWClient.inboxId, davonSCWClient2.inboxId)
    }

    @Test
    fun testsCanCreateGroup() {
        val group1 = runBlocking {
            boV3Client.conversations.newGroup(
                listOf(
                    davonSCW.walletAddress,
                    eriSCW.walletAddress
                )
            )
        }
        val group2 = runBlocking {
            davonSCWClient.conversations.newGroup(
                listOf(
                    boV3.walletAddress,
                    eriSCW.walletAddress
                )
            )
        }

        assertEquals(
            runBlocking { group1.members().map { it.inboxId }.sorted() },
            listOf(davonSCWClient.inboxId, boV3Client.inboxId, eriSCWClient.inboxId).sorted()
        )
        assertEquals(
            runBlocking { group2.members().map { it.addresses.first() }.sorted() },
            listOf(davonSCWClient.address, boV3Client.address, eriSCWClient.address).sorted()
        )
    }

    @Test
    fun testsCanSendMessages() {
        val boGroup = runBlocking {
            boV3Client.conversations.newGroup(
                listOf(
                    davonSCW.walletAddress,
                    eriSCW.walletAddress
                )
            )
        }
        runBlocking { boGroup.send("howdy") }
        val messageId = runBlocking { boGroup.send("gm") }
        runBlocking { boGroup.sync() }
        assertEquals(boGroup.messages().first().body, "gm")
        assertEquals(boGroup.messages().first().id, messageId)
        assertEquals(
            boGroup.messages().first().deliveryStatus,
            Message.MessageDeliveryStatus.PUBLISHED
        )
        assertEquals(boGroup.messages().size, 3)

        runBlocking { davonSCWClient.conversations.syncConversations() }
        val davonGroup = runBlocking { davonSCWClient.conversations.listGroups().last() }
        runBlocking { davonGroup.sync() }
        assertEquals(davonGroup.messages().size, 2)
        assertEquals(davonGroup.messages().first().body, "gm")
        runBlocking { davonGroup.send("from davon") }

        runBlocking { eriSCWClient.conversations.syncConversations() }
        val eriGroup = runBlocking { davonSCWClient.findGroup(davonGroup.id) }
        runBlocking { eriGroup?.sync() }
        assertEquals(eriGroup?.messages()?.size, 3)
        assertEquals(eriGroup?.messages()?.first()?.body, "from davon")
        runBlocking { eriGroup?.send("from eri") }
    }

    @Test
    fun testGroupConsent() {
        runBlocking {
            val davonGroup = runBlocking {
                davonSCWClient.conversations.newGroup(
                    listOf(
                        boV3.walletAddress,
                        eriSCW.walletAddress
                    )
                )
            }
            assertEquals(
                davonSCWClient.preferences.consentList.conversationState(davonGroup.id),
                ConsentState.ALLOWED
            )
            assertEquals(davonGroup.consentState(), ConsentState.ALLOWED)

            davonSCWClient.preferences.consentList.setConsentState(
                listOf(
                    ConsentListEntry(
                        davonGroup.id,
                        EntryType.CONVERSATION_ID,
                        ConsentState.DENIED
                    )
                )
            )
            assertEquals(
                davonSCWClient.preferences.consentList.conversationState(davonGroup.id),
                ConsentState.DENIED
            )
            assertEquals(davonGroup.consentState(), ConsentState.DENIED)

            davonGroup.updateConsentState(ConsentState.ALLOWED)
            assertEquals(
                davonSCWClient.preferences.consentList.conversationState(davonGroup.id),
                ConsentState.ALLOWED
            )
            assertEquals(davonGroup.consentState(), ConsentState.ALLOWED)
        }
    }

    @Test
    fun testCanAllowAndDenyInboxId() {
        runBlocking {
            val davonGroup = runBlocking {
                davonSCWClient.conversations.newGroup(
                    listOf(
                        boV3.walletAddress,
                        eriSCW.walletAddress
                    )
                )
            }
            assertEquals(
                davonSCWClient.preferences.consentList.inboxIdState(boV3Client.inboxId),
                ConsentState.UNKNOWN
            )
            davonSCWClient.preferences.consentList.setConsentState(
                listOf(
                    ConsentListEntry(
                        boV3Client.inboxId,
                        EntryType.INBOX_ID,
                        ConsentState.ALLOWED
                    )
                )
            )
            var alixMember = davonGroup.members().firstOrNull { it.inboxId == boV3Client.inboxId }
            assertEquals(alixMember!!.consentState, ConsentState.ALLOWED)

            assertEquals(
                davonSCWClient.preferences.consentList.inboxIdState(boV3Client.inboxId),
                ConsentState.ALLOWED
            )

            davonSCWClient.preferences.consentList.setConsentState(
                listOf(
                    ConsentListEntry(
                        boV3Client.inboxId,
                        EntryType.INBOX_ID,
                        ConsentState.DENIED
                    )
                )
            )
            alixMember = davonGroup.members().firstOrNull { it.inboxId == boV3Client.inboxId }
            assertEquals(alixMember!!.consentState, ConsentState.DENIED)

            assertEquals(
                davonSCWClient.preferences.consentList.inboxIdState(boV3Client.inboxId),
                ConsentState.DENIED
            )

            davonSCWClient.preferences.consentList.setConsentState(
                listOf(
                    ConsentListEntry(
                        eriSCWClient.address,
                        EntryType.ADDRESS,
                        ConsentState.ALLOWED
                    )
                )
            )
            alixMember = davonGroup.members().firstOrNull { it.inboxId == eriSCWClient.inboxId }
            assertEquals(alixMember!!.consentState, ConsentState.ALLOWED)
            assertEquals(
                davonSCWClient.preferences.consentList.inboxIdState(eriSCWClient.inboxId),
                ConsentState.ALLOWED
            )
            assertEquals(
                davonSCWClient.preferences.consentList.addressState(eriSCWClient.address),
                ConsentState.ALLOWED
            )
        }
    }

    @Test
    fun testCanStreamAllMessages() {
        val group1 = runBlocking {
            davonSCWClient.conversations.newGroup(
                listOf(
                    boV3.walletAddress,
                    eriSCW.walletAddress
                )
            )
        }
        val group2 = runBlocking {
            boV3Client.conversations.newGroup(
                listOf(
                    davonSCW.walletAddress,
                    eriSCW.walletAddress
                )
            )
        }
        val dm1 = runBlocking { davonSCWClient.conversations.findOrCreateDm(eriSCW.walletAddress) }
        val dm2 = runBlocking { boV3Client.conversations.findOrCreateDm(davonSCW.walletAddress) }
        runBlocking { davonSCWClient.conversations.syncConversations() }

        val allMessages = mutableListOf<DecodedMessage>()

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                davonSCWClient.conversations.streamAllMessages()
                    .collect { message ->
                        allMessages.add(message)
                    }
            } catch (e: Exception) {
            }
        }
        Thread.sleep(1000)
        runBlocking {
            group1.send("hi")
            group2.send("hi")
            dm1.send("hi")
            dm2.send("hi")
        }
        Thread.sleep(1000)
        assertEquals(4, allMessages.size)
        job.cancel()
    }

    @Test
    fun testCanStreamConversations() {
        val allMessages = mutableListOf<String>()

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                davonSCWClient.conversations.stream()
                    .collect { message ->
                        allMessages.add(message.topic)
                    }
            } catch (e: Exception) {
            }
        }
        Thread.sleep(1000)

        runBlocking {
            eriSCWClient.conversations.newGroup(listOf(boV3.walletAddress, davonSCW.walletAddress))
            boV3Client.conversations.newGroup(listOf(eriSCW.walletAddress, davonSCW.walletAddress))
            eriSCWClient.conversations.findOrCreateDm(davonSCW.walletAddress)
            boV3Client.conversations.findOrCreateDm(davonSCW.walletAddress)
        }

        Thread.sleep(1000)
        assertEquals(4, allMessages.size)
        job.cancel()
    }
}
