package com.systemsgo.hex.data.model

import java.util.UUID

/**
 * FEATURE-TERM-SNIPPETS: A user-saved terminal command ("snippet") that can be
 * re-run with a single tap from the SSH [com.systemsgo.hex.ui.screens.terminal.TerminalScreen]
 * instead of having to retype (or paste) the same command every session.
 *
 * Persisted as a JSON array (via Gson) inside [com.systemsgo.hex.data.repository.AppSettingsRepository],
 * the same encrypted-prefs store used for every other app setting — no new
 * Room table/migration needed for what is a small, app-wide list.
 *
 * @property id      Stable identifier so a single snippet can be deleted from the
 *                    list without relying on its (possibly duplicated) label/command.
 * @property label   Short human-readable name shown on the chip (e.g. "List files").
 * @property command The raw text sent to the SSH channel when the snippet runs
 *                    (a trailing newline is appended by the caller, not stored here,
 *                    so the same snippet text could in principle be reused for
 *                    "insert without executing" in the future).
 */
data class TerminalSnippet(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val command: String,
)
