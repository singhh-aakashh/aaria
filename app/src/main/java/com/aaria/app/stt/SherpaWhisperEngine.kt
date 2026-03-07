package com.aaria.app.stt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device Whisper Base transcription via sherpa-onnx.
 *
 * Model files must be placed in:
 *   app/src/main/assets/sherpa-onnx-whisper-base/
 *     base-encoder.int8.onnx   (~29 MB)
 *     base-decoder.int8.onnx   (~131 MB)
 *     base-tokens.txt
 *
 * Download from:
 *   https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base
 *
 * The recognizer is initialised lazily on the first [transcribe] call so the
 * application starts fast and the model loads on a background thread.
 */
class SherpaWhisperEngine(private val context: Context) {

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    /**
     * Transcribes a 16 kHz mono 16-bit PCM WAV file.
     * Returns the raw transcript string (may be empty if nothing was heard).
     * Throws [IllegalStateException] if the model assets are missing.
     */
    suspend fun transcribe(wavFile: File): String = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val rec = getOrCreateRecognizer()

        val samples = readWavSamples(wavFile)
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val result = rec.getResult(stream).text.trim()
            val elapsed = System.currentTimeMillis() - start
            Log.i(TAG, "sherpa-onnx transcription: ${result.take(120)} (${elapsed}ms, ${wavFile.length()} bytes)")
            result
        } finally {
            stream.release()
        }
    }

    /**
     * Releases native memory held by the recognizer.
     * Should be called when the owning component is destroyed.
     */
    fun release() {
        recognizer?.release()
        recognizer = null
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private fun getOrCreateRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        return synchronized(this) {
            recognizer ?: buildRecognizer().also { recognizer = it }
        }
    }

    private fun buildRecognizer(): OfflineRecognizer {
        val assetManager = context.assets

        // Verify model assets exist before trying to load — gives a clear error message.
        val requiredAssets = listOf(
            "$ASSET_DIR/base-encoder.int8.onnx",
            "$ASSET_DIR/base-decoder.int8.onnx",
            "$ASSET_DIR/base-tokens.txt",
        )
        for (asset in requiredAssets) {
            runCatching { assetManager.open(asset).close() }.onFailure {
                throw IllegalStateException(
                    "sherpa-onnx model asset missing: $asset\n" +
                    "Download from https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base\n" +
                    "and place the int8 files + tokens.txt under app/src/main/assets/$ASSET_DIR/"
                )
            }
        }

        Log.i(TAG, "Loading sherpa-onnx Whisper Base (int8) from assets/$ASSET_DIR …")
        val loadStart = System.currentTimeMillis()

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$ASSET_DIR/base-encoder.int8.onnx",
                    decoder = "$ASSET_DIR/base-decoder.int8.onnx",
                    // "hi" keeps the multilingual model transcribing in Hindi/English.
                    // Whisper Base (multilingual) auto-detects when language is set to ""
                    // but giving a hint improves Hinglish accuracy.
                    language = "hi",
                    task = "transcribe",
                    // Tail padding prevents the "invalid expand shape" ONNX error that
                    // occurs with consecutive decodes on Android.
                    tailPaddings = 1000,
                ),
                tokens = "$ASSET_DIR/base-tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )

        val rec = OfflineRecognizer(assetManager = assetManager, config = config)
        Log.i(TAG, "Whisper Base loaded in ${System.currentTimeMillis() - loadStart} ms")
        return rec
    }

    /**
     * Reads a 16-bit PCM WAV file and returns normalised float samples in [-1, 1].
     * Skips the 44-byte standard WAV header written by [WavWriter].
     */
    private fun readWavSamples(wavFile: File): FloatArray {
        val bytes = wavFile.readBytes()
        // Standard WAV header is 44 bytes; data starts at offset 44.
        val dataOffset = 44
        val numSamples = (bytes.size - dataOffset) / 2
        val buf = ByteBuffer.wrap(bytes, dataOffset, bytes.size - dataOffset)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val samples = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            samples[i] = buf.get().toFloat() / 32768f
        }
        return samples
    }

    companion object {
        private const val TAG = "SherpaWhisperEngine"
        private const val SAMPLE_RATE = 16000
        private const val ASSET_DIR = "sherpa-onnx-whisper-base"
    }
}
