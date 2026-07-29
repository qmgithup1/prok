package com.systemsgo.hex.amt.protocol

import android.content.Context
import com.systemsgo.hex.security.TofuTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Intel AMT / vPro WS-Management client — the AMT counterpart to
 * [com.systemsgo.hex.redfish.protocol.RedfishClient] /
 * [com.systemsgo.hex.ipmi.protocol.IpmiClient] for out-of-band server
 * management, targeting Intel's own Management Engine firmware directly
 * (port 16992 plain / 16993 TLS) rather than a separate BMC chip. Covers
 * phases 1-2 of AMT support: connectivity/identity check, power control,
 * real firmware-version identity, one-shot boot-device control, and the
 * audit log. [openSolSession] and [openKvmSession] are phase 3/4's bridges
 * into the non-WS-Man world — see [AmtSolSession] (APF-framed SOL) and
 * [AmtKvmSession] (RFB-based KVM) for the actual wire protocols; this class
 * itself only handles each one's WS-Man *enable* step. [openIderSession]
 * (phase 5) is the same shape but only gets as far as opening the
 * connection — see [AmtIderSession]'s doc comment for why the actual
 * media-serving protocol on top of that connection isn't implemented yet.
 *
 * ## Why hand-rolled SOAP instead of a WS-Man/CIM library
 * AMT implements a small, fixed subset of DMTF WS-Management (WS-Transfer
 * Get, WS-Enumeration Enumerate/Pull, and WS-Man method Invoke for CIM/IPS/
 * AMT "extrinsic methods") against a handful of classes whose exact request
 * shape is published in Intel's AMT SDK class reference — there's no WSDL
 * introspection involved. A generic CIM/WBEM client would be substantial
 * overkill for the ~4 message shapes this app actually needs, so this class
 * builds those envelopes directly, the same "small, purpose-built protocol
 * client" shape [com.systemsgo.hex.redfish.protocol.RedfishClient] and
 * [com.systemsgo.hex.ipmi.protocol.IpmiClient] already use.
 *
 * ## Auth
 * AMT's embedded web server exclusively speaks HTTP Digest (RFC 7616,
 * qop=auth) — never Basic, and it has no session/cookie concept, so every
 * POST is independently challenged. [request] does the standard
 * "send once, read the 401 WWW-Authenticate challenge, compute the Digest
 * response, resend once" round trip inline, matching
 * [com.systemsgo.hex.redfish.protocol.RedfishClient]'s single-method
 * request-with-retry style rather than plugging into OkHttp's
 * [okhttp3.Authenticator] interface — this keeps everything about one HTTP
 * exchange (challenge, response, retry) readable in one place.
 */
