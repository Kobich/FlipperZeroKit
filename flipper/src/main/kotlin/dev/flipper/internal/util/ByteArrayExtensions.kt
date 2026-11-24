package dev.flipper.internal.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun ByteArray.toInt(byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN): Int? = when (size) {
    1 -> this[0].toUByte().toInt()
    2 -> ByteBuffer.wrap(this).order(byteOrder).short.toInt()
    4 -> ByteBuffer.wrap(this).order(byteOrder).int
    else -> null
}
