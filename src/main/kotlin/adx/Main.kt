package adx

import org.adx.shared.formats.adx.Range
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.math.min
import kotlin.system.exitProcess

private fun Boolean.toInt(): Int = if (this) { 1 } else { 0 }
private fun ByteArray.reader() = ThrowingAbstractBinaryReader(BinaryReader(this))
private fun ByteBuffer.reader() = array().reader()
private fun ByteArray.reverse(): ByteArray {
    val out = ByteArrayOutputStream()
    for (i in indices) {
        out.write(this[size - i - 1].toInt())
    }
    return out.toByteArray()
}

sealed class BaseException(msg: String) : RuntimeException(msg)
class MZException(msg: String) : BaseException(msg)
class FormatException(msg: String) : BaseException(msg)

class DOSOffset(
    private val offset_: UShort,
    private val segment_: UShort,
) {
    val absoluteOffset get(): UInt = segment * 16U + offset
    val offset get(): UInt = offset_.toUInt()
    val segment get(): UInt = segment_.toUInt()

    fun base(): DOSOffset {
        return DOSOffset(0.toUShort(), segment_)
    }

    override fun toString(): String {
        return "%04X:%04X".format(segment.toLong() and 0xffff, offset.toLong() and 0xffff)
    }

    companion object {
        fun fromSegmentOffset(seg: UShort, offs: UShort) = DOSOffset(offs, seg)
    }
}

class MZExecutable() {
    val relocs: MutableList<DOSOffset> = mutableListOf()

    lateinit var header: ByteBuffer
    lateinit var body: ByteBuffer
    lateinit var overlay: ByteBuffer
    lateinit var relocBin: ByteBuffer

    lateinit var entryPointOffset: DOSOffset
    lateinit var stackOffset: DOSOffset
    var minAlloc: UShort = 0U
    var maxAlloc: UShort = 0U

    fun addReloc(segment: UShort, offset: UShort) {
        relocs.add(DOSOffset(offset, segment))
    }

    fun buildRelocs() {
        relocBin = ByteBuffer.allocate(relocs.size * 4)
        relocBin.order(ByteOrder.LITTLE_ENDIAN)
        for (r in relocs) {
            relocBin.putShort(r.offset.toShort())
            relocBin.putShort(r.segment.toShort())
        }
    }

    fun alignTo(value: Int, align: Int): Int {
        return value + 16 - (value % align)
    }

    fun buildHeader() {
        val minHeaderSize = alignTo(0x1C + relocBin.capacity(), 16)
        header = ByteBuffer.allocate(minHeaderSize)

        val wholeImageSize = header.capacity() + body.capacity()
        val pages = 1 + (wholeImageSize / 512) // including the final block
        val lastPage = wholeImageSize % 512

        header.order(ByteOrder.LITTLE_ENDIAN)
        header.put('M'.code.toByte())
        header.put('Z'.code.toByte())
        header.putShort(lastPage.toShort())
        header.putShort(pages.toShort())
        header.putShort(relocs.size.toShort())
        header.putShort((header.capacity() / 16).toShort())
        header.putShort(0x1234)
        header.putShort(0x4321)
        header.putShort(stackOffset.segment.toShort())
        header.putShort(stackOffset.offset.toShort())
        header.putShort(0)
        header.putShort(entryPointOffset.offset.toShort())
        header.putShort(entryPointOffset.segment.toShort())
        header.putShort(0x1C)
        header.putShort(0)
        header.put(relocBin.array())
    }

