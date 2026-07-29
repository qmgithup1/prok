package com.systemsgo.hex.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.FunctionObject
import org.mozilla.javascript.ScriptableObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PAC-SUPPORT FEATURE (Part 1/n): fetches a Proxy Auto-Config (.pac) file
 * and runs its `FindProxyForURL(url, host)` function to decide which proxy
 * (if any) to use for a given destination, instead of the user entering a
 * proxy host/port by hand.
 *
 * This file is step 1 only: fetch + execute + parse the result. It does
 * NOT open any proxy/SOCKS/HTTP-CONNECT connection itself — that's for a
 * later step once the resulting [PacProxyDirective] list is available.
 *
 * Reference: the PAC format has no single formal standard, but is
 * documented consistently by Mozilla and (historically) Netscape at
 * https://developer.mozilla.org/en-US/docs/Web/HTTP/Proxy_servers_and_tunneling/Proxy_Auto-Configuration_PAC_file
 *
 * ── Why Rhino, and why interpreted mode ─────────────────────────────
 * See the `rhino` entry in gradle/libs.versions.toml for why Rhino (and
 * this specific version) was chosen over GraalJS / J2V8 / WebView.
 *
 * On top of that: Rhino has two execution modes. Optimization level >= 0
 * compiles the script to real JVM bytecode at runtime via its
 * `ClassFileWriter` (fast, but historically depends on desktop-JVM-only
 * surface like `java.beans.PropertyChangeListener` during class loading —
 * not present on Android/ART). Optimization level -1 ("interpreted mode")
 * walks the parsed AST directly and never touches that path. PAC scripts
 * are tiny and run once per navigation, so the interpreter's slower
 * per-call overhead is irrelevant — this sets `optimizationLevel = -1`
 * unconditionally, which is the standard, widely-documented fix for
 * embedding Rhino on Android.
 *
 * ── Standard PAC helper functions ────────────────────────────────────
 * Real-world PAC scripts call helper functions that aren't part of plain
 * JavaScript (isPlainHostName, dnsDomainIs, shExpMatch, isInNet, etc.) —
 * they're a fixed environment the script author expects to already exist
 * in scope. [PAC_HELPER_PRELUDE] below defines the ones that are pure
 * string/logic (no I/O) directly in JS, plus `dnsResolve`/`isResolvable`/
 * `myIpAddress`, which are thin JS wrappers around real native lookups —
 * see [PacNativeDns] and the `__pacNative*` functions registered into scope
 * in [findProxyForUrl] below. `isInNet` now resolves hostnames via
 * `dnsResolve` the same way a real PAC engine does, in addition to already
 * handling the literal dotted-quad case. The `*Ex` variants (which return
 * every resolved address instead of just one) are still out of scope — no
 * PAC engine's `dnsResolve` does that either, and callers that need the
 * full address list would use `dnsResolveEx`, which real-world PAC scripts
 * essentially never call.
 *
 * `weekdayRange`/`dateRange`/`timeRange` are implemented in JS in the
 * prelude, following the standard argument-count-driven overloads (see
 * their doc comments there) — including the optional trailing `"GMT"`
 * literal that switches them from local time to UTC.
 */

/** One entry from a parsed `FindProxyForURL` return value. */
sealed class PacProxyDirective {
    /** Connect to the destination directly, no proxy. */
    object Direct : PacProxyDirective()

    /** Route through an HTTP proxy at [host]:[port]. */
    data class Proxy(val host: String, val port: Int) : PacProxyDirective()

    /** Route through a SOCKS proxy at [host]:[port]. */
    data class Socks(val host: String, val port: Int) : PacProxyDirective()

    /**
     * An entry that isn't recognized (unknown keyword, or PROXY/SOCKS
     * missing a valid `host:port`). Kept instead of silently dropped so
     * callers/tests can see exactly what the script returned.
     */
    data class Unrecognized(val raw: String) : PacProxyDirective()
}

/** Result of downloading the .pac file itself. */
sealed class PacFetchResult {
    data class Success(val script: String) : PacFetchResult()
    data class HttpError(val code: Int) : PacFetchResult()
    data class NetworkError(val message: String) : PacFetchResult()
}

/** Result of running `FindProxyForURL` against a fetched script. */
sealed class PacEvaluationResult {
    data class Success(val directives: List<PacProxyDirective>) : PacEvaluationResult()
    data class Error(val message: String) : PacEvaluationResult()
}

