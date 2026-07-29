# Intel AMT / vPro Support — Roadmap

**⚠️ RECONSTRUCTED — this file is referenced by dozens of doc comments across
`app/src/main/java/com/systemsgo/hex/amt/` and `ui/screens/BmcManagement*`
but was missing from the archive. It's been rebuilt from those comments so
the cross-references resolve; if you have the original, prefer that one and
diff against this.**

Systems Go's BMC management screen (`BmcManagementActivity`/`BmcManagementScreen`)
supports three out-of-band management protocols: IPMI, Redfish, and Intel
AMT/vPro. AMT was added in phases, each layering a new AMT capability on top
of the WS-Management client (`AmtClient`) and, from phase 3 onward, a second
raw-socket wire protocol for the redirection port (16992/16993 for WS-Man,
16994/16995 for SOL/KVM/IDE-R).

## Phase 1 — Identity + power control

The baseline: `AMT_ComputerSystemPackage`/`AMT_GeneralSettings` identity
fields and `CIM_PowerManagementService` power control (on/off/cycle/reset),
read-only "always available regardless of host OS state" data — the same
subset IPMI's Chassis Control exposes. UI: the read-only identity card plus
the power-control row in `BmcManagementScreen`'s phase-1 section.

## Phase 2 — Audit log + one-shot boot

- `AMT_AuditLog.ReadRecords` — the general security audit log (distinct
  from the phase-5 redirection access log — see below).
- One-shot boot device staging: `AMT_BootSettingData` + `CIM_BootConfigSetting`
  ("boot to PXE/BIOS/CD once"). Doesn't itself power-cycle the box; the UI
  message says so explicitly so a manual reset/power action is understood
  as still required.

## Phase 3 — SOL (Serial-over-LAN) console

The AMT counterpart to IPMI's `IpmiSolChannel`, in `AmtSolSession`. Not a
WS-Man call — WS-Man only best-effort enables the SOL listener
(`AMT_RedirectionService.RequestStateChange`); the console itself is a
second raw socket to the dedicated redirection port.

**Corrected protocol basis:** an early version of `AmtSolSession` (and
`AmtIderSession`, see phase 5) assumed a console connecting *directly* to
AMT's redirection port used the Intel AMT Port Forwarding Protocol (APF).
That's wrong for direct connections — APF only applies when *AMT* initiates
the transport (local LMS/HECI forwarding, or CIRA relaying through a Management
Presence Server). Confirmed against Intel's AMT SDK docs and MeshCentral's
`meshcmd` (`amt-redir-duk.js`/`amt-sol.js`): direct connections use a
separate `StartRedirectionSession` (`0x10`)/`StartRedirectionSessionReply`
(`0x11`) exchange with a 4-byte ASCII service tag (`"SOL "`, `"IDER"`,
`"KVMR"`), followed by an `AuthenticateSession` (`0x13`/`0x14`) exchange that
is itself a binary-framed HTTP Digest handshake. Both `AmtSolSession` and
`AmtIderSession` were fixed to match once this was confirmed.

## Phase 4 — KVM remote desktop

The AMT counterpart to `VncClient`, in `AmtKvmSession` — same
`StartRedirectionSession`/`AuthenticateSession` handshake as phase 3, with
service tag `"KVMR"`, then a standard RFB (VNC) framebuffer protocol over
the same socket. Supports Raw, CopyRect, and Hextile encodings (every RFB
server, AMT included, is required to support Raw as a fallback; Hextile
adds a real bandwidth win on the slow out-of-band management link this
runs over without needing a compression library). Tight/ZRLE decode (which
would need zlib/JPEG) remain real additional work, left as an explicit next
step rather than half-implemented.

## Phase 5 — IDE-R (IDE Redirection) virtual media

The AMT analogue of Redfish's `VirtualMedia` endpoints: mounts a local
`.iso`/`.img` as a virtual CD/DVD-ROM or floppy on the managed box,
regardless of its power/OS state. Same `StartRedirectionSession`/
`AuthenticateSession` handshake as phases 3–4, service tag `"IDER"`, in
`AmtIderSession`.

Past the handshake, real IDE-R diverges: AMT's firmware drives a virtual
IDE/ATAPI controller and expects the far end to answer real ATA/ATAPI
commands (IDENTIFY, PACKET, READ(10), INQUIRY, MODE SENSE, READ CAPACITY,
...) sourced from the mounted image — a small disk-emulation engine, not a
byte pipe. This splits into two pieces:

- **Command sets** — public T10 (SCSI/MMC, `AmtIderDiskEmulator`) and T13
  (plain ATA, `AmtIderFloppyEmulator`) standards, independently testable
  regardless of the envelope below.
- **Envelope** — how those CDB/task-file bytes are wrapped in messages once
  redirection starts. Intel-proprietary and undocumented outside the full
  AMT SDK's C headers, but confirmed byte-for-byte from MeshCentral's
  server-side `amt/amt-ider-module.js`. One deliberately-not-resolved
  ambiguity carried over from that source: `SendCommandEndResponse` has two
  reply frames, and this code mirrors the dominant, evidently
  firmware-tested one for every command-end response rather than the
  rarely-used one that embeds real sense codes.

