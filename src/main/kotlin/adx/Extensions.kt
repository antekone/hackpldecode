package adx

import org.apache.commons.codec.binary.Hex
import java.math.BigInteger

val ULong.hex get(): String {
    return "$${toString(16).padStart(8, padChar='0').uppercase()}"
}

val UByte.hex get(): String {
    return "$${toString(16).padStart(2, padChar='0').uppercase()}"
}

val UShort.hex get(): String {
    return "$${toString(16).padStart(4, padChar='0').uppercase()}"
}

val Short.hex get(): String {
    return toUShort().hex
}

val UInt.hex get(): String {
    return "$${toString(16).padStart(8, padChar='0').uppercase()}"
}

val Int.hex get(): String {
    return "$${toString(16).padStart(8, padChar='0').uppercase()}"
}

val ByteArray.hex get(): String {
    return Hex.encodeHexString(this)
}

val Boolean.hex get(): String {
    return if (this) { 1.toUByte().hex } else { 0.toUByte().hex }
}

fun BigInteger.toULong(): ULong {
    return this.toLong().toULong()
}

fun String.parseHex(): ByteArray {
    return Hex.decodeHex(this)
}