package com.hermes.agent.data.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

class GemmaBpeTokenizerTest {

    // ▁=0x2581. Tiny vocab: bos/eos, pieces, and byte fallback for 'é'.
    private val json = """
        {"version":"1.0","added_tokens":[{"id":2,"content":"<bos>"}],
         "model":{"type":"BPE","vocab":{"<pad>":0,"<eos>":1,"<bos>":2,"<unk>":3,
           "h":4,"i":5,"▁":6,"t":7,"e":8,"hi":9,"▁t":10,"▁th":11,"<0xC3>":12,"<0xA9>":13,"th":14},
          "merges":[["h","i"],["▁","t"],["t","h"],["▁t","h"]]}}
    """.trimIndent()

    private val tok = GemmaBpeTokenizer.fromReader(StringReader(json), maxTokens = 16)

    @Test
    fun `wraps in bos and eos and applies merges by rank`() {
        assertArrayEquals(longArrayOf(2, 9, 1), tok.encode("hi").ids)
        // "hi th" -> hi | ▁t+h -> ▁th
        assertArrayEquals(longArrayOf(2, 9, 11, 1), tok.encode("hi th").ids)
    }

    @Test
    fun `falls back to utf8 byte tokens for characters outside the vocab`() {
        assertArrayEquals(longArrayOf(2, 12, 13, 1), tok.encode("é").ids)
    }

    @Test
    fun `truncates but keeps eos`() {
        val ids = GemmaBpeTokenizer.fromReader(StringReader(json), maxTokens = 4).encode("hi hi hi hi hi").ids
        assertEquals(4, ids.size)
        assertEquals(1L, ids.last())
    }

    @Test
    fun `matches the reference tokenizer on the real tokenizer json`() {
        val path = System.getenv("GEMMA_TOKENIZER_JSON")
        assumeTrue("set GEMMA_TOKENIZER_JSON to run", path != null && File(path).exists())
        val real = File(path!!).inputStream().use { GemmaBpeTokenizer.fromTokenizerJson(it) }
        assertArrayEquals(
            longArrayOf(2, 8071, 236787, 3927, 1354, 1109, 7609, 236787, 26638, 35149, 1),
            real.encode("task: search result | query: blocked ventilation").ids,
        )
        assertArrayEquals(
            longArrayOf(2, 3250, 236787, 7293, 1109, 1816, 236787, 669, 31573, 2978, 138, 5594, 496, 33443,
                236764, 33375, 238582, 532, 64334, 163543, 236761, 107, 4199, 1757, 236888, 1),
            real.encode("title: none | text: The boiler room  has a café, 日本語 and emoji 😀.\nNew line!").ids,
        )
        assertArrayEquals(longArrayOf(2, 23391, 1), real.encode("hello").ids)
    }
}
