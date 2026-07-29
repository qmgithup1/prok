# systemsgo-cira-relay

The standalone companion relay/MPS component for SystemsGo/HexRDP's AMT
CIRA support — AMT-VPRO Roadmap **Phase 6, path (b)**. See
`../AMT_VPRO_ROADMAP.md`'s Phase 6 section for the full architectural
background (why a phone can't be an MPS, why path (b) over speaking
MeshCentral's own relay protocol) and the complete Intel APF message-flow
reference this component's device-facing half must implement.

This is a separate, non-Android piece of software. Nothing in the Android
app depends on how it's built internally — only on it speaking the
WebSocket protocol documented in
`app/src/main/java/com/systemsgo/hex/amt/protocol/CiraRelayTransport.kt`'s
top doc comment exactly.

## Status: both APF halves implemented; `wss://` done and end-to-end verified on the relay listener + client-side TOFU logic

| Half | State |
|---|---|
| **App-facing** — the WebSocket server speaking `CiraRelayTransport.kt`'s protocol to the phone (`channel-open`/`channel-open-ack`, binary pipe, ping/pong, `channel-close`) | **Done.** `src/wsChannelServer.js`, message-for-message against that file's doc comment. Attaches identically to either an `http.Server` (`ws://`) or `https.Server` (`wss://`) — see next row. |
| **Device-facing** — a real APF server terminating AMT devices' outbound TLS+APF connections (protocol version exchange, service request, userauth, `tcpip-forward`, `APF_CHANNEL_OPEN`/`_DATA`/`_CLOSE`, keepalive) | **Done.** `src/apfProtocol.js` (wire format), `src/apfTrustStore.js` (device allowlist/auth policy), `src/apfDeviceServer.js` (TLS listener + per-device state machine), `src/deviceRegistry.js`'s `ApfDeviceRegistry` (adapts it to the `DeviceRegistry` contract). Verified end-to-end against a hand-rolled fake device over a real TLS socket — see "Testing" below — but **not yet against real AMT hardware**; treat as implemented-and-self-tested, not field-verified. |
| **`wss://` listener on the app-facing side** (Phase 6 Part 3a) | **Done and verified for real, including the WebSocket upgrade over it.** `server.js` starts an additional `https.Server` on `WSS_PORT` when `WSS_TLS_KEY_PATH`/`WSS_TLS_CERT_PATH` are configured (`DISABLE_PLAIN_WS=true` to stop serving plain `ws://` at all). `test/wssEndToEnd.selftest.js` runs the real server + real `ws` WebSocketServer end-to-end (TLS handshake, Basic-auth, `channel-open`/ack, binary echo, close) — see "Testing" below. |
| **`wss://` client in `CiraRelayTransport.kt`** (`RdpProfile.ciraRelayUseTls`, Phase 6 Part 3b) | **Done.** `CiraRelayTransport.open` now connects `wss://` when `useTls` (fed from `RdpProfile.ciraRelayUseTls`) is set, verifying the relay's certificate via the project's shared `TofuTrustManager` (silent Trust-On-First-Use, pinned per `host:port`) — see that file's doc comment ("`wss://` trust mode") and `AMT_VPRO_ROADMAP.md`'s Phase 6 "Part 3" section for the full reasoning. `test/wssEndToEnd.selftest.js` verifies a line-for-line port of `TofuTrustManager`'s pin/verify/reject logic against the real relay and real TLS handshakes (pin-on-first-use, reuse-on-match, hard-reject-on-mismatch all pass) — the Kotlin code itself is still unverified by direct execution, since this environment has no JVM/Android toolchain (see "Testing" below and the roadmap's Part 3b section). |

The seam between the two halves is the `DeviceRegistry`/`Channel`
interface documented at the top of `src/deviceRegistry.js`. `src/server.js`
now constructs `ApfDeviceRegistry` automatically when `APF_TLS_PORT` is
configured, falling back to the Part 1 `EchoDeviceRegistry` stub otherwise
— see "Configuration" below.

### Device identity & trust policy (security decisions, confirmed with the user this pass)

- **`deviceId` mapping is layered**: the APF username from
  `APF_USERAUTH_REQUEST` is the primary lookup key, cross-checked against a
  *pinned* SHA-256 fingerprint of the device's TLS client certificate as an
  independent second factor. Both must match the same allowlist entry —
  see `src/apfTrustStore.js`'s doc comment for the full reasoning and the
  allowlist entry format.
- **Trust policy is an explicit allowlist** — no TOFU, no
  accept-any-certificate fallback. Every device (username + password +
  certificate fingerprint + the `deviceId` it maps to) is provisioned into
  `APF_DEVICE_ALLOWLIST`/`APF_DEVICE_ALLOWLIST_FILE` ahead of time.

## Running it

```bash
cd relay
npm install
RELAY_USERS='[{"username":"phone1","password":"correct-horse"}]' npm start
```

