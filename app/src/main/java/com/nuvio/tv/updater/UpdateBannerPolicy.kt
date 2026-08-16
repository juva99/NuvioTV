package com.nuvio.tv.updater

internal object UpdateBannerPolicy {
    fun shouldCheckAutomatically(
        bannerEnabled: Boolean,
        updateFeatureEnabled: Boolean
    ): Boolean = bannerEnabled && updateFeatureEnabled

    fun shouldShow(
        isRemoteNewer: Boolean,
        force: Boolean,
        bannerEnabled: Boolean,
        dismissedTag: String?,
        updateTag: String
    ): Boolean {
        if (!isRemoteNewer) return false
        if (force) return true
        return bannerEnabled && dismissedTag != updateTag
    }
}
