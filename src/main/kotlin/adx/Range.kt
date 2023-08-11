package org.adx.shared.formats.adx

import adx.hex
import kotlin.math.max
import kotlin.math.min

class Range(
    private val theOffset: ULong,
    private val theLength: ULong
) {
    companion object {
        fun ofOffsetsExclusive(offset: ULong, endOffsetExclusive: ULong): Range {
            return Range(offset, endOffsetExclusive - offset)
        }

        fun ofOffsets(offset: ULong, endOffsetInclusive: ULong): Range {
            return Range(offset, endOffsetInclusive - offset + 1.toULong())
        }

        fun ofUnlimited(): Range {
            return Range(0UL, ULong.MAX_VALUE)
        }
    }

    constructor(offset: Int, length: Int) : this(offset.toULong(), length.toULong())

    val offset: ULong get() = theOffset
    val length: ULong get() = theLength
    val endOffset: ULong get() = offset + length - 1.toULong()

    fun contains(otherOffset: ULong, otherLength: ULong): Boolean =
        (offset <= otherOffset) &&
        ((offset + length) >= (otherOffset + otherLength))

    fun contains(otherRange: Range): Boolean =
        contains(otherRange.offset, otherRange.length)

    fun contains(offset: ULong): Boolean = contains(offset, 1.toULong())

    fun isContainedBy(otherRange: Range): Boolean = otherRange.contains(this)

    fun isContainedBy(otherOffset: ULong, otherLength: ULong): Boolean =
        Range(otherOffset, otherLength).contains(this)

    fun overlapsWith(other: Range): Boolean {
        return (offset in other.offset .. other.endOffset) ||
               (other.offset in offset..endOffset)
    }

    fun isDisjointWith(other: Range): Boolean = !overlapsWith(other)

    fun sameStart(other: Range): Boolean = offset == other.offset
    fun sameEnd(other: Range): Boolean = endOffset == other.endOffset

    fun neighborWith(other: Range): Boolean =
        (offset == other.endOffset + 1.toULong()) ||
        (endOffset == other.offset - 1.toULong())

    fun joinable(other: Range): Boolean = overlapsWith(other) || neighborWith(other)
    fun joinable(offset: ULong): Boolean {
        return ((offset + 1.toULong()) == offset ||
                (offset - 1.toULong()) == endOffset)
    }

    fun join(other: Range): Range {
        val newOffset = min(offset, other.offset)
        val newEndOffset = max(endOffset, other.endOffset)
        val newLen = newEndOffset - newOffset + 1.toULong()
        return Range(newOffset, newLen)
    }

    fun commonWith(other: Range): Range {
        val newOffset = max(offset, other.offset)
        val newEndOffset = min(endOffset, other.endOffset)
        val newLen = newEndOffset - newOffset + 1.toULong()
        return Range(newOffset, newLen)
    }

    fun join(offset: ULong): Range = join(Range(offset, 1.toULong()))

    override operator fun equals(other: Any?): Boolean =
        if (other == null || other !is Range) false
        else offset == other.offset && length == other.length

    override fun toString(): String =
        "Range[offset=${offset.hex}-${endOffset.hex} (${length.hex})]"
}