package com.teletv.media.index

import kotlin.math.abs

/**
 * One candidate for part grouping. Pure metadata; [size] of 0 means unknown
 * (rows indexed before the size column existed).
 */
data class GroupCandidate(
    val messageId: Long,
    val date: Int, // unix seconds
    /** File name where present, else the caption. NOT the two concatenated:
     *  a trailing caption both breaks stem equality and hijacks the weak
     *  end-anchored rules. */
    val text: String,
    val size: Long,
    val durationSec: Int,
)

/** Members in part order. [groupId] is the first part's message id. */
data class DetectedGroup(
    val groupId: Long,
    val memberIds: List<Long>,
)

/**
 * Pure rule-based detection of videos that are pieces of one split file. No I/O
 * — unit testable, same shape as [TagExtractor].
 *
 * The whole difficulty is that "common stem + ordered index" describes both
 * `Movie.part1/part2` and `Show.第1集/第2集`. Only the marker vocabulary tells
 * them apart, so markers come in two strengths:
 *
 * - **Strong** (`part1`, `CD2`, `1of3`, `上/中/下`): the token itself says
 *   "piece of one file". Grouped on the name alone unless size contradicts.
 * - **Weak** (`Movie_01`, `Movie (2)`): indistinguishable from episode
 *   numbering by name, so they group only when the size fingerprint of a
 *   cap-driven split is present — equal non-final parts and a smaller tail.
 *
 * Failure is asymmetric: missing a group leaves today's behaviour, while fusing
 * a series into one item produces a hours-long item and one corrupted progress
 * record. Every ambiguous case therefore resolves to "no group".
 */
object GroupDetector {

    fun detect(candidates: List<GroupCandidate>): List<DetectedGroup> {
        if (candidates.size < 2) return emptyList()
        return candidates.mapNotNull(::mark)
            .groupBy { it.stem }
            .values
            .mapNotNull(::validate)
            .sortedBy { it.groupId }
    }

    private data class Marked(
        val candidate: GroupCandidate,
        val stem: String,
        val partIndex: Int,
        val strong: Boolean,
        /** Marked by 上/中/下, which are positional words rather than numbers. */
        val ordinal: Boolean = false,
    )

    // --- marking --------------------------------------------------------------

    private data class Marker(
        val range: IntRange,
        val index: Int,
        val strong: Boolean,
        val ordinal: Boolean,
    )

    private fun findMarker(name: String): Marker? {
        for (rule in STRONG_RULES) {
            val m = rule.find(name) ?: continue
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            if (idx <= MAX_PARTS) return Marker(m.range, idx, strong = true, ordinal = false)
        }
        CJK_ORDINAL.find(name)?.let { m ->
            ORDINALS[m.groupValues[1]]?.let { idx ->
                return Marker(m.range, idx, strong = true, ordinal = true)
            }
        }
        // Beyond this point only ambiguous markers remain, so an episode marker
        // anywhere in the name vetoes grouping. It deliberately does NOT veto the
        // strong rules above: `Show.S01E02.part1/part2` is a split episode.
        if (EPISODE.containsMatchIn(name)) return null

        for (rule in WEAK_RULES) {
            val m = rule.find(name) ?: continue
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            if (idx <= MAX_WEAK_PARTS) return Marker(m.range, idx, strong = false, ordinal = false)
        }
        return null
    }

    private fun mark(c: GroupCandidate): Marked? {
        val name = EXTENSION.replace(normalizeWidth(c.text).trim(), "").trim()
        if (name.isEmpty()) return null
        val m = findMarker(name) ?: return null
        return Marked(c, stemOf(name, m.range), m.index, m.strong, m.ordinal)
    }

    /**
     * What to title a merged group with: the file name minus its extension and
     * part marker, otherwise untouched.
     *
     * Deliberately not [stemOf]'s output. That one is an identity key — it
     * lowercases and collapses every delimiter to a space, so `SONE-054-C-凪ひかる`
     * would render as `sone 054 c 凪ひかる`, worse than the raw file name. Same
     * value/display split the tag index already makes.
     *
     * Returns the name unchanged when there is no marker to strip.
     */
    fun displayName(fileName: String): String {
        val name = EXTENSION.replace(normalizeWidth(fileName).trim(), "").trim()
        if (name.isEmpty()) return fileName
        val marker = findMarker(name) ?: return name
        val cut = name.substring(0, marker.range.first) + name.substring(marker.range.last + 1)
        return cut.trim { it in TRIMMABLE }.ifEmpty { name }
    }

