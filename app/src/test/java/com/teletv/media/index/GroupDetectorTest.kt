package com.teletv.media.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupDetectorTest {

    private val gb = 1024L * 1024 * 1024
    private var nextId = 100L
    private var nextDate = 1_700_000_000

    /** Successive candidates land minutes apart, as consecutive uploads do. */
    private fun row(text: String, size: Long = 0L, durationSec: Int = 2700): GroupCandidate =
        GroupCandidate(
            messageId = nextId++,
            date = (nextDate + 600).also { nextDate = it },
            text = text,
            size = size,
            durationSec = durationSec,
        )

    private fun idsOf(groups: List<DetectedGroup>): List<List<Long>> = groups.map { it.memberIds }

    // --- strong markers group ---------------------------------------------------

    @Test
    fun `three part split is grouped in part order`() {
        val p1 = row("Movie.2023.1080p.part1.mp4", 2 * gb)
        val p2 = row("Movie.2023.1080p.part2.mp4", 2 * gb)
        val p3 = row("Movie.2023.1080p.part3.mp4", gb)
        val groups = GroupDetector.detect(listOf(p1, p2, p3))
        assertEquals(listOf(listOf(p1.messageId, p2.messageId, p3.messageId)), idsOf(groups))
        assertEquals(p1.messageId, groups.single().groupId)
    }

    @Test
    fun `two part split is grouped`() {
        val a = row("Some Film CD1.avi", 2 * gb)
        val b = row("Some Film CD2.avi", gb)
        assertEquals(listOf(listOf(a.messageId, b.messageId)), idsOf(GroupDetector.detect(listOf(a, b))))
    }

    @Test
    fun `parts out of stream order are ordered by part index`() {
        // Newest-first streams deliver the last part first.
        val p3 = row("Film.part3.mkv", gb)
        val p2 = row("Film.part2.mkv", 2 * gb)
        val p1 = row("Film.part1.mkv", 2 * gb)
        val group = GroupDetector.detect(listOf(p3, p2, p1)).single()
        assertEquals(listOf(p1.messageId, p2.messageId, p3.messageId), group.memberIds)
        assertEquals(p1.messageId, group.groupId)
    }

    @Test
    fun `1of3 form is grouped`() {
        val rows = listOf(
            row("Trip 1of3.mp4", 2 * gb),
            row("Trip 2of3.mp4", 2 * gb),
            row("Trip 3of3.mp4", gb),
        )
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    @Test
    fun `cjk part markers are grouped`() {
        val rows = listOf(
            row("纪录片 第1部分.mp4", 2 * gb),
            row("纪录片 第2部分.mp4", gb),
        )
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    @Test
    fun `cjk shang xia ordinals are grouped`() {
        val rows = listOf(row("电影名 上.mp4", 2 * gb), row("电影名 下.mp4", gb))
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    @Test
    fun `cjk shang zhong xia triple is grouped in order`() {
        val a = row("电影名 上.mp4", 2 * gb)
        val b = row("电影名 中.mp4", 2 * gb)
        val c = row("电影名 下.mp4", gb)
        val group = GroupDetector.detect(listOf(a, b, c)).single()
        assertEquals(listOf(a.messageId, b.messageId, c.messageId), group.memberIds)
    }

    @Test
    fun `incomplete cjk ordinal set is not grouped`() {
        // 中/下 is missing its opening part; 上/中 is missing its ending.
        assertTrue(GroupDetector.detect(listOf(row("片 中.mp4", 2 * gb), row("片 下.mp4", gb))).isEmpty())
        assertTrue(GroupDetector.detect(listOf(row("剧 上.mp4", 2 * gb), row("剧 中.mp4", gb))).isEmpty())
    }

    @Test
    fun `full width markers are normalized`() {
        val rows = listOf(
            row("Movie．ｐａｒｔ１.mp4", 2 * gb),
            row("Movie．ｐａｒｔ２.mp4", gb),
        )
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    @Test
    fun `strong markers group even when sizes are unknown`() {
        // Rows indexed before the size column existed carry size 0.
        val rows = listOf(row("Old.part1.mp4", 0L), row("Old.part2.mp4", 0L))
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    // --- episodes must never group ----------------------------------------------

    @Test
    fun `twelve episode series is not grouped`() {
        val rows = (1..12).map { row("Show.EP%02d.mp4".format(it), 500L * 1024 * 1024) }
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `episodes with near identical sizes are not grouped`() {
        // The dangerous case: steady-bitrate encoding makes sizes look like a split.
        val rows = (1..4).map { row("Show_%02d.mp4".format(it), 500L * 1024 * 1024) }
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `sxxexx episodes are not grouped`() {
        val rows = listOf(
            row("Series.S01E01.1080p.mkv", gb),
            row("Series.S01E02.1080p.mkv", gb),
        )
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `cjk episode markers are not grouped`() {
        val rows = listOf(row("剧名 第1集.mp4", gb), row("剧名 第2集.mp4", gb))
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `split of an episode still groups`() {
        // Episode marker plus a strong split marker: a big episode cut in two.
        val rows = listOf(
            row("Series.S01E02.part1.mkv", 2 * gb),
            row("Series.S01E02.part2.mkv", gb),
        )
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    // --- weak markers need the size fingerprint ---------------------------------

    @Test
    fun `weak marker groups when the split fingerprint is present`() {
        val rows = listOf(
            row("Concert_01.mp4", 2 * gb),
            row("Concert_02.mp4", 2 * gb),
            row("Concert_03.mp4", gb / 2),
        )
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    @Test
    fun `weak marker does not group without sizes`() {
        val rows = listOf(row("Concert_01.mp4", 0L), row("Concert_02.mp4", 0L))
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `weak marker does not group when the tail is the same size`() {
        val rows = listOf(row("Concert_01.mp4", 2 * gb), row("Concert_02.mp4", 2 * gb))
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `parenthesised numbers group with the fingerprint`() {
        val rows = listOf(row("Holiday (1).mp4", 2 * gb), row("Holiday (2).mp4", gb))
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    // --- structural rejections ---------------------------------------------------

    @Test
    fun `non contiguous part indices do not group`() {
        val rows = listOf(row("Film.part1.mp4", 2 * gb), row("Film.part3.mp4", gb))
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `sequence not starting at one does not group`() {
        val rows = listOf(row("Film.part2.mp4", 2 * gb), row("Film.part3.mp4", gb))
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `duplicate part numbers do not group`() {
        val rows = listOf(row("Film.part1.mp4", 2 * gb), row("Film.part1.mp4", 2 * gb))
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `different stems do not group`() {
        val rows = listOf(row("Alpha.part1.mp4", 2 * gb), row("Beta.part2.mp4", gb))
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `contradicting sizes reject a strong marker group`() {
        // Non-final parts wildly unequal: not a cap-driven split.
        val rows = listOf(
            row("Film.part1.mp4", 2 * gb),
            row("Film.part2.mp4", 100L * 1024 * 1024),
            row("Film.part3.mp4", gb),
        )
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `parts uploaded years apart do not group`() {
        val p1 = GroupCandidate(1L, 1_600_000_000, "Film.part1.mp4", 2 * gb, 2700)
        val p2 = GroupCandidate(2L, 1_700_000_000, "Film.part2.mp4", gb, 2700)
        assertTrue(GroupDetector.detect(listOf(p1, p2)).isEmpty())
    }

    @Test
    fun `a lone part is not a group`() {
        assertTrue(GroupDetector.detect(listOf(row("Film.part1.mp4", 2 * gb))).isEmpty())
    }

    @Test
    fun `unmarked videos are not grouped`() {
        val rows = listOf(row("Sunset.mp4", gb), row("Beach.mp4", gb))
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `stem matching ignores delimiter style`() {
        val rows = listOf(row("My Film.part1.mp4", 2 * gb), row("My_Film-part2.mp4", gb))
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    @Test
    fun `two independent groups are detected separately`() {
        val a1 = row("Alpha.part1.mp4", 2 * gb)
        val a2 = row("Alpha.part2.mp4", gb)
        val b1 = row("Beta.part1.mp4", 2 * gb)
        val b2 = row("Beta.part2.mp4", gb)
        val groups = GroupDetector.detect(listOf(a1, a2, b1, b2))
        assertEquals(2, groups.size)
        assertEquals(
            listOf(listOf(a1.messageId, a2.messageId), listOf(b1.messageId, b2.messageId)),
            idsOf(groups),
        )
    }

    @Test
    fun `empty and blank input yields nothing`() {
        assertTrue(GroupDetector.detect(emptyList()).isEmpty())
        assertTrue(GroupDetector.detect(listOf(row(""), row("   "))).isEmpty())
    }

    // --- regressions from real library data --------------------------------

    @Test
    fun `zero based three digit parts group`() {
        // Straight from a real Saved Messages library: ffmpeg-style _000.._003,
        // three parts pinned at the 2 GB cap and a short tail.
        val a = row("SONE-801-C-村上悠華_000.mp4", 2_039_979_995L, 2295)
        val b = row("SONE-801-C-村上悠華_001.mp4", 2_040_188_559L, 2295)
        val c = row("SONE-801-C-村上悠華_002.mp4", 2_039_126_276L, 2294)
        val d = row("SONE-801-C-村上悠華_003.mp4", 195_352_984L, 221)
        val group = GroupDetector.detect(listOf(a, b, c, d)).single()
        assertEquals(listOf(a.messageId, b.messageId, c.messageId, d.messageId), group.memberIds)
        assertEquals(a.messageId, group.groupId)
    }

    @Test
    fun `keyframe aligned parts vary by a few percent and still group`() {
        // Real library: a 2 GB-capped split whose full parts range over 4.6%,
        // because the splitter cuts on keyframes rather than exact byte counts.
        val rows = listOf(
            row("SONE-543-UC_000.mp4", 2_002_000_000L),
            row("SONE-543-UC_001.mp4", 2_050_000_000L),
            row("SONE-543-UC_002.mp4", 2_094_000_000L),
            row("SONE-543-UC_003.mp4", 2_050_000_000L),
            row("SONE-543-UC_004.mp4", 1_900_000_000L),
        )
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    @Test
    fun `a nearly full tail still groups`() {
        // Tail at 93% of a full part — smaller, but nowhere near "much smaller".
        val rows = listOf(
            row("SONE-405_000.mp4", 2_040_000_000L),
            row("SONE-405_001.mp4", 2_040_000_000L),
            row("SONE-405_002.mp4", 1_900_000_000L),
        )
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    @Test
    fun `zero based strong markers group`() {
        val rows = listOf(row("Film.part0.mkv", 2 * gb), row("Film.part1.mkv", gb))
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    @Test
    fun `a run not starting at zero or one does not group`() {
        // Real data had FC2-PPV-..._5 / _6 with no siblings: parts of something
        // whose opening is not in this chat, so not presentable as one video.
        val rows = listOf(row("clip_5.mp4", 900L * 1024 * 1024), row("clip_6.mp4", 800L * 1024 * 1024))
        assertTrue(GroupDetector.detect(rows).isEmpty())
    }

    @Test
    fun `detection reads the file name alone not name plus caption`() {
        // The scan stores "fileName caption"; feeding that in leaves the marker
        // inside the stem, so siblings stop matching. Callers pass fileName.
        val rows = listOf(
            row("Movie.part1.mp4", 2 * gb),
            row("Movie.part2.mp4", gb),
        )
        assertEquals(1, GroupDetector.detect(rows).size)
    }

    // --- display name ---------------------------------------------------------

    @Test
    fun `display name strips extension and trailing part marker`() {
        assertEquals("SONE-054-C-凪ひかる", GroupDetector.displayName("SONE-054-C-凪ひかる_000.mp4"))
        assertEquals("MIDA-234-UC-新ありな", GroupDetector.displayName("MIDA-234-UC-新ありな_003.mp4"))
    }

    @Test
    fun `display name keeps case and inner delimiters`() {
        // Distinct from the identity stem, which would yield "movie 2023 1080p".
        assertEquals("Movie.2023.1080p", GroupDetector.displayName("Movie.2023.1080p.part1.mp4"))
    }

    @Test
    fun `display name strips strong and cjk markers`() {
        assertEquals("Some Film", GroupDetector.displayName("Some Film CD1.avi"))
        assertEquals("电影名", GroupDetector.displayName("电影名 上.mp4"))
    }

    @Test
    fun `display name leaves an unmarked name alone apart from the extension`() {
        assertEquals("Sunset over the bay", GroupDetector.displayName("Sunset over the bay.mp4"))
    }

    @Test
    fun `display name never returns empty`() {
        assertEquals("part1", GroupDetector.displayName("part1"))
        assertEquals("", GroupDetector.displayName(""))
    }
}