@Singleton
class PacFileParser @Inject constructor() {

    private val connectTimeoutMs = 15_000
    private val readTimeoutMs = 20_000

    // Real PAC files are a few KB; this is just a sanity ceiling against a
    // misbehaving/malicious server so a fetch can't consume unbounded memory.
    private val maxPacFileBytes = 1_000_000

    /**
     * Downloads the .pac file's raw JavaScript source from [pacUrl].
     * Runs on [Dispatchers.IO]; does not execute or parse anything.
     */
    suspend fun fetchPacScript(pacUrl: String): PacFetchResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(pacUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
                // Historical/standard MIME types for PAC files (RFC-less, but
                // this is what browsers and other PAC clients send); servers
                // that don't care about Accept will ignore this anyway.
                setRequestProperty(
                    "Accept",
                    "application/x-ns-proxy-autoconfig, application/x-javascript-config, text/plain, */*"
                )
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext PacFetchResult.HttpError(code)
            }

            val bytes = connection.inputStream.use { it.readBoundedBytes(maxPacFileBytes) }
            PacFetchResult.Success(String(bytes, Charsets.UTF_8))
        } catch (e: IOException) {
            PacFetchResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Executes `FindProxyForURL(url, host)` from [pacScript] for the given
     * [targetUrl]/[targetHost] and parses the result into [PacProxyDirective]s.
     *
     * Pure CPU work (no network I/O in this step) but still dispatched to
     * [Dispatchers.IO] as a courtesy, since a pathological/hostile PAC
     * script could otherwise busy-loop the calling thread; see
     * [instructionObserverThreshold] usage below for the actual guard.
     */
    suspend fun findProxyForUrl(
        pacScript: String,
        targetUrl: String,
        targetHost: String
    ): PacEvaluationResult = withContext(Dispatchers.IO) {
        PacRhinoContextFactory.ensureInstalledAsGlobal()
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1 // interpreted mode — see class doc for why
            cx.instructionObserverThreshold = MAX_SCRIPT_INSTRUCTIONS
            // SECURITY FIX: sandbox this Context so the untrusted PAC script
            // cannot reach the JVM/Java layer. Must be set before any script
            // (helper prelude or the PAC file itself) is evaluated. See
            // [DenyAllClassShutter] doc comment for why this is required.
            // Rhino throws IllegalStateException if a shutter is already set
            // on this Context — that can only mean a prior call already
            // locked it down (Context objects are thread-local and can be
            // reused across enter()/exit() pairs on the same thread), so a
            // duplicate-set attempt is harmless and safe to swallow.
            try {
                cx.setClassShutter(DenyAllClassShutter)
            } catch (e: IllegalStateException) {
                // Already sandboxed on this (reused) Context — fine.
            }

            val scope: ScriptableObject = cx.initStandardObjects()

            // Expose real DNS/interface lookups as global JS functions so the
            // dnsResolve/myIpAddress wrappers in PAC_HELPER_PRELUDE have
            // something to call — see PacNativeDns doc comment for why these
            // block (with a timeout) on the calling thread, which is fine
            // here since this whole function already runs on Dispatchers.IO.
            val dnsResolveFn = FunctionObject(
                "__pacNativeDnsResolve",
                PacNativeDns::class.java.getMethod("dnsResolve", String::class.java),
                scope
            )
            ScriptableObject.putProperty(scope, "__pacNativeDnsResolve", dnsResolveFn)

            val myIpAddressFn = FunctionObject(
                "__pacNativeMyIpAddress",
                PacNativeDns::class.java.getMethod("myIpAddress"),
                scope
            )
            ScriptableObject.putProperty(scope, "__pacNativeMyIpAddress", myIpAddressFn)

            cx.evaluateString(scope, PAC_HELPER_PRELUDE, "pac-helpers", 1, null)
            cx.evaluateString(scope, pacScript, "pac-file", 1, null)

            val findProxyFn = scope.get("FindProxyForURL", scope)
            if (findProxyFn !is Function) {
                return@withContext PacEvaluationResult.Error(
                    "PAC script does not define a FindProxyForURL(url, host) function"
                )
            }

            val rawResult = findProxyFn.call(cx, scope, scope, arrayOf<Any>(targetUrl, targetHost))
            val resultString = Context.toString(rawResult)
            PacEvaluationResult.Success(parseProxyDirectives(resultString))
        } catch (e: Exception) {
            // Rhino surfaces JS syntax/runtime errors as RuntimeException
            // subclasses (EcmaError, EvaluatorException, JavaScriptException,
            // plus our own instruction-limit Error above). A malformed or
            // hostile PAC file must never crash the caller, so this is a
            // deliberately broad catch at this single boundary.
            PacEvaluationResult.Error(e.message ?: e.javaClass.simpleName)
        } finally {
            Context.exit()
        }
    }

    /**
     * Parses a raw `FindProxyForURL` return value, e.g.
     * `"PROXY proxy.example.com:8080; SOCKS socks.example.com:1080; DIRECT"`,
     * into an ordered list of [PacProxyDirective] (the standard says try
     * each entry in order until one connects).
     */
    fun parseProxyDirectives(result: String): List<PacProxyDirective> {
        return result.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { entry -> parseSingleDirective(entry) }
    }

    private fun parseSingleDirective(entry: String): PacProxyDirective {
        val parts = entry.split(Regex("\\s+"), limit = 2)
        val keyword = parts.getOrNull(0)?.uppercase() ?: return PacProxyDirective.Unrecognized(entry)

        if (keyword == "DIRECT") return PacProxyDirective.Direct

        if (keyword != "PROXY" && keyword != "SOCKS") {
            return PacProxyDirective.Unrecognized(entry)
        }

        val hostPort = parts.getOrNull(1)?.trim() ?: return PacProxyDirective.Unrecognized(entry)
        val colonIdx = hostPort.lastIndexOf(':')
        if (colonIdx <= 0 || colonIdx == hostPort.length - 1) return PacProxyDirective.Unrecognized(entry)

        val host = hostPort.substring(0, colonIdx)
        val port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: return PacProxyDirective.Unrecognized(entry)

        return if (keyword == "PROXY") {
            PacProxyDirective.Proxy(host, port)
        } else {
            PacProxyDirective.Socks(host, port)
        }
    }

    companion object {
        // Generous but bounded — a normal PAC script's FindProxyForURL call
        // is a handful of string comparisons; this only exists to stop an
        // infinite loop (accidental or hostile) from hanging the thread.
        private const val MAX_SCRIPT_INSTRUCTIONS = 50_000_000
    }
}