    /** Name with the marker cut out, delimiters collapsed, lowercased. */
    private fun stemOf(name: String, marker: IntRange): String =
        (name.substring(0, marker.first) + name.substring(marker.last + 1))
            .lowercase()
            .replace(DELIMITERS, " ")
            .trim()

    // --- validation -----------------------------------------------------------

    private fun validate(raw: List<Marked>): DetectedGroup? {
        if (raw.size < 2 || raw.size > MAX_PARTS) return null
        val ordinalFixed = if (raw.all { it.ordinal }) remapOrdinals(raw) ?: return null else raw
        val indices = ordinalFixed.map { it.partIndex }.sorted()
        if (indices.distinct().size != indices.size) return null // duplicate part numbers
        // Splitters number from 0 (ffmpeg's segment muxer, `split`) or from 1;
        // both are contiguous runs. Anything else has a hole or is missing its
        // opening part, and cannot be presented as one continuous video.
        val base = indices.first()
        if (base !in 0..1) return null
        if (indices != (base until base + indices.size).toList()) return null
        val members =
            if (base == 0) ordinalFixed.map { it.copy(partIndex = it.partIndex + 1) } else ordinalFixed

        val byIndex = members.associateBy { it.partIndex }
        val ordered = (1..members.size).map { byIndex.getValue(it) }
        if (!uploadedTogether(ordered)) return null

        val strong = ordered.all { it.strong }
        val sizes = sizeVerdict(ordered)
        if (strong) {
            if (sizes == SizeVerdict.CONTRADICTS) return null
        } else {
            if (members.size > MAX_WEAK_PARTS) return null
            // An ambiguous marker needs the full fingerprint, tail included: a
            // series encoded at a steady bitrate has a last item the same size
            // as the rest, which is exactly what the tail check rejects.
            if (sizes != SizeVerdict.MATCHES || !tailIsSmaller(ordered)) return null
        }
        return DetectedGroup(
            groupId = ordered.first().candidate.messageId,
            memberIds = ordered.map { it.candidate.messageId },
        )
    }

    /**
     * 上/中/下 mean upper/middle/lower, not 1/2/3: a two-part split is 上/下,
     * which would otherwise read as parts 1 and 3 and fail the contiguity check.
     * Only the two complete sets are meaningful — 中/下 is missing its opening
     * part and 上/中 its ending, so neither forms a whole video.
     */
    private fun remapOrdinals(members: List<Marked>): List<Marked>? =
        when (members.map { it.partIndex }.sorted()) {
            listOf(1, 3) -> members.map { if (it.partIndex == 3) it.copy(partIndex = 2) else it }
            listOf(1, 2, 3) -> members
            else -> null
        }

    private fun uploadedTogether(ordered: List<Marked>): Boolean =
        ordered.map { it.candidate.date.toLong() }
            .zipWithNext()
            .all { (a, b) -> abs(b - a) <= UPLOAD_WINDOW_SEC }

    private enum class SizeVerdict { MATCHES, CONTRADICTS, UNKNOWN }

    /**
     * A cap-driven split leaves the non-final parts near-identical with the tail
     * no larger. Expressed relatively rather than against a 2 GB / 4 GB constant:
     * the cap belongs to whoever uploaded the file, is invisible to the client,
     * and has changed over Telegram's history.
     */
    private fun sizeVerdict(ordered: List<Marked>): SizeVerdict {
        val sizes = ordered.map { it.candidate.size }
        if (sizes.any { it <= 0L }) return SizeVerdict.UNKNOWN
        val nonFinal = sizes.dropLast(1)
        val largest = nonFinal.max()
        if (largest <= 0L) return SizeVerdict.UNKNOWN
        val spread = (largest - nonFinal.min()).toDouble() / largest
        if (spread > SIZE_SPREAD_TOLERANCE) return SizeVerdict.CONTRADICTS
        if (sizes.last() > largest * (1 + SIZE_SPREAD_TOLERANCE)) return SizeVerdict.CONTRADICTS
        return SizeVerdict.MATCHES
    }

