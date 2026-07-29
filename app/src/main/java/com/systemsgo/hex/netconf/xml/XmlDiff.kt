package com.systemsgo.hex.netconf.xml

/**
 * XML-EDITOR FEATURE (Part 4/N — diff tool): a real LCS (Longest Common
 * Subsequence) line diff — the same algorithm class `diff`/git use for
 * line-oriented text — used both by the standalone Diff Tool (compare any
 * two XML documents pasted/loaded into the RPC Tools tab) and by
 * "Compare Running vs Candidate" (diffs two live `get-config` results).
 *
 * Deliberately line-based rather than a byte/character diff: for
 * pretty-printed XML (see `prettyPrintXml` in NetconfSessionScreen.kt) a
 * line very closely corresponds to one XML node, so a line diff reads like
 * a real config diff instead of noisy character-level churn.
 */
object XmlDiff {

    enum class LineChangeType { UNCHANGED, ADDED, REMOVED }

    data class DiffLine(val type: LineChangeType, val text: String, val leftLineNo: Int?, val rightLineNo: Int?)

    /** Safety cap — an O(n*m) LCS table on two multi-tens-of-thousands-of-lines documents would be both slow and memory-heavy on a phone; degrade to a coarse whole-document REMOVED/ADDED pair instead of hanging past this. */
    private const val MAX_LINES_FOR_LCS = 4000

    fun diff(left: String, right: String): List<DiffLine> {
        val a = left.split('\n')
        val b = right.split('\n')
        if (a.size > MAX_LINES_FOR_LCS || b.size > MAX_LINES_FOR_LCS) {
            return listOf(DiffLine(LineChangeType.REMOVED, "$left  [document too large for line-by-line diff — ${a.size} lines]", 1, null)) +
                listOf(DiffLine(LineChangeType.ADDED, "$right  [document too large for line-by-line diff — ${b.size} lines]", null, 1))
        }

        val n = a.size
        val m = b.size
        // Standard LCS length table.
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        var leftLine = 1
        var rightLine = 1
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    result.add(DiffLine(LineChangeType.UNCHANGED, a[i], leftLine, rightLine))
                    i++; j++; leftLine++; rightLine++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    result.add(DiffLine(LineChangeType.REMOVED, a[i], leftLine, null))
                    i++; leftLine++
                }
                else -> {
                    result.add(DiffLine(LineChangeType.ADDED, b[j], null, rightLine))
                    j++; rightLine++
                }
            }
        }
        while (i < n) { result.add(DiffLine(LineChangeType.REMOVED, a[i], leftLine, null)); i++; leftLine++ }
        while (j < m) { result.add(DiffLine(LineChangeType.ADDED, b[j], null, rightLine)); j++; rightLine++ }
        return result
    }

    data class DiffStats(val added: Int, val removed: Int, val unchanged: Int)

    fun stats(lines: List<DiffLine>): DiffStats = DiffStats(
        added = lines.count { it.type == LineChangeType.ADDED },
        removed = lines.count { it.type == LineChangeType.REMOVED },
        unchanged = lines.count { it.type == LineChangeType.UNCHANGED },
    )
}