/**
 * SECURITY: denies script access to every Java class, full stop.
 *
 * Without this, a PAC script (arbitrary JS fetched from a network-supplied
 * `pacUrl` — and interceptable in transit by any network MITM if that URL
 * is `http://`) can reach `Packages.java.lang.Runtime`, `java.io.File`,
 * `java.lang.ProcessBuilder`, or reflectively into this app's own classes
 * via any host object's `getClass()` — i.e. arbitrary code execution inside
 * this app's process, not merely "which proxy to use for this URL".
 * `Context.initStandardObjects()` alone does NOT prevent this (only
 * `initSafeStandardObjects()` omits the top-level `Packages`/`java`/
 * `JavaAdapter`/`importClass` names, and even that doesn't stop
 * `getClass()`-based reflection). A [ClassShutter] is the actual, supported
 * Rhino mechanism: it is consulted on every attempt to resolve a Java class
 * name from script, so returning `false` unconditionally means no Java
 * class is ever visible to PAC script code.
 */
private object DenyAllClassShutter : ClassShutter {
    override fun visibleToScripts(fullClassName: String): Boolean = false
}

/**
 * Global Rhino [ContextFactory] override so [Context.instructionObserverThreshold]
 * is actually enforced — Rhino only calls `observeInstructionCount` when a
 * factory implements it, and `ContextFactory.initGlobal` may only be called
 * once per process, hence the guard.
 */
private object PacRhinoContextFactory : ContextFactory() {
    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Installs this factory as Rhino's process-wide global factory, once.
     * After this, plain `Context.enter()` calls pick it up automatically —
     * there's no need (and no legal way, since [ContextFactory.enterContext]
     * is `final`) to call through this instance directly.
     */
    fun ensureInstalledAsGlobal() {
        if (initialized.compareAndSet(false, true)) {
            try {
                initGlobal(this)
            } catch (e: IllegalStateException) {
                // Another factory already won the race (or a previous call
                // already installed this one) — either way, proceed; we only
                // need *some* global factory installed once per process.
            }
        }
    }

    // SECURITY FIX: [DenyAllClassShutter] below (installed per-Context in
    // [PacFileParser.findProxyForUrl]) is what actually closes off Java
    // reflection from PAC scripts. See that class's doc comment for why
    // `initStandardObjects()` alone is not enough.

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        // instructionObserverThreshold is read by Rhino's interpreter loop;
        // when it's > 0 this callback fires periodically with a running count.
        val threshold = cx.instructionObserverThreshold
        if (threshold > 0 && instructionCount > threshold) {
            throw Error("PAC script exceeded its execution step limit (possible infinite loop)")
        }
    }
}