    /**
     * Kept separate from the spread tolerance on purpose. A real split's tail can
     * be anywhere from 10% to 93% of a full part, so this margin has to be slack;
     * the spread tolerance meanwhile has to absorb only the few percent that
     * keyframe-aligned cutting introduces between the full parts.
     */
    private fun tailIsSmaller(ordered: List<Marked>): Boolean {
        val sizes = ordered.map { it.candidate.size }
        val largest = sizes.dropLast(1).max()
        return sizes.last() < largest * TAIL_MAX_RATIO
    }

    /** Full-width ASCII → half-width, so `ｐａｒｔ１` matches. Mirrors TagExtractor. */
    private fun normalizeWidth(s: String): String = buildString(s.length) {
        for (c in s) {
            when {
                c == '　' -> append(' ')
                c.code in 0xFF01..0xFF5E -> append((c.code - 0xFEE0).toChar())
                else -> append(c)
            }
        }
    }

    // --- vocabulary -----------------------------------------------------------

    private val EXTENSION =
        Regex("""\.(mp4|mkv|avi|mov|wmv|flv|m4v|webm|mpg|mpeg|ts|3gp)$""", RegexOption.IGNORE_CASE)

    private val DELIMITERS = Regex("""[\s._\-\[\]()（）【】]+""")

    /** Delimiters left dangling once a marker is cut out of a display name. */
    private const val TRIMMABLE = " ._-[]()（）【】、"

    /** Markers that by themselves mean "piece of one file". */
    private val STRONG_RULES = listOf(
        Regex("""(?<![a-z0-9])(?:part|pt)\s*[._\-]?\s*(\d{1,3})(?![0-9])""", RegexOption.IGNORE_CASE),
        Regex("""(?<![a-z0-9])(?:cd|disc|dvd)\s*[._\-]?\s*(\d{1,3})(?![0-9])""", RegexOption.IGNORE_CASE),
        Regex("""(?<![0-9])(\d{1,3})\s*of\s*\d{1,2}(?![0-9])""", RegexOption.IGNORE_CASE),
        Regex("""第\s*(\d{1,3})\s*(?:部分|段)"""),
        Regex("""分段\s*(\d{1,3})"""),
    )

    private val CJK_ORDINAL = Regex("""[（(\[【\s._\-]([上中下])[）)\]】\s._\-]*$""")
    private val ORDINALS = mapOf("上" to 1, "中" to 2, "下" to 3)

    /**
     * Episode numbering. Vetoes the weak rules only — a name carrying both an
     * episode marker and a strong split marker is a split episode.
     */
    private val EPISODE = Regex(
        """(?<![a-z0-9])(?:s\d{1,2}\s*e\d{1,3}|ep\s*\d{1,3}|e\d{1,3}|episode\s*\d{1,3})(?![0-9])""" +
            """|第\s*\d{1,3}\s*[集话話期]""",
        RegexOption.IGNORE_CASE,
    )

    /** Ambiguous with episode numbering; only group with size corroboration. */
    private val WEAK_RULES = listOf(
        Regex("""[（(\[【]\s*(\d{1,3})\s*[）)\]】]\s*$"""),
        Regex("""[._\-\s](\d{1,3})\s*$"""),
    )

    private const val MAX_PARTS = 8
    private const val MAX_WEAK_PARTS = 6
    /**
     * Spread allowed between the non-final parts. Measured against a real
     * library: splitters cut on keyframe boundaries, so "equal" parts of a
     * 2 GB-capped split actually range over a few percent (observed up to 4.6%).
     */
    private const val SIZE_SPREAD_TOLERANCE = 0.12
    /** Tail must be meaningfully below a full part — episodes sit at ~1.0. */
    private const val TAIL_MAX_RATIO = 0.98
    private const val UPLOAD_WINDOW_SEC = 7L * 24 * 3600
}
