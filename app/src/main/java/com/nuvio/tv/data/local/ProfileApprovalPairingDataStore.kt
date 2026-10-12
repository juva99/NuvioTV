package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profileapproval.NtfyProfileApprovalPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

const val PROFILE_APPROVAL_FEATURE = "profile_approval"
const val PROFILE_APPROVAL_TOPIC_KEY = "topic"

@Singleton
class ProfileApprovalPairingDataStore @Inject constructor(
    private val profileDataStoreFactory: ProfileDataStoreFactory
) {
    private val topicKey = stringPreferencesKey(PROFILE_APPROVAL_TOPIC_KEY)

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

    suspend fun setTopic(profileId: Int, topic: String?) {
        require(topic == null || NtfyProfileApprovalPolicy.isProfileTopic(topic)) {
            "Invalid ntfy profile topic"
        }
        profileDataStoreFactory.get(profileId, PROFILE_APPROVAL_FEATURE).edit { preferences ->
            if (topic == null) preferences.remove(topicKey) else preferences[topicKey] = topic
        }
    }
}
