package com.omni.aiassistant.qa

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A real one second 440 Hz sine wave, written as a 16 bit mono WAV.
 *
 * The WAV header is 44 bytes: RIFF(4) size(4) WAVE(4) fmt(4) 16(4) format(2)
 * channels(2) rate(4) byteRate(4) align(2) bits(2) data(4) size(4). Every
 * 4-byte field is written as four bytes - a shortened header makes the file
 * unreadable for the platform even though the file exists and can be opened.
 */
object QaWavFixture {

    fun write(name: String, directoryName: String = "Music"): File {
        val directory = android.os.Environment.getExternalStoragePublicDirectory(directoryName)
        directory.mkdirs()
        val file = File(directory, name)
        file.writeBytes(tone())
        return file
    }

    fun tone(sampleRate: Int = 8_000, hertz: Double = 440.0): ByteArray {
        val samples = sampleRate
        val dataSize = samples * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        le32(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1) // PCM
        le16(1) // mono
        le32(sampleRate)
        le32(sampleRate * 2)
        le16(2)
        le16(16)
        out.write("data".toByteArray())
        le32(dataSize)
        for (index in 0 until samples) {
            val value = (Math.sin(2.0 * Math.PI * hertz * index / sampleRate) * 20_000).toInt()
            le16(value)
        }
        return out.toByteArray()
    }
}