**Open follow-up (now closed):** `AMT_BootSettingData.UseIDER`/
`IDERBootDevice` — the "boot straight to the mounted IDE-R media" flag,
`AmtClient.armIderBootFlag` — was staged as pointless to wire up before
`AmtIderSession.mountAndServe` actually served media over the envelope.
Now that `mountAndServe` is implemented and wired into the UI (see
`BmcManagementActivity.amtMountIderMedia`), arming this flag alongside a
mount is a reasonable next step. Note Intel's docs mark `UseIDER`/
`IDERBootDevice` as only effective on AMT 3.0–10.x firmware; on newer
firmware a mounted IDE-R device is expected to already be first in the
managed OS's normal boot order without an extra flag, so `armIderBootFlag`
is harmless-but-unnecessary there rather than required.

**UI status:** `BmcManagementScreen`'s Media tab now offers a real
file-picker-and-mount flow (`ActivityResultContracts.OpenDocument`) backed
by `BmcManagementViewModel.amtMountIderMedia`/`amtUnmountIderMedia`, in
addition to the standalone "Test IDE-R Channel" connectivity check that
predates it.

Also on the Media tab: `AMT_RedirectionService.AccessLog` — the IDE-R/SOL
session log (date/time/IP:port of past redirection sessions), distinct from
phase 2's general `AMT_AuditLog`.

## Phase 6 — CIRA (path (b) chosen; app-side client for SOL/KVM/IDE-R is
## implemented and wired up; the relay/MPS component's app-facing half
## (`relay/`) AND device-facing real-APF half are both implemented and
## self-tested end-to-end against a hand-rolled fake device — see "Relay/MPS
## component — Part 2" below. `wss://` support (both the relay's app-facing
## listener and CiraRelayTransport.kt's client) is now also done — see
## "Phase 6 — Part 3" below. Remaining: verification against real AMT
## hardware (see "Not yet started").

**Protocol facts confirmed against Intel's own "AMT Port Forwarding
Protocol Reference Manual" (APF) and cross-checked against MeshCentral's
`mpsserver.js` (Ylianst/MeshCentral, Apache-2.0), the dominant open-source
Management Presence Server implementation, whose behaviour is observable
in its own issue-tracker logs:**

CIRA inverts the connection direction from every other protocol this app
speaks. The AMT device itself opens an outbound TLS connection to a
Management Presence Server (MPS) at a hostname/IP baked in during
provisioning (`AMT_RemoteAccessPolicyRule`/`AMT_ManagementPresenceRemoteSAP`
on the WS-Man side, out of scope here since it's a provisioning-time
concern, not a viewer concern) and authenticates with a device TLS client
certificate the MPS is provisioned to trust. Once that link is up, the
device speaks APF over it:

1. `APF_PROTOCOLVERSION` (192) exchange.
2. `APF_SERVICE_REQUEST` (5) for `"auth@amt.intel.com"` → `APF_SERVICE_ACCEPT` (6).
3. `APF_USERAUTH_REQUEST` (50) (username/password, the MPS's own device
   credential — a separate secret from both the WS-Man Digest credential
   and any redirection-port password) → `APF_USERAUTH_SUCCESS` (52).
4. A second `APF_SERVICE_REQUEST` for `"pfwd@amt.intel.com"`.
5. `APF_GLOBAL_REQUEST` (80), request type `"tcpip-forward"`, once per port
   the device wants reachable through the MPS — typically both 16992
   (WS-Man) and 16993 (WS-Man/TLS); the redirection ports (16994/16995)
   are forwarded the same way when SOL/KVM/IDE-R are in use.
6. Periodic `APF_KEEPALIVE_REQUEST`(208)/`APF_KEEPALIVE_REPLY`(209).

Message numbers: `APF_DISCONNECT`=1, `APF_SERVICE_REQUEST`=5,
`APF_SERVICE_ACCEPT`=6, `APF_USERAUTH_REQUEST`=50,
`APF_USERAUTH_FAILURE`=51, `APF_USERAUTH_SUCCESS`=52,
`APF_GLOBAL_REQUEST`=80, `APF_REQUEST_SUCCESS`=81, `APF_REQUEST_FAILURE`=82,
`APF_CHANNEL_OPEN`=90, `APF_CHANNEL_OPEN_CONFIRMATION`=91,
`APF_CHANNEL_OPEN_FAILURE`=92, `APF_CHANNEL_WINDOW_ADJUST`=93,
`APF_CHANNEL_DATA`=94, `APF_CHANNEL_CLOSE`=97, `APF_PROTOCOLVERSION`=192,
`APF_KEEPALIVE_REQUEST`=208, `APF_KEEPALIVE_REPLY`=209.

From here, when *any* console/viewer wants to reach the device, the MPS —
not the viewer — is the one that sends `APF_CHANNEL_OPEN` (90,
`chan_type="forwarded-tcpip"`, `target_port=16992/16993/16994/16995`)
back down the connection the device already opened; the device replies
`APF_CHANNEL_OPEN_CONFIRMATION` (91), and `APF_CHANNEL_DATA` (94) frames
in both directions then carry the actual WS-Man/redirection-port bytes —
i.e. everything this app's `AmtClient`/`AmtSolSession`/`AmtKvmSession`/
`AmtIderSession` already speak, unmodified, just wrapped in APF channel
framing instead of a raw socket.

