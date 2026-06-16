package com.pavlova.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.absoluteValue

/**
 * Pluggable tokenizer abstraction used by [NlpModelRunner] and
 * [EmbeddingEngine].
 *
 * Three concrete implementations:
 *   - [BpeTokenizer]       byte-level BPE for RoBERTa / GPT-2 style models
 *   - [WordPieceTokenizer] WordPiece for BERT-style models (e.g. MiniLM SBERT)
 *   - [HashTokenizer]      deterministic hash → safe id range; fallback when
 *                          no vocab assets ship alongside a model
 *
 * The companion factory [Tokenizer.load] picks the right implementation by
 * looking for a `<model_stem>_tokenizer.json` config in the assets folder.
 */
interface Tokenizer {
    /**
     * Encode [text] into a fixed-length token id array of length [maxLen].
     * The returned array is padded (with [padId]) and includes any special
     * tokens (CLS/SEP) expected by the model.
     */
    fun encode(text: String, maxLen: Int): IntArray

    /** Padding token id, used by callers that need to know it explicitly. */
    val padId: Int

    /** Model vocab size. Used to clamp fallback ids and for sanity checks. */
    val vocabSize: Int

    companion object {
        private const val TAG = "Tokenizer"

        /**
         * Look up tokenizer assets for [modelStem] (e.g. "roberta_sentiment")
         * inside `assets/`. Returns a real BPE/WordPiece tokenizer if the
         * config + vocab files are present; otherwise a [HashTokenizer] with
         * [fallbackVocabSize].
         */
        fun load(context: Context, modelStem: String, fallbackVocabSize: Int): Tokenizer {
            val configName = "${modelStem}_tokenizer.json"
            val configJson = runCatching {
                context.assets.open(configName).bufferedReader().use { it.readText() }
            }.getOrNull()
            if (configJson == null) {
                Log.d(TAG, "No tokenizer config for $modelStem — using hash fallback")
                return HashTokenizer(fallbackVocabSize)
            }
            return try {
                val cfg = JSONObject(configJson)
                val family = cfg.optString("family", "unknown")
                val padId = cfg.optInt("pad_token_id", 0)
                val unkId = cfg.optInt("unk_token_id", padId)
                val clsId = cfg.optInt("cls_token_id", -1)
                val sepId = cfg.optInt("sep_token_id", -1)
                val bosId = cfg.optInt("bos_token_id", clsId)
                val eosId = cfg.optInt("eos_token_id", sepId)
                val vocabSize = cfg.optInt("vocab_size", fallbackVocabSize)
                val doLowerCase = cfg.optBoolean("do_lower_case", false)
                when (family) {
                    "bpe" -> BpeTokenizer.fromAssets(
                        context, modelStem,
                        padId = padId,
                        unkId = unkId,
                        bosId = bosId.takeIf { it >= 0 } ?: clsId,
                        eosId = eosId.takeIf { it >= 0 } ?: sepId,
                        vocabSize = vocabSize
                    )
                    "wordpiece" -> WordPieceTokenizer.fromAssets(
                        context, modelStem,
                        padId = padId,
                        unkId = unkId,
                        clsId = clsId,
                        sepId = sepId,
                        vocabSize = vocabSize,
                        doLowerCase = doLowerCase
                    )
                    else -> {
                        Log.w(TAG, "Unknown tokenizer family '$family' for $modelStem")
                        HashTokenizer(fallbackVocabSize)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tokenizer asset load failed for $modelStem; falling back to hash", e)
                HashTokenizer(fallbackVocabSize)
            }
        }
    }
}

// ─── Fallback: hash tokenizer (always available) ────────────────────────

/**
 * Deterministic hash tokenizer used when no real vocab is shipped. Token ids
 * are clamped into `[0, vocabSize)` so the placeholder Keras TFLite models
 * (which have `vocab_size = 32_000`) don't crash with out-of-range GATHER.
 */
class HashTokenizer(override val vocabSize: Int) : Tokenizer {
    override val padId: Int = 0
    private val clsId = 1.coerceAtMost(vocabSize - 1)
    private val sepId = 2.coerceAtMost(vocabSize - 1)

    override fun encode(text: String, maxLen: Int): IntArray {
        val out = IntArray(maxLen) { padId }
        if (maxLen == 0) return out
        out[0] = clsId
        var pos = 1
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }
        for (w in words) {
            if (pos >= maxLen - 1) break
            val id = (w.hashCode().absoluteValue % vocabSize).coerceAtLeast(3)
            out[pos++] = id
        }
        if (pos < maxLen) out[pos] = sepId
        return out
    }
}

// ─── WordPiece (BERT / SBERT MiniLM) ────────────────────────────────────

/**
 * Greedy longest-match WordPiece tokenizer. Implements the BERT
 * pre-processing (lowercase + strip accents + punctuation split) so the
 * ids match what HuggingFace's `BertTokenizer` would emit.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    override val padId: Int,
    private val unkId: Int,
    private val clsId: Int,
    private val sepId: Int,
    override val vocabSize: Int,
    private val doLowerCase: Boolean,
    private val maxInputCharsPerWord: Int = 100
) : Tokenizer {

    override fun encode(text: String, maxLen: Int): IntArray {
        val out = IntArray(maxLen) { padId }
        if (maxLen == 0) return out
        out[0] = clsId
        var pos = 1
        for (word in basicTokenize(text)) {
            if (pos >= maxLen - 1) break
            val pieces = wordpiece(word)
            for (piece in pieces) {
                if (pos >= maxLen - 1) break
                out[pos++] = vocab[piece] ?: unkId
            }
        }
        if (pos < maxLen) out[pos] = sepId
        return out
    }

    private fun basicTokenize(text: String): List<String> {
        val cleaned = StringBuilder()
        for (cp in text.codePoints()) {
            // Drop control chars and replacement chars; whitespace → space.
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue
            if (isWhitespace(cp)) { cleaned.append(' '); continue }
            cleaned.appendCodePoint(cp)
        }
        val lowered = if (doLowerCase) stripAccents(cleaned.toString()).lowercase() else cleaned.toString()
        val tokens = mutableListOf<String>()
        for (raw in lowered.split(Regex("\\s+"))) {
            if (raw.isEmpty()) continue
            // Split off punctuation as separate tokens (BERT-style).
            val current = StringBuilder()
            for (ch in raw) {
                if (isPunctuation(ch.code)) {
                    if (current.isNotEmpty()) { tokens.add(current.toString()); current.clear() }
                    tokens.add(ch.toString())
                } else {
                    current.append(ch)
                }
            }
            if (current.isNotEmpty()) tokens.add(current.toString())
        }
        return tokens
    }

    private fun wordpiece(token: String): List<String> {
        if (token.length > maxInputCharsPerWord) return listOf("[UNK]")
        val subTokens = mutableListOf<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var current: String? = null
            while (start < end) {
                val piece = if (start == 0) token.substring(start, end) else "##" + token.substring(start, end)
                if (vocab.containsKey(piece)) { current = piece; break }
                end--
            }
            if (current == null) return listOf("[UNK]")
            subTokens.add(current)
            start = end
        }
        return subTokens
    }

    private fun stripAccents(s: String): String {
        val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (ch in nfd) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch)
        }
        return sb.toString()
    }

    private fun isWhitespace(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
            Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        val type = Character.getType(cp)
        return type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt()
    }

    private fun isPunctuation(cp: Int): Boolean {
        // BERT treats ASCII punctuation as splits, plus Unicode P* categories.
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        val type = Character.getType(cp)
        return type in intArrayOf(
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt()
        )
    }

    companion object {
        fun fromAssets(
            context: Context,
            modelStem: String,
            padId: Int,
            unkId: Int,
            clsId: Int,
            sepId: Int,
            vocabSize: Int,
            doLowerCase: Boolean
        ): Tokenizer {
            val vocab = HashMap<String, Int>(vocabSize)
            context.assets.open("${modelStem}_vocab.txt").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                    var i = 0
                    for (line in lines) {
                        vocab[line] = i
                        i++
                    }
                }
            }
            return WordPieceTokenizer(
                vocab = vocab,
                padId = padId,
                unkId = unkId,
                clsId = clsId,
                sepId = sepId,
                vocabSize = vocabSize,
                doLowerCase = doLowerCase
            )
        }
    }
}

// ─── Byte-level BPE (RoBERTa / GPT-2) ───────────────────────────────────

/**
 * Byte-level BPE tokenizer matching HuggingFace's `RobertaTokenizer`.
 *
 *  1. Pre-tokenise with the GPT-2 regex (keeps leading spaces on words).
 *  2. Map each byte of the UTF-8 encoded token to a printable unicode char
 *     using GPT-2's byte-to-unicode table (so merges work on a printable
 *     alphabet).
 *  3. Greedily apply BPE merges from `<stem>_merges.txt`.
 *  4. Look up the resulting tokens in `<stem>_vocab.json`.
 */
class BpeTokenizer(
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
    override val padId: Int,
    private val unkId: Int,
    private val bosId: Int,
    private val eosId: Int,
    override val vocabSize: Int
) : Tokenizer {

    private val byteEncoder: IntArray = bytesToUnicode()
    private val cache = HashMap<String, List<String>>()
    // Equivalent to: 's|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+
    private val splitPattern = Regex(
        "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"
    )

    override fun encode(text: String, maxLen: Int): IntArray {
        val out = IntArray(maxLen) { padId }
        if (maxLen == 0) return out
        var pos = 0
        if (bosId >= 0) { out[pos++] = bosId }
        for (match in splitPattern.findAll(text)) {
            val rawBytes = match.value.toByteArray(Charsets.UTF_8)
            val mapped = buildString(rawBytes.size) {
                for (b in rawBytes) appendCodePoint(byteEncoder[b.toInt() and 0xFF])
            }
            for (piece in bpe(mapped)) {
                if (pos >= maxLen - if (eosId >= 0) 1 else 0) break
                out[pos++] = encoder[piece] ?: unkId
            }
            if (pos >= maxLen - if (eosId >= 0) 1 else 0) break
        }
        if (eosId >= 0 && pos < maxLen) out[pos] = eosId
        return out
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        var word = token.map { it.toString() }.toMutableList()
        if (word.size < 2) { cache[token] = word; return word }
        while (true) {
            val pairs = getPairs(word)
            if (pairs.isEmpty()) break
            val bigram = pairs.minByOrNull { bpeRanks[it] ?: Int.MAX_VALUE } ?: break
            if (bigram !in bpeRanks) break
            val (first, second) = bigram
            val newWord = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                val j = (i until word.size).firstOrNull { word[it] == first } ?: -1
                if (j == -1) {
                    newWord.addAll(word.subList(i, word.size)); break
                }
                newWord.addAll(word.subList(i, j))
                if (j < word.size - 1 && word[j + 1] == second) {
                    newWord.add(first + second); i = j + 2
                } else {
                    newWord.add(word[j]); i = j + 1
                }
            }
            word = newWord
            if (word.size == 1) break
        }
        cache[token] = word
        return word
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        val pairs = HashSet<Pair<String, String>>(word.size)
        for (i in 0 until word.size - 1) pairs.add(word[i] to word[i + 1])
        return pairs
    }

