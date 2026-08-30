package com.hermes.agent.data.tools

import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Result of applying a patch to a file.
 */
data class PatchResult(
    val success: Boolean,
    val newContent: String,
    val hunksApplied: Int,
    val errorMessage: String? = null,
)

/**
 * Robust patch applier with fuzzy matching tolerance for whitespace/indentation drift.
 *
 * Ports upstream `tools/patch_parser.py` and `fuzzy_match.py`.
 */
object FuzzyPatcher {

    /**
     * Applies [patchText] to [originalContent].
     *
     * Supports Unified Diff, V4A diffs, and Search/Replace blocks.
     */
    fun applyPatch(originalContent: String, patchText: String): PatchResult {
        val trimmedPatch = patchText.trim()

        // 1. Check for Search/Replace block format: <<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE
        if (trimmedPatch.contains("<<<<<<< SEARCH") && trimmedPatch.contains("=======") && trimmedPatch.contains(">>>>>>>")) {
            return applySearchReplaceBlocks(originalContent, trimmedPatch)
        }

        // 2. Check for V4A format or Unified Diff format
        val hunks = parseHunks(trimmedPatch)
        if (hunks.isEmpty()) {
            // Fallback: try raw search/replace if patch has no @@ hunk headers
            return tryRawSearchReplace(originalContent, trimmedPatch)
        }

        var currentLines = originalContent.split("\n").toMutableList()
        var hunksApplied = 0

        for (hunk in hunks) {
            val matchIndex = findBestHunkMatch(currentLines, hunk)
            if (matchIndex == -1) {
                return PatchResult(
                    success = false,
                    newContent = originalContent,
                    hunksApplied = hunksApplied,
                    errorMessage = "Failed to match patch hunk against file content:\n${hunk.originalLines.joinToString("\n")}",
                )
            }

            // Remove matched lines and insert replacement lines
            for (i in 0 until hunk.originalLines.size) {
                if (matchIndex < currentLines.size) {
                    currentLines.removeAt(matchIndex)
                }
            }
            currentLines.addAll(matchIndex, hunk.replacementLines)
            hunksApplied++
        }

        return PatchResult(
            success = true,
            newContent = currentLines.joinToString("\n"),
            hunksApplied = hunksApplied,
        )
    }

    private data class ParsedHunk(
        val originalLines: List<String>,
        val replacementLines: List<String>,
        val hintLineIndex: Int? = null,
    )

    private fun parseHunks(patchText: String): List<ParsedHunk> {
        val hunks = mutableListOf<ParsedHunk>()
        val lines = patchText.split("\n")

        var currentOrig = mutableListOf<String>()
        var currentRepl = mutableListOf<String>()
        var inHunk = false
        var hintLineIndex: Int? = null

        fun flushHunk() {
            if (currentOrig.isNotEmpty() || currentRepl.isNotEmpty()) {
                hunks.add(ParsedHunk(currentOrig.toList(), currentRepl.toList(), hintLineIndex))
                currentOrig.clear()
                currentRepl.clear()
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("*** Begin Patch") || trimmed.startsWith("*** End Patch") ||
                trimmed.startsWith("*** Update File") || trimmed.startsWith("*** Add File") ||
                trimmed.startsWith("---") || trimmed.startsWith("+++")) {
                continue
            }

            if (line.startsWith("@@")) {
                flushHunk()
                inHunk = true
                // Extract line number hint if present: @@ -10,5 +10,6 @@
                val match = Regex("""@@ -(\d+)""").find(line)
                hintLineIndex = match?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
                continue
            }

            if (!inHunk && (line.startsWith("-") || line.startsWith("+") || line.startsWith(" "))) {
                inHunk = true
            }

            if (inHunk) {
                when {
                    line.startsWith("-") -> {
                        currentOrig.add(line.substring(1))
                    }
                    line.startsWith("+") -> {
                        currentRepl.add(line.substring(1))
                    }
                    line.startsWith(" ") -> {
                        val content = line.substring(1)
                        currentOrig.add(content)
                        currentRepl.add(content)
                    }
                    else -> {
                        // Unprefixed line treated as context if in hunk
                        currentOrig.add(line)
                        currentRepl.add(line)
                    }
                }
            }
        }

        flushHunk()
        return hunks
    }