**The architectural question this settles:** a phone is not a viable MPS.
An MPS must be reachable at a stable address for devices to dial into,
which a mobile client on a carrier network behind CGNAT structurally
cannot offer. So "CIRA support" in this app cannot mean *this app*
terminates the device's outbound connection — it means this app becomes a
*console client* to an MPS that's deployed somewhere reachable (most
realistically a self-hosted MeshCentral instance, since it's the dominant
open MPS and the only one with well-understood real-world behaviour).

The remaining wrinkle: MeshCentral does **not** expose raw APF channel
framing to its own browser/console clients. Internally, `mpsserver.js`'s
`SetupChannel` bridges an already-open device APF channel to a *separate*,
MeshCentral-specific relay protocol that its web UI speaks (session
cookies + its own websocket relay framing) — not documented as a stable
public wire format the way APF itself is (APF has an Intel-published
reference manual; MeshCentral's browser-facing relay is only defined by
its own source, which can change between versions). Two paths follow from
this, and **the decision needing the user's sign-off before writing the
client is which one to build**:

- **(a) Speak MeshCentral's own console-relay protocol** — most direct
  path to something usable against an existing MeshCentral deployment
  today, at the cost of being tied to (and needing to be re-verified
  against) that project's own relay implementation rather than a numbered
  Intel spec.
- **(b) Ship a small companion relay/MPS component with this project**
  that terminates real, spec-conformant APF from devices (implementing
  exactly the message flow above) and exposes a deliberately simple,
  *this project's own* framing to the app — e.g. a WebSocket carrying
  `{deviceId, targetPort}` channel-open plus raw binary frames after that
  — over which this app is the only intended client. More build effort
  (a whole second, non-Android component, likely Node/Kotlin/whatever
  suits deployment), but the wire contract between phone and relay is then
  fully under this project's control and testable end-to-end, and nothing
  about it depends on reverse-engineering another project's internals.

Default recommendation, absent a reason found during Phase 6's own
research to prefer otherwise: **(b)**, specifically because it keeps the
already-tested `AmtSolSession`/`AmtKvmSession`/`AmtIderSession` protocol
code completely unchanged (see next paragraph) and because it can be
verified against the same public APF reference manual this section cites,
rather than against a moving, undocumented target.

**Prep work done in this pass, independent of which path (a)/(b) gets
picked:** `AmtSolSession`, `AmtKvmSession`, and `AmtIderSession` each used
to hold a bare `lateinit var socket: Socket` and touched it in exactly
four ways (`getInputStream()`/`getOutputStream()`/`soTimeout`/`close()` —
confirmed by grep, nothing Socket-specific like the remote address was
ever used). That's now `AmtRedirectionTransport` (see
`AmtRedirectionTransport.kt`), a three-member interface with
`DirectSocketTransport` as its only implementation today, wrapping the
exact same `Socket`/`SSLSocket` those classes always built via their own
`openTransport()`. This is a pure mechanical rename with no behaviour
change (every `open()`/`close()` call site was updated to match). Its
entire purpose is so that whichever relay client gets built next — a
`CiraRelayTransport` implementing the same three members — can be handed
to any of these three sessions without touching a single line of their
RFB/APF-preamble/IDE-R-envelope logic.

## Done this pass (path (b) — app side)

