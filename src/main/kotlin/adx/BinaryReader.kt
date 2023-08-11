package adx

import org.adx.shared.formats.adx.Range
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Path

object BinaryReaderError {
    sealed class Base(msg: String) : IOException(msg)
    class IO(msg: String) : Base(msg)
    class OffsetOverflow(msg: String) : Base(msg)
    class RangeOutsideOfBuffer(msg: String) : Base(msg)
    class Read(msg: String) : Base(msg)
}

sealed class ByteOrderAwareValue<T>(
    protected val rawValue: T,
    private val littleEndianByDefault: Boolean = false
) {
    abstract fun toLittleEndian(): T

    fun toBigEndian(): T {
        // Normally values on JVM are all big endian, so this is always a no-op
        return rawValue()
    }

    fun toDefaultEndian(): T {
        return if (littleEndianByDefault)
            toLittleEndian()
        else
            toBigEndian()
    }

    fun rawValue(): T { return rawValue; }

    // Aliases
    fun le(): T { return toLittleEndian() }
    fun be(): T { return toBigEndian() }
    fun little(): T { return toLittleEndian() }
    fun big(): T { return toBigEndian() }
    fun default(): T { return toDefaultEndian() }
}

class UShortValue(
    rawValue: UShort,
    littleEndianByDefault: Boolean
) : ByteOrderAwareValue<UShort>(rawValue, littleEndianByDefault) {
    override fun toLittleEndian(): UShort {
        val v = rawValue.toInt() and 0xffff
        val a = (v and 0xff00) shr 8
        val b = (v and 0xff)
        return ((b shl 8) or a).toUShort()
    }
}

class UIntValue(
    rawValue: UInt,
    littleEndianByDefault: Boolean
) : ByteOrderAwareValue<UInt>(rawValue, littleEndianByDefault) {
    override fun toLittleEndian(): UInt {
        val a = (rawValue shr 0x18) and 0xff.toUInt()
        val b = (rawValue shr 0x10) and 0xff.toUInt()
        val c = (rawValue shr 0x08) and 0xff.toUInt()
        val d = rawValue and 0xff.toUInt()
        return ((d shl 0x18) or (c shl 0x10) or (b shl 8) or a)
    }
}

class ULongValue(
    rawValue: ULong,
    littleEndianByDefault: Boolean
) : ByteOrderAwareValue<ULong>(rawValue, littleEndianByDefault) {
    override fun toLittleEndian(): ULong {
        val a = (rawValue shr (8*7)) and 0xff.toULong()
        val b = (rawValue shr (8*6)) and 0xff.toULong()
        val c = (rawValue shr (8*5)) and 0xff.toULong()
        val d = (rawValue shr (8*4)) and 0xff.toULong()
        val e = (rawValue shr (8*3)) and 0xff.toULong()
        val f = (rawValue shr (8*2)) and 0xff.toULong()
        val g = (rawValue shr (8*1)) and 0xff.toULong()
        val h = (rawValue shr (8*0)) and 0xff.toULong()
        return (
            (h shl (8*7)) or
            (g shl (8*6)) or
            (f shl (8*5)) or
            (e shl (8*4)) or
            (d shl (8*3)) or
            (c shl (8*2)) or
            (b shl (8*1)) or
            (a shl (8*0))
        )
    }
}

class ThrowingAbstractBinaryReader(private val abs: AbstractBinaryReader) {
    private fun readError(msg: String): Nothing {
        throw BinaryReaderError.Read("read error: $msg, position=${position()}")
    }

    fun readU8(): UByte =
        abs.readU8() ?: readError("readU8")

    fun readU16(): UShortValue =
        abs.readU16() ?: readError("readU16")

    fun readU32(): UIntValue =
        abs.readU32() ?: readError("readU32")

    fun readU64(): ULongValue =
        abs.readU64() ?: readError("readU64")

    fun readBytes(howMuch: Int): ByteArray =
        abs.readBytes(howMuch) ?: readError("readBytes(${howMuch})")

