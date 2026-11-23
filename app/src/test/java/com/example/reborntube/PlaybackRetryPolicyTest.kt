package com.example.reborntube

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRetryPolicyTest {
    @Test
    fun retriesOnlyTransientNetworkFailures() {
        assertTrue(
            isRetryablePlaybackErrorCode(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
        )
        assertTrue(
            isRetryablePlaybackErrorCode(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            )
        )
        assertFalse(
            isRetryablePlaybackErrorCode(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            )
        )
        assertFalse(
            isRetryablePlaybackErrorCode(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            )
        )
    }
}