    companion object {
        /** GPT-2 byte → printable-unicode mapping. */
        private fun bytesToUnicode(): IntArray {
            val baseRanges = listOf(
                '!'.code..'~'.code,
                '¡'.code..'¬'.code,
                '®'.code..'ÿ'.code
            )
            val bs = mutableListOf<Int>()
            for (r in baseRanges) for (b in r) bs.add(b)
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val map = IntArray(256)
            for (i in bs.indices) map[bs[i]] = cs[i]
            return map
        }

        fun fromAssets(
            context: Context,
            modelStem: String,
            padId: Int,
            unkId: Int,
            bosId: Int,
            eosId: Int,
            vocabSize: Int
        ): Tokenizer {
            // Vocab: {"token": id, ...}
            val vocabJson = context.assets.open("${modelStem}_vocab.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val encoder = HashMap<String, Int>(vocabSize)
            val obj = JSONObject(vocabJson)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                encoder[k] = obj.getInt(k)
            }
            // Merges: lines of "a b"; the first non-comment line is rank 0.
            val ranks = HashMap<Pair<String, String>, Int>()
            context.assets.open("${modelStem}_merges.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                var rank = 0
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val parts = line.split(' ')
                    if (parts.size != 2) continue
                    ranks[parts[0] to parts[1]] = rank++
                }
            }
            return BpeTokenizer(
                encoder = encoder,
                bpeRanks = ranks,
                padId = padId,
                unkId = unkId,
                bosId = bosId,
                eosId = eosId,
                vocabSize = vocabSize
            )
        }
    }
}
