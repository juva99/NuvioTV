package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profileapproval.NtfyProfileApprovalPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

const val PROFILE_APPROVAL_FEATURE = "profile_approval"
const val PROFILE_APPROVAL_TOPIC_KEY = "topic"

@Singleton
class ProfileApprovalPairingDataStore @Inject constructor(
    private val profileDataStoreFactory: ProfileDataStoreFactory
) {
    private val topicKey = stringPreferencesKey(PROFILE_APPROVAL_TOPIC_KEY)
    private val mutex = Mutex()

    fun observeTopic(profileId: Int): Flow<String?> =
        profileDataStoreFactory.get(profileId, PROFILE_APPROVAL_FEATURE)
            .data
            .map { preferences ->
                preferences[topicKey]?.takeIf(NtfyProfileApprovalPolicy::isProfileTopic)
            }

    fun observeTopics(profileIds: List<Int>): Flow<Map<Int, String>> {
        if (profileIds.isEmpty()) return flowOf(emptyMap())
        return combine(profileIds.distinct().map { profileId ->
            observeTopic(profileId).map { topic -> profileId to topic }
        }) { entries ->
            entries.mapNotNull { (profileId, topic) -> topic?.let { profileId to it } }.toMap()
        }
    }

    suspend fun topicFor(profileId: Int): String? = mutex.withLock {
        readTopic(profileId)
    }

    suspend fun ensureTopic(profileId: Int): String = mutex.withLock {
        val store = profileDataStoreFactory.get(profileId, PROFILE_APPROVAL_FEATURE)
        var result: String? = null
        store.edit { preferences ->
            result = preferences[topicKey]?.takeIf(NtfyProfileApprovalPolicy::isProfileTopic)
                ?: NtfyProfileApprovalPolicy.generateProfileTopic()
            preferences[topicKey] = result!!
        }
        result!!
    }

    suspend fun setTopic(profileId: Int, topic: String) = mutex.withLock {
        require(NtfyProfileApprovalPolicy.isProfileTopic(topic)) {
            "Invalid ntfy profile topic"
        }
        profileDataStoreFactory.get(profileId, PROFILE_APPROVAL_FEATURE).edit { preferences ->
            preferences[topicKey] = topic
        }
    }

    suspend fun regenerateTopic(profileId: Int): String = mutex.withLock {
        val topic = NtfyProfileApprovalPolicy.generateProfileTopic()
        profileDataStoreFactory.get(profileId, PROFILE_APPROVAL_FEATURE).edit { preferences ->
            preferences[topicKey] = topic
        }
        topic
    }

    suspend fun disable(profileId: Int) = mutex.withLock {
        profileDataStoreFactory.get(profileId, PROFILE_APPROVAL_FEATURE).edit { preferences ->
            preferences.remove(topicKey)
        }
    }

    suspend fun removeProfile(profileId: Int) {
        if (profileId == 1) {
            disable(profileId)
        } else {
            profileDataStoreFactory.clearProfile(profileId)
        }
    }

    /**
     * Profile-scoped stores are cleared by ProfileDataStoreFactory during an
     * account reset. This method remains for the reset service API and clears
     * no unrelated profile settings.
     */
    suspend fun clearAll() = Unit

    private suspend fun readTopic(profileId: Int): String? =
        profileDataStoreFactory.get(profileId, PROFILE_APPROVAL_FEATURE)
            .data
            .first()[topicKey]
            ?.takeIf(NtfyProfileApprovalPolicy::isProfileTopic)
}
