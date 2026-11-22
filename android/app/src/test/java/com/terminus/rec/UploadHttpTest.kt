package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Upload outcome classification and the stable id used for server-side de-duplication. */
class UploadHttpTest {

    @Test fun `success codes are OK`() {
        assertEquals(UploadResult.OK, UploadHttp.classify(200))
        assertEquals(UploadResult.OK, UploadHttp.classify(204))
    }

    @Test fun `server trouble and auth keep the file queued`() {
        for (c in listOf(401, 403, 408, 429, 500, 502, 503)) assertEquals("code $c", UploadResult.RETRY, UploadHttp.classify(c))
    }

    @Test fun `permanent refusals are rejected so they cannot block the queue`() {
        for (c in listOf(400, 404, 413, 415, 422)) assertEquals("code $c", UploadResult.REJECT, UploadHttp.classify(c))
    }

    @Test fun `upload id is stable per file revision and safe for the server`() {
        val f = File.createTempFile("chunk", ".m4a").apply { writeBytes(ByteArray(10) { it.toByte() }); deleteOnExit() }
        val a = UploadHttp.uploadId(f)
        assertEquals(a, UploadHttp.uploadId(f))
        assertTrue(Regex("^[A-Za-z0-9._:-]{8,100}$").matches(a))
        f.writeBytes(ByteArray(11))
        assertNotEquals(a, UploadHttp.uploadId(f))
    }
}
