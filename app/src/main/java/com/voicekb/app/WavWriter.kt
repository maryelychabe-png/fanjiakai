package com.voicekb.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 把 16-bit PCM 单声道数据写成标准 WAV 文件。 */
object WavWriter {
    fun write(file: File, pcm: ByteArray, sampleRate: Int) {
        val dataLen = pcm.size
        val totalLen = 36 + dataLen
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalLen)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)                 // PCM 子块大小
            header.putShort(1)                // 音频格式 = PCM
            header.putShort(1)                // 声道数 = 1
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)     // 字节率 = 采样率 * 块对齐
            header.putShort(2)                // 块对齐 = 声道*位深/8
            header.putShort(16)               // 位深
            header.put("data".toByteArray())
            header.putInt(dataLen)
            out.write(header.array())
            out.write(pcm)
        }
    }
}
