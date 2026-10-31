package com.hermes.agent.data.memory

import java.io.InputStream
import java.io.Reader

/**
 * The Gemma family's SentencePiece-style BPE tokenizer, read straight from a
 * HuggingFace `tokenizer.json` (EmbeddingGemma 2 ships a 262k-entry vocab).
 *
 * Mirrors the file's pipeline: spaces become `▁`, text is BPE-merged, characters
 * missing from the vocab fall back to `<0xXX>` byte tokens, and the result is
 * wrapped as `<bos> … <eos>`.
 *
 * `tokenizer.json` is ~32 MB, so it is read with a small streaming parser rather
 * than a JSON tree, and the 515k merges are held as one sorted `LongArray`.
 */
class GemmaBpeTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val idToToken: Array<String?>,
    /** Sorted `(idA << 18 | idB) << 19 | rank`; see [rankOf]. */
    private val merges: LongArray,
    private val maxTokens: Int,
) {
    class Encoding(val ids: LongArray, val attentionMask: LongArray) {
        val size: Int get() = ids.size
    }

    fun encode(text: String): Encoding {
        val ids = ArrayList<Int>()
        ids.add(BOS)
        val budget = maxTokens - 2
        for (chunk in chunks(text.replace(' ', SPACE))) {
            if (ids.size - 1 >= budget) break
            bpe(chunk, ids)
        }
        // Truncate, then close with <eos>, as the model expects.
        while (ids.size > maxTokens - 1) ids.removeAt(ids.size - 1)
        ids.add(EOS)
        return Encoding(
            LongArray(ids.size) { ids[it].toLong() },
            LongArray(ids.size) { 1L },
        )
    }

    /** Split before every `▁` that follows a non-`▁`, which is where SentencePiece words start. */
    private fun chunks(s: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in 1 until s.length) {
            if (s[i] == SPACE && s[i - 1] != SPACE) {
                out.add(s.substring(start, i))
                start = i
            }
        }
        if (start < s.length) out.add(s.substring(start))
        return out
    }

    private fun bpe(chunk: String, out: MutableList<Int>) {
        // Initial symbols: one per code point, or its UTF-8 bytes when the vocab lacks it.
        val symbols = ArrayList<Int>()
        var i = 0
        while (i < chunk.length) {
            val cp = chunk.codePointAt(i)
            val piece = String(Character.toChars(cp))
            i += piece.length
            val id = vocab[piece]
            if (id != null) symbols.add(id)
            else for (b in piece.toByteArray(Charsets.UTF_8)) {
                symbols.add(vocab[byteToken(b)] ?: UNK)
            }
        }
        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestAt = -1
            for (k in 0 until symbols.size - 1) {
                val r = rankOf(symbols[k], symbols[k + 1])
                if (r < bestRank) { bestRank = r; bestAt = k }
            }
            if (bestAt < 0) break
            val merged = vocab[(idToToken[symbols[bestAt]] ?: break) + (idToToken[symbols[bestAt + 1]] ?: break)] ?: break
            symbols[bestAt] = merged
            symbols.removeAt(bestAt + 1)
        }
        out.addAll(symbols)
    }

    private fun rankOf(a: Int, b: Int): Int {
        val key = ((a.toLong() shl 18) or b.toLong()) shl 19
        var lo = 0
        var hi = merges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = merges[mid] and RANK_MASK.inv()
            when {
                k < key -> lo = mid + 1
                k > key -> hi = mid - 1
                else -> return (merges[mid] and RANK_MASK).toInt()
            }
        }
        return Int.MAX_VALUE
    }

    companion object {
        const val PAD = 0
        const val EOS = 1
        const val BOS = 2
        const val UNK = 3
        private const val SPACE = '▁'
        private const val RANK_MASK = (1L shl 19) - 1

        private fun byteToken(b: Byte) = "<0x%02X>".format(b.toInt() and 0xFF)

        fun fromTokenizerJson(input: InputStream, maxTokens: Int = 512): GemmaBpeTokenizer =
            input.bufferedReader(Charsets.UTF_8).use { fromReader(it, maxTokens) }

        fun fromReader(reader: Reader, maxTokens: Int = 512): GemmaBpeTokenizer {
            val p = JsonScan(reader)
            val vocab = HashMap<String, Int>(1 shl 19)
            var mergePairs: List<Pair<String, String>> = emptyList()
            p.eachMember { top ->
                if (top != "model") return@eachMember p.skipValue()
                p.eachMember { key ->
                    when (key) {
                        "vocab" -> p.eachMember { token -> vocab[token] = p.readInt() }
                        "merges" -> mergePairs = p.readMerges()
                        else -> p.skipValue()
                    }
                }
            }
            check(vocab.isNotEmpty() && mergePairs.isNotEmpty()) { "tokenizer.json has no BPE vocab/merges" }

            val idToToken = arrayOfNulls<String>(vocab.values.max() + 1)
            for ((t, id) in vocab) idToToken[id] = t
            val packed = LongArray(mergePairs.size)
            var n = 0
            for ((rank, pair) in mergePairs.withIndex()) {
                val a = vocab[pair.first] ?: continue
                val b = vocab[pair.second] ?: continue
                packed[n++] = (((a.toLong() shl 18) or b.toLong()) shl 19) or rank.toLong()
            }
            val sorted = packed.copyOf(n).also { it.sort() }
            return GemmaBpeTokenizer(vocab, idToToken, sorted, maxTokens)
        }
    }

    /** Just enough streaming JSON to walk tokenizer.json without building a tree. */
    private class JsonScan(private val r: Reader) {
        private var peeked = -2

        private fun peek(): Int { if (peeked == -2) peeked = r.read(); return peeked }
        private fun next(): Int { val c = peek(); peeked = -2; return c }
        private fun ws() { while (peek().let { it == 32 || it == 10 || it == 13 || it == 9 }) next() }
        private fun expect(c: Char) { ws(); check(next() == c.code) { "tokenizer.json: expected '$c'" } }

        fun eachMember(onKey: (String) -> Unit) {
            expect('{'); ws()
            if (peek() == '}'.code) { next(); return }
            while (true) {
                ws(); val k = readString(); expect(':'); ws()
                onKey(k); ws()
                if (next() == '}'.code) return
            }
        }

        fun readInt(): Int {
            ws(); var v = 0
            while (peek() in '0'.code..'9'.code) v = v * 10 + (next() - '0'.code)
            return v
        }

        fun readMerges(): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>(520_000)
            expect('['); ws()
            if (peek() == ']'.code) { next(); return out }
            while (true) {
                ws()
                if (peek() == '['.code) {
                    next(); ws(); val a = readString(); expect(','); ws(); val b = readString(); expect(']')
                    out.add(a to b)
                } else {
                    val s = readString(); val sp = s.indexOf(' ', 1)
                    out.add(s.substring(0, sp) to s.substring(sp + 1))
                }
                ws()
                if (next() == ']'.code) return out
            }
        }

        fun skipValue() {
            ws()
            when (peek()) {
                '"'.code -> readString()
                '{'.code -> eachMember { skipValue() }
                '['.code -> {
                    next(); ws()
                    if (peek() == ']'.code) { next(); return }
                    while (true) { skipValue(); ws(); if (next() == ']'.code) return }
                }
                else -> while (peek().let { it != -1 && it != ','.code && it != '}'.code && it != ']'.code }) next()
            }
        }

        fun readString(): String {
            check(next() == '"'.code) { "tokenizer.json: expected string" }
            val sb = StringBuilder()
            while (true) {
                val c = next()
                check(c != -1) { "tokenizer.json: unterminated string" }
                when (c) {
                    '"'.code -> return sb.toString()
                    '\\'.code -> when (val e = next()) {
                        'n'.code -> sb.append('\n'); 't'.code -> sb.append('\t'); 'r'.code -> sb.append('\r')
                        'b'.code -> sb.append('\b'); 'f'.code -> sb.append('\u000C')
                        'u'.code -> { var h = 0; repeat(4) { h = h * 16 + Character.digit(next(), 16) }; sb.append(h.toChar()) }
                        else -> sb.append(e.toChar())
                    }
                    else -> sb.append(c.toChar())
                }
            }
        }
    }
}
