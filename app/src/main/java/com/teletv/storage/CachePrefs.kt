package com.teletv.storage

/** A selectable cache cap. [bytes] is null for Unlimited (auto-trim disabled). */
enum class CacheCap(val bytes: Long?) {
    ONE_GB(1L * 1024 * 1024 * 1024),
    TWO_GB(2L * 1024 * 1024 * 1024),
    FIVE_GB(5L * 1024 * 1024 * 1024),
    UNLIMITED(null),
}

/**
 * Persisted cache-size cap, read at each automatic or manual trim. Generation-
 * independent (reused across sign-ins), like [com.teletv.security.PinRepository].
 */
class CachePrefs(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("storage_cache", android.content.Context.MODE_PRIVATE)

    fun currentCap(): CacheCap =
        CacheCap.entries.find { it.name == prefs.getString(KEY_CAP, DEFAULT.name) } ?: DEFAULT

    fun setCap(cap: CacheCap) {
        prefs.edit().putString(KEY_CAP, cap.name).apply()
    }

    private companion object {
        const val KEY_CAP = "cache_cap"
        val DEFAULT = CacheCap.TWO_GB
    }
}
