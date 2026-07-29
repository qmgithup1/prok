package com.systemsgo.hex.netconf.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** RFC 6241 §8.3 standard datastores, plus the two commonly-implemented extensions this app's YANG-discovery layer also surfaces. */
enum class NetconfDatastore(val elementName: String) {
    RUNNING("running"),
    CANDIDATE("candidate"),
    STARTUP("startup"),
    /** RFC 8342 (NMDA) operational datastore — read-only, server-populated. */
    OPERATIONAL("operational"),
    /** RFC 8342 (NMDA) intended datastore. */
    INTENDED("intended"),
}

sealed class NetconfFilter {
    data object None : NetconfFilter()
    data class Subtree(val xml: String) : NetconfFilter()
    /** RFC 6241 §8.9.1 xpath filter — requires the server to advertise `:xpath` capability. */
    data class XPath(val expression: String, val namespaces: Map<String, String> = emptyMap()) : NetconfFilter()

    fun toXml(): String = when (this) {
        is None -> ""
        is Subtree -> "<filter type=\"subtree\">$xml</filter>"
        is XPath -> {
            val nsDecls = namespaces.entries.joinToString(" ") { (prefix, uri) -> "xmlns:$prefix=\"$uri\"" }
            "<filter type=\"xpath\" select=\"${escapeAttr(expression)}\" $nsDecls/>"
        }
    }

    companion object {
        fun escapeAttr(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    }
}

/** How a `<commit>` should behave — plain, confirmed (RFC 6241 §8.4), or persisted-confirmed. */
data class CommitOptions(
    val confirmed: Boolean = false,
    val confirmTimeoutSeconds: Int? = null,
    val persist: String? = null,
    val persistId: String? = null,
)

enum class DefaultOperation { MERGE, REPLACE, NONE }
enum class TestOption { TEST_THEN_SET, SET, TEST_ONLY }
enum class ErrorOption { STOP_ON_ERROR, CONTINUE_ON_ERROR, ROLLBACK_ON_ERROR }

class NetconfRpcException(val rpcErrorXml: String, message: String) : Exception(message)

/**
 * NETCONF FEATURE (Part 2/N — RPC engine): the full RFC 6241 base operation
 * set plus RFC 5277 event notifications, implemented as a thin, testable
 * layer on top of [NetconfClient.sendRawRpc] — every method here just
 * builds the correct `<rpc>` body, awaits the correlated reply, and either
 * returns the raw `<rpc-reply>` payload (for get/get-config, where the
 * caller — the future YANG/XML-editor UI — wants the real XML) or throws
 * [NetconfRpcException] when the reply contains `<rpc-error>`.
 *
 * Kept as a separate class from [NetconfClient] itself (composition, not
 * inheritance) so the transport layer stays focused on connection/framing/
 * reconnect, and this layer stays focused on RFC 6241 semantics — mirrors
 * how [com.systemsgo.hex.redfish.protocol.RedfishClient] keeps its
 * low-level `request()` helper separate from its higher-level per-resource
 * methods.
 */
class NetconfOperations(private val client: NetconfClient) {

    // ── retrieval ────────────────────────────────────────────────────

    suspend fun get(filter: NetconfFilter = NetconfFilter.None): String =
        rpc("<get>${filter.toXml()}</get>")

    suspend fun getConfig(source: NetconfDatastore, filter: NetconfFilter = NetconfFilter.None): String =
        rpc("<get-config><source><${source.elementName}/></source>${filter.toXml()}</get-config>")

    // ── configuration ────────────────────────────────────────────────

    /**
     * @param configXml the `<config>...</config>` element body's *inner*
     *   XML — e.g. `<interfaces xmlns="..."><interface>...</interface></interfaces>` —
     *   this wraps it in `<config>` itself.
     */
    suspend fun editConfig(
        target: NetconfDatastore,
        configXml: String,
        defaultOperation: DefaultOperation = DefaultOperation.MERGE,
        testOption: TestOption? = null,
        errorOption: ErrorOption? = null,
    ): Unit {
        val body = buildString {
            append("<edit-config><target><${target.elementName}/></target>")
            append("<default-operation>${defaultOperation.name.lowercase()}</default-operation>")
            testOption?.let { append("<test-option>${it.name.lowercase().replace('_', '-')}</test-option>") }
            errorOption?.let { append("<error-option>${it.name.lowercase().replace('_', '-')}</error-option>") }
            append("<config>$configXml</config></edit-config>")
        }
        rpc(body)
    }

