package org.xmtp.android.library

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.xmtp.android.library.ConsentState.Companion.toFfiConsentState
import org.xmtp.android.library.libxmtp.Message
import uniffi.xmtpv3.FfiConversation
import uniffi.xmtpv3.FfiConversationCallback
import uniffi.xmtpv3.FfiConversations
import uniffi.xmtpv3.FfiCreateGroupOptions
import uniffi.xmtpv3.FfiDirection
import uniffi.xmtpv3.FfiGroupPermissionsOptions
import uniffi.xmtpv3.FfiListConversationsOptions
import uniffi.xmtpv3.FfiListMessagesOptions
import uniffi.xmtpv3.FfiMessage
import uniffi.xmtpv3.FfiMessageCallback
import uniffi.xmtpv3.FfiPermissionPolicySet
import uniffi.xmtpv3.FfiSubscribeException
import uniffi.xmtpv3.org.xmtp.android.library.libxmtp.GroupPermissionPreconfiguration
import uniffi.xmtpv3.org.xmtp.android.library.libxmtp.PermissionPolicySet
import java.util.Date
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit

data class Conversations(
    var client: Client,
    private val ffiConversations: FfiConversations,
) {

    enum class ConversationOrder {
        CREATED_AT,
        LAST_MESSAGE;
    }

    suspend fun fromWelcome(envelopeBytes: ByteArray): Conversation {
        val conversation = ffiConversations.processStreamedWelcomeMessage(envelopeBytes)
        return if (conversation.groupMetadata().conversationType() == "dm") {
            Conversation.Dm(Dm(client, conversation))
        } else {
            Conversation.Group(Group(client, conversation))
        }
    }

    suspend fun newGroup(
        accountAddresses: List<String>,
        permissions: GroupPermissionPreconfiguration = GroupPermissionPreconfiguration.ALL_MEMBERS,
        groupName: String = "",
        groupImageUrlSquare: String = "",
        groupDescription: String = "",
        groupPinnedFrameUrl: String = "",
    ): Group {
        return newGroupInternal(
            accountAddresses,
            GroupPermissionPreconfiguration.toFfiGroupPermissionOptions(permissions),
            groupName,
            groupImageUrlSquare,
            groupDescription,
            groupPinnedFrameUrl,
            null
        )
    }

    suspend fun newGroupCustomPermissions(
        accountAddresses: List<String>,
        permissionPolicySet: PermissionPolicySet,
        groupName: String = "",
        groupImageUrlSquare: String = "",
        groupDescription: String = "",
        groupPinnedFrameUrl: String = "",
    ): Group {
        return newGroupInternal(
            accountAddresses,
            FfiGroupPermissionsOptions.CUSTOM_POLICY,
            groupName,
            groupImageUrlSquare,
            groupDescription,
            groupPinnedFrameUrl,
            PermissionPolicySet.toFfiPermissionPolicySet(permissionPolicySet)
        )
    }

    private suspend fun newGroupInternal(
        accountAddresses: List<String>,
        permissions: FfiGroupPermissionsOptions,
        groupName: String,
        groupImageUrlSquare: String,
        groupDescription: String,
        groupPinnedFrameUrl: String,
        permissionsPolicySet: FfiPermissionPolicySet?,
    ): Group {
        if (accountAddresses.size == 1 &&
            accountAddresses.first().lowercase() == client.address.lowercase()
        ) {
            throw XMTPException("Recipient is sender")
        }
        val falseAddresses =
            if (accountAddresses.isNotEmpty()) client.canMessage(accountAddresses)
                .filter { !it.value }.map { it.key } else emptyList()
        if (falseAddresses.isNotEmpty()) {
            throw XMTPException("${falseAddresses.joinToString()} not on network")
        }

        val group =
            ffiConversations.createGroup(
                accountAddresses,
                opts = FfiCreateGroupOptions(
                    permissions = permissions,
                    groupName = groupName,
                    groupImageUrlSquare = groupImageUrlSquare,
                    groupDescription = groupDescription,
                    groupPinnedFrameUrl = groupPinnedFrameUrl,
                    customPermissionPolicySet = permissionsPolicySet
                )
            )

        return Group(client, group)
    }

    // Sync from the network the latest list of conversations
    suspend fun syncConversations() {
        ffiConversations.sync()
    }

    // Sync all existing local conversation data from the network (Note: call syncConversations() first to get the latest list of conversations)
    suspend fun syncAllConversations(): UInt {
        return ffiConversations.syncAllConversations()
    }

    suspend fun newConversation(peerAddress: String): Conversation {
        val dm = findOrCreateDm(peerAddress)
        return Conversation.Dm(dm)
    }

    suspend fun findOrCreateDm(peerAddress: String): Dm {
        if (peerAddress.lowercase() == client.address.lowercase()) {
            throw XMTPException("Recipient is sender")
        }
        val falseAddresses =
            client.canMessage(listOf(peerAddress)).filter { !it.value }.map { it.key }
        if (falseAddresses.isNotEmpty()) {
            throw XMTPException("${falseAddresses.joinToString()} not on network")
        }
        var dm = client.findDm(peerAddress)
        if (dm == null) {
            val dmConversation = ffiConversations.createDm(peerAddress.lowercase())
            dm = Dm(client, dmConversation)
        }
        return dm
    }

    suspend fun listGroups(
        after: Date? = null,
        before: Date? = null,
        limit: Int? = null,
    ): List<Group> {
        val ffiGroups = ffiConversations.listGroups(
            opts = FfiListConversationsOptions(
                after?.time?.nanoseconds?.toLong(DurationUnit.NANOSECONDS),
                before?.time?.nanoseconds?.toLong(DurationUnit.NANOSECONDS),
                limit?.toLong()
            )
        )

        return ffiGroups.map {
            Group(client, it)
        }
    }

    suspend fun listDms(
        after: Date? = null,
        before: Date? = null,
        limit: Int? = null,
    ): List<Dm> {
        val ffiDms = ffiConversations.listDms(
            opts = FfiListConversationsOptions(
                after?.time?.nanoseconds?.toLong(DurationUnit.NANOSECONDS),
                before?.time?.nanoseconds?.toLong(DurationUnit.NANOSECONDS),
                limit?.toLong()
            )
        )

        return ffiDms.map {
            Dm(client, it)
        }
    }

    suspend fun list(
        after: Date? = null,
        before: Date? = null,
        limit: Int? = null,
        order: ConversationOrder = ConversationOrder.CREATED_AT,
        consentState: ConsentState? = null,
    ): List<Conversation> {
        val ffiConversations = ffiConversations.list(
            FfiListConversationsOptions(
                after?.time?.nanoseconds?.toLong(DurationUnit.NANOSECONDS),
                before?.time?.nanoseconds?.toLong(DurationUnit.NANOSECONDS),
                limit?.toLong()
            )
        )

        val filteredConversations = filterByConsentState(ffiConversations, consentState)
        val sortedConversations = sortConversations(filteredConversations, order)

        return sortedConversations.map { it.toConversation() }
    }

    private fun sortConversations(
        conversations: List<FfiConversation>,
        order: ConversationOrder,
    ): List<FfiConversation> {
        return when (order) {
            ConversationOrder.LAST_MESSAGE -> {
                conversations.map { conversation ->
                    val message =
                        conversation.findMessages(
                            FfiListMessagesOptions(
                                null,
                                null,
                                1,
                                null,
                                FfiDirection.DESCENDING
                            )
                        )
                            .firstOrNull()
                    conversation to message?.sentAtNs
                }.sortedByDescending {
                    it.second ?: 0L
                }.map {
                    it.first
                }
            }

            ConversationOrder.CREATED_AT -> conversations
        }
    }

    private fun filterByConsentState(
        conversations: List<FfiConversation>,
        consentState: ConsentState?,
    ): List<FfiConversation> {
        return consentState?.let { state ->
            conversations.filter { it.consentState() == toFfiConsentState(state) }
        } ?: conversations
    }

    private fun FfiConversation.toConversation(): Conversation {
        return if (groupMetadata().conversationType() == "dm") {
            Conversation.Dm(Dm(client, this))
        } else {
            Conversation.Group(Group(client, this))
        }
    }

    fun stream(/*Maybe Put a way to specify group, dm, or both?*/): Flow<Conversation> =
        callbackFlow {
            val conversationCallback = object : FfiConversationCallback {
                override fun onConversation(conversation: FfiConversation) {
                    if (conversation.groupMetadata().conversationType() == "dm") {
                        trySend(Conversation.Dm(Dm(client, conversation)))
                    } else {
                        trySend(Conversation.Group(Group(client, conversation)))
                    }
                }

                override fun onError(error: FfiSubscribeException) {
                    Log.e("XMTP Conversation stream", error.message.toString())
                }
            }
            val stream = ffiConversations.stream(conversationCallback)
            awaitClose { stream.end() }
        }

    fun streamAllMessages(/*Maybe Put a way to specify group, dm, or both?*/): Flow<DecodedMessage> =
        callbackFlow {
            val messageCallback = object : FfiMessageCallback {
                override fun onMessage(message: FfiMessage) {
                    val decodedMessage = Message(client, message).decodeOrNull()
                    decodedMessage?.let { trySend(it) }
                }

                override fun onError(error: FfiSubscribeException) {
                    Log.e("XMTP all message stream", error.message.toString())
                }
            }

            val stream = ffiConversations.streamAllMessages(messageCallback)
            awaitClose { stream.end() }
        }
}