class AmtClient(
    private val host: String,
    /** 16992 = plain HTTP (default — most lab/SMB "admin control mode"
     *  setups). 16993 = HTTPS/TLS (requires the box to have been
     *  provisioned with a TLS certificate, e.g. via MeshCentral/SCS in an
     *  enterprise ACM deployment). */
    private val port: Int = 16992,
    private val username: String = "admin",
    private val password: String,
    private val useTls: Boolean = false,
    private val acceptSelfSignedCertificate: Boolean = true,
    /**
     * SECURITY FIX (TLS-TOFU-PARITY): application [Context], used only when
     * [useTls] and [acceptSelfSignedCertificate] are both on, to back a
     * [TofuTrustManager] instead of the old blind trust-all manager — see
     * that class's doc comment. Optional (default null) purely so existing
     * call sites/tests that construct this client without a Context still
     * compile; passing one is what actually gets pinning + MITM detection
     * instead of the legacy trust-all fallback below.
     */
    private val appContext: Context? = null,
    /**
     * AMT-VPRO FEATURE phase 6 (CIRA), WS-Man-over-CIRA follow-up: closes
     * the gap AMT_VPRO_ROADMAP.md's "Not yet started" section flagged —
     * "WS-Man over CIRA (device management — power control, boot device,
     * firmware/identity info)". When supplied, every WS-Man HTTP exchange
     * this class makes ([getGeneralInfo], [powerControl], [setOneShotBoot],
     * [getAuditLog], the best-effort SOL/KVM/IDE-R "enable" calls, ...)
     * goes through a [CiraWsmanHttpTransport] wrapping an
     * [AmtRedirectionTransport] this factory opens (a [CiraRelayTransport]
     * channel to the WS-Man port in practice) instead of [httpClient]
     * dialing [host]/[port] directly — which, under real CIRA, isn't a
     * reachable address at all (see [CiraRelayTransport]'s "What this class
     * does NOT cover" doc section, which is exactly the gap this parameter
     * closes). A factory rather than a pre-opened transport so a dropped
     * connection can be transparently reopened — see
     * [CiraWsmanHttpTransport]'s doc comment for why that's a real,
     * expected case rather than an edge condition.
     *
     * Doesn't change how [openSolSession]/[openKvmSession]/
     * [openIderSession] work — those already take their own
     * `externalTransport` parameter for the *redirection*-port channel,
     * entirely independent of this one (WS-Man and redirection are
     * different channels to different ports even under CIRA, exactly as
     * they are for a direct connection).
     */
    private val externalWsmanTransportFactory: (() -> AmtRedirectionTransport)? = null,
) {
    private val wsmanUrl: String
        get() = "${if (useTls) "https" else "http"}://$host:$port/wsman"

    private val httpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS) // power-state changes can take longer than a plain Get while the ME commits the transition
        if (useTls && acceptSelfSignedCertificate) {
            // Mirrors RedfishClient's identical reasoning: AMT boxes very
            // commonly run a self-signed or internal-CA TLS cert even in
            // legitimate enterprise deployments, so refusing it outright
            // would make TLS mode unusable for most real setups.
            //
            // SECURITY FIX (TLS-TOFU-PARITY): this used to be a blind
            // trust-all X509TrustManager — once the user opted in to
            // "accept self-signed certificate" for this AMT profile, *every*
            // future connection trusted *any* certificate, with no
            // fingerprint pinning and no detection of a later-substituted
            // (MITM) certificate, unlike Telnet/RDP/NETCONF/Guacamole
            // elsewhere in this app. Now uses the same silent TOFU pinning
            // those protocols use — see TofuTrustManager's doc comment.
            val identity = "$host:$port"
            // SECURITY FIX (TLS-TOFU-NO-FALLBACK): a missing appContext used to
            // fall back to a trust-all X509TrustManager (every certificate
            // accepted, no pinning, no MITM detection) — exactly the
            // vulnerability TofuTrustManager exists to close. Fail closed
            // instead of silently downgrading to an insecure connection.
            val trustManager: X509TrustManager = appContext?.let { TofuTrustManager(it, identity) }
                ?: throw IllegalStateException(
                    "acceptSelfSignedCertificate is on for '$identity' but no appContext was " +
                        "supplied to AmtClient — TOFU certificate pinning requires a Context to " +
                        "store the pinned fingerprint. Refusing to connect with a trust-all " +
                        "fallback. Pass appContext to AmtClient's constructor.",
                )
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            // Safe to skip the hostname/CN check here: the trust manager
            // above already pins the exact certificate fingerprint, which is
            // a strictly stronger identity guarantee than a name match.
            builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
        builder.build()
    }

    // Cached across calls on this instance so a healthy connection doesn't
    // repeat the full 401-challenge round trip on every single request —
    // reused directly as long as the nonce hasn't gone stale, in which case
    // [request] transparently re-challenges and refreshes it.
    private var cachedChallenge: DigestChallenge? = null
    private val nonceCount = java.util.concurrent.atomic.AtomicInteger(0)

    // AMT-VPRO FEATURE phase 6 (CIRA), WS-Man-over-CIRA follow-up — see
    // [externalWsmanTransportFactory]'s doc comment. Lazy so a plain direct
    // (non-CIRA) AmtClient never touches this at all, and so the actual
    // relay channel isn't opened until the first real WS-Man call needs it
    // rather than eagerly in the constructor.
    private val ciraWsmanTransport: CiraWsmanHttpTransport? by lazy {
        externalWsmanTransportFactory?.let { factory -> CiraWsmanHttpTransport(host = host, openTransport = factory) }
    }

    // ── connection lifecycle ─────────────────────────────────────────

    /** Verifies reachability + credentials by reading AMT_GeneralSettings —
     *  the same "cheap, always-present, read-only" probe
     *  [com.systemsgo.hex.redfish.protocol.RedfishClient.probeServiceRoot]
     *  uses for Redfish. Throws [AmtException] on any failure. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        getGeneralInfo()
        Unit
    }

    /** AMT's WS-Man endpoint itself is stateless per request (no
     *  session/cookie) — nothing to tear down there, unlike Redfish's
     *  SessionService login. The one thing this *does* need to close: a
     *  [externalWsmanTransportFactory]-backed CIRA channel is a real,
     *  persistent connection (see [CiraWsmanHttpTransport]'s doc comment
     *  on why it's kept open across calls rather than reopened per
     *  request), unlike [httpClient]'s pooled direct connections which
     *  OkHttp already manages its own lifecycle for. Safe to call even if
     *  no WS-Man call was ever made (the transport is opened lazily) or
     *  this client was never CIRA-backed at all. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        ciraWsmanTransport?.close()
    }

    // ── identity ──────────────────────────────────────────────────────

    suspend fun getGeneralInfo(): AmtGeneralInfo = withContext(Dispatchers.IO) {
        val resourceUri = "$NS_AMT/AMT_GeneralSettings"
        val fields = request(transferGetEnvelope(resourceUri))
        AmtGeneralInfo(
            // Firmware version isn't a field of AMT_GeneralSettings itself —
            // it's a separate lookup against CIM_SoftwareIdentity (phase 2 —
            // see AMT_VPRO_ROADMAP.md). Swallow failures here rather than
            // letting a version lookup break the connectivity/identity check
            // this function is primarily used for.
            amtVersion = runCatching { findSoftwareIdentityVersionString(SOFTWARE_ID_AMT_CORE_VERSION) }
                .getOrNull(),
            hostName = fields["HostName"],
            networkInterfaceEnabled = fields["AMTNetworkEnabled"] == "1",
            digestRealm = fields["DigestRealm"],
        )
    }

    /**
     * Finds a `CIM_SoftwareIdentity` instance by its `InstanceID` (e.g.
     * [SOFTWARE_ID_AMT_CORE_VERSION]) and returns its `VersionString`, or
     * null if that instance isn't present. `CIM_SoftwareIdentity` is a
     * multi-instance class (AMT exposes ~10 — Flash, Netstack, AMTApps,
     * Sku, the core FW version, etc. — see Intel's "Get Code Versions" /
     * "Get Core Version" use cases), so unlike [getGeneralInfo]'s single
     * Get this walks the Enumerate+Pull result one instance at a time
     * (`MaxElements=1` per [pullEnvelope], same as [getPowerStatus])
     * checking `InstanceID` on each, stopping as soon as it's found or the
     * enumeration runs out. A bounded loop rather than a truly unbounded
     * one only as a safety net against a malformed/never-ending
     * enumeration context.
     */
    private fun findSoftwareIdentityVersionString(instanceId: String): String? {
        val resourceUri = "$NS_CIM/CIM_SoftwareIdentity"
        var context = request(enumerateEnvelope(resourceUri))["EnumerationContext"] ?: return null
        repeat(MAX_SOFTWARE_IDENTITY_INSTANCES) {
            val fields = request(pullEnvelope(resourceUri, context))
            if (fields["InstanceID"] == instanceId) return fields["VersionString"]
            context = fields["EnumerationContext"] ?: return null
        }
        return null
    }

    // ── power ─────────────────────────────────────────────────────────

    /** Current reported power state via
     *  CIM_AssociatedPowerManagementService — an *association* class (no
     *  single key), so unlike the Get-based calls above this needs a real
     *  Enumerate+Pull round trip. */
    suspend fun getPowerStatus(): AmtPowerStatus = withContext(Dispatchers.IO) {
        val resourceUri = "$NS_CIM/CIM_AssociatedPowerManagementService"
        val enumFields = request(enumerateEnvelope(resourceUri))
        val context = enumFields["EnumerationContext"]
            ?: throw AmtException("AMT didn't return an enumeration context for power status.")
        val pullFields = request(pullEnvelope(resourceUri, context))
        val stateValue = pullFields["PowerState"]?.toIntOrNull()
            ?: throw AmtException("AMT's power-status response didn't include a PowerState value.")
        AmtPowerStatus(stateValue = stateValue, label = amtPowerStateLabel(stateValue))
    }

    /** Requests a power-state transition via
     *  CIM_PowerManagementService.RequestPowerStateChange, targeting the
     *  well-known "ManagedSystem" CIM_ComputerSystem instance — the exact
     *  shape documented in Intel's AMT SDK "Change System Power State" use
     *  case and used identically by every open-source AMT stack
     *  (MeshCommander, MeshCentral, python-amt). */
    suspend fun powerControl(action: AmtPowerAction) = withContext(Dispatchers.IO) {
        val resourceUri = "$NS_CIM/CIM_PowerManagementService"
        val managedElementEpr = """
            <p:ManagedElement>
              <a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address>
              <a:ReferenceParameters>
                <w:ResourceURI>$NS_CIM/CIM_ComputerSystem</w:ResourceURI>
                <w:SelectorSet>
                  <w:Selector Name="Name">ManagedSystem</w:Selector>
                  <w:Selector Name="CreationClassName">CIM_ComputerSystem</w:Selector>
                </w:SelectorSet>
              </a:ReferenceParameters>
            </p:ManagedElement>
        """.trimIndent()
        val paramsXml = "<p:PowerState>${action.wsmanValue}</p:PowerState>\n$managedElementEpr"
        val envelope = invokeEnvelope(
            resourceUri = resourceUri,
            methodName = "RequestPowerStateChange",
            selectorName = "Name",
            selectorValue = "Intel(r) AMT Power Management Service",
            parametersXml = paramsXml,
        )
        request(envelope)
        Unit
    }

    // ── boot control ─────────────────────────────────────────────────
    // AMT-VPRO FEATURE phase 2: "boot to PXE/BIOS/CD once" — the WS-Man
    // shape Intel's SDK calls "Choosing Remote Control Boot Options":
    //   1. (BIOS_SETUP only) AMT_BootSettingData.Put with BIOSSetup=true
    //      and every other flag/index reset to false/0 — a boot *source*
    //      and these BIOS flags are mutually exclusive, so a boot-source
    //      selection is always cleared first.
    //   1b. (IDER_FLOPPY/IDER_CD_DVD, phase 5 follow-up) same
    //      AMT_BootSettingData.Put path but UseIDER=true + IDERBootDevice
    //      instead of BIOSSetup=true — see [armIderBootFlag]'s doc comment.
    //      Also mutually exclusive with a boot source, cleared the same way.
    //   2. (PXE/CD_DVD/HARD_DRIVE) CIM_BootConfigSetting.ChangeBootOrder
    //      with the EPR of the target CIM_BootSourceSetting.
    //   3. Either way, CIM_BootService.SetBootConfigRole(Role=1 /
    //      IsNextSingleUse) — this is what actually arms it; step 1/2 alone
    //      only stages the setting. The BIOS clears the role itself after
    //      executing it once, so no explicit "undo" is needed here.

    /** Arms [device] to be used for exactly the next boot/reset. Callers
     *  still need to actually trigger that reset — e.g.
     *  `powerControl(AmtPowerAction.POWER_CYCLE)` or a manual reset — this
     *  only stages *what* the next boot uses, matching how Intel's own SDK
     *  separates "choose boot options" from "change power state".
     *
     *  [AmtBootDevice.IDER_FLOPPY]/[AmtBootDevice.IDER_CD_DVD] arm IDE-R
     *  virtual media instead of a physical source — see [armIderBootFlag]'s
     *  doc comment (AMT_VPRO_ROADMAP.md phase 5's "open follow-up", now
     *  closed). Pair this with [AmtIderSession.mountAndServe] having
     *  already served the matching media type, or the box just hangs on
     *  reset waiting for a device that isn't actually streaming anything. */
    suspend fun setOneShotBoot(device: AmtBootDevice): Unit = withContext(Dispatchers.IO) {
        when (device) {
            AmtBootDevice.BIOS_SETUP -> armBiosSetupBootFlag()
            AmtBootDevice.IDER_FLOPPY -> armIderBootFlag(AmtIderMediaType.FLOPPY)
            AmtBootDevice.IDER_CD_DVD -> armIderBootFlag(AmtIderMediaType.CD_ROM)
            else -> changeBootOrder(device.bootSourceInstanceId)
        }
        setBootConfigRole(ROLE_IS_NEXT_SINGLE_USE)
    }

    private fun changeBootOrder(bootSourceInstanceId: String?) {
        val resourceUri = "$NS_CIM/CIM_BootConfigSetting"
        // Null clears the boot-source selection (Intel's doc: "to disable
        // the chosen boot option, use an empty parameter") — used both to
        // support an explicit "no boot source" case and internally by
        // armBiosSetupBootFlag() before setting the BIOS-flag path, since
        // the two are mutually exclusive.
        val paramsXml = bootSourceInstanceId?.let { bootSourceEpr(it) } ?: ""
        val envelope = invokeEnvelope(
            resourceUri = resourceUri,
            methodName = "ChangeBootOrder",
            selectorName = "InstanceID",
            selectorValue = BOOT_CONFIG_SETTING_INSTANCE_ID,
            parametersXml = paramsXml,
        )
        request(envelope)
    }

    private fun bootSourceEpr(instanceId: String): String = """
        <p:Source>
          <a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address>
          <a:ReferenceParameters>
            <w:ResourceURI>$NS_CIM/CIM_BootSourceSetting</w:ResourceURI>
            <w:SelectorSet>
              <w:Selector Name="InstanceID">$instanceId</w:Selector>
            </w:SelectorSet>
          </a:ReferenceParameters>
        </p:Source>
    """.trimIndent()

    private fun setBootConfigRole(role: Int) {
        val resourceUri = "$NS_CIM/CIM_BootService"
        val bootConfigSettingEpr = """
            <p:BootConfigSetting>
              <a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address>
              <a:ReferenceParameters>
                <w:ResourceURI>$NS_CIM/CIM_BootConfigSetting</w:ResourceURI>
                <w:SelectorSet>
                  <w:Selector Name="InstanceID">$BOOT_CONFIG_SETTING_INSTANCE_ID</w:Selector>
                </w:SelectorSet>
              </a:ReferenceParameters>
            </p:BootConfigSetting>
        """.trimIndent()
        val paramsXml = "$bootConfigSettingEpr\n<p:Role>$role</p:Role>"
        val envelope = invokeEnvelope(
            resourceUri = resourceUri,
            methodName = "SetBootConfigRole",
            selectorName = "Name",
            selectorValue = BOOT_SERVICE_NAME,
            parametersXml = paramsXml,
        )
        request(envelope)
    }

    /** Sets `AMT_BootSettingData.BIOSSetup = true` (every other flag reset
     *  to false/0) via WS-Transfer Put — the one property this app's BIOS
     *  boot option needs. The current instance is fetched first and its
     *  `InstanceID`/`ElementName` reused (Intel's doc: those "cannot be
     *  modified", i.e. must be echoed back unchanged in the Put body, not
     *  guessed at) rather than hard-coding them. */
    private fun armBiosSetupBootFlag() {
        val resourceUri = "$NS_AMT/AMT_BootSettingData"
        changeBootOrder(null) // clear any boot-source selection first — see setOneShotBoot's doc comment
        val current = request(transferGetEnvelope(resourceUri))
        val instanceId = current["InstanceID"] ?: BOOT_SETTING_DATA_INSTANCE_ID
        val elementName = current["ElementName"] ?: "Intel(r) AMT: Boot Configuration Settings"
        val propsXml = """
            <p:InstanceID>$instanceId</p:InstanceID>
            <p:ElementName>$elementName</p:ElementName>
            <p:UseSOL>false</p:UseSOL>
            <p:UseSafeMode>false</p:UseSafeMode>
            <p:ReflashBIOS>false</p:ReflashBIOS>
            <p:BIOSSetup>true</p:BIOSSetup>
            <p:BIOSPause>false</p:BIOSPause>
            <p:LockPowerButton>false</p:LockPowerButton>
            <p:LockResetButton>false</p:LockResetButton>
            <p:LockKeyboard>false</p:LockKeyboard>
            <p:LockSleepButton>false</p:LockSleepButton>
            <p:UserPasswordBypass>false</p:UserPasswordBypass>
            <p:ForcedProgressEvents>false</p:ForcedProgressEvents>
            <p:FirmwareVerbosity>0</p:FirmwareVerbosity>
            <p:ConfigurationDataReset>false</p:ConfigurationDataReset>
            <p:IDERBootDevice>0</p:IDERBootDevice>
            <p:UseIDER>false</p:UseIDER>
            <p:BootMediaIndex>0</p:BootMediaIndex>
        """.trimIndent()
        request(putEnvelope(resourceUri, "AMT_BootSettingData", propsXml))
    }

    /** Sets `AMT_BootSettingData.UseIDER = true` + `IDERBootDevice` to arm
     *  the next boot to IDE-R virtual media — the IDE-R counterpart to
     *  [armBiosSetupBootFlag], closing the AMT_VPRO_ROADMAP.md phase 5
     *  "open follow-up" (it used to be pointless to add before
     *  [AmtIderSession.mountAndServe] actually served media; that's done
     *  now). Intel's `AMT_BootSettingData` class reference states
     *  `UseIDER`/`IDERBootDevice` **cannot** be set while a
     *  `CIM_BootSourceSetting` boot source is also chosen — confirmed both
     *  in Intel's prose and in `Invoke-AMTForceBoot.ps1`'s reference
     *  implementation, which clears the boot source before setting these —
     *  so, exactly like [armBiosSetupBootFlag], any existing boot-source
     *  selection is cleared first via [changeBootOrder]. The current
     *  instance's `InstanceID`/`ElementName` are echoed back unchanged, same
     *  reasoning as [armBiosSetupBootFlag] (Intel's doc: those "cannot be
     *  modified"). Only actually takes effect on AMT 3.0–10.x firmware —
     *  see the phase 5 deprecation note in AMT_VPRO_ROADMAP.md; this call
     *  itself doesn't (and can't) detect that, it just stages what Intel's
     *  spec says to stage. */
    private fun armIderBootFlag(mediaType: AmtIderMediaType) {
        val resourceUri = "$NS_AMT/AMT_BootSettingData"
        changeBootOrder(null) // clear any boot-source selection first — mutually exclusive with UseIDER, same as the BIOS-flag path
        val current = request(transferGetEnvelope(resourceUri))
        val instanceId = current["InstanceID"] ?: BOOT_SETTING_DATA_INSTANCE_ID
        val elementName = current["ElementName"] ?: "Intel(r) AMT: Boot Configuration Settings"
        val propsXml = """
            <p:InstanceID>$instanceId</p:InstanceID>
            <p:ElementName>$elementName</p:ElementName>
            <p:UseSOL>false</p:UseSOL>
            <p:UseSafeMode>false</p:UseSafeMode>
            <p:ReflashBIOS>false</p:ReflashBIOS>
            <p:BIOSSetup>false</p:BIOSSetup>
            <p:BIOSPause>false</p:BIOSPause>
            <p:LockPowerButton>false</p:LockPowerButton>
            <p:LockResetButton>false</p:LockResetButton>
            <p:LockKeyboard>false</p:LockKeyboard>
            <p:LockSleepButton>false</p:LockSleepButton>
            <p:UserPasswordBypass>false</p:UserPasswordBypass>
            <p:ForcedProgressEvents>false</p:ForcedProgressEvents>
            <p:FirmwareVerbosity>0</p:FirmwareVerbosity>
            <p:ConfigurationDataReset>false</p:ConfigurationDataReset>
            <p:IDERBootDevice>${mediaType.iderBootDeviceValue}</p:IDERBootDevice>
            <p:UseIDER>true</p:UseIDER>
            <p:BootMediaIndex>0</p:BootMediaIndex>
        """.trimIndent()
        request(putEnvelope(resourceUri, "AMT_BootSettingData", propsXml))
    }

    // ── SOL (Serial-over-LAN) ────────────────────────────────────────
    // AMT-VPRO FEATURE phase 3. Unlike everything above, this is not a
    // WS-Man call — see [AmtSolSession]'s doc comment for the wire
    // protocol. This method's only job is (1) best-effort enable the SOL
    // listener via the one WS-Man call that's relevant here, then (2) hand
    // off to AmtSolSession for the actual APF/SOL connection.

    /** Opens a SOL console session. Blocks (on [Dispatchers.IO]) through
     *  the full APF handshake before returning, so a failed password or a
     *  disabled SOL listener surfaces as a thrown [AmtException] here
     *  rather than silently on the first [AmtSolSession.receive].
     *
     *  AMT-VPRO FEATURE phase 6 (CIRA): [externalTransport], when supplied
     *  (a [CiraRelayTransport] in practice), is handed straight to
     *  [AmtSolSession] instead of it dialing [host]/[redirectionPort]
     *  itself. In that case [enableSolListener]'s best-effort WS-Man call
     *  is skipped entirely rather than attempted and swallowed — under
     *  real CIRA, [host]/[port] on this [AmtClient] instance have no
     *  directly-reachable meaning (see `CiraRelayTransport`'s top doc
     *  comment, "What this class does NOT cover"), so the call would just
     *  be a guaranteed-to-fail network round trip for no benefit. This
     *  means a CIRA session's SOL/IDE-R listener must already be enabled
     *  on the device (e.g. via MEBx or a prior direct/WS-Man-reachable
     *  session) — this pass doesn't attempt to forward that WS-Man call
     *  through the relay too. */
    suspend fun openSolSession(externalTransport: AmtRedirectionTransport? = null): AmtSolSession = withContext(Dispatchers.IO) {
        if (externalTransport == null) {
            runCatching { enableSolListener() } // best-effort — SOL may already be enabled via MEBx, and this call needs the SOL/IDER realm which read-only users may lack
        }
        val redirectionPort = when (port) {
            16992 -> 16994
            16993 -> 16995
            else -> port + 2 // best-effort for a non-default WS-Man port; AMT's redirection port isn't independently configurable from the WS-Man port in any deployment this app has seen
        }
        val session = AmtSolSession(
            host = host,
            redirectionPort = redirectionPort,
            useTls = useTls,
            acceptSelfSignedCertificate = acceptSelfSignedCertificate,
            username = username,
            password = password,
            appContext = appContext,
            externalTransport = externalTransport,
        )
        session.open()
        session
    }

    /** `AMT_RedirectionService.RequestStateChange(RequestedState=32771)` —
     *  Intel's vendor extension of the DMTF `EnabledState`/`RequestedState`
     *  ValueMap: values 0-10 are the standard DMTF states, but AMT overlays
     *  32768="IDER and SOL disabled", 32769="IDER enabled/SOL disabled",
     *  32770="SOL enabled/IDER disabled", 32771="IDER and SOL enabled" (per
     *  the AMT_RedirectionService WS-Management Class Reference). Requesting
     *  32771 rather than a SOL-only state keeps this one call idempotent
     *  with a future IDE-R phase instead of fighting it for the listener
     *  state — see AMT_VPRO_ROADMAP.md phase 5. */
    private fun enableSolListener() {
        val resourceUri = "$NS_AMT/AMT_RedirectionService"
        val envelope = invokeEnvelope(
            resourceUri = resourceUri,
            methodName = "RequestStateChange",
            selectorName = "Name",
            selectorValue = REDIRECTION_SERVICE_NAME,
            parametersXml = "<p:RequestedState>$REDIRECTION_BOTH_ENABLED</p:RequestedState>",
        )
        request(envelope)
    }

    // ── KVM ──────────────────────────────────────────────────────────
    // AMT-VPRO FEATURE phase 4: see AmtKvmSession's doc comment for the
    // wire protocol (RFB, not APF). The two WS-Man calls below are the
    // "enable" side — mirrors [enableSolListener]'s "best-effort, then
    // hand off to the raw-socket session class" shape, but KVM has its own
    // separate enable path (IPS_KVMRedirectionSettingData +
    // CIM_KVMRedirectionSAP), not AMT_RedirectionService's SOL/IDE-R
    // listener state.

    /** Opens a KVM session: best-effort WS-Man enable
     *  ([enableKvmRedirection]) followed by the RFB handshake
     *  ([AmtKvmSession.open]). [kvmPassword] is the KVM redirection
     *  password (`IPS_KVMRedirectionSettingData.RFBPassword`) — commonly
     *  provisioned equal to this client's own [password] but logically a
     *  separate secret (see [AmtKvmSession]'s doc comment), so it's passed
     *  explicitly rather than assumed; callers that don't know it should
     *  first try the profile's AMT password itself, which is the common
     *  case in practice. */
    /** AMT-VPRO FEATURE phase 6 (CIRA): [externalTransport] follows
     *  [openSolSession]'s identical parameter — see that doc comment for
     *  the full reasoning, including why [enableKvmRedirection] is skipped
     *  (not forwarded through the relay) when it's supplied. */
    suspend fun openKvmSession(kvmPassword: String, externalTransport: AmtRedirectionTransport? = null): AmtKvmSession = withContext(Dispatchers.IO) {
        if (externalTransport == null) {
            runCatching { enableKvmRedirection(kvmPassword) } // best-effort — KVM may already be enabled, and Put/RequestStateChange need the Redirection realm which read-only users may lack
        }
        val redirectionPort = when (port) {
            16992 -> 16994
            16993 -> 16995
            else -> port + 2 // see openSolSession's identical fallback reasoning
        }
        val session = AmtKvmSession(
            host = host,
            redirectionPort = redirectionPort,
            useTls = useTls,
            acceptSelfSignedCertificate = acceptSelfSignedCertificate,
            kvmPassword = kvmPassword,
            digestUsername = username,
            digestPassword = password,
            appContext = appContext,
            externalTransport = externalTransport,
        )
        session.open()
        session
    }

    /** `IPS_KVMRedirectionSettingData.Put(Enabled=true, RFBPassword=...)`
     *  then `CIM_KVMRedirectionSAP.RequestStateChange(RequestedState=2)` —
     *  the two-step "configure, then enable the SAP" shape Intel's SDK
     *  documents under "Enabling KVM Remote Control" (`Enabled` alone
     *  configures the setting data; the SAP's own `RequestedState` is what
     *  actually arms the redirection listener for it, the same
     *  configure-vs-enable split [enableSolListener] elides because
     *  AMT_RedirectionService only has the one combined call). The SAP
     *  instance's `Name` selector isn't a value Intel's class reference
     *  documents as a fixed literal the way [REDIRECTION_SERVICE_NAME] is
     *  for AMT_RedirectionService, so unlike that call this one discovers
     *  it via Enumerate+Pull first rather than guessing a hardcoded
     *  string. [kvmPassword] blank skips the `RFBPassword` property
     *  entirely (leaves whatever password, if any, is already configured
     *  on the box). */
    private fun enableKvmRedirection(kvmPassword: String) {
        val settingUri = "$NS_IPS/IPS_KVMRedirectionSettingData"
        val propsXml = buildString {
            append("<p:Enabled>true</p:Enabled>")
            if (kvmPassword.isNotEmpty()) append("<p:RFBPassword>$kvmPassword</p:RFBPassword>")
        }
        request(putEnvelope(settingUri, "IPS_KVMRedirectionSettingData", propsXml))

        val sapUri = "$NS_CIM/CIM_KVMRedirectionSAP"
        val enumFields = request(enumerateEnvelope(sapUri))
        val context = enumFields["EnumerationContext"]
            ?: throw AmtException("AMT didn't return an enumeration context for the KVM redirection SAP.")
        val pullFields = request(pullEnvelope(sapUri, context))
        val sapName = pullFields["Name"]
            ?: throw AmtException("AMT's CIM_KVMRedirectionSAP response didn't include a Name to address RequestStateChange to.")

        val envelope = invokeEnvelope(
            resourceUri = sapUri,
            methodName = "RequestStateChange",
            selectorName = "Name",
            selectorValue = sapName,
            parametersXml = "<p:RequestedState>$KVM_SAP_ENABLED</p:RequestedState>",
        )
        request(envelope)
    }

    // ── IDE-R ────────────────────────────────────────────────────────
    // AMT-VPRO FEATURE phase 5. See [AmtIderSession]'s doc comment for
    // exactly how far this goes: the WS-Man enable call below and the APF
    // channel-open are both real/verified, matching [openSolSession]'s
    // shape; the ATA/ATAPI media-serving layer past that point isn't.

    /** Opens (best-effort enable, then hand off to [AmtIderSession]) an
     *  IDE-R channel — the connectivity/diagnostic half of phase 5. Callers
     *  wanting to actually mount an image still can't yet — see
     *  [AmtIderSession.mountAndServe]'s doc comment. */
    /** AMT-VPRO FEATURE phase 6 (CIRA): [externalTransport] follows
     *  [openSolSession]'s identical parameter — see that doc comment for
     *  the full reasoning, including why [enableIderListener] is skipped
     *  (not forwarded through the relay) when it's supplied. */
    suspend fun openIderSession(externalTransport: AmtRedirectionTransport? = null): AmtIderSession = withContext(Dispatchers.IO) {
        if (externalTransport == null) {
            runCatching { enableIderListener() } // best-effort — same reasoning as openSolSession: IDER may already be enabled via MEBx, and this needs the SOL/IDER realm which read-only users may lack
        }
        val redirectionPort = when (port) {
            16992 -> 16994
            16993 -> 16995
            else -> port + 2 // see openSolSession's identical fallback reasoning
        }
        val session = AmtIderSession(
            host = host,
            redirectionPort = redirectionPort,
            useTls = useTls,
            acceptSelfSignedCertificate = acceptSelfSignedCertificate,
            username = username,
            password = password,
            appContext = appContext,
            externalTransport = externalTransport,
        )
        session.open()
        session
    }

    /** Same `AMT_RedirectionService.RequestStateChange(RequestedState=32771)`
     *  call [enableSolListener] makes — 32771 ("IDER and SOL enabled") was
     *  already chosen there specifically so this call would be idempotent
     *  with it (see that method's doc comment), so this is a thin,
     *  separately-named wrapper for phase-5 call-site clarity rather than a
     *  different WS-Man shape. */
    private fun enableIderListener() = enableSolListener()

    // ── redirection access log ──────────────────────────────────────
    // AMT-VPRO FEATURE phase 5 addition: `AMT_RedirectionService.AccessLog`
    // — confirmed against Intel's own "Get IDER Session Log" use case doc:
    // a plain WS-Transfer Get on the same AMT_RedirectionService instance
    // [enableSolListener] already addresses (Name='Intel(r) AMT Redirection
    // Service'), reading the `AccessLog` property rather than invoking a
    // method. Unlike the audit log ([decodeAuditRecord]), this needs no
    // binary decoding — Intel's doc states each entry is already a plain
    // string formatted "Date (MM/DD/YYYY), Time (hh:mm:ss), IP:Port", one
    // per Storage Redirection (IDE-R) or SOL session. AccessLog is a
    // multi-valued property, so — same reason [getAuditLog] needs
    // [requestWithRawBody]/[extractRepeatedText] instead of [request]'s
    // flat map — repeated `<h:AccessLog>` elements in one Get response
    // can't be represented by a last-value-wins map.
    suspend fun getRedirectionAccessLog(): List<AmtRedirectionAccessLogEntry> = withContext(Dispatchers.IO) {
        val resourceUri = "$NS_AMT/AMT_RedirectionService"
        val envelope = transferGetEnvelopeWithSelector(resourceUri, "Name", REDIRECTION_SERVICE_NAME)
        val (_, rawXml) = requestWithRawBody(envelope)
        extractRepeatedText(rawXml, "AccessLog").map(::parseAccessLogEntry)
    }

    /** Splits one `"Date (MM/DD/YYYY), Time (hh:mm:ss), IP:Port"` AccessLog
     *  string into its three comma-separated parts (per Intel's documented
     *  format). Falls back to leaving [AmtRedirectionAccessLogEntry.date]/
     *  [AmtRedirectionAccessLogEntry.time] null rather than throwing if a
     *  future firmware release doesn't match that exact shape — the raw
     *  string is always preserved regardless. */
    private fun parseAccessLogEntry(raw: String): AmtRedirectionAccessLogEntry {
        val parts = raw.split(",").map { it.trim() }
        return AmtRedirectionAccessLogEntry(
            raw = raw,
            date = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
            time = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
            ipPort = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
        )
    }

    // ── audit log ────────────────────────────────────────────────────
    // AMT-VPRO FEATURE phase 2: AMT_AuditLog.ReadRecords — unlike every
    // other class this file reads, this doesn't return XML-modeled
    // properties; EventRecords is an array of base64-encoded fixed-layout
    // binary structs (documented in prose, not WSDL/MOF, under "Reading
    // the Audit Log" / "Read Audit Log Record" in the AMT Implementation
    // and Reference Guide). See [decodeAuditRecord].

    /** Reads up to [maxRecords] audit-log entries, oldest first (that's
     *  the order AMT returns them in — `ReadRecords` has no "most recent
     *  first" mode). Paginates via `StartIndex`/`RecordsReturned` since a
     *  single `ReadRecords` call only returns as many records as fit in
     *  one WS-Man response. Records this app can't confidently decode
     *  (currently: anything Kerberos-initiated — see [decodeAuditRecord])
     *  are skipped rather than surfaced malformed. */
    suspend fun getAuditLog(maxRecords: Int = 50): List<AmtAuditLogEntry> = withContext(Dispatchers.IO) {
        val resourceUri = "$NS_AMT/AMT_AuditLog"
        val results = mutableListOf<AmtAuditLogEntry>()
        var startIndex = 1
        while (results.size < maxRecords) {
            val envelope = invokeEnvelope(
                resourceUri = resourceUri,
                methodName = "ReadRecords",
                selectorName = "Name",
                selectorValue = AUDIT_LOG_NAME,
                parametersXml = "<p:StartIndex>$startIndex</p:StartIndex>",
            )
            val (fields, rawXml) = requestWithRawBody(envelope)
            val recordsReturned = fields["RecordsReturned"]?.toIntOrNull() ?: 0
            if (recordsReturned <= 0) break
            for (b64 in extractRepeatedText(rawXml, "EventRecords")) {
                runCatching { decodeAuditRecord(b64) }.getOrNull()?.let { results.add(it) }
                if (results.size >= maxRecords) break
            }
            val total = fields["TotalRecordCount"]?.toIntOrNull() ?: (startIndex + recordsReturned - 1)
            startIndex += recordsReturned
            if (startIndex > total) break
        }
        results
    }

    /** Decodes one `ReadRecords` `EventRecords[i]` entry. Layout confirmed
     *  against Intel's own `AMT_AuditLog.ReadRecords` class-reference
     *  prose (not the MOF/WSDL, which doesn't cover this binary shape) and
     *  cross-checked against Google's amt-forensics decoder, whose
     *  `struct.unpack(">HHB", ...)` on the first five bytes agrees with
     *  Intel's doc word-for-word: a 2-byte big-endian **uint16**
     *  AuditAppID, a 2-byte big-endian **uint16** EventID (five bytes in
     *  total before InitiatorType, not three — an earlier version of this
     *  decoder read both as 1-byte fields, which corrupted the offset of
     *  every field after them for every record, not just Kerberos ones),
     *  then a 1-byte InitiatorType. For InitiatorType HTTP_DIGEST(0) or
     *  LOCAL_INITIATOR(2), where the fixed-size fields after the
     *  initiator section are unambiguous, decoding continues with a
     *  4-byte big-endian Unix timestamp, 1-byte MCLocationType, 1-byte
     *  NetAddress length + that many bytes. KERBEROS_SID(1) (and
     *  KVM(3), also undocumented here) records have an initiator field of
     *  unconfirmed length; parsing further for those would risk silently
     *  misreading every field after it, so this throws for that case
     *  (caught by [getAuditLog]'s runCatching, i.e. that record is
     *  skipped rather than shown with wrong data) — Google's decoder
     *  independently reaches the same "skip Kerberos" outcome, which is
     *  further evidence this isn't a decoding gap Intel documents. */
    private fun decodeAuditRecord(base64: String): AmtAuditLogEntry {
        val d = java.util.Base64.getDecoder().decode(base64)
        require(d.size >= 5) { "Audit record too short: ${d.size} bytes" }
        val auditAppId = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        val eventId = ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
        val initiatorType = d[4].toInt() and 0xFF
        var offset = 5
        val initiator: String? = when (initiatorType) {
            0 -> { // HTTP_DIGEST: 1-byte username length + username bytes
                val len = d[offset].toInt() and 0xFF
                offset += 1
                val name = String(d, offset, len, Charsets.UTF_8)
                offset += len
                name
            }
            2 -> null // LOCAL_INITIATOR: no extra field
            else -> throw AmtException("Unsupported audit-log InitiatorType $initiatorType (Kerberos SID/KVM records aren't decoded)")
        }
        var timestamp: Long? = null
        var netAddress: String? = null
        if (offset + 6 <= d.size) {
            timestamp = ((d[offset].toLong() and 0xFF) shl 24) or
                ((d[offset + 1].toLong() and 0xFF) shl 16) or
                ((d[offset + 2].toLong() and 0xFF) shl 8) or
                (d[offset + 3].toLong() and 0xFF)
            // d[offset + 4] is MCLocationType (IPv4/IPv6/None) — not currently surfaced.
            val netAddrLen = d[offset + 5].toInt() and 0xFF
            offset += 6
            if (netAddrLen > 0 && offset + netAddrLen <= d.size) {
                netAddress = String(d, offset, netAddrLen, Charsets.US_ASCII)
            }
        }
        return AmtAuditLogEntry(
            auditAppId = auditAppId,
            auditAppName = AUDIT_APP_NAMES[auditAppId] ?: "App $auditAppId",
            eventId = eventId,
            initiatorType = initiatorType,
            initiator = initiator,
            timestampEpochSeconds = timestamp,
            netAddress = netAddress,
        )
    }

    // ── HTTP + Digest auth ────────────────────────────────────────────

    private data class DigestChallenge(val realm: String, val nonce: String, val opaque: String?, val qop: String?)

    /** Sends [envelopeXml], handling the Digest challenge/response and
     *  parsing the result into a flat leaf-tag → text map (sufficient for
     *  the small, known-shape responses this class reads — see
     *  [flattenXml]). Throws [AmtException] on transport failure, a SOAP
     *  Fault, or a non-zero CIM ReturnValue. */
    private fun request(envelopeXml: String): Map<String, String> = requestWithRawBody(envelopeXml).first

    /** Same request/Digest/validation flow as [request], but also returns
     *  the raw response body — needed by [getAuditLog], whose
     *  `EventRecords` field repeats (one entry per audit record), which
     *  [flattenXml]'s "last-value-wins" flat map can't represent. Kept as
     *  the one place other callers funnel through so [request] stays the
     *  simple, common-case entry point everywhere else in this file. */
    private fun requestWithRawBody(envelopeXml: String): Pair<Map<String, String>, String> =
        ciraWsmanTransport?.let { requestOverCira(envelopeXml, it) } ?: requestOverDirectHttp(envelopeXml)

    /** AMT-VPRO FEATURE phase 6 (CIRA), WS-Man-over-CIRA follow-up: the
     *  [ciraWsmanTransport] counterpart to [requestOverDirectHttp] below —
     *  same "send once, read a 401 Digest challenge, resend once" shape,
     *  reusing this class's own [cachedChallenge]/[digestHeader]/
     *  [parseDigestChallenge]/[parseWsmanResponse] (transport-agnostic —
     *  none of them touch [httpClient] or OkHttp types directly), just
     *  driving [CiraWsmanHttpTransport.exchange] instead of
     *  [httpClient].newCall(...).execute(). */
    private fun requestOverCira(
        envelopeXml: String,
        transport: CiraWsmanHttpTransport,
    ): Pair<Map<String, String>, String> {
        val path = "/wsman"
        val bodyBytes = envelopeXml.toByteArray(Charsets.UTF_8)
        // Same readTimeout AmtClient's own OkHttp client uses (see its
        // .readTimeout(15, TimeUnit.SECONDS) comment: power-state changes
        // can take longer than a plain Get while the ME commits the
        // transition) — kept identical rather than picked independently.
        val readTimeoutMs = 15_000

        val existing = cachedChallenge
        val firstHeaders = existing?.let { mapOf("Authorization" to digestHeader(it, path)) } ?: emptyMap()

        val firstResponse = try {
            transport.exchange(path, firstHeaders, bodyBytes, readTimeoutMs)
        } catch (e: java.io.IOException) {
            throw AmtException(
                "Couldn't reach the AMT device's WS-Man endpoint through the CIRA relay — check " +
                    "the relay is reachable, the relay credentials are correct, and the device is " +
                    "currently dialed in to it.",
                cause = e,
            )
        }

        if (firstResponse.statusCode == 401) {
            val header = firstResponse.headers["www-authenticate"]
                ?: throw AmtException("AMT returned 401 with no Digest challenge — check the AMT username.")
            val challenge = parseDigestChallenge(header)
                ?: throw AmtException("Couldn't parse AMT's Digest challenge.")
            cachedChallenge = challenge
            val retryResponse = try {
                transport.exchange(path, mapOf("Authorization" to digestHeader(challenge, path)), bodyBytes, readTimeoutMs)
            } catch (e: java.io.IOException) {
                throw AmtException(
                    "Couldn't reach the AMT device's WS-Man endpoint through the CIRA relay.",
                    cause = e,
                )
            }
            return parseWsmanResponseRaw(retryResponse)
        }
        return parseWsmanResponseRaw(firstResponse)
    }

    /** [parseWsmanResponse]'s counterpart for a [RawHttpResponse] instead
     *  of an OkHttp [okhttp3.Response] — same three checks (401 → auth
     *  failure, non-2xx → HTTP failure, SOAP Fault/non-zero ReturnValue →
     *  [AmtException]), just reading from the already-decoded status
     *  code/body [CiraWsmanHttpTransport.exchange] already produced instead
     *  of pulling them off a live OkHttp response object. */
    private fun parseWsmanResponseRaw(resp: RawHttpResponse): Pair<Map<String, String>, String> {
        if (resp.statusCode == 401) {
            throw AmtException("Authentication failed — check the AMT username/password.")
        }
        if (resp.statusCode !in 200..299) {
            throw AmtException("AMT returned HTTP ${resp.statusCode} for this request.")
        }
        val fields = flattenXml(resp.body)
        fields["Reason"]?.let { throw AmtException("AMT SOAP fault: $it") }
        fields["ReturnValue"]?.toIntOrNull()?.let { rv ->
            if (rv != 0) {
                throw AmtException(
                    "AMT rejected the request (ReturnValue=$rv — see the CIM ReturnValue codes " +
                        "in the AMT SDK reference).",
                    returnValueCode = rv,
                )
            }
        }
        return fields to resp.body
    }

    /** The original direct-connection path (unchanged from before the CIRA
     *  follow-up) — [httpClient] dialing [host]/[port] itself. */
    private fun requestOverDirectHttp(envelopeXml: String): Pair<Map<String, String>, String> {
        val url = wsmanUrl
        val mediaType = "application/soap+xml;charset=UTF-8".toMediaType()

        fun buildRequest(authHeader: String?): Request {
            val builder = Request.Builder().url(url).post(envelopeXml.toRequestBody(mediaType))
            if (authHeader != null) builder.header("Authorization", authHeader)
            return builder.build()
        }

        val existing = cachedChallenge
        val firstAuthHeader = existing?.let { digestHeader(it, url) }

        val firstResponse = try {
            httpClient.newCall(buildRequest(firstAuthHeader)).execute()
        } catch (e: java.io.IOException) {
            throw AmtException(
                "Couldn't reach $host:$port — check the host is powered (standby power is " +
                    "enough for AMT to answer) and reachable on this network.",
                cause = e,
            )
        }

        firstResponse.use { resp ->
            if (resp.code == 401) {
                val header = resp.header("WWW-Authenticate")
                    ?: throw AmtException("AMT returned 401 with no Digest challenge — check the AMT username.")
                val challenge = parseDigestChallenge(header)
                    ?: throw AmtException("Couldn't parse AMT's Digest challenge.")
                cachedChallenge = challenge
                val retryResponse = try {
                    httpClient.newCall(buildRequest(digestHeader(challenge, url))).execute()
                } catch (e: java.io.IOException) {
                    throw AmtException("Couldn't reach $host:$port.", cause = e)
                }
                return retryResponse.use { parseWsmanResponse(it) }
            }
            return parseWsmanResponse(resp)
        }
    }

    private fun parseWsmanResponse(resp: okhttp3.Response): Pair<Map<String, String>, String> {
        val bodyText = resp.body?.string().orEmpty()
        if (resp.code == 401) {
            throw AmtException("Authentication failed — check the AMT username/password.")
        }
        if (!resp.isSuccessful) {
            throw AmtException("AMT returned HTTP ${resp.code} for this request.")
        }
        val fields = flattenXml(bodyText)
        fields["Reason"]?.let { throw AmtException("AMT SOAP fault: $it") }
        fields["ReturnValue"]?.toIntOrNull()?.let { rv ->
            if (rv != 0) {
                throw AmtException(
                    "AMT rejected the request (ReturnValue=$rv — see the CIM ReturnValue codes " +
                        "in the AMT SDK reference).",
                    returnValueCode = rv,
                )
            }
        }
        return fields to bodyText
    }

    private fun parseDigestChallenge(header: String): DigestChallenge? {
        if (!header.trim().startsWith("Digest", ignoreCase = true)) return null
        val params = mutableMapOf<String, String>()
        // Simple "key=value" pair extractor good enough for the fixed set
        // of params AMT's Digest challenge actually sends (realm, nonce,
        // opaque, qop, algorithm, stale) — values are always quoted except
        // qop and algorithm, so this handles both.
        Regex("""(\w+)=("([^"]*)"|([^,\s]+))""").findAll(header).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues[3].ifEmpty { m.groupValues[4] }
            params[key] = value
        }
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it.equals("auth", true) }
        return DigestChallenge(realm = realm, nonce = nonce, opaque = params["opaque"], qop = qop)
    }

    private fun digestHeader(challenge: DigestChallenge, url: String): String {
        val uri = java.net.URI(url).rawPath.ifEmpty { "/wsman" }
        val method = "POST"
        val ha1 = md5Hex("$username:${challenge.realm}:$password")
        val ha2 = md5Hex("$method:$uri")
        val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val nc = "%08x".format(nonceCount.incrementAndGet())
        val response = if (challenge.qop != null) {
            md5Hex("$ha1:${challenge.nonce}:$nc:$cnonce:${challenge.qop}:$ha2")
        } else {
            md5Hex("$ha1:${challenge.nonce}:$ha2")
        }
        return buildString {
            append("Digest username=\"$username\", realm=\"${challenge.realm}\", ")
            append("nonce=\"${challenge.nonce}\", uri=\"$uri\", response=\"$response\", algorithm=MD5")
            if (challenge.qop != null) append(", qop=${challenge.qop}, nc=$nc, cnonce=\"$cnonce\"")
            challenge.opaque?.let { append(", opaque=\"$it\"") }
        }
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ── SOAP envelope building ────────────────────────────────────────

    private fun soapHeader(action: String, resourceUri: String, extra: String = ""): String = """
          <s:Header>
            <a:To>$wsmanUrl</a:To>
            <a:ReplyTo><a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>
            <w:ResourceURI>$resourceUri</w:ResourceURI>
            <a:Action>$action</a:Action>
            <a:MessageID>uuid:${UUID.randomUUID()}</a:MessageID>
            <w:OperationTimeout>PT8.000S</w:OperationTimeout>
            $extra
          </s:Header>
    """.trimIndent()

    private fun envelope(headerXml: String, bodyXml: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="$NS_SOAP" xmlns:a="$NS_ADDR" xmlns:w="$NS_WSMAN">
        $headerXml
          <s:Body>
            $bodyXml
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun transferGetEnvelope(resourceUri: String): String {
        val action = "http://schemas.xmlsoap.org/ws/2004/09/transfer/Get"
        return envelope(soapHeader(action, resourceUri), "")
    }

    /** Same WS-Transfer Get as [transferGetEnvelope], but for classes like
     *  `AMT_RedirectionService` that have more than one addressable
     *  instance and so need a `SelectorSet` to pick which one — the Get
     *  counterpart to [invokeEnvelope]'s selector handling, used by
     *  [getRedirectionAccessLog]. */
    private fun transferGetEnvelopeWithSelector(resourceUri: String, selectorName: String, selectorValue: String): String {
        val action = "http://schemas.xmlsoap.org/ws/2004/09/transfer/Get"
        val selectorSet = """
            <w:SelectorSet>
              <w:Selector Name="$selectorName">$selectorValue</w:Selector>
            </w:SelectorSet>
        """.trimIndent()
        return envelope(soapHeader(action, resourceUri, extra = selectorSet), "")
    }

    private fun enumerateEnvelope(resourceUri: String): String {
        val action = "http://schemas.xmlsoap.org/ws/2004/09/enumeration/Enumerate"
        val body = """<n:Enumerate xmlns:n="$NS_ENUM"/>"""
        return envelope(soapHeader(action, resourceUri), body)
    }

    private fun pullEnvelope(resourceUri: String, enumerationContext: String): String {
        val action = "http://schemas.xmlsoap.org/ws/2004/09/enumeration/Pull"
        val body = """
            <n:Pull xmlns:n="$NS_ENUM">
              <n:EnumerationContext>$enumerationContext</n:EnumerationContext>
              <n:MaxElements>1</n:MaxElements>
            </n:Pull>
        """.trimIndent()
        return envelope(soapHeader(action, resourceUri), body)
    }

    /** WS-Transfer Put — modifies an existing singleton instance in place.
     *  Used for `AMT_BootSettingData` ([armBiosSetupBootFlag]) and
     *  `IPS_KVMRedirectionSettingData` ([enableKvmRedirection]), both of
     *  which (like `AMT_GeneralSettings`, read via [transferGetEnvelope])
     *  have exactly one instance per device, so — same as that Get — no
     *  `SelectorSet` is needed to identify which instance. */
    private fun putEnvelope(resourceUri: String, className: String, propertiesXml: String): String {
        val action = "http://schemas.xmlsoap.org/ws/2004/09/transfer/Put"
        val body = """
            <p:$className xmlns:p="$resourceUri">
              $propertiesXml
            </p:$className>
        """.trimIndent()
        return envelope(soapHeader(action, resourceUri), body)
    }

    private fun invokeEnvelope(
        resourceUri: String,
        methodName: String,
        selectorName: String,
        selectorValue: String,
        parametersXml: String,
    ): String {
        val action = "$resourceUri/$methodName"
        val selectorSet = """
            <w:SelectorSet>
              <w:Selector Name="$selectorName">$selectorValue</w:Selector>
            </w:SelectorSet>
        """.trimIndent()
        val header = soapHeader(action, resourceUri, extra = selectorSet)
        val body = """
            <p:${methodName}_INPUT xmlns:p="$resourceUri">
              $parametersXml
            </p:${methodName}_INPUT>
        """.trimIndent()
        return envelope(header, body)
    }

    // ── XML response parsing ──────────────────────────────────────────

    /** Flattens every leaf (no-child) element into local-name → text
     *  content. AMT's responses for the classes this app calls never
     *  repeat a leaf tag name within one response, so a flat map is
     *  sufficient — a full DOM/XPath model would be overkill for the
     *  handful of scalar fields phase 1 reads. Namespace prefixes are
     *  stripped since callers only care about the local tag name. */
    private fun flattenXml(xml: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var currentTag: String? = null
        var currentText = StringBuilder()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    currentText = StringBuilder()
                }
                XmlPullParser.TEXT -> currentText.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val text = currentText.toString().trim()
                    if (text.isNotEmpty() && parser.name == currentTag) {
                        result[parser.name] = text
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }
        return result
    }

    /** Like [flattenXml] but returns *every* occurrence of a given leaf
     *  tag's text, in document order, instead of collapsing to one value.
     *  Only needed for `AMT_AuditLog.ReadRecords`' repeated `EventRecords`
     *  entries — every other response this file parses has at most one of
     *  each leaf tag, which is exactly why [flattenXml] gets away with a
     *  flat map everywhere else. */
    private fun extractRepeatedText(xml: String, localName: String): List<String> {
        val result = mutableListOf<String>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var inTarget = false
        var text = StringBuilder()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    inTarget = parser.name == localName
                    if (inTarget) text = StringBuilder()
                }
                XmlPullParser.TEXT -> if (inTarget) text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (inTarget && parser.name == localName) {
                        val t = text.toString().trim()
                        if (t.isNotEmpty()) result.add(t)
                    }
                    inTarget = false
                }
            }
            eventType = parser.next()
        }
        return result
    }

    companion object {
        private const val NS_SOAP = "http://www.w3.org/2003/05/soap-envelope"
        private const val NS_ADDR = "http://schemas.xmlsoap.org/ws/2004/08/addressing"
        private const val NS_WSMAN = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd"
        private const val NS_ENUM = "http://schemas.xmlsoap.org/ws/2004/09/enumeration"
        // Per DSP0227 (WS-Man CIM Bindings): DMTF CIM_* classes live under
        // .../cim-schema/2/, Intel's own AMT_* classes under
        // .../amt-schema/1/ — a different vendor namespace, not a version
        // bump of the same one. Confirmed against Intel's own WS-Management
        // Class Reference, not guessed.
        private const val NS_CIM = "http://schemas.dmtf.org/wbem/wscim/1/cim-schema/2"
        private const val NS_AMT = "http://intel.com/wbem/wscim/1/amt-schema/1"
        // Intel's "Integrated Platform Services" vendor namespace — a third
        // one alongside NS_CIM/NS_AMT above, holding the handful of classes
        // (KVM among them) that were added after AMT's original CIM/AMT
        // split and given their own prefix rather than folded into either.
        private const val NS_IPS = "http://intel.com/wbem/wscim/1/ips-schema/1"

        // ── boot control ──
        // Fixed instance names Intel's SDK guarantees for these singleton
        // classes (confirmed against the AMT SDK class reference, not
        // guessed): AMT has had exactly one CIM_BootConfigSetting /
        // AMT_BootSettingData instance since Release 1.0, and CIM_BootService's
        // Name has been "Intel(r) AMT Boot Service" since Release 6.0 (the
        // oldest release this app's `minSdk`-equivalent AMT support targets).
        private const val BOOT_CONFIG_SETTING_INSTANCE_ID = "Intel(r) AMT: Boot Configuration 0"
        private const val BOOT_SETTING_DATA_INSTANCE_ID = "Intel(r) AMT:BootSettingData 0"
        private const val BOOT_SERVICE_NAME = "Intel(r) AMT Boot Service"
        // CIM_BootService.SetBootConfigRole's Role parameter: DMTF
        // ValueMap {0=Unknown, 1=IsNext, 2=IsNextSingleUse, 3=IsDefault, ...}
        // in the abstract CIM schema, but Intel's AMT implementation only
        // ever accepts IsNextSingleUse(1) and (since Release 7.0) IsNotNext
        // (32768) — "Intel AMT supports only 'IsNextSingleUse' and
        // 'IsNotNext'" per the SDK's "Set...Boot...for the Next Boot" use
        // case, which is also why this app only ever sends 1: every boot
        // this feature sets is explicitly one-shot.
        private const val ROLE_IS_NEXT_SINGLE_USE = 1

        // ── SOL ──
        // Fixed instance name for AMT_RedirectionService, confirmed against
        // Intel's WS-Management Class Reference (same "one singleton
        // instance, fixed Name" shape as BOOT_SERVICE_NAME above).
        private const val REDIRECTION_SERVICE_NAME = "Intel(r) AMT Redirection Service"
        // See enableSolListener()'s doc comment for the full ValueMap.
        private const val REDIRECTION_BOTH_ENABLED = 32771

        // ── KVM ──
        // CIM_KVMRedirectionSAP.RequestStateChange's RequestedState: the
        // plain DMTF EnabledState/RequestedState ValueMap (not an Intel
        // vendor overlay the way AMT_RedirectionService's is) —
        // 2="Enabled" is standard DMTF CIM_EnabledLogicalElement.
        private const val KVM_SAP_ENABLED = 2

        // ── firmware identity ──
        // The CIM_SoftwareIdentity.InstanceID Intel's own "Get Core Version"
        // use case reads for the AMT/ME firmware version specifically (the
        // class also holds ~9 other instances — Flash, Netstack, Sku, etc. —
        // that this app doesn't need).
        private const val SOFTWARE_ID_AMT_CORE_VERSION = "AMT FW Core Version"
        private const val MAX_SOFTWARE_IDENTITY_INSTANCES = 16

        // ── audit log ──
        private const val AUDIT_LOG_NAME = "Intel(r) AMT:Audit Log"
        // AMT_AuditLog record AuditAppID → human name, per the "Event Groups
        // and Event IDs" table in the AMT Implementation and Reference
        // Guide (cross-checked against Google's amt-forensics decoder,
        // which independently reverse-engineered the same table). EventID
        // is *not* similarly mapped here — it's only unique per AuditAppID
        // (hundreds of entries across all apps), so [AmtAuditLogEntry]
        // surfaces the raw numeric id rather than a name table this app
        // would have to keep in sync with every future AMT release.
        private val AUDIT_APP_NAMES = mapOf(
            16 to "Security Admin",
            17 to "RCO",
            18 to "Redirection Manager",
            19 to "Firmware Update Manager",
            20 to "Security Audit Log",
            21 to "Network Time",
            22 to "Network Administration",
            23 to "Storage Administration",
            24 to "Event Manager",
            25 to "System Defense Manager",
            26 to "Agent Presence Manager",
            27 to "Wireless Configuration",
            28 to "EAC",
            29 to "KVM",
            30 to "User Opt-In",
        )

        private fun amtPowerStateLabel(value: Int): String = when (value) {
            1 -> "Other"
            2 -> "On"
            3 -> "Sleep - Light"
            4 -> "Sleep - Deep"
            5 -> "Power Cycle (Off - Soft)"
            6 -> "Off - Hard"
            7 -> "Hibernate (Off - Soft)"
            8 -> "Off - Soft"
            9 -> "Power Cycle (Off - Hard)"
            10 -> "Master Bus Reset"
            11 -> "Diagnostic Interrupt (NMI)"
            12 -> "Off - Soft Graceful"
            13 -> "Off - Hard Graceful"
            14 -> "Master Bus Reset Graceful"
            15 -> "Power Cycle (Off - Soft Graceful)"
            16 -> "Power Cycle (Off - Hard Graceful)"
            else -> "Unknown ($value)"
        }
    }
}
