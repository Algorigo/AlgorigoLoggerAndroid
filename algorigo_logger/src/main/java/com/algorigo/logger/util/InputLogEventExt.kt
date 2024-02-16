package com.algorigo.logger.util

import com.amazonaws.services.logs.model.InputLogEvent


internal class InputLogEventExt(message: String, timestamp: Long, maxSize: Int = Int.MAX_VALUE) :
    InputLogEvent() {

    internal val size: Int

    init {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val bytesSize = bytes.size + EXTRA_MSG_PAYLOAD_SIZE
        val msg = if (bytesSize > maxSize) {
            size = maxSize
            bytes.sliceArray(0 until (maxSize - EXTRA_MSG_PAYLOAD_SIZE)).toString(Charsets.UTF_8)
        } else {
            size = bytesSize
            message
        }
        withMessage(msg)
        withTimestamp(timestamp)
    }

    companion object {
        private const val EXTRA_MSG_PAYLOAD_SIZE = 26
    }
}
