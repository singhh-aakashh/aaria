package com.aaria.app.recording

import java.io.File
import java.io.RandomAccessFile

/**
 * Writes 16-bit mono PCM to a file and completes it with a WAV header on [finish].
 * Uses a temp file for raw PCM, then writes the final WAV with header to [outputFile].
 */
object WavWriter {

    private const val SAMPLE_RATE = 16000
    private const val BITS_PER_SAMPLE = 16
    private const val NUM_CHANNELS = 1
    private const val BYTES_PER_SAMPLE = 2
    private const val HEADER_SIZE = 44

    fun createWavFile(outputFile: File): WavOutputStream {
        return WavOutputStream(outputFile)
    }

    private fun prependWavHeader(rawPcmFile: File, wavFile: File, numSamples: Int) {
        val dataSize = numSamples * BYTES_PER_SAMPLE
        val fileSize = HEADER_SIZE + dataSize
        rawPcmFile.inputStream().use { input ->
            wavFile.outputStream().use { output ->
                writeHeader(output, fileSize, dataSize)
                input.copyTo(output)
            }
        }
    }

    private fun writeHeader(out: java.io.OutputStream, fileSize: Int, dataSize: Int) {
        val byteRate = SAMPLE_RATE * NUM_CHANNELS * BYTES_PER_SAMPLE
        val blockAlign = (NUM_CHANNELS * BITS_PER_SAMPLE / 8).toShort()
        out.write("RIFF".toByteArray())
        out.write(intToLittleEndian(fileSize - 8))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToLittleEndian(16))
        out.write(shortToLittleEndian(1))
        out.write(shortToLittleEndian(NUM_CHANNELS.toShort()))
        out.write(intToLittleEndian(SAMPLE_RATE))
        out.write(intToLittleEndian(byteRate))
        out.write(shortToLittleEndian(blockAlign))
        out.write(shortToLittleEndian(BITS_PER_SAMPLE.toShort()))
        out.write("data".toByteArray())
        out.write(intToLittleEndian(dataSize))
    }

    private fun intToLittleEndian(v: Int): ByteArray {
        return byteArrayOf(
            (v and 0xff).toByte(),
            (v shr 8 and 0xff).toByte(),
            (v shr 16 and 0xff).toByte(),
            (v shr 24 and 0xff).toByte()
        )
    }

    private fun shortToLittleEndian(v: Short): ByteArray {
        val i = v.toInt() and 0xffff
        return byteArrayOf((i and 0xff).toByte(), (i shr 8 and 0xff).toByte())
    }

    class WavOutputStream(private val outputFile: File) {
        private val tempRaw = File(outputFile.parent, "aaria_raw_${System.currentTimeMillis()}.pcm")
        private var totalSamples = 0
        private val buffer = java.io.FileOutputStream(tempRaw)

        fun write(pcm: ShortArray) {
            for (s in pcm) {
                buffer.write(s.toInt() and 0xff)
                buffer.write(s.toInt() shr 8 and 0xff)
            }
            totalSamples += pcm.size
        }

        fun finish() {
            buffer.close()
            prependWavHeader(tempRaw, outputFile, totalSamples)
            tempRaw.delete()
        }

        fun cancel() {
            buffer.close()
            tempRaw.delete()
        }
    }
}
