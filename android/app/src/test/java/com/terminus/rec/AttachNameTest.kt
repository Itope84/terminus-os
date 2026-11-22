package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

class AttachNameTest {
    @Test fun `screenshots read as Screenshot`() {
        assertEquals("Screenshot", AttachName.friendly("Screenshot_20261024-231502.png"))
        assertEquals("Screenshot", AttachName.friendly("231502_Screenshot_2026-10-24-23-15-02.jpg"))
    }
    @Test fun `camera files read as Photo`() {
        assertEquals("Photo", AttachName.friendly("PXL_20261024_231502123.jpg"))
        assertEquals("Photo", AttachName.friendly("IMG_4821.HEIC"))
        assertEquals("Photo", AttachName.friendly("20261024_231502.jpg"))
    }
    @Test fun `real names are kept minus the staging stamp`() {
        assertEquals("quarterly report.pdf", AttachName.friendly("231502_quarterly report.pdf"))
        assertEquals("diagram-v2.png", AttachName.friendly("231502_2_diagram-v2.png"))
        assertEquals("notes.txt", AttachName.friendly("/data/media/231502_notes.txt"))
    }
    @Test fun `summary names count and first two`() {
        assertEquals("Screenshot", AttachName.summary(listOf("Screenshot_1.png")))
        assertEquals("3 files: Screenshot, a.pdf +1 more", AttachName.summary(listOf("Screenshot_1.png", "a.pdf", "b.pdf")))
    }
}