/**
 * Real DNS/interface lookups backing the `dnsResolve`/`myIpAddress` PAC
 * helper wrappers — see the `__pacNative*` registrations in
 * [PacFileParser.findProxyForUrl]. Both methods are `@JvmStatic` (rather
 * than plain Kotlin object members) because Rhino's [FunctionObject] needs
 * a real `static` `java.lang.reflect.Method` to wrap — it has no notion of
 * a Kotlin singleton instance to dispatch through.
 */
private object PacNativeDns {
    // A PAC script's dnsResolve() is a blocking call by contract (the script
    // can't do anything until it gets an answer), and this whole evaluation
    // already runs on Dispatchers.IO — but java.net.InetAddress.getByName()
    // has no built-in timeout and can hang far longer than a PAC lookup
    // should on a dead/firewalled DNS server. Running it on a helper
    // executor and bounding the wait with Future.get(timeout) gives it a
    // hard ceiling; if the timeout fires, the underlying lookup thread is
    // simply abandoned (InetAddress gives no way to cancel it) rather than
    // blocking FindProxyForURL indefinitely — an acceptable, standard
    // tradeoff for a resolver used only to evaluate proxy rules.
    private const val DNS_TIMEOUT_MS = 5_000L

    private val executor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "pac-dns-resolve").apply { isDaemon = true }
    }

    /** Resolves [host] to a dotted-quad IPv4/IPv6 literal, or null on failure/timeout. */
    @JvmStatic
    fun dnsResolve(host: String): String? {
        return try {
            val future = executor.submit(java.util.concurrent.Callable {
                java.net.InetAddress.getByName(host).hostAddress
            })
            future.get(DNS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // Covers UnknownHostException (real "can't resolve"),
            // TimeoutException (see comment above), and any other lookup
            // failure — all mean the same thing to a PAC script: null.
            null
        }
    }

    /**
     * Returns this device's own IPv4 address on its active (non-loopback)
     * network interface — what a real PAC engine's myIpAddress() reports,
     * used by scripts to decide proxy behavior based on which network the
     * client itself is on (e.g. "direct when on the office LAN"). Falls
     * back to the loopback address if no such interface is found, matching
     * the documented PAC behavior for a client with no live network route.
     */
    @JvmStatic
    fun myIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}

