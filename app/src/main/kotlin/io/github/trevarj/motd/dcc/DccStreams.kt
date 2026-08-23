package io.github.trevarj.motd.dcc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

internal class DccTransferIntegrityException(
    message: String,
) : IllegalStateException(message)

internal suspend fun receiveDccBytes(
    input: InputStream,
    output: OutputStream,
    ack: OutputStream,
    expectedBytes: Long?,
    maxBytes: Long,
    progressStepBytes: Long,
    persistProgress: suspend (Long) -> Unit,
): Long =
    withContext(Dispatchers.IO) {
        require(maxBytes >= 0) { "DCC maximum size must be non-negative" }
        require(progressStepBytes > 0) { "DCC progress step must be positive" }
        require(expectedBytes == null || expectedBytes >= 0) { "DCC expected size must be non-negative" }

        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var lastPersisted = 0L

        suspend fun persistIfChanged() {
            if (total != lastPersisted) {
                lastPersisted = total
                persistProgress(total)
            }
        }
        while (expectedBytes == null || total < expectedBytes) {
            val remainingSafety = maxBytes - total
            if (remainingSafety <= 0L) {
                if (input.read() >= 0) {
                    persistIfChanged()
                    throw DccTransferIntegrityException("Transfer exceeded the DCC size limit")
                }
                break
            }
            val remainingExpected = expectedBytes?.minus(total) ?: Long.MAX_VALUE
            val limit = minOf(buffer.size.toLong(), remainingExpected, remainingSafety).toInt()
            val read = input.read(buffer, 0, limit)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
            ack.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(total.toInt()).array())
            if (total - lastPersisted >= progressStepBytes) {
                persistIfChanged()
            }
        }
        output.flush()
        persistIfChanged()
        if (expectedBytes != null && total < expectedBytes) {
            throw DccTransferIntegrityException("Transfer ended after $total of $expectedBytes bytes")
        }
        total
    }