    suspend fun copyConfig(target: NetconfDatastore, source: NetconfDatastore) =
        rpc("<copy-config><target><${target.elementName}/></target><source><${source.elementName}/></source></copy-config>")

    /** Copies a raw config XML document directly into [target] (RFC 6241 §7.3 <copy-config> with an inline <config> source). */
    suspend fun copyConfigFromXml(target: NetconfDatastore, configXml: String) =
        rpc("<copy-config><target><${target.elementName}/></target><source><config>$configXml</config></source></copy-config>")

    suspend fun deleteConfig(target: NetconfDatastore) {
        require(target != NetconfDatastore.RUNNING) { "delete-config MUST NOT target :running (RFC 6241 §7.4)" }
        rpc("<delete-config><target><${target.elementName}/></target></delete-config>")
    }

    // ── locking ──────────────────────────────────────────────────────

    suspend fun lock(target: NetconfDatastore) {
        rpc("<lock><target><${target.elementName}/></target></lock>")
        client.notifyLockAcquired(target.elementName)
    }

    suspend fun unlock(target: NetconfDatastore) {
        rpc("<unlock><target><${target.elementName}/></target></unlock>")
        client.notifyLockReleased(target.elementName)
    }

    // ── validation / commit workflow ─────────────────────────────────

    suspend fun validate(source: NetconfDatastore) =
        rpc("<validate><source><${source.elementName}/></source></validate>")

    suspend fun commit(options: CommitOptions = CommitOptions()) {
        val body = if (!options.confirmed) "<commit/>" else buildString {
            append("<commit><confirmed/>")
            options.confirmTimeoutSeconds?.let { append("<confirm-timeout>$it</confirm-timeout>") }
            options.persist?.let { append("<persist>$it</persist>") }
            options.persistId?.let { append("<persist-id>$it</persist-id>") }
            append("</commit>")
        }
        rpc(body)
    }

    suspend fun cancelCommit(persistId: String? = null) =
        rpc(if (persistId != null) "<cancel-commit><persist-id>$persistId</persist-id></cancel-commit>" else "<cancel-commit/>")

    suspend fun discardChanges() = rpc("<discard-changes/>")

    // ── session management ───────────────────────────────────────────

    suspend fun closeSession() {
        rpc("<close-session/>")
        client.disconnect()
    }

    suspend fun killSession(sessionId: Int) = rpc("<kill-session><session-id>$sessionId</session-id></kill-session>")

    // ── notifications (RFC 5277) ─────────────────────────────────────

    /**
     * Sends `<create-subscription>` and (on success) starts forwarding every
     * subsequent [NetconfClient.notifications] event through [onEvent] on
     * [scope], filtered by [streamFilter] if given (client-side filtering —
     * `<create-subscription>` itself already supports server-side
     * start/stop time and an event `<filter>`, both passed through here).
     * Returns once the subscription RPC itself is acknowledged; events keep
     * arriving asynchronously for the life of the session.
     */
    suspend fun createSubscription(
        stream: String = "NETCONF",
        filter: NetconfFilter = NetconfFilter.None,
        startTime: String? = null,
        stopTime: String? = null,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        streamFilter: ((String) -> Boolean)? = null,
        onEvent: (String) -> Unit = {},
    ) {
        val body = buildString {
            append("<create-subscription xmlns=\"urn:ietf:params:xml:ns:netconf:notification:1.0\">")
            append("<stream>$stream</stream>")
            append(filter.toXml())
            startTime?.let { append("<startTime>$it</startTime>") }
            stopTime?.let { append("<stopTime>$it</stopTime>") }
            append("</create-subscription>")
        }
        rpc(body)
        client.notifications
            .onEach { event -> if (streamFilter == null || streamFilter(event)) onEvent(event) }
            .launchIn(scope)
    }

    // ── shared reply handling ─────────────────────────────────────────

    private suspend fun rpc(bodyXml: String): String {
        val reply = client.sendRawRpc(bodyXml)
        if (reply.contains("<rpc-error") || reply.contains("<rpc-error>")) {
            throw NetconfRpcException(reply, extractFirstErrorMessage(reply))
        }
        return reply
    }

    private fun extractFirstErrorMessage(replyXml: String): String {
        val m = Regex("<error-message[^>]*>(.*?)</error-message>", RegexOption.DOT_MATCHES_ALL).find(replyXml)
        return m?.groupValues?.get(1)?.trim()?.ifBlank { null }
            ?: "NETCONF server returned rpc-error (no error-message element)"
    }
}
