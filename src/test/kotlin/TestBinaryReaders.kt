import adx.AbstractBinaryReader
import adx.BinaryReader
import adx.parseHex
import adx.toULong
import org.adx.shared.formats.adx.Range
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigInteger
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.*

class TestBinaryReaders {
    companion object {
        private val testData1 = byteArrayOf(
            0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(),
            1, 2, 3, 4, 5)

        private val testData2 = byteArrayOf(
            0xff.toByte(), // 0
            0xca.toByte(), // 1
            0xfe.toByte(), // 2
            0xba.toByte(), // 3
            0xbe.toByte(), // 4
            1,             // 5
            2,             // 6
            3,             // 7
            4,             // 8
            5,             // 9
            6,7,8,9,10,11) // 10

        @JvmStatic
        fun getInstances(): List<AbstractBinaryReader> {
            val orig = BinaryReader(testData2)
            val subReader = orig.region(Range.ofOffsets(1UL, 9UL))!!

            val testFile = Files.createTempFile("binaryreader-", "-temp")
            testFile.writeBytes(testData1)
            val testFile2 = Files.createTempFile("binaryreader-", "-temp2")
            testFile2.writeBytes(testData2)
            val testFile3 = Files.createTempFile("binaryreader-", "-temp3")
            testFile3.writeBytes(testData2)

            val fileReader = BinaryReader(testFile)
            val fileReader2 = BinaryReader(testFile2, Range.ofOffsets(1UL, 9UL))
            val fileReader3 = BinaryReader(testFile3).region(Range.ofOffsets(1UL, 9UL))!!

            return listOf(
                BinaryReader(testData1),
                BinaryReader(testData2, Range.ofOffsets(1UL, 9UL)),
                subReader,
                fileReader,
                fileReader2,
                fileReader3
            )
        }
    }

    @ParameterizedTest
    @MethodSource("getInstances")
    fun `should be able to read byte in sequence`(r: AbstractBinaryReader) {
        assertEquals(0xca.toUByte(), r.readU8())
        assertEquals(0xfe.toUByte(), r.readU8())
        assertEquals(0xba.toUByte(), r.readU8())
        assertEquals(0xbe.toUByte(), r.readU8())
        assertEquals(1.toUByte(), r.readU8())
        assertEquals(2.toUByte(), r.readU8())
        assertEquals(3.toUByte(), r.readU8())
        assertEquals(4.toUByte(), r.readU8())
        assertEquals(5.toUByte(), r.readU8())
        assertNull(r.readU8())
        assertNull(r.readU8())
        assertNull(r.readU8())
    }

    @ParameterizedTest
    @MethodSource("getInstances")
    fun `should be able to read u16 in sequence`(r: AbstractBinaryReader) {
        var v = r.readU16()
        assertNotNull(v)
        assertEquals(0xcafe.toUShort(), v.toBigEndian())
        assertEquals(0xfeca.toUShort(), v.toLittleEndian())

        v = r.readU16()
        assertNotNull(v)
        assertEquals(0xbabe.toUShort(), v.toBigEndian())
        assertEquals(0xbeba.toUShort(), v.toLittleEndian())

        v = r.readU16()
        assertNotNull(v)
        assertEquals(0x0102.toUShort(), v.toBigEndian())
        assertEquals(0x0201.toUShort(), v.toLittleEndian())

        v = r.readU16()
        assertNotNull(v)
        assertEquals(0x0304.toUShort(), v.toBigEndian())
        assertEquals(0x0403.toUShort(), v.toLittleEndian())

        v = r.readU16()
        assertNull(v)
    }

    @ParameterizedTest
    @MethodSource("getInstances")
    fun `should be able to read u32 in sequence`(r: AbstractBinaryReader) {
        var v = r.readU32()
        assertNotNull(v)
        assertEquals(0xcafebabe.toUInt(), v.toBigEndian())
        assertEquals(0xbebafeca.toUInt(), v.toLittleEndian())

        v = r.readU32()
        assertNotNull(v)
        assertEquals(0x01020304.toUInt(), v.toBigEndian())
        assertEquals(0x04030201.toUInt(), v.toLittleEndian())

        v = r.readU32()
        assertNull(v)
    }

    @ParameterizedTest
    @MethodSource("getInstances")
    fun `should be able to read u64 in sequence`(r: AbstractBinaryReader) {
        var v = r.readU64()
        assertNotNull(v)
        assertEquals(BigInteger("cafebabe01020304", 16).toULong(), v.toBigEndian())
        assertEquals(0x04030201bebafeca.toULong(), v.toLittleEndian())

        v = r.readU64()
        assertNull(v)
    }

    @ParameterizedTest
    @MethodSource("getInstances")
    fun `seekRelative should work properly`(r: AbstractBinaryReader) {
        assertEquals(0xCA.toUByte(), r.readU8())
        assertTrue(r.seekRelative(1L))
        assertEquals(0xBA.toUByte(), r.readU8())
        assertTrue(r.seekRelative(-1))
        assertEquals(0xBA.toUByte(), r.readU8())
        assertTrue(r.seekRelative(-3))
        assertEquals(0xCA.toUByte(), r.readU8())
        assertEquals(0xFE.toUByte(), r.readU8())
        assertTrue(r.seekRelative(2))
        assertEquals(0x01.toUByte(), r.readU8())
        assertFalse(r.seekRelative(100))
        assertEquals(0x02.toUByte(), r.readU8())
    }

    @ParameterizedTest
    @MethodSource("getInstances")
    fun `seekAbsolute should work properly`(r: AbstractBinaryReader) {
        assertEquals(0xCA.toUByte(), r.readU8())
        assertTrue(r.seekAbsolute(0UL))
        assertEquals(0xCA.toUByte(), r.readU8())
        assertTrue(r.seekAbsolute(2UL))
        assertEquals(0xBA.toUByte(), r.readU8())
        assertFalse(r.seekAbsolute(100UL))
        assertEquals(0xBE.toUByte(), r.readU8())
    }

    @ParameterizedTest
    @MethodSource("getInstances")
    fun `readBytes should work and fail properly`(r: AbstractBinaryReader) {
        assertNull(r.readBytes(0))
        assertContentEquals("cafeba".parseHex(), r.readBytes(3))
        assertContentEquals("be0102".parseHex(), r.readBytes(3))
        assertContentEquals("0304".parseHex(), r.readBytes(2))
        assertNull(r.readBytes(5))
        assertContentEquals("05".parseHex(), r.readBytes(1))
        assertNull(r.readBytes(1))
    }

    @Test
    fun `regions should work ok`() {
        val testArr = byteArrayOf(1, 2, 3, 4, 5)
        val rdr1 = BinaryReader(testArr)
        rdr1.seekRelative(1)
        val rdr2 = rdr1.region(Range.ofOffsets(1U, 2U))
        assertEquals(0x03U, rdr2?.readU8())
        assertEquals(0x04U, rdr2?.readU8())
        assertEquals(null, rdr2?.readU8())
        assertEquals(null, rdr2?.readU8())
    }

    @Test
    fun `should be able to read whole file`() {
        val data = byteArrayOf(1,2,3,4,5)
        val testFile = Files.createTempFile("binaryreader-", "-temp")
        testFile.writeBytes(data)
        val rdr = BinaryReader(testFile)
        assertContentEquals(data, rdr.readBytes(data.size))
    }
}