- **Decision confirmed:** path (b) — this project's own relay component,
  not MeshCentral's console-relay protocol — per this section's earlier
  reasoning. `AMT_VPRO_ROADMAP.md`'s own APF message-flow account above is
  the reference the relay component (not built in this pass — see "Not yet
  started") will need to match.

- **Connection setup UI** (`RdpProfile` columns `ciraEnabled`/
  `ciraRelayHost`/`ciraRelayPort`/`ciraRelayUsername`/`ciraRelayPassword`/
  `ciraDeviceId`, migration 62→63; the "Connect via CIRA relay" toggle and
  its fields in `ProfileFormDialog`/`ProtocolOptionsSection`; the "CIRA"
  connection-list badge) — done in an earlier pass, unchanged by this one.

- **`CiraRelayTransport.kt`** — the actual `AmtRedirectionTransport`
  implementation, an OkHttp `WebSocket` client speaking this project's own
  minimal relay protocol (see that file's top doc comment for the full,
  now-finalized wire contract: `ws://{host}:{port}/cira/v1/channel`,
  Basic-auth handshake header, a `channel-open`/`channel-open-ack` JSON
  preamble, then a raw binary pipe, ping/pong keepalive, plain WebSocket
  close). This is the first place that protocol is defined — the relay
  component (separate, not built here) must match it exactly.

- **Wired end-to-end**, not just implemented in isolation:
  `AmtClient.openSolSession`/`openKvmSession`/`openIderSession` each grew
  an `externalTransport: AmtRedirectionTransport? = null` parameter (skips
  their own best-effort WS-Man "enable" call when non-null, since that
  call has no relay path in this pass — see next bullet) and hand it
  straight to `AmtSolSession`/`AmtKvmSession`/`AmtIderSession` instead of
  building a `DirectSocketTransport`. `BmcManagementActivity`'s
  `amtOpenSolConsole`/`amtOpenKvmConsole`/`amtOpenIderDiagnostic`/
  `amtMountIderMedia` now call `openCiraTransport(profile)` (a thin
  `CiraRelayTransport.open(...)` wrapper) and pass its result through
  whenever the active profile has `ciraEnabled = true`, instead of calling
  the no-argument overload unconditionally. `connect()`'s AMT branch skips
  the direct WS-Man identity probe for a CIRA profile (there's nothing
  directly reachable to probe — see next bullet) and just stashes an
  unconnected `AmtClient` for those four call sites to use.

- **Known, flagged gap — WS-Man over CIRA is out of scope for this pass.**
  `AmtClient`'s own WS-Man HTTP calls (16992/16993 — identity check, power
  control, boot-device selection, the best-effort "enable SOL/KVM/IDE-R
  listener" calls) go over a *separate* `OkHttpClient` straight to
  `host`/`port`, entirely outside `AmtRedirectionTransport`. Under real
  CIRA there is no directly-reachable `host`/`port` for those to reach, so
  a CIRA profile's `amtPowerControl`/`amtRefresh`/`amtSetOneShotBoot` now
  fail fast with a clear message (`R.string.amt_cira_not_yet_implemented`)
  instead of attempting (and timing out) a doomed direct call — **this
  was a deliberate scope decision, not an oversight**: forwarding WS-Man
  itself would need `AmtClient`'s HTTP layer to grow a transport seam of
  its own (a bigger change than adding `externalTransport` to three
  session classes), so it's follow-up work. Practical effect: a
  CIRA-enabled profile can open SOL/KVM/IDE-R sessions through the relay,
  but not general device management (power, boot order, firmware info)
  — the profile's `BmcManagementScreen` only makes sense as a
  console-only screen for CIRA until that follow-up lands. Also flagged,
  not resolved: whether a CIRA-forwarded channel to the *TLS* redirection
  port (16995) needs a second, session-level TLS handshake on top of the
  already-TLS APF/CIRA connection isn't confirmed by anything cited above
  — `BmcManagementActivity.ciraRedirectionPort()` always targets the
  plain port (16994) for CIRA regardless of `RdpProfile.amtUseTls` rather
  than guess. And there's no `ciraRelayUseTls` profile column yet, so
  `CiraRelayTransport` always connects `ws://`, never `wss://` — fine for
  a relay reachable only over a private network/VPN, not for one exposed
  on the open internet; `wss://` support is follow-up work, not done here.

## Relay/MPS component — Part 1 (app-facing half done, this pass)

Lives in `relay/` at the repo root — a separate Node.js component, not
part of the Android Gradle build (see `relay/README.md`). Split in two
because the app-facing half's protocol was already fully pinned down by
`CiraRelayTransport.kt`'s doc comment (making it directly implementable
and independently testable), while the device-facing half is real,
previously-unimplemented APF protocol work needing its own dedicated pass
— see "Not yet started" below for the precise cut line handed to that
next pass.

- **`relay/src/wsChannelServer.js`** — the app-facing WebSocket server,
  implemented message-for-message against `CiraRelayTransport.kt`'s six
  numbered protocol sections (connect/Basic-auth, `channel-open`,
  `channel-open-ack`, binary data pipe, ping/pong keepalive via `ws`'s
  built-in auto-pong, and `channel-close`/normal WS close). Nothing here
  is a stub — this is the real implementation the Android client already
  matches.
- **`relay/src/deviceRegistry.js`** — defines the `DeviceRegistry`/
  `Channel` interface (`openChannel(deviceId, targetPort)` returning an
  `EventEmitter`-based channel with `write()`/`close()`/`'data'`/`'close'`/
  `'error'`) that is the seam between the two halves, with the exact
  method-by-method contract a real APF-backed implementation must satisfy
  documented in that file's own doc comment (cross-referenced to the APF
  message numbers earlier in this section). The only concrete
  implementation wired up today, `EchoDeviceRegistry`, backs a single
  fake `deviceId` with an in-process loopback so `wsChannelServer.js` can
  be exercised end-to-end (`relay/test/echoClient.js`, `npm run
  test:echo`) with no AMT hardware and no real APF server existing yet.
- **Verification note:** this pass's environment has no outbound network
  access, so `npm install` could not actually be run (confirmed:
  `registry.npmjs.org` is unreachable here) — every file was syntax-checked
  with `node --check` and passes, but the `ws`-dependent runtime paths
  (`WebSocketServer`, the `verifyClient` auth callback, the echo
  round-trip) are unverified beyond code review. Run `npm install && npm
  start` plus `npm run test:echo` yourself before relying on this.

## Relay/MPS component — Part 2 (device-facing APF server, this pass)

