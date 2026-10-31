# a11y-bridge

Reads the Android screen as JSON, from `adb`, in tens of milliseconds.

An `AccessibilityService` that answers one question — *what is on screen right now* — and nothing
else. No UI, no launcher icon, no network calls: a read is one `getWindows()` walk of the node
tree, serialized, handed back over loopback or written to a file.

```
uiautomator dump        1.9 – 8.5 s    a fresh UiAutomation connection, waitForIdle, a new process
a11y-bridge (broadcast) 0.3 – 0.5 s    the read, plus `am` starting a process
a11y-bridge (socket)    0.04 – 0.08 s  the read, plus the adb round trip
```

Measured on a vivo V2055A, Android 11: `capture_ms` 5–30 for a launcher, settings page or chat
screen (108 nodes).

## Why it exists

`uiautomator dump` builds a `UiAutomation` connection per invocation, waits for the UI to go idle,
and throws the connection away. An accessibility service keeps that connection for the life of the
device, so the read itself is the only cost. The alternatives all fail for the same reason: a
shell-uid process cannot hold an accessibility connection, and the hidden-API list blocks every
attempt to forge one. A service, enabled once by the person holding the phone, is the only way in.

## Build

CI builds it (`.github/workflows/build-apk.yml`): unit tests, then `assembleRelease`, APK on the
run page. Locally, with a JDK 17 and an Android SDK:

```bash
gradle test            # the rules, the JSON shape, the protocol, the socket — all on the JVM
gradle assembleRelease # app/build/outputs/apk/release/app-release.apk
```

The APK is debug-signed so it can be sideloaded; it is not for a store.

## Install

```bash
adb install -r app-release.apk
# Then, once, on the phone: 设置 → 无障碍 → A11y Bridge 读屏 → 打开
```

The service is the only component that matters. Everything below is `adb shell`.

## Read

```bash
# Fast path: one forward, then a read is the read alone.
./tooling/a11y-dump.sh --socket

# Same thing by hand (the token comes from a DUMP-protected broadcast):
adb forward tcp:47913 tcp:47913
adb shell am broadcast -a io.github.zh_tx3.a11ybridge.PROBE --es cmd listen --es token mytoken1234
printf 'mytoken1234 dump 400 - 1\n' | nc 127.0.0.1 47913

# Fallback: nothing to set up, ~0.4 s.
./tooling/a11y-dump.sh

# A still screen, answered from the last read (~1 ms): no event has arrived since.
./tooling/a11y-dump.sh --socket --reuse

./tooling/a11y-dump.sh --stop        # close the listener
```

## The two transports

**Socket** — `ServerSocket` on `127.0.0.1` *and* `::1`, port 47913, one line per request, one
framed reply per line:

```
<token> dump [budgetMs] [reuse|-] [seq]   →  OK <bytes>\n<body>
<token> ping                              →  OK <bytes>\n<body>
```

The length in the frame is what makes the reply safe to read with `nc` (which otherwise stops at
4 KB). One connection serves as many reads as it likes; a poll loop should keep it open. A wrong
token gets no reply and the connection is dropped.

The listener closes itself after ten idle minutes — a port nobody is using should not stay open.
The next `--socket` call notices, re-broadcasts `listen` with a fresh token and reads: that first
read costs ~0.4 s instead of ~0.05 s, and the ones after it are fast again.

**Broadcast** — `io.github.zh_tx3.a11ybridge.PROBE`, registered with
`android.permission.DUMP`, so only adb shell or root can send it:

```bash
adb shell am broadcast -a io.github.zh_tx3.a11ybridge.PROBE --es cmd dump --ei seq 1 --ei budget 400
adb shell cat /sdcard/Android/data/io.github.zh_tx3.a11ybridge/files/a11y-bridge/tree.json
```

`cmd` is `dump` (also writes `tree.json`), `listen <token> [port]`, `unlisten`, `ping`. The action
of the older dsh-Go `.a11y` fork (`io.github.ccch1mneyyy.dsh_app.A11Y_PROBE`) is answered too, so
clients written against that keep working.

## The JSON

`schema: a11y-bridge/1`. A node emits only what it has; every field but `i`, `b` and `children` is
optional.

```json
{ "schema": "a11y-bridge/1", "seq": 7, "at": 1761000000000, "capture_ms": 38,
  "screen": [0,0,1080,2400], "pkg": "com.android.settings",
  "keyboard": false, "own_in_front": false, "dsh_in_front": false,
  "system_in_front": false, "truncated": false,
  "windows": [ { "id": 12, "kind": "app", "pkg": "com.android.settings", "title": "设置",
                 "bounds": [0,0,1080,2400],
                 "root": { "i": 0, "cls": "android.widget.FrameLayout", "b": [0,0,1080,2400],
                           "children": [
                             { "i": 17, "d": 3, "cls": "android.widget.TextView", "text": "WLAN",
                               "rid": "android:id/title", "b": [100,780,400,840], "children": [] },
                             { "i": 22, "d": 3, "cls": "android.widget.Switch", "text": "WLAN",
                               "state": "已开启", "b": [900,780,1040,840],
                               "flags": ["clickable","checkable","checked"], "children": [] } ] } } ],
  "nodes": 412 }
```

- `i` is the depth-first index of the node in this capture.
- `flags`: clickable, long_clickable, editable, scrollable, checkable, checked, selected, focused,
  disabled, password, heading, hint_shown.
- `scroll`: which ways the node can scroll *now*, from its action list.
- `role` / `state` / `range`: chromeRole or Compose role description, API 30 `stateDescription`,
  and slider/progress range.
- `truncated`: the node cap (2000), depth cap (80), window cap (4) or the time budget was hit. The
  tree may be incomplete; raise `budgetMs` and read again.
- `hidden_window`: a full-screen window the system refused to hand over (its title, when known).
- `dsh_in_front` repeats `own_in_front` for clients written against the dsh-Go fork this started in.

**Flutter apps put their text in `desc`, not `text`.** A Flutter screen reads as an empty `text`
with a full `desc`; both must be read.

## What is deliberately not here

- **No wildcard bind.** A screen reader answering on `0.0.0.0` is a screen reader on the LAN.
- **No listener open by default, and none left open.** It exists only after `listen`, and shuts
  itself down when nothing has asked for a read in ten minutes.
- **No pre-read cache by default.** `reuse` is opt-in: an unchanged event count is not proof that
  the pixels are unchanged.
- **No `isAccessibilityTool=true`.** That flag is a claim about blind users, and this is not that.
- **No node actions yet** (`dispatchGesture` tap/type would be the next step; `canPerformGestures`
  is already declared).

## Tests

`gradle test` — everything that can be decided without a device:

- `TreeTest` — which windows count, what a full-screen window hides, unreadable windows, the
  keyboard flag, the node and depth caps, top-first ordering.
- `TreeJsonTest` — the wire format, field omission, escaping, node indices, the fork's alias.
- `ProtocolTest` — token check, defaults, framing, bodies containing newlines.
- `BridgeTest` — real loopback sockets: framing, many reads on one connection, wrong token, a
  handler failure answered in the frame, `stop()` releasing the port.
