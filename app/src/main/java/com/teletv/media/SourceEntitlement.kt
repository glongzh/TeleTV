package com.teletv.media

/**
 * Gate for using a non-default media source. The seam for a future paid
 * subscription: the default Saved Messages source is always permitted; every
 * other source passes through [canUseSource]. This phase permits everything;
 * a later change swaps the implementation to enforce entitlements without
 * touching source, index, or grid code.
 */
interface SourceEntitlement {
    fun canUseSource(chatId: Long): Boolean
}

/** Current behavior: every source is allowed. */
object AllowAllEntitlement : SourceEntitlement {
    override fun canUseSource(chatId: Long): Boolean = true
}
