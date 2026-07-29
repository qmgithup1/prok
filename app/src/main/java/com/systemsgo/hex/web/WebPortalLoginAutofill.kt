package com.systemsgo.hex.web

import org.json.JSONObject

/**
 * WEB-PORTAL-SMART-AUTOFILL FEATURE: builds the JS snippet WebPortalScreen's
 * onPageFinished injects (via WebView.evaluateJavascript) to fill a
 * recognized portal's *in-page* HTML login form — the counterpart to
 * WebPortalActivity's existing onReceivedHttpAuthRequest handling, which
 * only covers the browser-level HTTP Basic/Digest challenge, never page
 * content. Gated by [com.systemsgo.hex.data.model.RdpProfile.webAutoFillLoginForm]
 * — see that field's doc comment for the trust-boundary reasoning.
 *
 * Deliberately conservative:
 *  - Never submits the form. Filling the fields and leaving the sign-in tap
 *    to the user avoids ever sending credentials to a page this code guessed
 *    wrong about, and matches how a browser's own saved-password autofill
 *    behaves (fills, doesn't submit).
 *  - Only fills once per page load (idempotent — re-running the script, e.g.
 *    if onPageFinished fires again for an in-page navigation, is a silent
 *    no-op the second time via the `data-systemsgo-autofilled` marker below),
 *    so it never fights the user for control of a field they've started
 *    editing themselves.
 *  - Uses each input's native value-setter (not `.value =` directly) before
 *    dispatching `input`/`change` events, because Guacamole's Angular form
 *    and Proxmox's ExtJS form both bind to those DOM events, not to the
 *    property write itself — a plain `.value =` would visually fill the box
 *    but leave the framework's own model thinking it's still empty, so the
 *    sign-in button (often disabled until the model considers the form
 *    non-empty) would never enable.
 *
 * Selector lists below are hand-verified against each product's default
 * login page markup (Guacamole 1.5.x, ESXi/vCenter 7-8 H5 client, iDRAC9/
 * iLO6, Proxmox VE 8.x) as of when this was written; a vendor UI refresh can
 * always drift these out of date, which is exactly why [GENERIC_FALLBACK]
 * exists below as a catch-all rather than only ever supporting an exact,
 * brittle list.
 */
object WebPortalLoginAutofill {

    /** One product's candidate selectors, tried top-to-bottom until one matches. */
    private class PortalSelectors(val usernameSelectors: List<String>, val passwordSelectors: List<String>)

    private val GUACAMOLE = PortalSelectors(
        usernameSelectors = listOf(
            "input[autocomplete=\"username\"]",
            "input.username-field",
            "input[name=\"username\"]",
        ),
        passwordSelectors = listOf(
            "input[autocomplete=\"current-password\"]",
            "input.password-field",
            "input[name=\"password\"]",
        ),
    )

    private val ESXI_VCENTER = PortalSelectors(
        usernameSelectors = listOf(
            "#username",
            "input[name=\"username\"]",
            "input[formcontrolname=\"username\"]",
        ),
        passwordSelectors = listOf(
            "#password",
            "input[name=\"password\"]",
            "input[formcontrolname=\"password\"]",
        ),
    )

    private val IDRAC_ILO = PortalSelectors(
        usernameSelectors = listOf(
            "#user", "#username", "#username_id",
            "input[name=\"user\"]", "input[name=\"username\"]",
        ),
        passwordSelectors = listOf(
            "#password", "#password_id",
            "input[name=\"password\"]",
        ),
    )

    private val PROXMOX = PortalSelectors(
        usernameSelectors = listOf(
            "input[name=\"username\"]",
            "#loginform input[type=\"text\"]",
        ),
        passwordSelectors = listOf(
            "input[name=\"password\"]",
            "#loginform input[type=\"password\"]",
        ),
    )

    /** Tried after every named product above draws a blank on a given page. */
    private const val GENERIC_FALLBACK = """
        (function () {
            var pass = document.querySelector('input[type="password"]:not([disabled])');
            if (!pass) return null;
            var form = pass.closest('form') || document;
            var candidates = form.querySelectorAll(
                'input[type="text"]:not([disabled]), input[type="email"]:not([disabled]), input:not([type]):not([disabled])'
            );
            var user = null;
            for (var i = 0; i < candidates.length; i++) {
                var el = candidates[i];
                var rect = el.getBoundingClientRect();
                if (rect.width > 0 && rect.height > 0) { user = el; break; }
            }
            return user ? [user, pass] : null;
        })()
    """

    private fun selectorPairScript(selectors: PortalSelectors): String {
        val userList = selectors.usernameSelectors.joinToString(", ") { "\"$it\"" }
        val passList = selectors.passwordSelectors.joinToString(", ") { "\"$it\"" }
        return """
            (function () {
                var userSel = [$userList];
                var passSel = [$passList];
                var user = null, pass = null;
                for (var i = 0; i < userSel.length && !user; i++) { user = document.querySelector(userSel[i]); }
                for (var i = 0; i < passSel.length && !pass; i++) { pass = document.querySelector(passSel[i]); }
                return (user && pass) ? [user, pass] : null;
            })()
        """
    }

    /**
     * Builds the full script to hand to WebView.evaluateJavascript(). Both
     * credentials are JSON-encoded (via org.json.JSONObject.quote) rather
     * than hand-escaped, so they're safe to splice into a JS string literal
     * regardless of quotes/backslashes/unicode the password might contain.
     */
    fun buildScript(username: String, password: String): String {
        if (username.isBlank()) return ""
        val userJs = JSONObject.quote(username)
        val passJs = JSONObject.quote(password)
        val finders = listOf(GUACAMOLE, ESXI_VCENTER, IDRAC_ILO, PROXMOX)
            .joinToString(",\n") { selectorPairScript(it) }

        return """
            (function () {
                if (document.documentElement.getAttribute('data-systemsgo-autofilled') === '1') return;

                function setValue(el, value) {
                    var proto = Object.getPrototypeOf(el);
                    var setter = Object.getOwnPropertyDescriptor(proto, 'value') &&
                                 Object.getOwnPropertyDescriptor(proto, 'value').set;
                    if (setter) { setter.call(el, value); } else { el.value = value; }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }

                var finders = [$finders, $GENERIC_FALLBACK];
                for (var i = 0; i < finders.length; i++) {
                    var pair = finders[i];
                    if (pair && pair[0] && pair[1]) {
                        // Never overwrite a field the user already started typing into.
                        if (pair[0].value || pair[1].value) return;
                        setValue(pair[0], $userJs);
                        setValue(pair[1], $passJs);
                        document.documentElement.setAttribute('data-systemsgo-autofilled', '1');
                        return;
                    }
                }
            })();
        """.trimIndent()
    }
}