    fun region(subRange: Range): ThrowingAbstractBinaryReader {
        return ThrowingAbstractBinaryReader(abs.region(subRange) ?: throw BinaryReaderError.RangeOutsideOfBuffer(subRange.toString()))
    }

    fun available(): Int =
        abs.available()

    fun seekRelative(offset: Long): Boolean =
        abs.seekRelative(offset)

    fun seekAbsolute(newPosition: ULong): Boolean =
        abs.seekAbsolute(newPosition)

    fun position(): ULong =
        abs.position()

    fun setLittleEndianByDefault() =
        abs.setLittleEndianByDefault()

    fun setBigEndianByDefault() =
        abs.setBigEndianByDefault()

    fun clone() = ThrowingAbstractBinaryReader(abs.clone())
}

interface AbstractBinaryReader: AutoCloseable {
    fun readU8(): UByte?
    fun readU16(): UShortValue?
    fun readU32(): UIntValue?
    fun readU64(): ULongValue?
    fun readBytes(howMuch: Int): ByteArray?
    fun region(subRange: Range): AbstractBinaryReader?
    fun available(): Int
    fun seekRelative(offset: Long): Boolean
    fun seekAbsolute(newPosition: ULong): Boolean
    fun position(): ULong
    fun currentRange(): Range
    fun setLittleEndianByDefault()
    fun setBigEndianByDefault()
    fun clone(): AbstractBinaryReader
}

interface RandomAccessor: AutoCloseable {
    fun size(): ULong
    fun getU8(offset: ULong): UByte?
    fun getU16(offset: ULong): UShort?
    fun getU32(offset: ULong): UInt?
    fun getU64(offset: ULong): ULong?
    fun copyOfRange(range: Range): ByteArray?
}

class FileRandomAccessor(private val raf: RandomAccessFile, private val overridenRange: Range? = null) : RandomAccessor {
    private val threadLocalBuffer1 = ThreadLocal.withInitial { ByteBuffer.allocate(1) }
    private val threadLocalBuffer2 = ThreadLocal.withInitial { ByteBuffer.allocate(2) }
    private val threadLocalBuffer4 = ThreadLocal.withInitial { ByteBuffer.allocate(4) }
    private val threadLocalBuffer8 = ThreadLocal.withInitial { ByteBuffer.allocate(8) }

    override fun toString(): String {
        return "FileRandomAccessor[path=${raf}]"
    }

    override fun close() {
        raf.close()
    }

    override fun size(): ULong {
        return overridenRange?.length ?: raf.length().toULong()
    }

    override fun getU8(offset: ULong): UByte? {
        return try {
            val buf = threadLocalBuffer1.get()
            buf.clear()
            if (buf.array().size != raf.channel.read(buf, offset.toLong()))
                return null
            ByteArrayRandomAccessor(buf.array()).getU8(0UL)
        } catch (e: IOException) {
            null
        }
    }

    override fun getU16(offset: ULong): UShort? {
        return try {
            val buf = threadLocalBuffer2.get()
            buf.clear()
            if (buf.array().size != raf.channel.read(buf, offset.toLong()))
                return null
            ByteArrayRandomAccessor(buf.array()).getU16(0UL)
        } catch (e: IOException) {
            null
        }
    }

    override fun getU32(offset: ULong): UInt? {
        return try {
            val buf = threadLocalBuffer4.get()
            buf.clear()
            if (buf.array().size != raf.channel.read(buf, offset.toLong()))
                return null
            ByteArrayRandomAccessor(buf.array()).getU32(0UL)
        } catch (e: IOException) {
            null
        }
    }

    override fun getU64(offset: ULong): ULong? {
        return try {
            val buf = threadLocalBuffer8.get()
            buf.clear()
            if (buf.array().size != raf.channel.read(buf, offset.toLong()))
                return null
            ByteArrayRandomAccessor(buf.array()).getU64(0UL)
        } catch (e: IOException) {
            null
        }
    }

