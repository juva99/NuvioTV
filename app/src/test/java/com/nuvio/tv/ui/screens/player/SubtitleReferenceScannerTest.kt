package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(UnstableApi::class)
class SubtitleReferenceScannerTest {

    @Test
    fun `known content range total is not the length of the returned range`() {
        val range = requireNotNull(
            parseSubtitleContentRange("bytes 0-16777215/100000000")
        )

        assertEquals(0L, range.start)
        assertEquals(16_777_215L, range.end)
        assertEquals(100_000_000L, range.totalLength)
        assertEquals(16_777_216L, range.end - range.start + 1L)
    }

    @Test
    fun `unknown content range total is accepted without inventing a stream length`() {
        val range = requireNotNull(
            parseSubtitleContentRange("bytes 16777216-33554431/*")
        )

        assertEquals(16_777_216L, range.start)
        assertEquals(33_554_431L, range.end)
        assertNull(range.totalLength)
    }

    @Test
    fun `malformed content ranges and numeric overflow are rejected`() {
        listOf(
            null,
            "",
            "bytes 0-9/unknown",
            "bytes 10-9/100",
            "bytes 0-9/9",
            "bytes 0-9223372036854775808/*",
            "bytes 0-9/9223372036854775808",
            "bytes 9223372036854775808-9223372036854775809/*"
        ).forEach { header ->
            assertNull("Expected invalid Content-Range: $header", parseSubtitleContentRange(header))
        }
    }

    @Test
    fun `invalid nonzero range responses become index unavailable`() = runBlocking {
        val connection = mockk<java.net.HttpURLConnection>(relaxed = true)
        every { connection.responseCode } returns 206
        val parsedUri = mockk<Uri>(relaxed = true)

        var currentFactory: SequencedDataSourceFactory? = null
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns parsedUri
        mockkObject(PlayerPlaybackNetworking)
        every {
            PlayerPlaybackNetworking.openConnection(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns connection
        every {
            PlayerPlaybackNetworking.createDataSourceFactory(any(), any())
        } answers { requireNotNull(currentFactory) }

        mockkConstructor(MatroskaExtractor::class)
        every { anyConstructed<MatroskaExtractor>().sniff(any()) } returns true
        every { anyConstructed<MatroskaExtractor>().init(any()) } just Runs
        every { anyConstructed<MatroskaExtractor>().read(any(), any()) } answers {
            secondArg<PositionHolder>().position = RANGE_START
            Extractor.RESULT_SEEK
        }
        every { anyConstructed<MatroskaExtractor>().release() } just Runs

        try {
            listOf(
                null,
                "not-a-content-range",
                "bytes 0-16777215/*"
            ).forEach { nonzeroResponse ->
                currentFactory = SequencedDataSourceFactory(
                    initialResponse = "bytes 0-16777215/*",
                    nonzeroResponse = nonzeroResponse
                )
                val scanner = SubtitleReferenceScanner(
                    context = mockk<Context>(relaxed = true),
                    url = "https://example.test/video.mkv",
                    headers = emptyMap(),
                    store = SubtitleReferenceCueStore()
                )

                assertEquals(SubtitleReferenceScanResult.IndexUnavailable, scanner.scan())
                assertEquals(
                    listOf(0L, RANGE_START),
                    currentFactory!!.openedPositions
                )
                scanner.close()
            }
        } finally {
            unmockkConstructor(MatroskaExtractor::class)
            unmockkObject(PlayerPlaybackNetworking)
            unmockkStatic(Uri::class)
        }
    }

    private class SequencedDataSourceFactory(
        initialResponse: String,
        nonzeroResponse: String?
    ) : DataSource.Factory {
        private val responses = listOf(
            contentRangeHeaders(initialResponse),
            nonzeroResponse?.let(::contentRangeHeaders) ?: emptyMap<String, List<String>>()
        )
        val openedPositions = mutableListOf<Long>()
        private var nextResponse = 0

        override fun createDataSource(): DataSource {
            val responseHeaders = responses[nextResponse++]
            return object : DataSource {
                private var openedUri: Uri? = null

                override fun addTransferListener(transferListener: TransferListener) = Unit

                override fun open(dataSpec: DataSpec): Long {
                    openedUri = dataSpec.uri
                    openedPositions += dataSpec.position
                    return C.LENGTH_UNSET.toLong()
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    C.RESULT_END_OF_INPUT

                override fun getUri(): Uri? = openedUri

                override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

                override fun close() = Unit
            }
        }

        private companion object {
            fun contentRangeHeaders(value: String): Map<String, List<String>> =
                mapOf("Content-Range" to listOf(value))
        }
    }

    private companion object {
        const val RANGE_START = 16_777_216L
    }
}
