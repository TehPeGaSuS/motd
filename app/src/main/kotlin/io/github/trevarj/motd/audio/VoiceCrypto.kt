package io.github.trevarj.motd.audio

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceEncryptionResult(
    val file: File,
    val keyFragment: String,
    val mimeType: String = "application/vnd.motd.voice",
)

@Singleton
class VoiceCrypto @Inject constructor(
    private val cacheStore: AudioCacheStore,
) {
    private val random = SecureRandom()

    fun encrypt(input: File): VoiceEncryptionResult {
        val keyBytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        val output = cacheStore.tempFile("voice-encrypted-", ".motdvoice")
        output.outputStream().use { out ->
            out.write(MAGIC)
            out.write(nonce)
            input.inputStream().use { source ->
                javax.crypto.CipherOutputStream(out, cipher).use { encrypted ->
                    source.copyTo(encrypted)
                }
            }
        }
        return VoiceEncryptionResult(
            file = output,
            keyFragment = "motd-key=${B64.encodeToString(keyBytes)}",
        )
    }

    fun decrypt(input: File, keyFragment: String): File {
        val key = parseKey(keyFragment)
        input.inputStream().use { source ->
            val header = ByteArray(MAGIC.size)
            require(source.read(header) == MAGIC.size && header.contentEquals(MAGIC)) {
                "Unsupported encrypted voice file."
            }
            val nonce = ByteArray(NONCE_BYTES)
            require(source.read(nonce) == NONCE_BYTES) { "Encrypted voice file is truncated." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(AAD)
            val output = cacheStore.tempFile("voice-decrypted-", ".audio")
            try {
                javax.crypto.CipherInputStream(source, cipher).use { decrypted ->
                    output.outputStream().use { out -> decrypted.copyTo(out) }
                }
            } catch (error: Exception) {
                output.delete()
                throw error
            }
            return output
        }
    }

    private fun parseKey(fragment: String): ByteArray {
        val value = fragment.substringAfter("motd-key=", fragment).substringBefore('&')
        return B64_DECODER.decode(value).also { require(it.size == KEY_BYTES) { "Invalid voice key." } }
    }

    companion object {
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64_DECODER = Base64.getUrlDecoder()
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'O'.code.toByte(), 'T'.code.toByte(), 'D'.code.toByte(), 'V'.code.toByte(), 1)
        private val AAD = "motd-voice-v1".toByteArray(Charsets.UTF_8)
    }
}