    private fun findBestHunkMatch(fileLines: List<String>, hunk: ParsedHunk): Int {
        val orig = hunk.originalLines
        if (orig.isEmpty()) return 0
        val n = fileLines.size
        val m = orig.size

        if (m > n) return -1

        // 1. Try exact match near hint
        val startSearch = hunk.hintLineIndex?.let { max(0, min(it, n - m)) } ?: 0
        for (i in 0..n - m) {
            val checkIdx = if (hunk.hintLineIndex != null && i == 0) startSearch else i
            if (isExactMatch(fileLines, checkIdx, orig)) {
                return checkIdx
            }
        }

        // 2. Try trimmed / whitespace normalized match
        for (i in 0..n - m) {
            if (isTrimmedMatch(fileLines, i, orig)) {
                return i
            }
        }

        // 3. Try fuzzy similarity match (threshold > 0.8)
        var bestScore = 0.0
        var bestIdx = -1

        for (i in 0..n - m) {
            val score = calculateSimilarity(fileLines, i, orig)
            if (score > bestScore && score >= 0.8) {
                bestScore = score
                bestIdx = i
            }
        }

        return bestIdx
    }

    private fun isExactMatch(fileLines: List<String>, start: Int, orig: List<String>): Boolean {
        for (j in orig.indices) {
            if (fileLines[start + j] != orig[j]) return false
        }
        return true
    }

    private fun isTrimmedMatch(fileLines: List<String>, start: Int, orig: List<String>): Boolean {
        for (j in orig.indices) {
            if (fileLines[start + j].trim() != orig[j].trim()) return false
        }
        return true
    }

    private fun calculateSimilarity(fileLines: List<String>, start: Int, orig: List<String>): Double {
        var totalSim = 0.0
        for (j in orig.indices) {
            val lineA = fileLines[start + j].trim()
            val lineB = orig[j].trim()
            totalSim += lineSimilarity(lineA, lineB)
        }
        return totalSim / orig.size
    }

    private fun lineSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val maxLen = max(s1.length, s2.length)
        val dist = levenshtein(s1, s2)
        return 1.0 - (dist.toDouble() / maxLen)
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,
                    min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost),
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun applySearchReplaceBlocks(originalContent: String, patchText: String): PatchResult {
        var result = originalContent
        val regex = Regex("""<<<<<<< SEARCH\r?\n([\s\S]*?)\r?\n=======\r?\n([\s\S]*?)\r?\n>>>>>>>""")
        val matches = regex.findAll(patchText).toList()

        if (matches.isEmpty()) {
            return PatchResult(false, originalContent, 0, "No valid SEARCH/REPLACE blocks found")
        }

        var hunksApplied = 0
        for (match in matches) {
            val searchBlock = match.groupValues[1]
            val replaceBlock = match.groupValues[2]

            if (result.contains(searchBlock)) {
                result = result.replaceFirst(searchBlock, replaceBlock)
                hunksApplied++
            } else {
                // Try trimmed lines search
                val searchLines = searchBlock.split("\n")
                val origLines = result.split("\n")
                val matchIdx = findBestHunkMatch(origLines, ParsedHunk(searchLines, replaceBlock.split("\n")))
                if (matchIdx != -1) {
                    val mutable = origLines.toMutableList()
                    for (i in searchLines.indices) {
                        if (matchIdx < mutable.size) mutable.removeAt(matchIdx)
                    }
                    mutable.addAll(matchIdx, replaceBlock.split("\n"))
                    result = mutable.joinToString("\n")
                    hunksApplied++
                } else {
                    return PatchResult(
                        false,
                        originalContent,
                        hunksApplied,
                        "Could not locate search block in file:\n$searchBlock",
                    )
                }
            }
        }

        return PatchResult(true, result, hunksApplied)
    }

    private fun tryRawSearchReplace(originalContent: String, patchText: String): PatchResult {
        // Check if patchText is just replacement text
        return PatchResult(false, originalContent, 0, "Unable to parse patch hunks or search/replace blocks")
    }
}
