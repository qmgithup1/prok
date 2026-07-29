package com.systemsgo.hex.remote

import android.content.Context

import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.spice.protocol.SpiceCredentials
import com.systemsgo.hex.spice.protocol.SpiceSessionClient
import com.systemsgo.hex.data.model.RdpCredentials
import com.systemsgo.hex.data.model.RdpPerformance
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.model.SshAuthType
import com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter
import com.systemsgo.hex.ssh.protocol.SshAuthMode
import com.systemsgo.hex.ssh.protocol.SshClient
import com.systemsgo.hex.ssh.protocol.SshCredentials
import com.systemsgo.hex.ssh.protocol.SshTunnelCredentials
import com.systemsgo.hex.vnc.protocol.VncClient
import com.systemsgo.hex.vnc.protocol.VncCredentials

/**
 * Builds the right [RemoteSessionClient] implementation for a profile's
 * [ProtocolType]. This is the single place that knows about all three
 * protocol client classes — everything downstream (the session ViewModel,
 * the session UI) only depends on the common [RemoteSessionClient] surface.
 *
 * When an RDP or VNC profile has [RdpProfile.sshTunnelEnabled] set to true
 * the returned client is an [SshTunneledClient] that first opens an SSH
 * tunnel — threaded through the full [RdpProfile.effectiveSshTunnelHops]
 * chain (one hop, or a full ProxyJump chain of any length) — and then
 * connects the inner RDP/VNC client through it, making the tunnel
 * completely transparent to the rest of the app.
 */
object RemoteSessionFactory {

