package com.ze.iptvplayer.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer

/**
 * MP2 (MPEG-1 Layer II) audio — the standard on Portuguese/European DVB channels (RTP, SIC, ...)
 * — has no decoder on most phones, so those channels played video without sound. This adds a
 * small software decoder (JLayer, pure Java) that ExoPlayer uses only when the phone itself
 * can't decode the track.
 */
class Mp2RenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out
        )
        out.add(Mp2AudioRenderer(eventHandler, eventListener, audioSink))
    }
}

class Mp2AudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink
) : DecoderAudioRenderer<Mp2Decoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "Mp2AudioRenderer"

    override fun supportsFormatInternal(format: Format): Int =
        if (MimeTypes.AUDIO_MPEG_L2 == format.sampleMimeType) {
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): Mp2Decoder =
        Mp2Decoder(format)

    override fun getOutputFormat(decoder: Mp2Decoder): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(decoder.channelCount)
            .setSampleRate(decoder.sampleRate)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
}

@Suppress("UNCHECKED_CAST")
class Mp2Decoder(inputFormat: Format) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
        arrayOfNulls<DecoderInputBuffer>(16) as Array<DecoderInputBuffer>,
        arrayOfNulls<SimpleDecoderOutputBuffer>(16) as Array<SimpleDecoderOutputBuffer>
    ) {

    /** Known after the first frame has been decoded (the renderer asks for it at that point). */
    @Volatile var channelCount: Int = if (inputFormat.channelCount > 0) inputFormat.channelCount else 2
        private set
    @Volatile var sampleRate: Int = if (inputFormat.sampleRate > 0) inputFormat.sampleRate else 48000
        private set

    /**
     * JLayer's frame decoder remembers the Bitstream it was first given, so ONE Bitstream must live
     * as long as the decoder; each incoming audio packet is appended to [feed] rather than getting
     * a Bitstream of its own (which made every packet after the first decode as garbage).
     */
    private var feed = FeedStream()
    private var bitstream = Bitstream(feed)
    private var decoder = Decoder()
    private var loggedError = false

    private fun resetDecoder() {
        feed = FeedStream()
        bitstream = Bitstream(feed)
        decoder = Decoder()
    }

    /** InputStream whose content is appended packet by packet; reading past the end returns -1. */
    private class FeedStream : InputStream() {
        private var buf = ByteArray(0)
        private var pos = 0

        fun feed(bytes: ByteArray) {
            val remaining = buf.size - pos
            val merged = ByteArray(remaining + bytes.size)
            System.arraycopy(buf, pos, merged, 0, remaining)
            System.arraycopy(bytes, 0, merged, remaining, bytes.size)
            buf = merged
            pos = 0
        }

        override fun read(): Int = if (pos < buf.size) buf[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= buf.size) return -1
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = buf.size - pos
    }

    override fun getName(): String = "Mp2Decoder"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer { buffer -> releaseOutputBuffer(buffer) }

    override fun createUnexpectedDecodeException(error: Throwable): DecoderException =
        DecoderException("Unexpected MP2 decode error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean
    ): DecoderException? {
        if (reset) resetDecoder()
        val data = inputBuffer.data
        val pcm = ByteArrayOutputStream()
        if (data != null) {
            val bytes = ByteArray(data.limit())
            data.position(0)
            data.get(bytes)
            feed.feed(bytes)
            try {
                while (true) {
                    val header = bitstream.readFrame() ?: break
                    val out = decoder.decodeFrame(header, bitstream) as SampleBuffer
                    channelCount = out.channelCount
                    sampleRate = out.sampleFrequency
                    val shorts = out.buffer
                    for (i in 0 until out.bufferLength) {
                        val v = shorts[i].toInt()
                        pcm.write(v and 0xFF)          // 16-bit little-endian PCM
                        pcm.write((v shr 8) and 0xFF)
                    }
                    bitstream.closeFrame()
                }
            } catch (e: Exception) {
                // Corrupt frame or a decoder bug: start clean on the next packet, and never let
                // the failure take the video down with it.
                if (!loggedError) {
                    loggedError = true
                    Log.e("ZecaAudio", "MP2 decode problem: $e", e)
                }
                resetDecoder()
            }
        }
        val result = pcm.toByteArray()
        val out = outputBuffer.init(inputBuffer.timeUs, result.size)
        out.put(result)
        out.flip()
        return null
    }
}
