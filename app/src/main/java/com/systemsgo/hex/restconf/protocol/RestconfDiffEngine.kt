package com.systemsgo.hex.restconf.protocol

/**
 * RESTCONF FEATURE (Part 4/4): a dependency-free line-diff engine — same
 * "no Compose/Android classes, usable from a plain unit test" shape as
 * [RestconfFormatting] next to it. Feeds the Response Viewer's Diff tab
 * ([com.systemsgo.hex.restconf.ui.RestconfDiffViewer]), which line-diffs the
 * current response's (pretty-printed) body against either the previous
 * response in the session or a pinned baseline — useful for RESTCONF/YANG
 * work specifically because "did this config drift between two GETs" or
 * "what did my PATCH actually change" are both exactly this kind of
 * text-diff question.
 *
 * Classic O(n·m) LCS table + backtrack — the textbook Myers-adjacent
 * algorithm, not the real Myers diff (which is O((n+m)·D) and more code for
 * a difference that only matters on multi-thousand-line inputs). Response
 * bodies here are realistically tens to low-hundreds of lines once
 * pretty-printed, so the simpler table is the right tradeoff; [MAX_DIFF_LINES]
 * is a hard backstop so a pathological huge body can't allocate an
 * unreasonable `dp` table (~4·n·m bytes) or block the UI thread.
 */
object RestconfDiffEngine {

    private const val MAX_DIFF_LINES = 4000

    enum class Op { EQUAL, ADDED, REMOVED }

    data class Line(
        val op: Op,
        val text: String,
        val oldLineNumber: Int? = null,
        val newLineNumber: Int? = null,
    )

    data class Result(
        val lines: List<Line>,
        val addedCount: Int,
        val removedCount: Int,
        val truncated: Boolean,
    ) {
        val isIdentical: Boolean get() = !truncated && addedCount == 0 && removedCount == 0
    }

    fun diffLines(oldText: String, newText: String): Result {
        if (oldText == newText) {
            // Fast path: identical bodies (the common case when polling an
            // idle resource for drift) — no need to build a table at all.
            val lines = oldText.lines().mapIndexed { idx, l -> Line(Op.EQUAL, l, idx + 1, idx + 1) }
            return Result(lines, addedCount = 0, removedCount = 0, truncated = false)
        }

        val oldLines = oldText.lines()
        val newLines = newText.lines()
        if (oldLines.size > MAX_DIFF_LINES || newLines.size > MAX_DIFF_LINES) {
            return Result(emptyList(), addedCount = 0, removedCount = 0, truncated = true)
        }

        val n = oldLines.size
        val m = newLines.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val out = mutableListOf<Line>()
        var added = 0
        var removed = 0
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                oldLines[i] == newLines[j] -> {
                    out += Line(Op.EQUAL, oldLines[i], i + 1, j + 1)
                    i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out += Line(Op.REMOVED, oldLines[i], oldLineNumber = i + 1)
                    removed++; i++
                }
                else -> {
                    out += Line(Op.ADDED, newLines[j], newLineNumber = j + 1)
                    added++; j++
                }
            }
        }
        while (i < n) { out += Line(Op.REMOVED, oldLines[i], oldLineNumber = i + 1); removed++; i++ }
        while (j < m) { out += Line(Op.ADDED, newLines[j], newLineNumber = j + 1); added++; j++ }

        return Result(out, addedCount = added, removedCount = removed, truncated = false)
    }
}