With no `RELAY_USERS` set it accepts unauthenticated connections (logged
as a warning on startup) — fine for local testing, never for anything
reachable off a trusted network.

### Smoke-testing the app-facing protocol without any AMT device

```bash
# terminal 1
npm start
# terminal 2
npm run test:echo
```

`test/echoClient.js` performs the exact same handshake
`CiraRelayTransport.kt` does — connect, `channel-open` against the fixed
`echo-test` device, send a payload, confirm it comes back unchanged as a
binary frame. This validates the relay's app-facing framing in isolation;
it is **not** a stand-in for a real AMT device (an actual `AmtSolSession`
pointed at this relay's echo device will fail its own handshake, since it
gets its own `StartRedirectionSession` bytes reflected back rather than
AMT's `StartRedirectionSessionReply` — see `deviceRegistry.js`'s
`EchoChannel` doc comment).

### Testing the device-facing APF server without real AMT hardware

`test/apfDeviceServer.selftest.js` plays the *device* role by hand over a
raw `tls` socket (no `ws`/npm deps needed — `apfDeviceServer.js` and
everything it depends on uses only Node builtins), against a real
`ApfDeviceServer` instance: full handshake
(`APF_PROTOCOLVERSION`→`APF_SERVICE_REQUEST`/auth→`APF_USERAUTH_REQUEST`→`APF_SERVICE_REQUEST`/pfwd→`tcpip-forward`),
a relay-initiated `APF_CHANNEL_OPEN` round trip with bidirectional
`APF_CHANNEL_DATA`, a device-initiated `APF_CHANNEL_CLOSE`, and a
wrong-password negative case. This is what was actually run to verify the
implementation in this pass (see "Verification note" below) — everything
else beyond it (real AMT firmware's exact handshake timing/quirks,
concurrent multi-device load, the keepalive path under real network
conditions) is still unverified.

```bash
cd relay
mkdir -p test-certs && cd test-certs
openssl req -x509 -newkey rsa:2048 -keyout server.key -out server.crt -days 365 -nodes -subj "/CN=relay-test"
openssl req -x509 -newkey rsa:2048 -keyout device.key -out device.crt -days 365 -nodes -subj "/CN=device-test"
cd ..
node test/apfDeviceServer.selftest.js
```

### Testing the app-facing `wss://` listener + `CiraRelayTransport.kt`'s TOFU trust logic together

```bash
cd relay
mkdir -p test-certs && cd test-certs
openssl req -x509 -newkey rsa:2048 -keyout wss1.key -out wss1.crt -days 365 -nodes -subj "/CN=cira-relay-test"
openssl req -x509 -newkey rsa:2048 -keyout wss2.key -out wss2.crt -days 365 -nodes -subj "/CN=cira-relay-test"
cd ..
npm install   # needs `ws` — see package.json
npm run test:wss
```

`test/wssEndToEnd.selftest.js` starts the *real* `server.js` app-facing
listener (real `https.Server` + real `ws` `WebSocketServer` — the exact
code a deployed relay runs, `WSS_TLS_KEY_PATH`/`WSS_TLS_CERT_PATH` set to
`wss1.crt`/`wss1.key`) and drives it with a hand-rolled client that
reimplements `TofuTrustManager.kt`'s exact pin-then-verify logic (see that
test file's doc comment) on top of a real TLS socket, then does the full
`channel-open`/`channel-open-ack`/binary-echo/`close` handshake against the
`echo-test` device — the same six-section protocol `CiraRelayTransport.kt`
speaks. It asserts, against real execution rather than code review:

1. A first connection to a fresh identity pins the certificate and the
   channel/echo round-trip works over real `wss://`.
2. A second connection to the *same* certificate reuses the pin and also
   succeeds.
3. Restarting the relay with a *different* certificate (`wss2.crt`, same
   `CN`, different key) and reconnecting is hard-rejected before any
   WebSocket traffic — the MITM-detection path.