Security decisions confirmed with the user before implementation (see
`relay/src/apfTrustStore.js`'s doc comment for the full reasoning):
**`deviceId` mapping is layered** (APF username as primary key, TLS
client-certificate SHA-256 fingerprint pinned as an independent second
factor — both must match); **trust policy is an explicit allowlist**, no
TOFU and no accept-anything fallback.

- **`relay/src/apfProtocol.js`** — the APF wire format: message-type
  constants, a bounds-checked incremental buffer decoder
  (`InsufficientDataError` for "not enough bytes yet" vs.
  `ApfProtocolError` for genuinely malformed data), and encoders for every
  message this relay sends. Written from Intel's published APF reference
  material; **not yet cross-checked against a packet capture from real AMT
  hardware** — see its own doc comment for the two spots (protocol-version
  reserved padding, keepalive cookie semantics) most worth re-verifying
  first if real-device behaviour ever disagrees with it.
- **`relay/src/apfTrustStore.js`** — the device allowlist/auth policy
  described above, loaded from `APF_DEVICE_ALLOWLIST_FILE`/
  `APF_DEVICE_ALLOWLIST`.
- **`relay/src/apfDeviceServer.js`** — the TLS listener (`requestCert:
  true`, `rejectUnauthorized: false` — trust is enforced by this project's
  own fingerprint pinning at the `APF_USERAUTH_REQUEST` step, not Node's
  CA-chain validation, since AMT devices' certificates come from a private
  provisioning CA/are self-signed) and the full per-device state machine:
  `APF_PROTOCOLVERSION` exchange, the two-service auth/pfwd handshake,
  `tcpip-forward` registration, relay-initiated `APF_CHANNEL_OPEN` with
  timeout, bidirectional `APF_CHANNEL_DATA`, `APF_CHANNEL_CLOSE` from
  either side, and periodic `APF_KEEPALIVE_REQUEST`/`_REPLY` with a
  dead-connection timeout.
- **`relay/src/deviceRegistry.js`'s `ApfDeviceRegistry`** — the thin
  adapter satisfying the `DeviceRegistry` contract by delegating to
  `ApfDeviceServer`, distinguishing "deviceId not in the allowlist at all"
  (`UnknownDeviceError`) from "allowlisted but not currently connected"
  (plain `Error`, same as any other `openChannel` failure).
- **`relay/src/server.js`** now constructs `ApfDeviceRegistry` automatically
  when `APF_TLS_PORT` is configured, falling back to Part 1's
  `EchoDeviceRegistry` otherwise — no other file needed to change, exactly
  as Part 1's cut line anticipated.
- **Verification**: `relay/test/apfDeviceServer.selftest.js` plays the
  device role over a real `tls` socket (hand-encoded APF frames, no `ws`/
  npm deps needed since this module tree only uses Node builtins) against
  a real `ApfDeviceServer` — full handshake, a relay-initiated channel
  open, bidirectional data, device-initiated close, and a wrong-password
  rejection, all passing. This is real execution, not just `node --check`
  — a step up from Part 1's syntax-only verification — but it is **still
  not a real AMT device**; hardware-level quirks (exact handshake timing,
  real certificate provisioning behaviour, real keepalive cadence under
  network jitter) remain unverified. See `relay/README.md`'s "Testing"
  section to reproduce.

## Phase 6 — Part 3 (`wss://` support, both sides — this pass, split
## across two conversations: 3a = relay listener, 3b = app client)

Closes the gap flagged at the end of "Done this pass (path (b) — app
side)" above: `RdpProfile.ciraRelayUseTls` now actually changes which
scheme `CiraRelayTransport` connects with, and the relay can terminate
that connection with real TLS.

### Part 3a — relay's app-facing `wss://` listener

- **`relay/src/wsChannelServer.js`/`server.js`**: `server.js` now starts an
  additional `https.Server` on `WSS_PORT` (independent of the plain `PORT`)
  whenever `WSS_TLS_KEY_PATH`/`WSS_TLS_CERT_PATH` are configured, with
  `DISABLE_PLAIN_WS=true` available to stop serving `ws://` at all once a
  deployment has moved to TLS. This certificate is entirely separate from
  the device-facing APF listener's own `APF_TLS_KEY_PATH`/`APF_TLS_CERT_PATH`
  — two independent TLS server identities, one per direction of this relay.
- **Verification**: `relay/test/wssListener.selftest.js` — a real TLS
  handshake plus an HTTP round-trip against the new listener. Additionally,
  **the `ws`-based WebSocket upgrade over that listener is now also
  verified for real** (closing the gap this section used to flag): this
  environment still can't reach `registry.npmjs.org`, but a compatible
  `ws@8.20.0` happened to already be present on disk as a transitive
  dependency of an unrelated local tool and was copied into
  `relay/node_modules/ws` to run `relay/test/wssEndToEnd.selftest.js` —
  see that file's doc comment and Part 3b below. Not a substitute for a
  real `npm install` in an environment with registry access, but real
  execution of the real server code, not a mock.

### Part 3b — `CiraRelayTransport.kt`'s `wss://` client

- **`CiraRelayTransport.open`** gained `useTls`/`appContext` parameters:
  when `useTls` (fed from `RdpProfile.ciraRelayUseTls`), it connects
  `wss://` instead of `ws://` to the same host/port/path.
  `BmcManagementActivity.openCiraTransport` now passes both through.
