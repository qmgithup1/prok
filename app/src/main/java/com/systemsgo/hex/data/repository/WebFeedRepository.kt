package com.systemsgo.hex.data.repository

import com.systemsgo.hex.data.db.WebFeedSubscriptionDao
import com.systemsgo.hex.data.model.WebFeedSubscription
import com.systemsgo.hex.security.CryptoHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RD-WEB-FEED FEATURE: persistence for saved feed subscriptions. Mirrors
 * [RdpProfileRepository]'s encrypt-on-write / decrypt-on-read pattern for the
 * `password` field — same CryptoHelper + per-row AAD ("<id>:password")
 * scheme, so a feed's login is protected at rest exactly like a connection's.
 */
@Singleton
class WebFeedRepository @Inject constructor(
    private val dao: WebFeedSubscriptionDao
) {
    private fun aad(id: String) = "$id:password"

    private fun WebFeedSubscription.withEncryptedSecrets(): WebFeedSubscription =
        copy(password = CryptoHelper.encrypt(password, aad(id)))

    private fun WebFeedSubscription.withDecryptedSecrets(): WebFeedSubscription = try {
        copy(password = CryptoHelper.decrypt(password, aad(id)))
    } catch (e: SecurityException) {
        android.util.Log.e("WebFeedRepository", "Failed to decrypt password for feed $id", e)
        copy(password = "")
    }

    fun getAllSubscriptions(): Flow<List<WebFeedSubscription>> =
        dao.getAllSubscriptions().map { list -> list.map { it.withDecryptedSecrets() } }

    suspend fun getSubscriptionById(id: String): WebFeedSubscription? =
        dao.getSubscriptionById(id)?.withDecryptedSecrets()

    suspend fun saveSubscription(subscription: WebFeedSubscription) =
        dao.insertSubscription(subscription.withEncryptedSecrets())

    suspend fun updateSubscription(subscription: WebFeedSubscription) =
        dao.updateSubscription(subscription.withEncryptedSecrets())

    suspend fun deleteSubscription(subscription: WebFeedSubscription) =
        dao.deleteSubscription(subscription)

    suspend fun updateRefreshResult(id: String, error: String) =
        dao.updateRefreshResult(id, System.currentTimeMillis(), error)
}