    fun render(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header.array())
        out.write(body.array())
        return out.toByteArray()
    }

    fun headerData(): ByteArray = header.array()
    fun headerDataReader() = ThrowingAbstractBinaryReader(BinaryReader(headerData()))

    fun bodyData(): ByteArray = body.array()
    fun bodyDataReader() = ThrowingAbstractBinaryReader(BinaryReader(bodyData()))

    fun entryPoint() = entryPointOffset
    fun stack() = stackOffset

    companion object {
        private fun ioError(): Nothing {
            throw MZException("I/O error")
        }

        fun fromBinaryReader(nr: BinaryReader): MZExecutable {
            val r = ThrowingAbstractBinaryReader(nr)

            r.setLittleEndianByDefault()
            val magic = r.readU16().default()
            if (magic.toUInt() != 0x5a4dU)
                throw MZException("invalid magic")

            val lastPageBytes = r.readU16().default()
            val pages = r.readU16().default()
            val relocCount = r.readU16().default()
            val headerSize = r.readU16().default().toUInt() * 16U
            val minAlloc = r.readU16().default()
            val maxAlloc = r.readU16().default()
            val initRelSS = r.readU16().default()
            val initSP = r.readU16().default()
            val checksum = r.readU16().default()
            val initIP = r.readU16().default()
            val initRelCS = r.readU16().default()
            val relocTableOffset = r.readU16().default()
            val overlay = r.readU16().default()
            if (overlay.toUInt() != 0U) {
                throw MZException("executable not supported")
            }

            val headerDataSize = (headerSize - r.position()).toInt()
            if (headerDataSize < 0) {
                throw MZException("invalid header size")
            }

            val headerData = r.readBytes(headerDataSize)
            val fileSize = pages.toUInt() * 512U + lastPageBytes.toUInt()
            val bodySize = min(r.available() - 1, (fileSize - headerSize).toInt())

            val bodyData = r.readBytes(bodySize)

            val mz = MZExecutable().apply {
                stackOffset = DOSOffset.fromSegmentOffset(initRelSS, initSP)
                entryPointOffset = DOSOffset.fromSegmentOffset(initRelCS, initIP)
                this.minAlloc = minAlloc
                this.maxAlloc = maxAlloc
                body = ByteBuffer.wrap(bodyData)
                header = ByteBuffer.wrap(headerData)
            }

            return mz
        }
    }
}

class HackplBitStream(private val input: ThrowingAbstractBinaryReader) {
    private var bitCache: Short = 0
    private var bitCacheSize = 0
    private var iter = 0
    private var pos = 0

    init { fill() }

    override fun toString(): String {
        return "BitStream[pos=$pos,bitCache=${bitCache.hex},bitCacheSize=$bitCacheSize]"
    }

    fun isEmpty(): Boolean {
        return input.available() == 0 && bitCacheSize == 0
    }

    private fun fill() {
        val a = input.readU16().le()
        pos += 2
        bitCache = a.toShort()
        bitCacheSize = 16
    }

    fun getBit(): Boolean {
        val one = bitCache < 0
        bitCache = (bitCache * 2).toShort()
        bitCacheSize--
        iter++

        if (bitCacheSize == 0 && !isEmpty()) {
            fill()
        }

        return one
    }

    fun getBits(n: Int): Int {
        var out = 0
        for (i in 0 ..< n) {
            out = (out shl 1) or getBit().toInt()
        }
        return out
    }

    fun getDataByte(): Int {
        val b = input.readU8()
        pos++
        return b.toInt()
    }

    fun getDataWord(): Int {
        val a = input.readU16().le()
        pos += 2
        return a.toInt()
    }
}

class HackplExecutable(val mz: MZExecutable) {
    private var checksumsValidated = false
    lateinit var oepSSSP: DOSOffset
    lateinit var oepCSIP: DOSOffset

    init {
        validate()
    }

    fun extractOEPMeta() {
        val rdr = mz.bodyDataReader()
        val base = mz.entryPoint().base().absoluteOffset.toULong() + 4U
        rdr.seekAbsolute(base)

        val oepSP = rdr.readU16().le()
        val oepRelSS = rdr.readU16().le()
        val oepIP = rdr.readU16().le()

        oepSSSP = DOSOffset.fromSegmentOffset(oepRelSS, oepSP)
        oepCSIP = DOSOffset.fromSegmentOffset(0U, oepIP)
    }

    fun calcUncompressedMemoryNeeded(): Int {
        var totalBytesNeeded = 0
        for ((blockType, reader) in embeddedBlock()) {
            if (blockType == 1.toUByte()) {
                reader.seekRelative(26L)
                val uncompressedSize = reader.readU16().le()
                totalBytesNeeded += uncompressedSize.toInt()
            }
        }

        return totalBytesNeeded
    }

