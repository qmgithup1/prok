# Mosh support — status and roadmap

## Confirmed correct (this pass)

**Wire protocol field numbers** — cross-checked byte-for-byte against
upstream's actual `src/protobufs/{transportinstruction,hostinput,userinput}.proto`
(previously only cross-checked against an independent Go re-implementation,
mosh-go, because the sandbox that wrote MoshWireProtocol.kt had no network
access to fetch upstream's real sources). Every field number
MoshWireProtocol.kt emits/parses matches upstream exactly:

- `TransportBuffers.Instruction`: 1=protocol_version, 2=old_num, 3=new_num,
  4=ack_num, 5=throwaway_num, 6=diff, 7=chaff.
- `HostBuffers.Instruction` extensions: 2=HostBytes{4=hoststring},
  3=ResizeMessage{5=width,6=height}, 7=EchoAck{8=echo_ack_num}.
- `ClientBuffers.Instruction` extensions: 2=Keystroke{4=keys},
  3=ResizeMessage{5=width,6=height}.

No changes needed — this was already correct, now verified rather than
inferred.

## Predictive local echo — implemented (line-local scope)

`MoshPredictionEngine.kt` ports upstream's `PredictionEngine`
(src/frontend/terminaloverlay.h/.cc) for the common case: normal ASCII
typing and Backspace at the end of an already-synced line. The
SRTT/flagging confidence model (when to show predictions at all, when to
underline them) is ported with upstream's exact threshold constants
(SRTT_TRIGGER_LOW/HIGH = 20/30ms, FLAG_TRIGGER_LOW/HIGH = 50/80ms).

**What's different from upstream, and why:** upstream predicts against a
full VT100 `Framebuffer` — a persistent 2-D cell grid with cursor state,
mirroring exactly what the server's terminal emulator computed
(`STMClient::process_user_input` feeds `new_user_byte(byte,
local_framebuffer)` a cell grid, not a byte stream). That's what lets it
predict arrow-key cursor moves and mid-line inserts, and validate each
predicted cell individually against confirmed server output.

This app has no such cell grid for *any* protocol screen (SSH, IPMI-SOL,
mosh) — `TerminalScreen.kt`'s `parseAnsiChunk` is an SGR-color annotator
over appended text, not a cursor-addressable emulator. So
`MoshPredictionEngine` reconciles by string-prefix matching the pending
predicted suffix against confirmed server text instead of validating a
cell grid, and it doesn't attempt to predict anything that needs cursor
position (arrow keys, mid-line edits) — those correctly fall back to
"become tentative" (clear the prediction, wait for the real server echo),
exactly like upstream does for every case *it* can't safely predict
either.

**Integration points (MoshSessionClient.kt):**
- `sendText()` / `sendControlByte()` feed outgoing keystrokes to the engine
  before queuing them for the server (paste >100 bytes resets instead of
  predicting, matching upstream's bulk-data rule).
- `receiveLoop()` feeds confirmed `hoststring` text to
  `predictionEngine.onServerText()` for reconciliation before emitting to
  `terminalOutput`.
- `tickLoop()` feeds `transport.smoothedRttMs()` to
  `predictionEngine.onRttSampleMs()` each tick, driving the same
  SRTT/flagging hysteresis upstream uses.
- `predictionOverlay: StateFlow<MoshPredictionEngine.Overlay>` is exposed
  for the UI layer to render as an underlined suffix after the confirmed
  stream — kept fully separate from `terminalOutput` so a wrong prediction
  can never corrupt confirmed terminal history, only be visually replaced.

**Not yet done — UI wiring:** TerminalScreen.kt (821 lines, Compose) needs
a render pass that appends `predictionOverlay.value.pendingText` after the
confirmed text with `SpanStyle(textDecoration = Underline)` when
`.underlined` is true, and only when `.visible` is true, positioned at the
mosh session's current output tail. This wasn't done in this pass to avoid
blind edits to a large, unreviewed Compose file — the exact insertion point
is wherever the mosh terminal output composable currently collects
`terminalOutput`.

## Full framebuffer parity — not started, scoped for future work

To match upstream mosh exactly (predict arrow keys, mid-line inserts,
survive full-screen apps like vim/htop the same way upstream does).

Migration path if/when this is undertaken:
1. Port `src/terminal/terminalframebuffer.{h,cc}` (Cell/Row/Framebuffer) —
   the persistent 2-D grid.
2. Port `src/terminal/parser*` + `terminaldispatcher.*` — the VT100/ANSI
   state machine that turns a byte stream into Framebuffer mutations. This
   is the same engine the server side already effectively re-derives by
   parsing hoststring diffs, but the *client* needs its own copy to predict
   against.
3. Replace `TerminalScreen.kt`'s `parseAnsiChunk` rendering path with a
   renderer that reads from this Framebuffer instead of an appended string
   — this is the cross-cutting part, since it'd change rendering for every
   protocol (SSH/IPMI-SOL/serial/telnet/rlogin), not just mosh.
4. Only then port `PredictionEngine` itself 1:1 (already have the
   confirmed-correct reference: `terminaloverlay.h/.cc`), since it needs
   step 1-2's Framebuffer to predict/validate against.

Rough size: steps 1-2 are a few thousand lines of C++ to port; step 3 is a
rendering-architecture change affecting every terminal-protocol screen in
the app. This is a real multi-session project, not a follow-up patch.