    override fun copyOfRange(range: Range): ByteArray? {
        return try {
            val buf = ByteBuffer.allocate(range.length.toInt())
            if (range.length.toInt() != raf.channel.read(buf, range.offset.toLong()))
                return null
            buf.array()
        } catch (e: IOException) {
            null
        }
    }
}

class ByteArrayRandomAccessor(private val data: ByteArray) : RandomAccessor {
    override fun toString(): String {
        return "ByteArrayRandomAccessor[data.size=${data.size}]"
    }

    override fun close() {}

    override fun size(): ULong {
        return data.size.toULong()
    }

    override fun copyOfRange(range: Range): ByteArray? {
        // TODO: range check is missing
        return data.copyOfRange(range.offset.toInt(), range.endOffset.toInt() + 1)
    }

    override fun getU8(offset: ULong): UByte? {
        val rawOffset = offset.toInt()
        return if (rawOffset >= 0 && rawOffset < data.size) {
            data[rawOffset].toUByte()
        } else {
            null
        }
    }

    override fun getU16(offset: ULong): UShort? {
        val rawOffset = offset.toInt()
        return if (rawOffset >= 0 && (rawOffset + 1) < data.size) {
            val byte1 = data[rawOffset].toInt() and 0xFF
            val byte2 = data[rawOffset + 1].toInt() and 0xFF

            ((byte1 shl 8) or byte2).toUShort()
        } else {
            null
        }
    }

    override fun getU32(offset: ULong): UInt? {
        val rawOffset = offset.toInt()
        return if (rawOffset >= 0 && (rawOffset + 3) < data.size) {
            val byte1 = data[rawOffset].toInt() and 0xFF
            val byte2 = data[rawOffset + 1].toInt() and 0xFF
            val byte3 = data[rawOffset + 2].toInt() and 0xFF
            val byte4 = data[rawOffset + 3].toInt() and 0xFF

            ((byte1 shl 24) or (byte2 shl 16) or (byte3 shl 8) or byte4).toUInt()
        } else {
            null
        }
    }

    override fun getU64(offset: ULong): ULong? {
        val rawOffset = offset.toInt()
        return if (rawOffset >= 0 && (rawOffset + 7) < data.size) {
            val byte1 = data[rawOffset].toULong() and 0xFF.toULong()
            val byte2 = data[rawOffset + 1].toULong() and 0xFFUL
            val byte3 = data[rawOffset + 2].toULong() and 0xFFUL
            val byte4 = data[rawOffset + 3].toULong() and 0xFFUL
            val byte5 = data[rawOffset + 4].toULong() and 0xFFUL
            val byte6 = data[rawOffset + 5].toULong() and 0xFFUL
            val byte7 = data[rawOffset + 6].toULong() and 0xFFUL
            val byte8 = data[rawOffset + 7].toULong() and 0xFFUL

            (byte1 shl (8*7)) or (byte2 shl (8*6)) or (byte3 shl (8*5)) or
            (byte4 shl (8*4)) or (byte5 shl (8*3)) or (byte6 shl (8*2)) or
            (byte7 shl (8*1)) or (byte8 shl (8*0))
        } else {
            null
        }
    }
}

