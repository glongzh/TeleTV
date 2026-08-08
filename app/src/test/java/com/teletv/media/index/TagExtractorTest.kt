package com.teletv.media.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagExtractorTest {

    private fun values(raw: String, category: TagCategory): List<String> =
        TagExtractor.extract(raw).filter { it.category == category }.map { it.value }

    // --- codes ---------------------------------------------------------------

    @Test
    fun `code with hyphen is normalized uppercase`() {
        assertEquals(listOf("ABC-123"), values("[abc-123] some title", TagCategory.CODE))
    }

    @Test
    fun `code without hyphen gains one`() {
        assertEquals(listOf("ABC-00123"), values("ABC00123.mp4", TagCategory.CODE))
    }

    @Test
    fun `codec tokens are not codes`() {
        assertTrue(values("Movie.2020.x264.mp4", TagCategory.CODE).isEmpty())
        assertTrue(values("clip hevc10", TagCategory.CODE).isEmpty())
    }

    @Test
    fun `code glued to letters does not match`() {
        // Every letter run here is preceded by an alphanumeric, so the lookbehind
        // blocks it. (A run at the very start of the string is a legitimate code —
        // see `code without hyphen gains one`.)
        assertTrue(values("file9abc123", TagCategory.CODE).isEmpty())
        assertTrue(values("xabc123x456", TagCategory.CODE).none { it == "X-456" })
    }

    // --- noise ---------------------------------------------------------------

    @Test
    fun `release noise produces no terms`() {
        val terms = values("Title.2023.1080p.WEB-DL.x265.AAC.mkv", TagCategory.TERM)
        assertEquals(listOf("title"), terms)
    }

    @Test
    fun `years and bare numbers are dropped`() {
        val terms = values("holiday 2019 0042", TagCategory.TERM)
        assertEquals(listOf("holiday"), terms)
    }

    @Test
    fun `cjk noise words are dropped`() {
        val terms = values("电影名 高清 中文字幕", TagCategory.TERM)
        assertEquals(listOf("电影名"), terms)
    }

    // --- tokenization --------------------------------------------------------

    @Test
    fun `cjk runs stay whole and cjk delimiters split`() {
        val terms = values("【张三】风景、旅行记录", TagCategory.TERM)
        assertEquals(listOf("张三", "风景", "旅行记录"), terms)
    }

    @Test
    fun `mixed language string extracts both scripts`() {
        val terms = values("张三 birthday party.mp4", TagCategory.TERM)
        assertEquals(listOf("张三", "birthday", "party"), terms)
    }

    @Test
    fun `full width characters are normalized`() {
        assertEquals(listOf("ABC-123"), values("ＡＢＣ－１２３", TagCategory.CODE))
    }

    @Test
    fun `display keeps original case while value is lowercased`() {
        val tag = TagExtractor.extract("Sunset").single { it.category == TagCategory.TERM }
        assertEquals("sunset", tag.value)
        assertEquals("Sunset", tag.display)
    }

    @Test
    fun `duplicate tokens emit one tag`() {
        assertEquals(listOf("beach"), values("beach Beach BEACH", TagCategory.TERM))
    }

    // --- edges ---------------------------------------------------------------

    @Test
    fun `blank text yields nothing`() {
        assertTrue(TagExtractor.extract("").isEmpty())
        assertTrue(TagExtractor.extract("   ").isEmpty())
    }

    @Test
    fun `single char tokens are dropped`() {
        assertTrue(values("a b 张", TagCategory.TERM).isEmpty())
    }

    // --- facets --------------------------------------------------------------

    @Test
    fun `facets derive type and year from metadata`() {
        // 2021-06-15 12:00:00 UTC
        val facets = TagExtractor.facets("video", 1623758400)
        assertTrue(facets.any { it.category == TagCategory.TYPE && it.value == "video" })
        assertTrue(facets.any { it.category == TagCategory.YEAR && it.value == "2021" })
        assertFalse(facets.any { it.category == TagCategory.TERM })
    }

    // --- performer names (fixtures taken from a real library) ----------------

    private fun names(f: String) = TagExtractor.names(f).map { it.value }

    @Test
    fun `name after code and qualifier`() {
        assertEquals(listOf("凪ひかる"), names("SONE-054-C-凪ひかる_000.mp4"))
        assertEquals(listOf("新ありな"), names("MIDA-234-UC-新ありな_000.mp4"))
        assertEquals(listOf("三佳詩"), names("ABF-363-U-三佳詩_000.mp4"))
    }

    @Test
    fun `name directly after code`() {
        assertEquals(listOf("三田真鈴"), names("SONE-392-三田真鈴_000.mp4"))
    }

    @Test
    fun `name before the code`() {
        assertEquals(listOf("古川いおり"), names("古川いおり-STARS-094-UC_000.mp4"))
        assertEquals(listOf("霧島レオナ"), names("霧島レオナ-hodv-21402-C_000.mp4"))
    }

    @Test
    fun `qualifier glued to the name is stripped`() {
        // The sibling files hyphenate; this one does not.
        assertEquals(listOf("瀬戸環奈"), names("SONE-811-C瀬戸環奈_000.mp4"))
    }

    @Test
    fun `name after a mid-name part marker`() {
        assertEquals(listOf("紫堂るい"), names("SNOS-008-C_000-紫堂るい.mp4"))
    }

    @Test
    fun `prose is not a name`() {
        // Too long, and mixed script, so neither is mistaken for a performer.
        assertTrue(names("更多视频请在tg收藏夹输入@AnchorPorn.MP4").isEmpty())
        assertTrue(names("最新流出FC2_PPV系列笑容甜美19岁清纯漂亮美少女酒店援交.mp4").isEmpty())
        // Trailing latin is the tell: trimming it would forge a false name.
        assertTrue(names("最新流出FC2.mp4").isEmpty())
    }

    @Test
    fun `long title yields only the short name beside it`() {
        assertEquals(listOf("小林杏"), names("91CM-248 我的姐姐不可能那么淫荡  小林杏.mp4"))
    }

    @Test
    fun `latin only names yield nothing`() {
        assertTrue(names("FC2-PPV-2551759~1.mp4").isEmpty())
        assertTrue(names("video_2021-11-05_20-34-08.mp4").isEmpty())
        assertTrue(names("hhd800.com@FC2-PPV-2903593_5.mp4").isEmpty())
    }

    @Test
    fun `dates are not names`() {
        assertTrue(names("8月15日.mp4").isEmpty())
    }

    @Test
    fun `blank input yields nothing`() {
        assertTrue(names("").isEmpty())
        assertTrue(names("   ").isEmpty())
    }
}
