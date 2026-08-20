package com.hermes.agent.domain.product

/** Product-owned identity consumed by otherwise shared engine modules. */
data class ProductIdentity(
    val displayName: String,
    val notificationChannelId: String,
) {
    init {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(notificationChannelId.isNotBlank()) { "notificationChannelId must not be blank" }
    }
}