- **Trust-mode decision (made this pass, not asked back to the user per
  this phase's own brief — an implementation detail, not a security
  policy call):** `wss://` mode always uses `TofuTrustManager` (silent
  Trust-On-First-Use, pinned per `"$relayHost:$relayPort"`, hostname
  verification skipped in favor of the exact-fingerprint pin) — the same
  pattern `AmtClient`'s own `acceptSelfSignedCertificate` path already
  uses, and for the same reason: a self-hosted relay very commonly runs a
  self-signed certificate, and TOFU gives real MITM detection (unlike a
  blind trust-all) without requiring a CA-signed cert or a manual trust-
  store import. `open` fails closed (`IllegalArgumentException`) if
  `useTls` is true and no `appContext` is supplied, rather than silently
  falling back to trust-all.
- **No shared `TlsSocketFactoryBuilder` extracted**, despite
  `RdpWebSocketTransport.kt` already having near-identical
  system/TOFU/pinned/custom-CA trust-manager-selection logic: that class's
  four trust modes exist because `RdpWebSocketConfig.TlsOptions` gives RDP
  profiles UI for all four (validate/allow-self-signed/pin/custom-CA).
  `RdpProfile`'s CIRA fields expose exactly one switch
  (`ciraRelayUseTls`), so there are only two modes here (`ws://` /
  `wss://`+TOFU) — not enough shared surface to justify a new abstraction
  over copying ~15 lines already mirrored a third time in `AmtClient.kt`
  itself. Revisit if CIRA ever grows its own pinning/custom-CA UI.
- **Testing this end-to-end — actually run this pass, real execution, not
  just documented steps:** `relay/test/wssEndToEnd.selftest.js` starts the
  real `server.js` `wss://` listener (real `https.Server` + real `ws`
  `WebSocketServer`) and drives it with a hand-rolled client that
  reimplements `TofuTrustManager.checkServerTrusted`'s exact algorithm
  line-for-line — TLS connect with hostname verification off, compute the
  peer certificate's SHA-256 fingerprint, accept+pin on first use, accept
  silently on a matching pin, hard-abort on a mismatched one — layered
  under the same `channel-open`/`channel-open-ack`/binary-echo/`close`
  protocol `CiraRelayTransport.kt` speaks. All three cases passed for
  real: (1) first connection to a fresh identity pins the cert and the
  echo round-trip works over real `wss://`; (2) a second connection to the
  *same* cert reuses the pin and also works; (3) restarting the relay with
  a *different* cert for the same identity is hard-rejected before any
  WebSocket traffic, proving the MITM-detection path actually fires and
  isn't just a code-reading assumption. See `relay/README.md`'s "Testing"
  section for the exact commands.
  **What is still NOT verified by this**: `CiraRelayTransport.kt` itself
  was never compiled or executed — this environment has no JVM/Android
  toolchain (Node only), so the TOFU logic under test is a faithful port
  reviewed against `TofuTrustManager.kt` line-by-line, not the literal
  Kotlin bytecode running. Compiling and running the actual app against
  this same relay setup (steps below, for whoever has a toolchain) is the
  remaining step to fully close this gap:
  1. `cd relay/test-certs && openssl req -x509 -newkey rsa:2048 -keyout
     wss.key -out wss.crt -days 365 -nodes -subj "/CN=cira-relay"`.
  2. `WSS_TLS_KEY_PATH=wss.key WSS_TLS_CERT_PATH=wss.crt WSS_PORT=8443 npm
     start` from `relay/`.
  3. In the app's CIRA profile, set the relay port to `8443`, turn on
     "Use wss:// (TLS) to the relay", and connect — `TofuTrustManager`
     pins the self-signed certificate automatically, no manual trust-store
     import needed.
  4. Regenerate `wss.crt`/`wss.key` (same `CN`, different key), restart
     the relay, reconnect — the app should hard-fail instead of silently
     accepting the new certificate; `TofuTrustManager.clearPin` is the
     only way to recover, same as any other TOFU-pinned protocol here.

## Phase 6 — Part 4 (WS-Man over CIRA — this pass)

Closes the item the previous "Not yet started" section flagged: general
AMT device management (identity check, power control, boot device, the
audit/redirection-access logs, the SOL/KVM/IDE-R "enable" WS-Man calls) now
works for a CIRA-enabled profile, not just the SOL/KVM/IDE-R redirection
sessions Part 3 (and the pass before it) already carried.

