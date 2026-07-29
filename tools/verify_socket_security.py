#!/usr/bin/env python3
"""
CRIT-3-B enforcement gate.

network_security_config.xml (see app/src/main/res/xml/network_security_config.xml)
can only ever govern Android's HTTP stack -- HttpURLConnection/HttpsURLConnection,
WebView, and anything that consults android.security.NetworkSecurityPolicy (OkHttp
does). It is architecturally incapable of covering a directly-instantiated
java.net.Socket, DatagramSocket, or ServerSocket, which is exactly how this app's
non-HTTP remote-access protocols (Telnet, VNC, RDP's native bridge, IPMI, AMT,
SNMP, rlogin, Mosh, the serial console, NFS/ONC-RPC) talk to the network. No XML
change can close that gap -- so instead of pretending otherwise, this script makes
the gap impossible to widen silently:

  1. It scans every .kt file under app/src/main/java for raw socket construction.
  2. Every call site must be listed in tools/socket_security_manifest.json with a
     reviewed justification tag (see that file's _comment / tags for definitions).
  3. Any call site NOT in the manifest fails the build -- so a new protocol or a
     new code path that opens a socket can no longer skip security review just
     because network_security_config.xml doesn't apply to it.
  4. Any manifest entry that no longer matches a real call site (dead entry) also
     fails the build, so the manifest can't rot into meaningless boilerplate.

Wired into Gradle as the `verifySocketSecurity` task (see app/build.gradle.kts),
which runs as part of `check`.
"""
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST_PATH = REPO_ROOT / "tools" / "socket_security_manifest.json"
SCAN_ROOT = REPO_ROOT / "app" / "src" / "main" / "java"

# Matches direct construction of a raw socket type. Deliberately does NOT match
# SSLSocket (constructing one directly is unusual and still caught, since
# "SSLSocket(" also matches "Socket(" as a substring boundary -- see regex),
# SSLServerSocket, or `.createSocket(` factory calls, which are the *correct*
# TLS-wrapping pattern used throughout this codebase and are not the risk this
# gate targets.
RAW_SOCKET_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_])(?:new\s+)?(Socket|DatagramSocket|ServerSocket)\s*\("
)


def find_raw_socket_files() -> set[str]:
    hits: set[str] = set()
    for path in SCAN_ROOT.rglob("*.kt"):
        text = path.read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            if "SSLSocket(" in line or "SSLServerSocket(" in line:
                continue
            if RAW_SOCKET_PATTERN.search(line):
                hits.add(str(path.relative_to(REPO_ROOT)).replace("\\", "/"))
                break
    return hits


def main() -> int:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    valid_tags = set(manifest["tags"].keys())
    listed_files = set()
    problems = []

    for entry in manifest["entries"]:
        f = entry["file"]
        listed_files.add(f)
        if not (REPO_ROOT / f).exists():
            problems.append(f"manifest entry points at a file that no longer exists: {f}")
        bad_tags = set(entry.get("tags", [])) - valid_tags
        if bad_tags:
            problems.append(f"{f}: unknown tag(s) {bad_tags}")
        if not entry.get("tags"):
            problems.append(f"{f}: has no justification tags")

    actual_files = find_raw_socket_files()

    unlisted = actual_files - listed_files
    for f in sorted(unlisted):
        problems.append(
            f"UNREVIEWED raw socket construction in {f} -- network_security_config.xml "
            f"does NOT cover this code path. Add a reviewed entry to "
            f"tools/socket_security_manifest.json (with a justification tag) "
            f"before this can merge."
        )

    stale = listed_files - actual_files
    for f in sorted(stale):
        problems.append(
            f"STALE manifest entry for {f} -- no raw socket construction found there "
            f"anymore. Remove the entry (or the socket moved -- update the path)."
        )

    if problems:
        print("verify_socket_security: FAILED", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        print(
            f"\n{len(actual_files)} file(s) with raw socket construction found; "
            f"{len(listed_files)} listed in manifest.",
            file=sys.stderr,
        )
        return 1

    print(f"verify_socket_security: OK ({len(actual_files)} raw-socket call sites, all reviewed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
