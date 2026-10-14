package com.hazbu.xblock

import java.io.File
import java.io.InputStream

/** Compact domain membership filter shared through the API 101 service file. */
class DomainBloom private constructor(private val bits: ByteArray) {
    companion object {
        private const val BYTE_SIZE = 512 * 1024
        private const val HASHES = 4

        fun build(file: File): ByteArray {
            val bits = ByteArray(BYTE_SIZE)
            file.forEachLine { line ->
                val domain = line.trim().lowercase()
                if (domain.contains('.') && !domain.contains('*') && !domain.contains('/')) {
                    add(bits, domain)
                }
            }
            return bits
        }

        fun read(input: InputStream): DomainBloom? {
            val bytes = input.readBytes()
            return if (bytes.size == BYTE_SIZE) DomainBloom(bytes) else null
        }

        private fun add(bits: ByteArray, value: String) {
            val first = hash(value, 0x811c9dc5L)
            val second = hash(value, 0x9e3779b9L) or 1L
            repeat(HASHES) { index ->
                val bit = Math.floorMod(first + index * second, BYTE_SIZE * 8L).toInt()
                bits[bit ushr 3] = (bits[bit ushr 3].toInt() or (1 shl (bit and 7))).toByte()
            }
        }

        private fun hash(value: String, seed: Long): Long {
            var result = seed
            value.forEach { char ->
                result = (result xor char.code.toLong()) * 0x100000001b3L
            }
            return result
        }
    }

    fun mightContain(value: String): Boolean {
        val normalized = value.trim('.').lowercase()
        if (normalized.isEmpty()) return false
        val first = hash(normalized, 0x811c9dc5L)
        val second = hash(normalized, 0x9e3779b9L) or 1L
        return (0 until HASHES).all { index ->
            val bit = Math.floorMod(first + index * second, bits.size * 8L).toInt()
            (bits[bit ushr 3].toInt() and (1 shl (bit and 7))) != 0
        }
    }

    private fun hash(value: String, seed: Long): Long {
        var result = seed
        value.forEach { char ->
            result = (result xor char.code.toLong()) * 0x100000001b3L
        }
        return result
    }
}
