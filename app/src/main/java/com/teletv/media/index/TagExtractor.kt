package com.teletv.media.index

import java.util.Calendar
import java.util.TimeZone

/** One tag produced by extraction: identity [value] plus UI [display] form. */
data class ExtractedTag(
    val category: TagCategory,
    val value: String,
    val display: String,
)

/**
 * Pure rule-based tag extraction over caption/file-name text. No I/O — unit
 * testable. Frequency thresholds for TERM tags are NOT applied here; every
 * candidate is emitted and stored, thresholds are applied at query time.
 */
object TagExtractor {

    /**
     * Release-noise tokens (compared lowercased). Seeded from Jellyfin's naming
     * cleanup rules; extend freely as real library data surfaces new noise.
     */
    private val NOISE_WORDS = setOf(
        // resolutions / quality
        "1080p", "1080i", "720p", "480p", "576p", "2160p", "4320p", "4k", "8k",
        "uhd", "fhd", "hd", "sd", "hq", "hdr", "hdr10", "dv", "sdr",
        // codecs / audio
        "x264", "x265", "h264", "h265", "hevc", "avc", "av1", "vp9", "xvid", "divx",
        "aac", "ac3", "eac3", "dts", "truehd", "atmos", "flac", "mp3", "opus", "2ch", "6ch",
        "10bit", "8bit",
        // sources / release tags
        "web", "dl", "webdl", "webrip", "bluray", "bdrip", "brrip", "dvdrip", "dvd",
        "hdtv", "hdrip", "camrip", "remux", "proper", "repack", "internal", "limited",
        "extended", "unrated", "remastered", "complete", "final", "fix", "sample", "rip",
        // container / misc
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v", "webm", "mpg", "mpeg", "3gp", "iso",
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp",
        "video", "photo", "image", "part", "cd1", "cd2", "disc",
        // common CJK release noise
        "高清", "超清", "完整版", "无水印", "字幕", "中字", "中文字幕", "双语", "合集", "资源",
    )

    /** Word delimiters for tokenization; contiguous CJK runs stay whole. */
    private val DELIMITERS = Regex("[\\s\\p{Zs}\\[\\]【】()（）{}<>《》.,，、。_\\-/\\\\|#@~+：:；;！!？?'\"‘’“”&*=%^`]+")

    /**
     * Series-code pattern: 2–6 letters + 2–5 digits, optional hyphen, not glued
     * to surrounding alphanumerics (so `x264`-style tokens can't half-match).
     */
    private val CODE = Regex("(?<![A-Za-z0-9])([A-Za-z]{2,6})-?(\\d{2,5})(?![0-9])")

    private val YEAR_TOKEN = Regex("^(19|20)\\d{2}$")
    private val DIGITS_ONLY = Regex("^\\d+$")

    // --- name extraction ------------------------------------------------------

    private val EXTENSION =
        Regex("""\.(mp4|mkv|avi|mov|wmv|flv|m4v|webm|mpg|mpeg|ts|3gp)$""", RegexOption.IGNORE_CASE)

    /** Split markers, anywhere in the name — including mid-name `_000` forms. */
    private val PART_MARKER = Regex(
        """(?<![a-z0-9])(?:part|pt|cd|disc|dvd)\s*[._\-]?\s*\d{1,3}(?![0-9])""" +
            """|_\d{2,3}(?![0-9])|[（(\[【]\s*\d{1,3}\s*[）)\]】]|第\s*\d{1,3}\s*(?:部分|段)""",
        RegexOption.IGNORE_CASE,
    )

    /** Latin qualifier letters that precede a name: `-C-`, `-UC-`, `C瀬戸環奈`. */
    private const val ASCII_TRIM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_.~"

    private const val NAME_MIN = 2
    private const val NAME_MAX = 8