/** Reads at most [maxBytes] from this stream; throws if the content is larger. */
private fun InputStream.readBoundedBytes(maxBytes: Int): ByteArray {
    val buffer = ByteArray(8192)
    val out = java.io.ByteArrayOutputStream()
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) {
            throw IOException("PAC file exceeds the $maxBytes byte limit")
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/**
 * Standard PAC helper functions, evaluated into scope before the fetched
 * script runs. Definitions follow the documented contracts (MDN's PAC
 * reference), written from scratch here — not copied from any single
 * source's reference implementation.
 *
 * `dnsResolve`/`isResolvable`/`myIpAddress` are thin wrappers around the
 * native `__pacNative*` bindings registered into scope by
 * [PacFileParser.findProxyForUrl] (see [PacNativeDns]), so `isInNet` also
 * works for hostname arguments now, not just literal dotted-quads.
 *
 * NOT included (require real network I/O, and essentially never appear in
 * real-world PAC scripts): `dnsResolveEx`, `isResolvableEx`, `myIpAddressEx`
 * — the `*Ex` variants return every resolved address instead of just one.
 */
private const val PAC_HELPER_PRELUDE = """
function isPlainHostName(host) {
    return host.indexOf('.') == -1 && host.indexOf(':') == -1;
}

function dnsDomainIs(host, domain) {
    if (host.length < domain.length) return false;
    return host.substring(host.length - domain.length) == domain;
}

function localHostOrDomainIs(host, fullHost) {
    if (host == fullHost) return true;
    var suffix = '.' + host;
    return fullHost.length >= suffix.length &&
        fullHost.substring(0, suffix.length) == suffix ||
        fullHost.indexOf(host + '.') == 0;
}

function dnsDomainLevels(host) {
    var count = 0;
    for (var i = 0; i < host.length; i++) {
        if (host.charAt(i) == '.') count++;
    }
    return count;
}

// Real DNS lookup, backed by PacNativeDns.dnsResolve() via the
// __pacNativeDnsResolve binding registered into scope before this prelude
// runs. Returns null on any failure/timeout, same as a real PAC engine.
function dnsResolve(host) {
    return __pacNativeDnsResolve(host);
}

// Real local-interface lookup, backed by PacNativeDns.myIpAddress().
function myIpAddress() {
    return __pacNativeMyIpAddress();
}

function isResolvable(host) {
    return dnsResolve(host) != null;
}

function isInNet(host, pattern, mask) {
    var ip = _isDottedQuad(host) ? host : dnsResolve(host);
    if (ip == null || !_isDottedQuad(ip)) return false;

    var ipParts = _quadToInt(ip);
    var patternParts = _quadToInt(pattern);
    var maskParts = _quadToInt(mask);
    if (ipParts == null || patternParts == null || maskParts == null) return false;

    for (var i = 0; i < 4; i++) {
        if ((ipParts[i] & maskParts[i]) != (patternParts[i] & maskParts[i])) return false;
    }
    return true;
}

function _isDottedQuad(s) {
    if (typeof s != 'string') return false;
    var parts = s.split('.');
    if (parts.length != 4) return false;
    for (var i = 0; i < 4; i++) {
        if (!/^[0-9]{1,3}${'$'}/.test(parts[i])) return false;
        var n = parseInt(parts[i], 10);
        if (n < 0 || n > 255) return false;
    }
    return true;
}

function _quadToInt(s) {
    if (!_isDottedQuad(s)) return null;
    var parts = s.split('.');
    var out = [];
    for (var i = 0; i < 4; i++) out.push(parseInt(parts[i], 10));
    return out;
}

function shExpMatch(str, shexp) {
    var pattern = '^' + shexp
        .replace(/[.+^${'$'}{}()|\[\]\\]/g, '\\${'$'}&')
        .replace(/\*/g, '.*')
        .replace(/\?/g, '.') + '${'$'}';
    return new RegExp(pattern).test(str);
}

var _PAC_WEEKDAYS = {SUN: 0, MON: 1, TUE: 2, WED: 3, THU: 4, FRI: 5, SAT: 6};
var _PAC_MONTHS = {
    JAN: 0, FEB: 1, MAR: 2, APR: 3, MAY: 4, JUN: 5,
    JUL: 6, AUG: 7, SEP: 8, OCT: 9, NOV: 10, DEC: 11
};

/** Current local-or-GMT date/time fields, in the shape weekdayRange/dateRange/timeRange need. */
function _pacNow(gmt) {
    var d = new Date();
    return gmt ? {
        year: d.getUTCFullYear(), month: d.getUTCMonth(), day: d.getUTCDate(),
        wday: d.getUTCDay(), hour: d.getUTCHours(), min: d.getUTCMinutes(), sec: d.getUTCSeconds()
    } : {
        year: d.getFullYear(), month: d.getMonth(), day: d.getDate(),
        wday: d.getDay(), hour: d.getHours(), min: d.getMinutes(), sec: d.getSeconds()
    };
}

/** Strips a trailing literal "GMT" argument, returning [argsWithoutGmt, wasGmt]. */
function _pacSplitGmt(args) {
    if (args.length > 0 && args[args.length - 1] === 'GMT') {
        return [Array.prototype.slice.call(args, 0, args.length - 1), true];
    }
    return [Array.prototype.slice.call(args), false];
}

// weekdayRange(wd1[, wd2][, "GMT"]) — wd is "SUN".."SAT". One argument
// tests today against a single day; two test an inclusive range that wraps
// across the week boundary if wd2 comes before wd1 (e.g. FRI..MON).
function weekdayRange() {
    var split = _pacSplitGmt(arguments);
    var args = split[0], gmt = split[1];
    var w1 = _PAC_WEEKDAYS[args[0]];
    if (w1 === undefined) return false;
    var now = _pacNow(gmt);
    if (args.length < 2) return now.wday == w1;
    var w2 = _PAC_WEEKDAYS[args[1]];
    if (w2 === undefined) return false;
    return w1 <= w2 ? (now.wday >= w1 && now.wday <= w2) : (now.wday >= w1 || now.wday <= w2);
}

// dateRange(...[, "GMT"]) — the most overloaded of the three PAC time
// functions; argument shape (count and whether entries are month-name
// strings, year-sized numbers, or plain day numbers) decides which of the
// standard forms applies: single day/month/year, a range of one of those,
// day+month (+day+month) pairs, month+year (+month+year) pairs, or a full
// day+month+year (+day+month+year) range.
function dateRange() {
    var split = _pacSplitGmt(arguments);
    var args = split[0], gmt = split[1];
    var now = _pacNow(gmt);

    function isMonthTok(v) { return typeof v == 'string' && _PAC_MONTHS[v] !== undefined; }
    function isYearNum(v) { return typeof v == 'number' && v >= 1000; }

    if (args.length == 1) {
        if (isMonthTok(args[0])) return now.month == _PAC_MONTHS[args[0]];
        if (isYearNum(args[0])) return now.year == args[0];
        return now.day == args[0];
    }
    if (args.length == 2) {
        if (isMonthTok(args[0]) && isMonthTok(args[1])) {
            var m1 = _PAC_MONTHS[args[0]], m2 = _PAC_MONTHS[args[1]];
            return m1 <= m2 ? (now.month >= m1 && now.month <= m2) : (now.month >= m1 || now.month <= m2);
        }
        if (isYearNum(args[0]) && isYearNum(args[1])) {
            return now.year >= args[0] && now.year <= args[1];
        }
        return now.day >= args[0] && now.day <= args[1];
    }
    if (args.length == 3) {
        // day, month, year — single exact date.
        return now.day == args[0] && now.month == _PAC_MONTHS[args[1]] && now.year == args[2];
    }
    if (args.length == 4) {
        if (isMonthTok(args[0]) && isYearNum(args[1]) && isMonthTok(args[2]) && isYearNum(args[3])) {
            // month1, year1, month2, year2
            var start = args[1] * 12 + _PAC_MONTHS[args[0]];
            var end = args[3] * 12 + _PAC_MONTHS[args[2]];
            var cur = now.year * 12 + now.month;
            return cur >= start && cur <= end;
        }
        // day1, month1, day2, month2 — range within/across the current year, no year given.
        var start2 = _PAC_MONTHS[args[1]] * 100 + args[0];
        var end2 = _PAC_MONTHS[args[3]] * 100 + args[2];
        var cur2 = now.month * 100 + now.day;
        return start2 <= end2 ? (cur2 >= start2 && cur2 <= end2) : (cur2 >= start2 || cur2 <= end2);
    }
    if (args.length == 6) {
        // day1, month1, year1, day2, month2, year2 — full exact-date range.
        var start3 = new Date(args[2], _PAC_MONTHS[args[1]], args[0]).getTime();
        var end3 = new Date(args[5], _PAC_MONTHS[args[4]], args[3]).getTime();
        var cur3 = new Date(now.year, now.month, now.day).getTime();
        return cur3 >= start3 && cur3 <= end3;
    }
    return false;
}

// timeRange(...[, "GMT"]) — hour-only, hour+minute, or hour+minute+second
// forms, each given either once (single instant test) or twice (an
// inclusive range that wraps past midnight if the end is before the start).
function timeRange() {
    var split = _pacSplitGmt(arguments);
    var args = split[0], gmt = split[1];
    var now = _pacNow(gmt);
    var curSec = now.hour * 3600 + now.min * 60 + now.sec;

    if (args.length == 1) return now.hour == args[0];
    if (args.length == 2) {
        var s1 = args[0] * 3600, s2 = args[1] * 3600;
        return s1 <= s2 ? (curSec >= s1 && curSec < s2) : (curSec >= s1 || curSec < s2);
    }
    if (args.length == 4) {
        var s3 = args[0] * 3600 + args[1] * 60, s4 = args[2] * 3600 + args[3] * 60;
        return s3 <= s4 ? (curSec >= s3 && curSec < s4) : (curSec >= s3 || curSec < s4);
    }
    if (args.length == 6) {
        var s5 = args[0] * 3600 + args[1] * 60 + args[2];
        var s6 = args[3] * 3600 + args[4] * 60 + args[5];
        return s5 <= s6 ? (curSec >= s5 && curSec <= s6) : (curSec >= s5 || curSec <= s6);
    }
    return false;
}

function alert(message) {
    // No-op: some PAC files call alert() for debugging; Rhino has no UI.
}
"""
