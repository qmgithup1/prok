package com.systemsgo.hex.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * RD-WEB-FEED FEATURE: a saved subscription to an RD Web Access
 * "RemoteApp and Desktop Connections" feed (MS-TSWP), e.g.
 * `https://rds.contoso.com/RDWeb/Feed/webfeed.aspx`.
 *
 * This only stores *how to reach and authenticate to* the feed — the actual
 * list of published RemoteApps/Desktops it returns is never persisted here;
 * it's re-fetched live (see [com.systemsgo.hex.webfeed.RdWebFeedClient]) each
 * time the user opens the feed's screen or pulls to refresh. Individual
 * resources the user chooses to "Add" become ordinary [RdpProfile] rows
 * (tagged via [RdpProfile.webFeedSubscriptionId]/[RdpProfile.webFeedAlias]),
 * exactly like a profile imported from a plain .rdp file — see
 * [com.systemsgo.hex.util.RdpFileParser], which the feed client reuses to
 * turn each resource's server-side .rdp file into a profile.
 */
@Entity(tableName = "web_feed_subscriptions")
data class WebFeedSubscription(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    /** Display name, e.g. "Contoso RD Web". Defaults to the host if left blank. */
    val name: String,
    /** Full, normalized feed URL — always ends in .../Feed/webfeed.aspx. */
    val feedUrl: String,
    val username: String = "",
    /** Stored encrypted at rest — see WebFeedRepository.withEncryptedSecrets(), same
     *  CryptoHelper + per-row AAD pattern RdpProfile.password already uses. */
    val password: String = "",
    val domain: String = "",
    // BUG-3-PARITY: mirrors RdpProfile.acceptSelfSignedCertificate — many internal
    // RD Web deployments sit behind a self-signed or internal-CA certificate.
    val acceptSelfSignedCertificate: Boolean = false,
    // When true, refreshing this feed automatically saves every resource that
    // isn't already imported as a connection (and updates ones that are),
    // instead of requiring the user to tap "Add" per resource every time.
    val autoImportNewResources: Boolean = false,
    // Optional folder (see ConnectionFolder) that imported connections from
    // this feed are filed under, so they stay grouped and distinguishable
    // from manually-added connections. Empty string = no folder.
    val targetFolderId: String = "",
    val lastRefreshed: Long = 0L,
    // Last error message from a refresh attempt (blank = last refresh, if any,
    // succeeded). Shown in the feed list so the user can see at a glance which
    // subscriptions need attention (expired password, server unreachable, ...).
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