    /** Extract CODE and TERM candidates from raw caption/file-name text. */
    fun extract(rawText: String): List<ExtractedTag> {
        if (rawText.isBlank()) return emptyList()
        val normalized = normalizeWidth(rawText)
        val tags = LinkedHashMap<String, ExtractedTag>() // key = category|value, first display wins

        // Codes first, on the un-tokenized string, so `ABC-123` survives the
        // delimiter split intact.
        for (match in CODE.findAll(normalized)) {
            val letters = match.groupValues[1]
            if (letters.lowercase() in NOISE_WORDS) continue
            val value = "${letters.uppercase()}-${match.groupValues[2]}"
            tags.putIfAbsent("${TagCategory.CODE.name}|$value", ExtractedTag(TagCategory.CODE, value, value))
        }

        for (token in normalized.split(DELIMITERS)) {
            if (token.length < 2) continue
            val value = token.lowercase()
            if (value in NOISE_WORDS) continue
            if (DIGITS_ONLY.matches(value)) continue // bare numbers, incl. years — YEAR facet comes from send date
            if (YEAR_TOKEN.matches(value)) continue
            tags.putIfAbsent("${TagCategory.TERM.name}|$value", ExtractedTag(TagCategory.TERM, value, token))
        }
        return tags.values.toList()
    }

    /**
     * Performer names from a **file name** (not the caption, which carries prose).
     *
     * Names get their own facet because frequency mining structurally cannot
     * surface them: in a library of distinct titles a performer appears in
     * exactly one item, so any `TERM` threshold above 1 hides precisely the most
     * discriminating field while promoting series prefixes that the `CODE` facet
     * already expresses better.
     *
     * Extraction is subtractive. A release file name is a series code, optional
     * qualifier letters, a name, and a part marker in some order; strip every
     * part that is recognisably structure and a CJK run of name length is what
     * is left. Anything mixed-script, too short, or too long is prose, not a
     * name, and is dropped.
     */
    fun names(fileName: String): List<ExtractedTag> {
        if (fileName.isBlank()) return emptyList()
        var s = normalizeWidth(fileName)
        s = EXTENSION.replace(s, " ")
        s = CODE.replace(s, " ")       // series code — already its own facet
        s = PART_MARKER.replace(s, " ") // _000 / part2 / CD1 / (3)
        val out = LinkedHashMap<String, ExtractedTag>()
        for (token in s.split(DELIMITERS)) {
            // Leading only. Qualifier letters precede the name (`C瀬戸環奈`),
            // whereas trailing latin means a mixed-script token — `最新流出FC2`
            // is release prose, and trimming its tail would forge a plausible
            // four-character "name" out of it.
            val core = token.trimStart { it in ASCII_TRIM }
            if (core.length !in NAME_MIN..NAME_MAX) continue
            if (!core.all { it.isCjk() }) continue // mixed script means prose
            if (core.lowercase() in NOISE_WORDS) continue
            out.putIfAbsent(core, ExtractedTag(TagCategory.NAME, core, core))
        }
        return out.values.toList()
    }

    private fun Char.isCjk(): Boolean =
        this in '぀'..'ヿ' || this in '一'..'鿿' || this == '・'

    /** TYPE and YEAR facets from message metadata — full coverage, no parsing. */
    fun facets(type: String, dateUnixSeconds: Int): List<ExtractedTag> {
        val year = Calendar.getInstance(TimeZone.getDefault()).run {
            timeInMillis = dateUnixSeconds * 1000L
            get(Calendar.YEAR).toString()
        }
        return listOf(
            ExtractedTag(TagCategory.TYPE, type, type),
            ExtractedTag(TagCategory.YEAR, year, year),
        )
    }

    /**
     * Map full-width ASCII forms (ＡＢＣ１２３) to half-width so rules match.
     * Internal (not private): search reuses this exact normalization so a typed
     * query and the indexed text fold the same way.
     */
    internal fun normalizeWidth(s: String): String = buildString(s.length) {
        for (c in s) {
            when {
                c == '　' -> append(' ') // ideographic space
                c.code in 0xFF01..0xFF5E -> append((c.code - 0xFEE0).toChar())
                else -> append(c)
            }
        }
    }
}