    private fun validate() {
        // Issue #003 doesn't conform to this -- or maybe I only have some
        // version that has been tampered with?

//        if (mz.headerData().size != 4 || !mz.headerData().contentEquals(Hex.decodeHex("FACECAFE"))) {
//            throw FormatException("Header should be 4 bytes, and should contain the FACECAFE tag")
//        }
    }

    private fun tcpChecksum(chunk: ThrowingAbstractBinaryReader): UShort {
        var bl: Int = 0
        var bh: Int = 0

        while (chunk.available() > 0) {
            val byte = chunk.readU8().toInt()
            bl += byte
            if (bl > 0xFF) {
                bl++
                bl = bl and 0xff
            }
            bh += bl
            if (bh > 0xff) {
                bh++
                bh = bh and 0xff
            }
        }

        return (bh shl 8 or bl).toUShort()
    }

    fun embeddedBlock() = sequence {
        val rdr = ThrowingAbstractBinaryReader(BinaryReader(mz.bodyData()))
        rdr.setLittleEndianByDefault()
        rdr.seekRelative(6)

        while (true) {
            val blockType = rdr.readU8()
            if (blockType.toInt() == 0)
                break

            val blockChecksum = rdr.readU16().default()
            val blockSize = rdr.readU16().default()
            rdr.seekRelative(-2)

            val blockReader = rdr.region(Range.ofOffsets(0U, blockSize.toULong()))
            rdr.seekRelative(blockSize.toLong())

            if (!checksumsValidated) {
                val calculated = tcpChecksum(blockReader.clone())
                if (calculated != blockChecksum) {
                    throw FormatException("block checksum failed: expected ${blockChecksum.hex}, but got ${calculated.hex}")
                }
            }

            yield(Pair(blockType, blockReader))
        }

        checksumsValidated = true
    }

    fun decompressBlock(rdr: ThrowingAbstractBinaryReader, scratch: ByteBuffer): Int {
        val scratchPos = scratch.position()

        val array1 = rdr.readBytes(8)
        val array2 = rdr.readBytes(0x10)

        val bits = HackplBitStream(rdr)
        val stopLength = bits.getBits(16)

        while ((scratch.position() - scratchPos) < stopLength) {
            var one = bits.getBit()
            if (one) {
                var numberOfOnes = 0
                for (i in 0 ..< 7) {
                    one = bits.getBit()
                    if (!one) {
                        break
                    }
                    numberOfOnes++
                }

                val index = if (bits.getBit()) (2 * numberOfOnes + 1) else (2 * numberOfOnes)
                var b = array2[index].toInt()
                if (b == 0x00) {
                    val dataByte = bits.getDataByte()
                    val value = dataByte - scratch.position()
                    if (value >= 0) {
                        throw RuntimeException("EXE is corrupted")
                    }

                    val sourcePtr = -value

                    for (i in 0 ..< 2) {
                        val byte = scratch[sourcePtr + i]
                        scratch.put(byte)
                    }

                    continue
                } else if (b == 0x0F) {
                    val bit = bits.getBit()
                    b += if (bit) {
                        val dataByte = bits.getDataByte()
                        if (dataByte == 0xff) {
                            // read and use 2 bytes instead of 1
                            // this case actually never gets hit
                            TODO("b==0x0F, bit==1, dataByte=0xFF")
                        } else {
                            dataByte
                        }
                    } else {
                        val dataBits = bits.getBits(4)
                        dataBits
                    }
                }

                val twoBits = bits.getBits(2)
                val array1Index = if (twoBits > 0) {
                    val thirdBit = bits.getBit().toInt()
                    val threeBits = ((twoBits shl 1) or thirdBit) - 1
                    if (threeBits > 5) {
                        var result = threeBits shl 1
                        result = result or bits.getBit().toInt()
                        result - 6
                    } else {
                        threeBits
                    }
                } else {
                    0
                }

                val array1Value = array1[array1Index].toInt()
                var add = 0
                if (array1Value > 0) {
                    val bitCount = array1Value - 1
                    add = if (bitCount > 0) {
                        val add2 = (1 shl bitCount) or bits.getBits(bitCount)
                        add2
                    } else {
                        1
                    }
                }

                val dataByte = (add shl 8) or bits.getDataByte()
                val addrNeg = dataByte - scratch.position()
                val addr = -addrNeg

                if (scratch.position() <= dataByte && addrNeg != 0) {
                    throw RuntimeException("EXE is corrupted")
                }

                for (i in 0 ..< (b + 2)) {
                    val byte = scratch[addr + i]
                    scratch.put(byte)
                }
            } else {
                val nextByte = bits.getDataByte()
                scratch.put(nextByte.toByte())
            }
        }

        return scratch.position() - scratchPos
    }