    fun create(
        profile: RdpProfile,
        displayWidth: Int,
        displayHeight: Int,
        compressionQuality: Int = 75,  // FIX #4: wired through from AppSettings.compressionQuality
        // SETTINGS-CONSOLIDATE FIX: colorDepth/performanceLevel are now global
        // AppSettings (Settings → Connection) instead of per-profile fields,
        // so every session — profile-based or Quick Connect — uses the same
        // values a user configures once, rather than each profile silently
        // carrying its own (easy to forget) copy.
        colorDepth: Int = 32,
        performanceLevel: Int = RdpPerformance.LAN,
        // ENTRA-ID-AUTH FEATURE: the caller (RdpSessionViewModel via
        // GatewayTokenProvider) resolves this *before* calling create() —
        // this object stays a plain synchronous factory, so token
        // acquisition (a suspend MSAL call) can't happen in here. Ignored
        // when profile.gatewayAuthMode isn't ENTRA_ID. See
        // RdpCredentials.gatewayBearerToken's doc comment.
        gatewayBearerToken: String = "",
        // UDP-TRANSPORT FEATURE: global, same reasoning as colorDepth/
        // performanceLevel just above — see AppSettings.udpTransportEnabled's
        // doc comment.
        udpTransportEnabled: Boolean = false,
        // PAC-SUPPORT FEATURE: same "resolve a suspend precondition before
        // calling this plain synchronous factory" shape as
        // [gatewayBearerToken] above (see GatewayTokenProvider's doc
        // comment) — this object can't itself await
        // [com.systemsgo.hex.proxy.PacProxyResolver.fetchPacScript]/
        // `findProxyForUrl`'s suspend calls, so the caller resolves once via
        // `PacProxyResolver.resolve(profile, ...PacProxyResolver.outboundDialTarget(profile))`
        // and passes the result straight through. Defaults to Direct so
        // every existing call site (and every profile with no pacUrl and no
        // static proxy configured) behaves exactly as before. Consumed
        // below for BOTH the RDP path (overrides the proxyEnabled/
        // proxyType/proxyHost/... that would otherwise come straight from
        // [profile] — see [toRdpProxyFields]) and any SSH-based path
        // (SshClient/SshTunneledClient's own `outboundProxy` parameter).
        resolvedProxy: com.systemsgo.hex.proxy.PacProxyResolver.Resolved =
            com.systemsgo.hex.proxy.PacProxyResolver.Resolved.Direct,
        // GUACAMOLE-PROTOCOL FEATURE: same "resolve the suspend precondition
        // before calling this plain synchronous factory" shape as
        // gatewayBearerToken/resolvedProxy above — logging in against the
        // Guacamole REST API (com.systemsgo.hex.guacamole.GuacamoleRepository.login)
        // is itself a suspend network call this object can't make. The
        // caller resolves it once (surfacing GuacamoleApiException.AuthenticationFailed/
        // Unreachable as the ERROR HANDLING states before ever reaching
        // here) and passes the resulting session through. Null only for
        // every profile that isn't ProtocolType.GUACAMOLE.
        guacamoleSession: com.systemsgo.hex.guacamole.GuacamoleSession? = null,
        // BUG-B / BUG-H: pass appContext so VncClient can give it to bVNC (TLS),
        // and SshClient/SshTunnelManager can persist TOFU keys in SharedPreferences.
        appContext: Context,
    ): RemoteSessionClient {
        return when (profile.protocolType) {

            // ── SSH direct terminal session ────────────────────────────────
            ProtocolType.SSH -> SshClient(
                credentials = SshCredentials(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMode = profile.sshAuthType.toSshAuthMode(),
                    password = profile.password,
                    privateKeyPem = profile.sshPrivateKey,
                    privateKeyPassphrase = profile.sshPrivateKeyPassphrase,
                    agentForwardingEnabled = profile.sshAgentForwardingEnabled,
                ),
                appContext = appContext,  // BUG-H FIX: needed for TOFU persistence
                // PAC-SUPPORT FEATURE: this device's own outbound TCP dial
                // to profile.host is the thing being proxied here — see
                // resolvedProxy's doc comment just above for how the
                // caller derived it (PacProxyResolver.outboundDialTarget()
                // for a direct SSH profile is simply profile.host/port).
                outboundProxy = resolvedProxy,
                // DYN-PROXY: `ssh -D` equivalent, started on this same session
                // alongside the interactive shell.
                socksProxyEnabled = profile.socksProxyEnabled,
                socksProxyPort = profile.socksProxyPort,
                // X11 FORWARDING FEATURE: `ssh -X`/`-Y` equivalent, requested
                // on the same shell channel. See RdpProfile.x11ForwardingEnabled.
                x11ForwardingEnabled = profile.x11ForwardingEnabled,
                x11DisplayHost = profile.x11DisplayHost,
                x11DisplayNumber = profile.x11DisplayNumber,
                x11AuthCookie = profile.x11AuthCookie,
                // SSH-PORT-FORWARD FEATURE: user-defined static -L/-R
                // forwards, requested on the same session. See
                // RdpProfile.sshPortForwards.
                portForwards = profile.sshPortForwards,
            )

            // ── Mosh (SSH-bootstrapped, then UDP/SSP) ────────────────────────
            // MOSH FEATURE: merged into RdpProfile rather than a standalone
            // profile type (see RdpProfile.kt's MOSH-specific fields doc
            // comment) — this is the one place that translates the merged
            // fields into the MoshProfile that MoshSessionManager/
            // MoshSessionClient (unchanged, still their own small model)
            // actually take. Deliberately NOT wrapped in SshTunneledClient
            // like Telnet/Rlogin/Serial Console below: Mosh already makes
            // its own SSH connection directly to profile.host/port to
            // bootstrap mosh-server (see MoshSessionManager), and the
            // session that matters for the rest of its lifetime is a raw
            // UDP/SSP socket that has nothing to tunnel over SSH in the
            // first place — so resolvedProxy/sshTunnelEnabled are simply
            // not consumed for MOSH profiles today.
            ProtocolType.MOSH -> {
                val moshProfile = com.systemsgo.hex.data.model.MoshProfile(
                    // NOTE: RdpProfile.id is a String (UUID) but MoshProfile.id
                    // is a Long left over from its standalone-Room-entity design
                    // that was never wired up — neither MoshSessionManager nor
                    // MoshSessionClient ever reads id/name, so both are simply
                    // left at MoshProfile's defaults (0L / "") rather than a
                    // lossy String→Long conversion that would serve no purpose.
                    host = profile.host,
                    sshPort = profile.port,
                    username = profile.username,
                    authMode = profile.sshAuthType.toSshAuthMode(),
                    remoteMoshServerCommand = profile.moshRemoteServerCommand.ifBlank { "mosh-server" },
                    udpPortRange = profile.moshUdpPortRange,
                    remoteLocale = profile.moshRemoteLocale,
                    colorMode = profile.moshColorMode,
                    predictionMode = com.systemsgo.hex.data.model.MoshPredictionMode.entries
                        .firstOrNull { it.name == profile.moshPredictionMode }
                        ?: com.systemsgo.hex.data.model.MoshPredictionMode.ADAPTIVE,
                )
                com.systemsgo.hex.mosh.protocol.MoshSessionClient(
                    profile = moshProfile,
                    password = profile.password.toCharArray(),
                    appContext = appContext,
                    privateKeyPem = profile.sshPrivateKey.toCharArray(),
                    privateKeyPassphrase = profile.sshPrivateKeyPassphrase.toCharArray(),
                )
            }

            // ── Telnet direct terminal session (optionally SSH-tunneled) ────
            ProtocolType.TELNET -> {
                val telnetFactory = { host: String, port: Int ->
                    com.systemsgo.hex.telnet.protocol.TelnetClient(
                        credentials = com.systemsgo.hex.telnet.protocol.TelnetCredentials(
                            host = host,
                            port = port,
                            useTls = profile.telnetUseTls,
                        ),
                        appContext = appContext,
                    )
                }
                if (profile.sshTunnelEnabled && profile.effectiveSshTunnelHops.isNotEmpty()) {
                    SshTunneledClient(
                        tunnelHops        = profile.toSshTunnelHops(),
                        targetHost        = profile.host,
                        targetPort        = profile.port,
                        appContext        = appContext,
                        // PAC-SUPPORT FEATURE: proxies the first tunnel
                        // hop's dial-out, not the final Telnet target — see
                        // SshTunneledClient.outboundProxy's doc comment.
                        outboundProxy     = resolvedProxy,
                        innerClientFactory = { localPort ->
                            telnetFactory("127.0.0.1", localPort)
                        }
                    )
                } else {
                    // NOTE: a non-tunneled Telnet profile has no outbound-proxy
                    // mechanism of its own today (no proxy* fields on
                    // TelnetCredentials) — resolvedProxy is simply unused in
                    // this branch. A pacUrl set on a profile that ends up
                    // here has no effect; only SSH-tunneled Telnet profiles
                    // (the branch above) actually get proxied.
                    telnetFactory(profile.host, profile.port)
                }
            }

            // ── Rlogin direct terminal session (optionally SSH-tunneled) ────
            ProtocolType.RLOGIN -> {
                val rloginFactory = { host: String, port: Int ->
                    com.systemsgo.hex.rlogin.protocol.RloginClient(
                        credentials = com.systemsgo.hex.rlogin.protocol.RloginCredentials(
                            host = host,
                            port = port,
                            clientUsername = profile.username,
                            remoteUsername = profile.rloginRemoteUsername.ifBlank { profile.username },
                            terminalType = profile.rloginTerminalType.ifBlank { "xterm/38400" },
                        ),
                        appContext = appContext,
                    )
                }
                if (profile.sshTunnelEnabled && profile.effectiveSshTunnelHops.isNotEmpty()) {
                    SshTunneledClient(
                        tunnelHops        = profile.toSshTunnelHops(),
                        targetHost        = profile.host,
                        targetPort        = profile.port,
                        appContext        = appContext,
                        // PAC-SUPPORT FEATURE: proxies the first tunnel
                        // hop's dial-out, not the final Rlogin target —
                        // same reasoning as Telnet's identical branch above.
                        outboundProxy     = resolvedProxy,
                        innerClientFactory = { localPort ->
                            rloginFactory("127.0.0.1", localPort)
                        }
                    )
                } else {
                    // NOTE: same as Telnet just above — a non-tunneled
                    // Rlogin profile has no outbound-proxy mechanism of its
                    // own, so resolvedProxy is simply unused in this branch.
                    rloginFactory(profile.host, profile.port)
                }
            }

            // ── Serial Console direct terminal session (optionally SSH-tunneled) ──
            // See com.systemsgo.hex.serialconsole.protocol.SerialConsoleClient's
            // class doc — not to be confused with the RDP-redirect feature's
            // serialRedirectMode/serialNetworkHost/serialNetworkPort, which
            // are a completely different feature (a serial device forwarded
            // *into* an RDP session) and are never read here.
            ProtocolType.SERIAL_CONSOLE -> {
                val serialConsoleFactory = { host: String, port: Int ->
                    com.systemsgo.hex.serialconsole.protocol.SerialConsoleClient(
                        credentials = com.systemsgo.hex.serialconsole.protocol.SerialConsoleCredentials(
                            host = host,
                            port = port,
                            transport = profile.serialConsoleTransport,
                            baudRate = profile.serialConsoleBaudRate,
                            dataBits = profile.serialConsoleDataBits,
                            parity = profile.serialConsoleParity,
                            stopBits = profile.serialConsoleStopBits,
                            localDevicePath = profile.serialConsoleDevicePath,
                            hardwareFlowControl = profile.serialConsoleHardwareFlowControl,
                        ),
                        appContext = appContext,
                    )
                }
                if (profile.sshTunnelEnabled && profile.effectiveSshTunnelHops.isNotEmpty()) {
                    SshTunneledClient(
                        tunnelHops        = profile.toSshTunnelHops(),
                        targetHost        = profile.host,
                        targetPort        = profile.port,
                        appContext        = appContext,
                        // PAC-SUPPORT FEATURE: proxies the first tunnel
                        // hop's dial-out, not the final Serial Console
                        // target — same reasoning as Telnet/Rlogin's
                        // identical branches above.
                        outboundProxy     = resolvedProxy,
                        innerClientFactory = { localPort ->
                            serialConsoleFactory("127.0.0.1", localPort)
                        }
                    )
                } else {
                    // NOTE: same as Telnet/Rlogin just above — a non-tunneled
                    // Serial Console profile has no outbound-proxy mechanism
                    // of its own, so resolvedProxy is simply unused here.
                    serialConsoleFactory(profile.host, profile.port)
                }
            }

            // ── RDP ───────────────────────────────────────────────────────
            // VIRTUALBOX-VRDE FEATURE (Part 1/N): VRDE is wire-compatible
            // RDP (see ProtocolType.VIRTUALBOX_VRDE's doc comment), so it
            // shares this entire branch with plain RDP rather than getting
            // a duplicate client. The one behavioral difference is below,
            // at `useNla = ...`: VirtualBox's built-in VRDE server doesn't
            // speak NLA/CredSSP, so it's forced off for this protocol type
            // regardless of what profile.useNla (an RDP-editor field this
            // protocol's own editor doesn't even expose) happens to hold.
            ProtocolType.RDP, ProtocolType.VIRTUALBOX_VRDE -> {
                // PAC-SUPPORT FEATURE: when this profile tunnels RDP over
                // SSH, the REAL outbound dial this device makes is the
                // tunnel's first hop (handled below via SshTunneledClient's
                // outboundProxy), and the inner RDP client only ever talks
                // to 127.0.0.1:<localPort> — applying resolvedProxy there
                // too would mean routing a loopback connection through an
                // HTTP/SOCKS proxy, which is meaningless at best and breaks
                // the tunnel at worst. So the inner RDP client's own
                // AFreeRdpBridge-level proxy fields stay NONE/disabled in
                // that case; resolvedProxy is consumed by the tunnel instead.
                val useSshTunnel = profile.sshTunnelEnabled && profile.effectiveSshTunnelHops.isNotEmpty()
                val rdpProxyFields = if (useSshTunnel) RdpProxyFields.NONE else resolvedProxy.toRdpProxyFields()
                val rdpFactory = { host: String, port: Int ->
                    RdpRemoteAdapter(
                        credentials = RdpCredentials(
                            host = host,
                            port = port,
                            username = profile.username,
                            password = profile.password,
                            domain = profile.domain,
                            // VIRTUALBOX-VRDE FEATURE: see the doc comment on
                            // this branch's `ProtocolType.RDP, ProtocolType.VIRTUALBOX_VRDE ->` line above.
                            useNla = if (profile.protocolType == ProtocolType.VIRTUALBOX_VRDE) false else profile.useNla,
                            acceptSelfSignedCertificate = profile.acceptSelfSignedCertificate,  // BUG-3 FIX
                            gatewayEnabled = profile.gatewayEnabled,
                            gatewayHost = profile.gatewayHost,
                            gatewayPort = profile.gatewayPort,
                            gatewayUsername = profile.gatewayUsername,
                            gatewayPassword = profile.gatewayPassword,
                            gatewayDomain = profile.gatewayDomain,
                            gatewayAuthMode = com.systemsgo.hex.data.model.GatewayAuthMode.fromName(profile.gatewayAuthMode),
                            gatewayBearerToken = gatewayBearerToken,
                            // PAC-SUPPORT FEATURE: proxyEnabled/proxyType/
                            // proxyHost/proxyPort/proxyUsername/proxyPassword
                            // below come from [resolvedProxy] — the caller's
                            // already-resolved PAC-or-static decision (see
                            // that param's doc comment on create() above) —
                            // rather than straight from `profile.proxy*`.
                            // When resolvedProxy is the default Direct (every
                            // call site that doesn't resolve PAC, and every
                            // profile with pacUrl blank whose static
                            // proxyEnabled is also false), this is
                            // byte-for-byte the same as reading profile.proxy*
                            // directly, so no existing behavior changes.
                            // pacUrl itself is carried through only for
                            // traceability — see RdpCredentials.pacUrl's doc.
                            proxyEnabled = rdpProxyFields.enabled,
                            proxyType = rdpProxyFields.type,
                            proxyHost = rdpProxyFields.host,
                            proxyPort = rdpProxyFields.port,
                            proxyUsername = rdpProxyFields.username,
                            proxyPassword = rdpProxyFields.password,
                            pacUrl = profile.pacUrl,
                            // RDP-OVER-WEBSOCKET FEATURE: same copy-through gap
                            // every other per-profile setting needs (see the
                            // doc comment on RdpCredentials.transportMode).
                            // Forced back to TCP when this profile is also
                            // tunneled over SSH (useSshTunnel above): in that
                            // case rdpFactory is already invoked with
                            // ("127.0.0.1", <tunnel's local port>) below, and
                            // RdpWebSocketTransport would ignore that host/port
                            // entirely in favor of webSocketConfig's own URL —
                            // combining the two would silently do something
                            // other than what either setting says on its own,
                            // so SSH-tunnel wins outright rather than guessing.
                            transportMode = if (useSshTunnel) {
                                com.systemsgo.hex.rdp.transport.RdpTransportMode.TCP
                            } else {
                                com.systemsgo.hex.rdp.transport.RdpTransportMode.fromName(profile.transportMode)
                            },
                            webSocketConfig = com.systemsgo.hex.rdp.transport.RdpWebSocketConfigCodec.decode(profile.webSocketConfig),
                            remoteAppEnabled = profile.remoteAppEnabled,
                            remoteAppProgram = profile.remoteAppProgram,
                            remoteAppWorkingDir = profile.remoteAppWorkingDir,
                            remoteAppCmdLine = profile.remoteAppCmdLine,
                            // REMOTEAPP-WINDOWS FEATURE: see the doc comment
                            // on RdpCredentials.remoteAppDisplayMode.
                            remoteAppDisplayMode = profile.remoteAppDisplayMode,
                            // MIC-REDIRECT FIX: previously omitted here, so
                            // profile.enableSound never reached the native
                            // layer regardless of what the user chose in the
                            // connection form. See the doc comment on
                            // RdpCredentials.enableSound.
                            enableSound = profile.enableSound,
                            enableMicRedirect = profile.enableMicRedirect,
                            // CLIPBOARD FIX: previously omitted here too — see
                            // the doc comment on RdpCredentials.enableClipboard.
                            enableClipboard = profile.enableClipboard,
                            // DRIVE-REDIRECT FIX: previously omitted here too — see
                            // the doc comment on RdpCredentials.enableDriveRedirect.
                            enableDriveRedirect = profile.enableDriveRedirect,
                            // PRINTER-REDIRECT FIX: same copy-through gap as
                            // enableDriveRedirect above — see the doc comment
                            // on RdpCredentials.enablePrinterRedirect.
                            enablePrinterRedirect = profile.enablePrinterRedirect,
                            // WEBCAM-REDIRECT FIX: same copy-through gap as
                            // enablePrinterRedirect above — see the doc comment
                            // on RdpCredentials.enableWebcamRedirect.
                            enableWebcamRedirect = profile.enableWebcamRedirect,
                            // SMARTCARD-REDIRECT FEATURE: same copy-through gap as
                            // enableWebcamRedirect above — see the doc comment
                            // on RdpCredentials.enableSmartcardRedirect.
                            enableSmartcardRedirect = profile.enableSmartcardRedirect,
                            // PARALLEL-REDIRECT FEATURE: same copy-through gap as
                            // enableSmartcardRedirect above — see the doc comment
                            // on RdpCredentials.enableParallelRedirect.
                            enableParallelRedirect = profile.enableParallelRedirect,
                            parallelPortPath = profile.parallelPortPath,
                            // SERIAL-REDIRECT FEATURE: same copy-through gap as
                            // enableParallelRedirect above — see the doc comment
                            // on RdpCredentials.enableSerialRedirect.
                            enableSerialRedirect = profile.enableSerialRedirect,
                            serialPortPath = profile.serialPortPath,
                            // SERIAL-OVER-NETWORK FEATURE: same copy-through gap
                            // as enableSerialRedirect above — see the doc comment
                            // on RdpCredentials.serialRedirectMode.
                            serialRedirectMode = profile.serialRedirectMode,
                            serialNetworkHost = profile.serialNetworkHost,
                            serialNetworkPort = profile.serialNetworkPort,
                            // MULTI-MONITOR FEATURE: see the doc comment on
                            // RdpCredentials.preferredMonitorId / RdpProfile.preferredMonitorId.
                            preferredMonitorId = profile.preferredMonitorId,
                            // CODEC-NEGOTIATION FEATURE: same copy-through gap as
                            // preferredMonitorId above — see the doc comment on
                            // RdpCredentials.codecPreference.
                            codecPreference = profile.codecPreference,
                        ),
                        displayWidth       = displayWidth,
                        displayHeight      = displayHeight,
                        // BUG-FLAGS FIX: the UI-level choice (0..4) is not the same
                        // thing as FreeRDP_PerformanceFlags, which is a real
                        // PERF_DISABLE_* bitmask. Convert here so "Low Bandwidth"
                        // actually disables wallpaper/animations/theming instead of
                        // silently sending 0 (no effects disabled).
                        performanceMode    = RdpPerformance.flagsFor(performanceLevel),
                        colorDepth         = colorDepth,                // FIX #3
                        compressionQuality = compressionQuality,        // FIX #4
                        enableUdpTransport = udpTransportEnabled,       // UDP-TRANSPORT FEATURE
                        appContext         = appContext,                // TLS-TOFU FIX
                    )
                }
                if (useSshTunnel) {
                    SshTunneledClient(
                        tunnelHops        = profile.toSshTunnelHops(),
                        targetHost        = profile.host,
                        targetPort        = profile.port,
                        appContext        = appContext,  // BUG-H FIX
                        // PAC-SUPPORT FEATURE: this is where resolvedProxy
                        // actually gets used for a tunneled RDP profile —
                        // see rdpProxyFields' doc comment above for why it's
                        // deliberately NOT also applied to the inner RDP
                        // client just below.
                        outboundProxy     = resolvedProxy,
                        innerClientFactory = { localPort ->
                            // The inner RDP client connects to localhost:localPort
                            rdpFactory("127.0.0.1", localPort)
                        }
                    )
                } else {
                    rdpFactory(profile.host, profile.port)
                }
            }

            // ── VNC ───────────────────────────────────────────────────────
            ProtocolType.VNC -> {
                val vncFactory = { host: String, port: Int ->
                    VncClient(
                        credentials = VncCredentials(
                            host = host,
                            port = port,
                            password = profile.password,
                            viewOnly = profile.vncViewOnly,
                            // VENCRYPT FIX: only consumed by RfbConnectable for the
                            // VeNCrypt-Plain/X509Plain/TLSPlain sub-types — harmless
                            // for every other VNC server (base RFB has no username).
                            username = profile.username,
                            // ULTRAVNC-REPEATER FEATURE: see the doc comment on
                            // RdpProfile.vncRepeaterEnabled.
                            repeaterEnabled = profile.vncRepeaterEnabled,
                            repeaterId = profile.vncRepeaterId,
                            repeaterMode = profile.vncRepeaterMode,
                            // LISTEN-MODE FEATURE: see the doc comment on
                            // RdpProfile.vncListenModeEnabled.
                            listenModeEnabled = profile.vncListenModeEnabled,
                            listenPort = profile.vncListenPort,
                        ),
                        appContext = appContext,  // BUG-B FIX
                    )
                }
                if (profile.sshTunnelEnabled && profile.effectiveSshTunnelHops.isNotEmpty()) {
                    SshTunneledClient(
                        tunnelHops        = profile.toSshTunnelHops(),
                        targetHost        = profile.host,
                        targetPort        = profile.port,
                        appContext        = appContext,  // BUG-H FIX
                        // PAC-SUPPORT FEATURE: proxies the tunnel's first
                        // hop dial-out — see SshTunneledClient.outboundProxy
                        // and RDP's identical wiring above for the full
                        // reasoning.
                        outboundProxy     = resolvedProxy,
                        innerClientFactory = { localPort ->
                            vncFactory("127.0.0.1", localPort)
                        }
                    )
                } else {
                    // NOTE: a non-tunneled VNC profile has no outbound-proxy
                    // mechanism of its own today (no proxy* fields on
                    // VncCredentials, unlike RDP's AFreeRdpBridge path) —
                    // resolvedProxy is simply unused in this branch. A
                    // pacUrl set on a profile that ends up here has no
                    // effect; only SSH-tunneled VNC profiles (the branch
                    // above) actually get proxied.
                    vncFactory(profile.host, profile.port)
                }
            }

            // ── Web/HTTPS portal ─────────────────────────────────────────────
            // WEB-PORTAL FEATURE: intentionally NOT handled like the four
            // protocols above. A web-portal profile isn't a
            // RemoteSessionClient at all (no framebuffer, no terminal) — it's
            // an embedded WebView, launched directly by
            // com.systemsgo.hex.remote.SessionLauncher, which routes WEB
            // profiles to WebPortalActivity *before* RemoteSessionFactory is
            // ever consulted. Reaching this branch means some call site
            // launched a WEB profile the old way instead of going through
            // SessionLauncher — fail loudly rather than silently trying to
            // open a framebuffer/terminal session against an HTTP(S) portal.
            // ── SPICE (Part 4/N) ─────────────────────────────────────────────
            // Unlike WEB/REDFISH/IPMI/AMT below, SPICE *is* a real
            // RemoteSessionClient (a canvas/framebuffer session like
            // RDP/VNC) — it is not routed elsewhere by SessionLauncher.
            // com.systemsgo.hex.spice.protocol.SpiceSessionClient wraps
            // com.systemsgo.hex.spice.native.SpiceBridge (systemsgo_spice_jni.c's
            // real SpiceSession/Main/Display/Inputs channel logic, Part 3/N)
            // the same way RdpRemoteAdapter/VncClient wrap their own native
            // bridges above. If SpiceBridge.isAvailable is false at runtime
            // (SPICE prebuilt failed for this device's ABI, or CI simply
            // hasn't produced one — see systemsgo_spice_jni.c's Part 2/N CI step),
            // SpiceSessionClient.connect() fails cleanly with a user-facing
            // error rather than this factory throwing, so the failure surfaces
            // through the same "connection failed" UI path every other
            // protocol uses instead of a distinct crash-shaped error() here.
            //
            // NOTE: SSH-tunneling SPICE (mirroring the RDP/VNC
            // sshTunnelEnabled branches above) is not wired up in this Part —
            // SpiceCredentials has no proxy/tunnel fields yet. A SPICE
            // profile with sshTunnelEnabled set simply connects directly,
            // ignoring the tunnel, until a future part adds that branch.
            ProtocolType.SPICE -> SpiceSessionClient(
                credentials = SpiceCredentials(
                    host = profile.host,
                    port = profile.port,
                    password = profile.password,
                )
            )

            // ── RTSP camera/NVR live view (view-only) ────────────────────────
            // RTSP FEATURE: no SSH-tunnel branch, matching SPICE just above —
            // com.systemsgo.hex.rtsp.protocol.RtspCredentials has no
            // proxy/tunnel fields yet, so a profile with sshTunnelEnabled set
            // simply connects directly for now.
            ProtocolType.RTSP -> com.systemsgo.hex.rtsp.protocol.RtspClient(
                credentials = com.systemsgo.hex.rtsp.protocol.RtspCredentials(
                    host = profile.host,
                    port = profile.port,
                    path = profile.rtspStreamPath,
                    username = profile.username,
                    password = profile.password,
                    transport = com.systemsgo.hex.rtsp.protocol.RtspTransportMode.entries
                        .firstOrNull { it.name == profile.rtspTransportMode }
                        ?: com.systemsgo.hex.rtsp.protocol.RtspTransportMode.TCP_INTERLEAVED,
                    useTls = profile.rtspUseTls,
                )
            )

            ProtocolType.WEB -> error(
                "ProtocolType.WEB profiles are not RemoteSessionClient sessions — " +
                "route them through com.systemsgo.hex.remote.SessionLauncher " +
                "(WebPortalActivity), not RemoteSessionFactory.create()."
            )

            // PROXMOX-API FEATURE (Part 1/N): same reasoning as WEB above —
            // Proxmox's own API is a management/monitoring surface, not a
            // framebuffer, so it's routed straight to ProxmoxManagementActivity
            // by SessionLauncher before RemoteSessionFactory is ever consulted.
            // (Its VNC *console* for an individual QEMU VM does eventually
            // reach a real VNC RemoteSessionClient — but via a synthesized
            // ProtocolType.VNC quick-connect profile pointed at a local
            // loopback bridge, never via a ProtocolType.PROXMOX profile
            // itself. See ProxmoxManagementActivity.openVncConsole().)
            ProtocolType.PROXMOX -> error(
                "ProtocolType.PROXMOX profiles are not RemoteSessionClient sessions — " +
                "route them through com.systemsgo.hex.remote.SessionLauncher " +
                "(ProxmoxManagementActivity), not RemoteSessionFactory.create()."
            )

            // MODBUS-TCP FEATURE (Part 2/2): like PROXMOX above, a Modbus
            // profile isn't a framebuffer/terminal RemoteSessionClient —
            // it's a register-polling dashboard, routed straight to
            // ModbusManagementActivity by SessionLauncher before
            // RemoteSessionFactory is ever consulted.
            ProtocolType.MODBUS_TCP -> error(
                "ProtocolType.MODBUS_TCP profiles are not RemoteSessionClient sessions — " +
                "route them through com.systemsgo.hex.remote.SessionLauncher " +
                "(ModbusManagementActivity), not RemoteSessionFactory.create()."
            )

            // VMWARE-VSPHERE FEATURE (Part 3/N): same "management API, not a
            // framebuffer session" shape as PROXMOX/MODBUS_TCP above —
            // routes to VSphereManagementActivity, which SessionLauncher
            // dispatches to before RemoteSessionFactory is ever consulted
            // (see SessionLauncher's VMWARE_VSPHERE branch). This branch
            // exists purely so the `when` stays exhaustive; it is
            // unreachable in practice.
            ProtocolType.VMWARE_VSPHERE -> error(
                "ProtocolType.VMWARE_VSPHERE profiles are not RemoteSessionClient " +
                "sessions — they route through com.systemsgo.hex.remote.SessionLauncher " +
                "to VSphereManagementActivity, not RemoteSessionFactory.create()."
            )

            // REDFISH-IPMI FEATURE (AMT-VPRO FEATURE joins the same branch;
            // SNMP FEATURE joins it too): none of the four is a
            // framebuffer/terminal RemoteSessionClient, all are routed
            // straight to BmcManagementActivity / SnmpManagementActivity by
            // SessionLauncher before RemoteSessionFactory is ever consulted.
            // See com.systemsgo.hex.redfish.protocol.RedfishClient /
            // com.systemsgo.hex.ipmi.protocol.IpmiClient /
            // com.systemsgo.hex.amt.protocol.AmtClient /
            // com.systemsgo.hex.snmp.protocol.SnmpClient for the actual
            // protocol implementations.
            ProtocolType.REDFISH, ProtocolType.IPMI, ProtocolType.AMT, ProtocolType.SNMP -> error(
                "ProtocolType.${profile.protocolType} profiles are not RemoteSessionClient " +
                "sessions — route them through com.systemsgo.hex.remote.SessionLauncher " +
                "(BmcManagementActivity/SnmpManagementActivity), not RemoteSessionFactory.create()."
            )

            // RESTCONF FEATURE (Part 1/4): same reasoning as REDFISH/IPMI/AMT
            // above — a RESTCONF session is a REST/YANG explorer (request
            // builder + response viewer + YANG browser, Parts 2-3), not a
            // framebuffer/terminal RemoteSessionClient, so SessionLauncher
            // routes it straight to RestconfExplorerActivity before
            // RemoteSessionFactory is ever consulted.
            ProtocolType.RESTCONF -> error(
                "ProtocolType.RESTCONF profiles are not RemoteSessionClient sessions — " +
                "route them through com.systemsgo.hex.remote.SessionLauncher " +
                "(RestconfExplorerActivity), not RemoteSessionFactory.create()."
            )

            // NETCONF FEATURE: same reasoning as REDFISH/IPMI/AMT/RESTCONF
            // above — NetconfClient is a structured-RPC client, not a
            // framebuffer/terminal RemoteSessionClient, and is routed
            // straight to NetconfSessionActivity by SessionLauncher. See
            // com.systemsgo.hex.netconf.protocol.NetconfClient.
            ProtocolType.NETCONF -> error(
                "ProtocolType.NETCONF profiles are not RemoteSessionClient sessions — " +
                "route them through com.systemsgo.hex.remote.SessionLauncher " +
                "(NetconfSessionActivity), not RemoteSessionFactory.create()."
            )

            // WAKE-ON-LAN-STANDALONE FEATURE: same "not a RemoteSessionClient"
            // reasoning as WEB/PROXMOX/MODBUS_TCP/REDFISH/IPMI/AMT/SNMP/RESTCONF/
            // NETCONF above — sending a Magic Packet has no framebuffer/terminal
            // session to open at all, so SessionLauncher never builds an Intent
            // for RdpSessionActivity for a WAKE_ON_LAN profile in the first
            // place; the "Connect" tap short-circuits to
            // MainViewModel.sendWakeOnLan() directly (see HomeScreen's
            // launchConnect). This branch only exists so `when (profile.protocolType)`
            // stays exhaustive.
            ProtocolType.WAKE_ON_LAN -> error(
                "ProtocolType.WAKE_ON_LAN profiles are not RemoteSessionClient sessions — " +
                "they send a Magic Packet directly (see MainViewModel.sendWakeOnLan()), " +
                "never through RemoteSessionFactory.create()."
            )

            // SFTP-STANDALONE FEATURE: same "not a RemoteSessionClient" reasoning
            // as WAKE_ON_LAN/WEB/PROXMOX/... above — a standalone SFTP
            // connection is a file browser (FileTransferScreen, hosted by
            // SftpFileTransferActivity), not a terminal/framebuffer session, so
            // SessionLauncher routes it straight to that Activity and this
            // branch should be unreachable in practice.
            ProtocolType.SFTP -> error(
                "ProtocolType.SFTP profiles are not RemoteSessionClient sessions — " +
                "route them through com.systemsgo.hex.remote.SessionLauncher " +
                "(SftpFileTransferActivity), not RemoteSessionFactory.create()."
            )

            // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: same "not a
            // RemoteSessionClient" reasoning as SFTP immediately above — each
            // of these five is a file browser (FileTransferScreen, hosted by
            // FileTransferActivity — see that Activity's class doc for how it
            // picks the right FileTransferManager config per protocolType),
            // not a terminal/framebuffer session, so SessionLauncher routes
            // all five straight to that Activity and this branch should be
            // unreachable in practice.
            ProtocolType.FTP, ProtocolType.FTPS, ProtocolType.WEBDAV,
            ProtocolType.SMB, ProtocolType.NFS -> error(
                "ProtocolType.${profile.protocolType} profiles are not RemoteSessionClient " +
                "sessions — route them through com.systemsgo.hex.remote.SessionLauncher " +
                "(FileTransferActivity), not RemoteSessionFactory.create()."
            )

            // GUACAMOLE-PROTOCOL FEATURE: unlike WEB/REDFISH/IPMI/AMT/SNMP/
            // RESTCONF above, this genuinely is a RemoteSessionClient (see
            // com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient's
            // class doc) — SessionLauncher routes it to RdpSessionActivity
            // like RDP/VNC/SSH, not to a dedicated Activity.
            ProtocolType.GUACAMOLE -> {
                val session = guacamoleSession ?: error(
                    "ProtocolType.GUACAMOLE requires a pre-resolved GuacamoleSession " +
                    "(see RemoteSessionFactory.create's guacamoleSession parameter doc) " +
                    "— the caller must await GuacamoleRepository.login() first."
                )
                val base = profile.guacServerUrl.trimEnd('/')
                val wsScheme = if (base.startsWith("https://")) "wss://" else "ws://"
                val tunnelUrl = wsScheme + base.removePrefix("https://").removePrefix("http://") + "/websocket-tunnel"
                com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient(
                    com.systemsgo.hex.guacamole.protocol.GuacamoleTunnelConfig(
                        tunnelWebSocketUrl = tunnelUrl,
                        authToken = session.authToken,
                        dataSource = profile.guacDataSource.ifBlank { session.dataSource },
                        connectionIdentifier = profile.guacConnectionIdentifier,
                        width = displayWidth,
                        height = displayHeight,
                        acceptSelfSignedCertificate = profile.acceptSelfSignedCertificate,
                        // AUDIO-PLAYBACK FEATURE: request raw PCM — the only format
                        // GuacamoleDisplayRenderer actually decodes.
                        audioMimetypes = listOf("audio/L16;rate=44100,channels=2"),
                    ),
                    appContext = appContext,
                )
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    // PAC-SUPPORT FEATURE: the subset of RdpCredentials' proxy* fields that
    // [PacProxyResolver.Resolved] maps onto — kept as its own small holder
    // purely so [toRdpProxyFields] can be computed once per create() call
    // (see ProtocolType.RDP's `rdpProxyFields` val) instead of re-deriving
    // each field independently.
    private data class RdpProxyFields(
        val enabled: Boolean,
        val type: com.systemsgo.hex.data.model.ProxyType,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
    ) {
        companion object {
            val NONE = RdpProxyFields(
                enabled = false,
                type = com.systemsgo.hex.data.model.ProxyType.SOCKS, // inert while enabled=false
                host = "",
                port = 1080,
                username = "",
                password = "",
            )
        }
    }

    private fun com.systemsgo.hex.proxy.PacProxyResolver.Resolved.toRdpProxyFields(): RdpProxyFields = when (this) {
        is com.systemsgo.hex.proxy.PacProxyResolver.Resolved.Direct -> RdpProxyFields.NONE
        is com.systemsgo.hex.proxy.PacProxyResolver.Resolved.UseProxy -> RdpProxyFields(
            enabled = true,
            type = type,
            host = host,
            port = port,
            username = username,
            password = password,
        )
    }

    private fun SshAuthType.toSshAuthMode() = when (this) {
        SshAuthType.PASSWORD    -> SshAuthMode.PASSWORD
        SshAuthType.PRIVATE_KEY -> SshAuthMode.PRIVATE_KEY
    }

    // SSH-PROXYJUMP-CHAIN FEATURE: builds the full ordered chain
    // SshTunnelManager/SshTunneledClient need from
    // [RdpProfile.effectiveSshTunnelHops] — which already transparently
    // upgrades an older single-hop profile (sshTunnelHost/sshTunnelPort/...)
    // into a one-entry list, so this is the single call site that needs to
    // know about that migration at all. Replaces the old
    // toSshTunnelCredentials() that built exactly one SshTunnelCredentials
    // from the (now-deprecated) single-hop fields directly.
    private fun RdpProfile.toSshTunnelHops(): List<SshTunnelCredentials> =
        effectiveSshTunnelHops.map { hop ->
            SshTunnelCredentials(
                host                 = hop.host,
                port                 = hop.port,
                username             = hop.username,
                authMode             = hop.authType.toSshAuthMode(),
                password             = hop.password,
                privateKeyPem        = hop.privateKey,
                privateKeyPassphrase = hop.privateKeyPassphrase,
            )
        }
}