class BinaryReader(
    private val data: RandomAccessor,
    private val range: Range? = null
) : AbstractBinaryReader {
    constructor(data: ByteArray, range: Range? = null) :
            this(ByteArrayRandomAccessor(data), range)
    constructor(file: RandomAccessFile, range: Range? = null) :
            this(FileRandomAccessor(file), range)
    constructor(file: File, range: Range? = null, dataRange: Range? = null) :
            this(FileRandomAccessor(RandomAccessFile(file, "r"), dataRange), range)
    constructor(path: Path, range: Range? = null, dataRange: Range? = null) :
            this(path.toFile(), range, dataRange)

    init {
        if (range != null) {
            if (range.offset >= data.size() || range.endOffset >= data.size()) {
                throw BinaryReaderError.RangeOutsideOfBuffer("tried to use an invalid range")
            }
        }
    }

    private var position: ULong = 0UL
    private val rangeOffset: ULong = range?.offset ?: 0UL
    private val rangeEndOffset: ULong = range?.endOffset ?: data.size().toULong()
    private var littleEndianByDefault = false

    override fun clone(): BinaryReader {
        return BinaryReader(data, range)
    }

    private fun advance(howMuch: Int) {
        val howMuchAsULong = howMuch.toULong()
        val rawOffset = position + rangeOffset

        if (ULong.MAX_VALUE - rawOffset < howMuchAsULong) {
            // This case is an internal error, and should never happen.
            throw BinaryReaderError.OffsetOverflow(
                "can't advance the reader further; offset overflow occured"
            )
        }

        // Offset outside of data's bounds is not a fatal error; it will be
        // reported by the read*() functions (they'll simply return nulls).

        position += howMuchAsULong
    }

    override fun currentRange(): Range = Range(rangeOffset, rangeEndOffset)

    private fun rangeOfNextBytes(howMuch: ULong): Range? {
        val requestedRange = Range(position + rangeOffset, howMuch)
        return if (!currentRange().contains(requestedRange))
            null
        else {
            if ((available().toULong() + 1U) >= howMuch) {
                requestedRange
            } else null
        }
    }

    private fun rangeOfNextBytes(howMuch: Long): Range? =
        rangeOfNextBytes(howMuch.toULong())

    private fun rangeOfNextBytes(howMuch: Int): Range? =
        rangeOfNextBytes(howMuch.toULong())

    override fun setLittleEndianByDefault() { littleEndianByDefault = true }
    override fun setBigEndianByDefault() { littleEndianByDefault = false }

    override fun region(subRange: Range): AbstractBinaryReader? {
        if (range != null) {
            if (!range.contains(subRange))
                return null
        }

        val r = Range.ofOffsets(
            (range?.offset ?: 0UL) + subRange.offset + position(),
            (range?.endOffset ?: 0UL) + subRange.endOffset + position())

        if (available() < r.length.toInt())
            return null

        return BinaryReader(data, r)
    }

    override fun readU8(): UByte? {
        val range = rangeOfNextBytes(1) ?: return null
        val result = data.getU8(range.offset) ?: return null
        advance(1)
        return result
    }

    override fun readU16(): UShortValue? {
        val range = rangeOfNextBytes(2) ?: return null
        val result = data.getU16(range.offset) ?: return null
        advance(2)
        return UShortValue(result, littleEndianByDefault)
    }

    override fun readU32(): UIntValue? {
        val range = rangeOfNextBytes(4) ?: return null
        val result = data.getU32(range.offset) ?: return null
        advance(4)
        return UIntValue(result, littleEndianByDefault)
    }

    override fun readU64(): ULongValue? {
        val range = rangeOfNextBytes(8) ?: return null
        val result = data.getU64(range.offset) ?: return null
        advance(8)
        return ULongValue(result, littleEndianByDefault)
    }

    override fun readBytes(howMuch: Int): ByteArray? {
        if (howMuch == 0) return null
        val requestedRawRange = rangeOfNextBytes(howMuch) ?: return null
        val result = data.copyOfRange(requestedRawRange)
        advance(howMuch)
        return result;
    }

    override fun available(): Int = (rangeEndOffset - rangeOffset - position).toInt()

    override fun seekRelative(offset: Long): Boolean {
        if (offset < 0L) {
            if ((-offset).toULong() > position) {
                return false
            }
        } else if (offset > 0L) {
            if (offset.toULong() > (ULong.MAX_VALUE - position)) {
                return false
            }
        } else {
            return false
        }

        val rawOffset = position + offset.toULong()
        return seekAbsolute(rawOffset)
    }

    override fun seekAbsolute(newPosition: ULong): Boolean {
        val requestedPoint = newPosition + rangeOffset
        if (requestedPoint > rangeEndOffset)
            return false

        position = newPosition
        return true
    }

    override fun position(): ULong {
        return position
    }

    override fun toString(): String {
        return "BinaryReader[data=${data},range=${range}]"
    }

    override fun close() {
        data.close()
    }
}