    fun processRelocations(r: ThrowingAbstractBinaryReader, mz: MZExecutable) {
        val table = r.readBytes(0x10)
        val bits = HackplBitStream(r)

        while (true) {
            var counter = bits.getDataWord()
            if (counter == 0)
                break

            val segmentAdd = bits.getDataWord()
            var address = bits.getDataWord()

            while (counter-- > 1) {
                mz.addReloc(segmentAdd.toUShort(), address.toUShort())

                val twoBits = bits.getBits(2)
                val index = if (twoBits == 3) {
                    var numberOfOnes = 0
                    for (i in 0 ..< 15) {
                        if (!bits.getBit())
                            break
                        numberOfOnes++
                    }
                    twoBits + numberOfOnes
                } else {
                    twoBits
                }

                val tableByte = table[index].toUByte().toInt()
                address += if (tableByte > 0) {
                    val value = (1 shl tableByte) or bits.getBits(tableByte)
                    value
                } else {
                    1
                }
            }
        }
    }

    private fun findPatterns(r: ThrowingAbstractBinaryReader, pattern: ByteArray, mask: ByteArray): List<ByteArray> {
        if (pattern.isEmpty() || mask.isEmpty())
            return listOf()

        val hits = mutableListOf<ByteArray>()

        val hit = ByteArray(mask.size)
        for (i in mask.indices) {
            hit[i] = (pattern[i + 1].toInt() and mask[i].toInt()).toByte()
        }

        outer@ while (r.available() > mask.size) {
//            val lastPos = r.position()
            val byte = r.readU8().toByte()
            if (byte == pattern[0]) {
                val potential = r.readBytes(mask.size)
//                println("${lastPos.hex}: ${potential.hex}")
                for (i in mask.indices) {
                    val c = (potential[i].toInt() and mask[i].toInt()).toByte()
                    if (c != hit[i]) {
                        r.seekRelative(-mask.size.toLong())
                        continue@outer
                    }
                }

                hits.find { it.contentEquals(potential) } ?: hits.add(potential)
            }
        }

        return hits
    }

    fun findEncryptedArticleBlobs(unpacked: MZExecutable): List<Range> {
        // 1000:20db b8 16 26                MOV        AX, 0x2616
        // 1000:20de 50                      PUSH       AX
        // 1000:20df 57                      PUSH       DI
        // 1000:20e0 b8 00 f0                MOV        AX, 0xf000
        // 1000:20e3 50                      PUSH       AX
        // 1000:20e4 bf 58 10                MOV        DI, 0x1058
        // 1000:20e7 1e                      PUSH       DS
        // 1000:20e8 57                      PUSH       DI
        // 1000:20e9 9a 00 00 1b 35          CALLF      decrypt_using_supplied_password

        val pattern = "B816265057B800F050BF58101E579A".parseHex()
        val mask =      "0000FFFFFF0000FFFF0000FFFFFF".parseHex()

        val results = findPatterns(unpacked.bodyDataReader(), pattern, mask)
        if (results.isEmpty()) {
            throw FormatException("can't locate encrypted article blocks in this binary")
        }

        val ranges = mutableListOf<Range>()

        for (result in results) {
            val r1 = result.reader()

            val blob1Segment = r1.readU16().le()
            r1.seekRelative(3)
            val blob1Size = r1.readU16().le()

            ranges.add(
                Range(
                    DOSOffset.fromSegmentOffset(blob1Segment, 0U).absoluteOffset.toULong(),
                    blob1Size.toULong()
                )
            )
        }

        return ranges
    }

