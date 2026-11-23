package com.example.reborntube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackCodecTest {
    @Test
    fun recognizesModernVideoCodecs() {
        assertEquals("video/av01", videoCodecMimeType("av01.0.08M.08"))
        assertEquals("video/x-vnd.on2.vp9", videoCodecMimeType("vp09.00.10.08"))
        assertEquals("video/avc", videoCodecMimeType("avc1.640028"))
    }

    @Test
    fun ignoresUnknownCodecs() {
        assertNull(videoCodecMimeType("unrecognized"))
    }
}