- **The problem this closes:** `AmtClient`'s WS-Man calls went over their
  own `OkHttpClient`, dialing `host`/`port` directly — a real `Socket`
  OkHttp opens itself. `AmtRedirectionTransport`/`CiraRelayTransport` (the
  seam Part 3's SOL/KVM/IDE-R support already uses) is a plain byte pipe —
  a WebSocket under the relay, not something OkHttp's connection layer has
  any public seam to adopt in place of dialing its own socket. So carrying
  WS-Man over CIRA needed an HTTP client that speaks HTTP/1.1 directly over
  an already-open `AmtRedirectionTransport`'s streams, not a way to hand
  OkHttp someone else's socket.
- **`CiraWsmanHttpTransport.kt`** — that HTTP/1.1 client: writes a POST
  (headers + `Content-Length`-framed body) straight to
  `AmtRedirectionTransport.outputStream`, and reads the status line/headers/
  body back off `inputStream` with a byte-at-a-time line reader (deliberately
  not a buffered `Reader`, which would risk consuming past the header
  block's terminal blank line into the body). Handles both
  `Content-Length`- and `Transfer-Encoding: chunked`-delimited bodies, the
  latter defensively — every AMT SDK example and open-source stack this
  file's sibling classes already cite (MeshCommander, MeshCentral,
  python-amt) shows `Content-Length` for WS-Man responses, so the chunked
  path is untested against real firmware, unlike the `Content-Length` path
  which is exactly what `AmtClient`'s existing OkHttp path already relies
  on. Keeps one relay channel open across every WS-Man call on an
  `AmtClient` instance (opening a fresh CIRA channel per call would mean a
  full WebSocket-handshake-plus-`channel-open` round trip for every single
  WS-Man request) and transparently reopens it — via a factory, not a
  pre-opened transport — whenever the last response said
  `Connection: close` (AMT's embedded server commonly sends this after a
  401 Digest challenge) or a read/write fails outright, retried once per
  call so a genuinely unreachable relay/device still surfaces as a real
  error.
- **`AmtClient.kt`**: gained an `externalWsmanTransportFactory` constructor
  parameter, the WS-Man counterpart to `openSolSession`/`openKvmSession`/
  `openIderSession`'s existing `externalTransport` parameter — a
  *separate* relay channel from those (WS-Man and redirection are
  different channels to different device ports even under CIRA, same as a
  direct connection). `requestWithRawBody` now branches on whether this
  factory was supplied: `requestOverDirectHttp` (renamed, otherwise
  unchanged — the original OkHttp path) or the new `requestOverCira`, which
  drives `CiraWsmanHttpTransport.exchange` through the exact same
  Digest-challenge/retry logic (`cachedChallenge`, `digestHeader`,
  `parseDigestChallenge`) the direct path already had — those were already
  transport-agnostic (no OkHttp types), so nothing about the Digest
  handshake itself needed to change. `disconnect()`, previously a no-op
  (WS-Man itself has no session to close), now closes this transport if one
  was opened — the underlying CIRA channel is a real persistent connection,
  unlike OkHttp's self-managed connection pool.
- **`BmcManagementActivity.kt`**: `connect()`'s AMT branch no longer
  special-cases CIRA profiles into a "stash an unconnected `AmtClient`"
  path — it builds `AmtClient` with `externalWsmanTransportFactory` (via
  the new `buildWsmanTransportFactory`/`ciraWsmanPort` helpers, mirroring
  `openCiraTransport`/`ciraRedirectionPort`'s existing shape for the
  redirection port) whenever `p.ciraEnabled`, then calls `client.connect()`
  and `refreshAmt()` unconditionally — a real identity probe now happens
  for a CIRA profile too. `amtPowerControl`/`amtRefresh`/
  `amtSetOneShotBoot` dropped their `R.string.amt_cira_not_yet_implemented`
  early-return guards entirely; they now just call through to `AmtClient`
  the same way a direct profile always did, since the transport
  `AmtClient` was built with already determines direct-vs-relay.
- **RESOLVED (follow-up pass, see `TlsLayeredTransport.kt`) — TLS-over-CIRA
  for both the WS-Man port and the SOL/KVM/IDE-R redirection ports.**
  Confirmed against MeshCentral's own open-source MPS server
  (`mpsserver.js`) and Intel's APF Port Forwarding Protocol Reference
  Manual: the decision is keyed by *port number*, not by whether the outer
  relay/APF tunnel is already encrypted. 16993/16995 are always AMT's local
  TLS-mode ports and 16992/16994 always plain, independent of CIRA;
  MeshCentral's CIRA-path connections read the device's own real
  `APF_TCP_FORWARD_LISTEN` bound-port list to decide, and its Relay/LMS
  path tries 16993-with-TLS first and falls back to 16992 plain — in both
  cases "is this port a TLS port" is answered independently of the tunnel.
  `TlsLayeredTransport` (new file) implements the inner handshake as an
  `AmtRedirectionTransport` decorator — a `java.net.Socket` facade
  (`TransportBackedSocket`) over the already-open relay channel, handed to
  `SSLSocketFactory.createSocket(Socket, host, port, autoClose)` so the
  platform's own TLS provider does the handshake/record-layer work (the
  same technique mail libraries use for STARTTLS), reusing
  `TofuTrustManager` exactly like `AmtClient`'s direct-connect TLS path and
  `CiraRelayTransport`'s own `wss://` mode. `ciraWsmanPort()`/
  `ciraRedirectionPort()` in `BmcManagementActivity` now switch on
  `RdpProfile.amtUseTls` (16992/16993 and 16994/16995 respectively) instead
  of always forcing the plain port, and `connect()`'s AMT branch no longer
  fails fast for a CIRA + TLS profile — `R.string.amt_cira_tls_not_supported`
  is now unused (left in `strings.xml` rather than removed, in case a
  future regression needs the same fail-fast message reinstated quickly).
  **Still not verified against real hardware** — see "Not yet started"
  below; the port-number/boundPorts *logic* is confirmed against
  MeshCentral's real implementation, but the `SSLSocketFactory`-over-a-fake-
  `Socket` technique itself has not been exercised against a real AMT TLS
  listener through a real relay.