    private fun derivePassword001(password: ByteArray): ByteArray {
        val passOut = ByteArrayOutputStream()
        passOut.write(password)

        for (i in password.indices) {
            passOut.write(password.last() - 0x43)
        }

        return passOut.toByteArray()
    }

    private fun derivePassword002(password: ByteArray): ByteArray {
        val passOut = ByteArrayOutputStream()
        passOut.write(password)

        while (passOut.size() != 0x64) {
            passOut.write(password.last() - 0x43)
        }

        return passOut.toByteArray()
    }

    // #003 and #004
    private fun derivePassword003(password: ByteArray): ByteArray {
        val passOut = ByteArrayOutputStream()

        for (i in 0 ..< (password.size / 2 + password.size % 2)) {
            val ch1 = password[i]
            val ch2 = password[password.size - 1 - i]
            passOut.write(ch1.toInt() xor ch2.toInt())
        }

        var lastChar: Int = 0
        for (i in 0 ..< password.size / 2) {
            lastChar = password[(password.size / 2) - i - 1].toInt()
            passOut.write(lastChar)
        }

        while (passOut.size() != 0x64) {
            passOut.write(Math.abs(lastChar - 0x43))
        }

        return passOut.toByteArray()
    }

    private fun calculateSumByte(p: ByteArray): Int {
        var sum = 0

        for (i in p.indices) {
            sum += ((3 * p[i]) + (((i + 1) and 0xff) * 2)) % 0x100
        }

        return sum and 0xff
    }

    fun decryptBlob(blob: ByteArray, password: ByteArray, issue: Int, maxSize: Int? = null): ByteArray {
        val derivedPassword = when (issue) {
            1 -> derivePassword001(password)
            2 -> derivePassword002(password)
            3, 4 -> derivePassword003(password)
            else ->
                throw RuntimeException("unsupported issue: $issue")
        }

        val sumByte = calculateSumByte(derivedPassword)
        val magicByte = 0xED - derivedPassword.size
        val passFactor = sumByte xor magicByte

        val decrypted = ByteArrayOutputStream(blob.size)
        val xorMap = ByteArrayOutputStream(blob.size)

        for (i in blob.indices) {
            if (maxSize != null && i >= maxSize) break

            val firstMod = (blob.size - i) % (derivedPassword.size + 0x6F)
            val passIndex = (blob.size - i) % derivedPassword.size
            val byteFactor = derivedPassword[passIndex].toInt() xor firstMod
            val outByte = passFactor xor byteFactor

            xorMap.write(outByte)
            decrypted.write(blob[i].toInt() xor (outByte and 255))
        }

        return decrypted.toByteArray()
    }

    fun dataChecksum(blob: ByteArray): Int {
        var sum = 0

        for (i in blob.indices) {
            sum += (blob[i].toInt() and 255) * ((i + 1) % 0x100)
        }

        return sum
    }
}

// Not fast enough... need OpenCL for that ;)
class Password {
    var pass = ByteBuffer.allocate(1)
    val alphabet = "abcdefghijklmnopqrstuvwxyz "

    init {
        pass.put(0)
    }

    fun next(): ByteBuffer {
        pass.put(0, (pass[0] + 1).toByte())

        for (i in 0 ..< pass.capacity()) {
            val carry = pass[i] >= alphabet.length
            if (carry) {
                pass.put(i, 0)
                if (i + 1 < pass.capacity()) {
                    pass.put(i + 1, (pass[i + 1] + 1).toByte())
                } else {
                    pass = ByteBuffer.allocate(pass.capacity() + 1)
                    for (k in 0 ..< pass.capacity()) {
                        pass.put(k, 0)
                    }
                    break
                }
            } else {
                break
            }
        }

        return pass
    }

    fun array() = pass.array()

    override fun toString(): String {
        val sb = StringBuffer()
        for (i in 0 ..< pass.capacity()) {
            sb.append(alphabet[pass.get(i).toInt() and 255])
        }
        return sb.toString()
    }
}

@CommandLine.Command(
    name = "extract",
    description = ["Extractor/unpacker for H@CKPL zines 001-004"])