This closes the "not yet exercised" gap noted in the Status table above
for the app-facing `wss://` listener, and is the closest this pass's
environment can get to running `CiraRelayTransport.kt` itself (no JVM/
Android toolchain here — see `AMT_VPRO_ROADMAP.md`'s Part 3b section):
the TOFU algorithm under test is a line-for-line port of
`TofuTrustManager.checkServerTrusted`, exercised against the real relay
server and real TLS handshakes, not a mock of either.



| Var | Default | Meaning |
|---|---|---|
| `PORT` | `8787` | App-facing WebSocket listen port. Must match the port configured in `RdpProfile.ciraRelayPort` for profiles pointed at this deployment. |
| `WS_PATH` | `/cira/v1/channel` | Must match `CiraRelayTransport.kt`'s `WS_PATH` constant exactly. |
| `RELAY_USERS` | *(none — unauthenticated)* | JSON array of `{"username","password"}` pairs checked against the app's Basic-auth handshake header. |
| `CHANNEL_OPEN_TIMEOUT_MS` | `8000` | How long a connection may sit idle after the WS handshake before sending its `channel-open` frame. |
| `ECHO_TEST_DEVICE_ID` | `echo-test` | The `deviceId` the fallback stub registry recognizes when `APF_TLS_PORT` is unset. |
| `APF_TLS_PORT` | *(unset — falls back to the echo stub)* | Device-facing APF TLS listen port. Setting this switches `server.js` from `EchoDeviceRegistry` to the real `ApfDeviceRegistry`. |
| `APF_TLS_HOST` | `0.0.0.0` | Device-facing listen address. |
| `APF_TLS_KEY_PATH` / `APF_TLS_CERT_PATH` | *(required if `APF_TLS_PORT` set)* | This relay's own TLS server key/cert — what devices must be provisioned to connect to. Unrelated to the app-facing `wss://` cert below (separate `https.Server`, separate key/cert). |
| `WSS_TLS_KEY_PATH` / `WSS_TLS_CERT_PATH` | *(unset — app-facing side stays `ws://`-only)* | This relay's TLS server key/cert for the **app-facing** `wss://` listener (Phase 6 Part 3a) — what `CiraRelayTransport.kt` verifies via `TofuTrustManager` when `RdpProfile.ciraRelayUseTls` is on. Setting both starts an additional `https.Server` on `WSS_PORT` alongside the plain one on `PORT`. |
| `WSS_PORT` | `8788` | App-facing `wss://` listen port (only used when `WSS_TLS_KEY_PATH`/`WSS_TLS_CERT_PATH` are set). Must match the port configured in the profile's `ciraRelayPort` when `ciraRelayUseTls` is on. |
| `DISABLE_PLAIN_WS` | `false` | When `true`, stops serving plain `ws://` on `PORT` entirely — only the `wss://` listener on `WSS_PORT` accepts connections. Requires `WSS_TLS_KEY_PATH`/`WSS_TLS_CERT_PATH` to also be set (fails fast on startup otherwise). |
| `APF_DEVICE_ALLOWLIST_FILE` / `APF_DEVICE_ALLOWLIST` | *(one required if `APF_TLS_PORT` set)* | JSON array of `{"username","password","deviceId","certSha256"}` — see `src/apfTrustStore.js`'s doc comment for the full format and the layered-auth reasoning. `_FILE` (a path, reloadable, keeps secrets out of process env dumps) is preferred over the inline variant. |
| `APF_KEEPALIVE_INTERVAL_MS` | `30000` | How often this relay pings each connected device with `APF_KEEPALIVE_REQUEST`. |
| `APF_KEEPALIVE_TIMEOUT_MS` | `10000` | How long to wait for `APF_KEEPALIVE_REPLY` before treating a device connection as dead. |
| `APF_CHANNEL_OPEN_TIMEOUT_MS` | `8000` | How long to wait for `APF_CHANNEL_OPEN_CONFIRMATION`/`_FAILURE` after sending `APF_CHANNEL_OPEN` to a device. |

## Known gaps (flagged, not silently decided — see roadmap for full detail)

- **`wss://` on the app-facing side is implemented and end-to-end verified**
  (real relay server, real `ws` WebSocketServer, real TLS handshakes —
  `npm run test:wss`, see "Testing" above) **but `CiraRelayTransport.kt`
  itself has not been directly executed** — this environment has no JVM/
  Android toolchain, only Node. `test/wssEndToEnd.selftest.js` verifies a
  faithful line-for-line port of `TofuTrustManager`'s trust logic against
  the real relay instead; treat the Kotlin side as implemented-and-
  logic-verified, not compiled-and-run. Plain `ws://` remains the default
  and is fine behind a VPN/private network; `wss://` is what makes an
  open-internet-exposed relay deployment safe to enable `ciraRelayUseTls`
  against.
- **Not yet run against real AMT hardware.** `apfProtocol.js`'s byte
  layouts (especially `APF_PROTOCOLVERSION`'s reserved padding and the
  keepalive cookie semantics) are implemented from Intel's published APF
  reference material and verified against a hand-rolled fake device (see
  "Testing" above), not against a live vPro chipset or a packet capture.
  Treat as a strong first implementation to validate against real
  hardware, not as field-proven.
- **No outbound flow-control throttling** on `APF_CHANNEL_DATA` — this
  relay advertises a window but doesn't enforce it against the device's
  own advertised window on send. Acceptable for SOL/KVM/IDE-R's
  request/response-paced traffic; worth revisiting for anything
  higher-throughput.
- **No WS-Man forwarding.** Matches the Android app's own documented scope
  decision (`AmtClient`'s WS-Man HTTP calls aren't routed through
  `AmtRedirectionTransport`/this relay at all in this pass) — see
  `AMT_VPRO_ROADMAP.md`.
- **No persistence, metrics, or multi-instance/HA story.** Single process,
  in-memory device/channel registries — one relay instance per deployment,
  no shared state across instances.