- **Verification**: no JVM/Android toolchain in this environment (same
  constraint Part 3b's own verification note already recorded), so
  `CiraWsmanHttpTransport`/`AmtClient`'s CIRA branch were reviewed
  line-by-line against `CiraRelayTransport`'s already-tested
  `AmtRedirectionTransport` contract and RFC 7230's request/status-line/
  header/body-framing rules, but never compiled or run — this is a real
  gap, not a formality. Compiling and running the app's `BmcManagementScreen`
  against `relay/`'s `EchoDeviceRegistry` fake device (from Part 1 — a real
  APF/AMT device isn't needed to at least exercise the HTTP framing/Digest
  round trip end-to-end, since `EchoDeviceRegistry` will just echo whatever
  WS-Man bytes are sent rather than answer them like real AMT firmware
  would) is the natural first check for whoever has a toolchain, followed
  by the same real-hardware verification "Not yet started" below already
  calls for.

## Not yet started

- **Verification against real AMT hardware** — everything in "Part 2" and
  "Part 4" above is implemented and (at best) self-tested/reviewed against
  a fake device or by inspection, not a real vPro chipset. Needs either
  physical AMT hardware provisioned to dial this relay, or a more
  sophisticated APF simulator than the hand-rolled one in the self-test, to
  close that gap.
- **Compiling/running this pass's Kotlin changes** — Part 4's `AmtClient`/
  `CiraWsmanHttpTransport` changes, and this follow-up's
  `TlsLayeredTransport`/`BmcManagementActivity` changes, were never
  compiled: this sandbox has a JRE (`java`) but no `javac`/`kotlinc`/
  `gradle` and no network access to fetch them (confirmed, not assumed —
  `gradle`/`kotlinc` aren't installed and `curl` to Gradle's own servers
  returns 403 here). This is a lower bar than real-hardware verification
  and worth doing first in an environment that has the actual Android/
  Kotlin toolchain.

## Done since this file was last accurate

- **IDE-R write support** — split into two separate architecture decisions
  rather than one change: the CD/DVD-ROM side (`AmtIderDiskEmulator`) stays
  permanently read-only, matching real optical media (a real CD/DVD-ROM
  SCSI target has no WRITE(10)/WRITE(12) to fall back to — `OP_WRITE_10`/
  `OP_WRITE_12` are now explicit cases returning `ILLEGAL_REQUEST`, not an
  accident of the generic `else`); the floppy/HDD-image side
  (`AmtIderFloppyEmulator`) is the actually-writable half — WRITE SECTORS /
  WRITE SECTORS NO RETRY (0x30/0x31), opt-in via a `writable: Boolean =
  false` constructor parameter so an existing read-only mount is never
  silently upgraded to accept writes. On the envelope side, `AmtIderSession`
  now tracks a pending write between `CMD_COMMAND_WRITTEN` (registers only)
  and the one-or-more `CMD_DATA_FROM_HOST` messages that follow with the
  actual payload — envelope framing confirmed against MeshCentral's
  `amt/amt-ider-module.js` `SendCommandEndResponse`/data-from-host handling,
  same source already cited above for the rest of the envelope — then
  reports the real result via the same `sendCommandComplete`/
  `sendCommandError` pair the read path already used, instead of the old
  hardcoded "writes not supported" stub response. `BmcManagementScreen`'s
  media tab surfaces this as a "Writable" toggle, shown only when Floppy is
  the selected media type (CD/DVD-ROM has no such toggle, since it's
  ignored either way).

- **Tight/ZRLE encodings for KVM** — implemented in `AmtKvmSession.kt`
  (`readTightRect`/`readZrleRect`, `sendSetEncodings` now advertises
  Tight/ZRLE/Hextile/CopyRect/Raw in that preference order). Uses
  `java.util.zip.Inflater` for zlib and `android.graphics.BitmapFactory`
  for Tight's JPEG subrects — both already part of the platform, no new
  dependency. See that class's doc comment above `sendSetEncodings` for
  the remaining documented gap (the rare explicit-no-zlib subencoding —
  reserved/undefined by RFC 6143 in the first place, never sent by any
  compliant server — and JPEG chroma subsampling nuances, already handled
  by the platform decoder regardless).

- **Tight gradient filter** — `readTightGradientPixels` in
  `AmtKvmSession.kt` implements the third and last Tight filter (Copy and
  Palette already existed; Gradient was the one documented gap). Per
  RFC 6143 §7.6.7 / the TightVNC spec's own reconstruction pseudo-code,
  each colour component (R, G, B independently, since Tight's CPIXEL is
  always RGB byte order) is predicted from `left + up - upperLeft`
  (clamped to 0-255, treating out-of-rectangle neighbours as 0) and the
  wire byte is the difference from that prediction, mod 256; decoding
  reconstructs top-to-bottom/left-to-right so neighbours are always
  already known. Verified against an independently-written encoder in
  `AmtKvmSessionTightGradientTest.kt` (flat-colour, ramp, and random-noise
  cases — the last specifically to exercise the mod-256 wraparound, since
  a flat/ramp image alone can pass with a sign-handling bug that only a
  negative-difference case would catch).