class Extractor : Runnable {
    @CommandLine.Option(
        names = ["-i", "--issue"],
        description = ["Issue number: 1, 2, 3 or 4 (required)"],
        required = true)
    lateinit var issue: Integer

    @CommandLine.Parameters(
        description = ["Path to the H@CKPL.EXE file (required)"],
        paramLabel = "FILE"
    )
    lateinit var filename: File

    @CommandLine.Option(
        names = ["-u", "--unpack"],
        description = ["Unpack and save the generated EXE file"],
        paramLabel = "EXE"
    )
    var unpack: String? = null

    @CommandLine.Option(
        names = ["-e", "--extract"],
        description = ["Extract texts and store them in the generated TXT file (encoded as UTF-8)"],
        paramLabel = "TXT"
    )
    var extract: String? = null

    override fun run() {
        val unpack = unpack
        val extract = extract

        if (unpack == null && extract == null) {
            throw RuntimeException("nothing to do, please use `-u` or `-e`")
        }

        val passwords = listOf(
            "patience is a virtue",
            "november rain",
            "beta",
            "FREE KEVIN MITNICK",
            "beta")

        if (issue < 1 || issue > min(4, passwords.size)) {
            throw RuntimeException("`-i` only supports issues 1, 2, 3 or 4")
        }

        println("H@CKPL unpacker/decoder, written by @antekone")
        println("https://anadoxin.org/blog")
        println()

        val r = BinaryReader(filename)

        val hackpl = HackplExecutable(MZExecutable.fromBinaryReader(r))
        val totalBytesNeeded = hackpl.calcUncompressedMemoryNeeded()
        var totalBytesUncompressed = 0
        val unpackedBuf = ByteBuffer.allocate(totalBytesNeeded)
        unpackedBuf.order(ByteOrder.LITTLE_ENDIAN)

        val newMZ = MZExecutable()

        for ((type, reader) in hackpl.embeddedBlock()) {
            reader.readU16() // skip size
            if (type == 1.toUByte()) {
                totalBytesUncompressed += hackpl.decompressBlock(reader, unpackedBuf)
            } else if (type == 2.toUByte()) {
                hackpl.processRelocations(reader, newMZ)
            }
        }

        if (totalBytesNeeded != totalBytesUncompressed) {
            throw RuntimeException("sorry, decompression error -- is this a valid H@CKPL executable?")
        }

        hackpl.extractOEPMeta()

        newMZ.entryPointOffset = hackpl.oepCSIP
        newMZ.stackOffset = hackpl.oepSSSP
        newMZ.body = unpackedBuf
        newMZ.minAlloc = hackpl.mz.minAlloc
        newMZ.maxAlloc = hackpl.mz.maxAlloc
        newMZ.buildRelocs()
        newMZ.buildHeader()

        if (unpack != null) {
            val mz = newMZ.render()
            println("- Unpacked: ${filename.length()} bytes -> ${mz.size} bytes")
            File(unpack).writeBytes(mz)
            println("  Written to: $unpack")
        }

        if (extract != null) {
            val blobs = hackpl.findEncryptedArticleBlobs(newMZ).map {
                newMZ.bodyData().reader().region(it).readBytes(it.length.toInt()).reverse()
            }

            var size = 0L
            FileOutputStream(File(extract)).use { out ->
                for (i in blobs.indices) {
                    val decrypted = hackpl.decryptBlob(
                        blobs[i],
                        passwords[issue.toInt() - 1].toByteArray(), issue.toInt())

                    val s = String(decrypted, Charset.forName("IBM852"))
                    val sBytes = s.toByteArray()
                    size += sBytes.size
                    out.write(sBytes)
                }
            }

            println("- Extracted ${size} bytes")
            println("  Written to: $extract")
        }

        println()
        println("Enjoy!")
    }
}

fun main(args: Array<String>) {
    val cli = CommandLine(Extractor())

    cli.setExecutionExceptionHandler { ex, _, _ ->
        when (ex) {
            is BaseException -> {
                println("error: unsupported or corrupted executable: ${ex.message}")
            }
            is RuntimeException -> {
                println("error: ${ex.message}")
            }
        }
        0
    }

    val exitCode = cli.execute(*args)
    exitProcess(exitCode)
}