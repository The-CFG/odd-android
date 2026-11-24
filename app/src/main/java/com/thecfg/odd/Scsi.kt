package com.thecfg.odd

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val AUDIO_SECTOR = 2352
private const val TIMEOUT = 15000

class FatalException(msg: String) : Exception(msg)
class BotResult(val data: ByteArray, val status: Int)
data class TocEntry(val no: Int, val control: Int, val lba: Int)
class Msc(val intf: UsbInterface, val epIn: UsbEndpoint, val epOut: UsbEndpoint)

/** USB Mass Storage, Bulk-Only(0x50) 인터페이스 찾기 */
fun findMsc(dev: UsbDevice): Msc? {
    for (i in 0 until dev.interfaceCount) {
        val f = dev.getInterface(i)
        if (f.interfaceClass != 8 || f.interfaceProtocol != 0x50) continue
        var ei: UsbEndpoint? = null
        var eo: UsbEndpoint? = null
        for (j in 0 until f.endpointCount) {
            val e = f.getEndpoint(j)
            if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (e.direction == UsbConstants.USB_DIR_IN) ei = e else eo = e
        }
        if (ei != null && eo != null) return Msc(f, ei, eo)
    }
    return null
}

private fun u32(v: Long) = intArrayOf((v shr 24).toInt() and 255, (v shr 16).toInt() and 255, (v shr 8).toInt() and 255, v.toInt() and 255)
private fun u16(v: Int) = intArrayOf((v shr 8) and 255, v and 255)
private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)

private val PROFILE_NAMES = mapOf(
    0x08 to "CD-ROM", 0x09 to "CD-R", 0x0A to "CD-RW",
    0x10 to "DVD-ROM", 0x11 to "DVD-R", 0x12 to "DVD-RAM", 0x13 to "DVD-RW", 0x14 to "DVD-RW",
    0x15 to "DVD-R DL", 0x16 to "DVD-R DL", 0x1A to "DVD+RW", 0x1B to "DVD+R",
    0x2A to "DVD+RW DL", 0x2B to "DVD+R DL",
    0x40 to "BD-ROM", 0x41 to "BD-R", 0x42 to "BD-R", 0x43 to "BD-RE",
)

/** USB Bulk-Only Transport 위의 SCSI/MMC 명령 (odd.py 의 BOT 클래스 포팅) */
class Bot(private val conn: UsbDeviceConnection, private val epIn: UsbEndpoint, private val epOut: UsbEndpoint) : SectorSource {
    private var tag = 1

    // Android 에는 clearHalt 가 없어서 CLEAR_FEATURE(ENDPOINT_HALT) 를 직접 보낸다
    private fun clearHalt(ep: UsbEndpoint) {
        conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 1000)
    }

    private fun bulkIn(len: Int): ByteArray {
        val out = ByteArray(len)
        var got = 0
        while (got < len) {
            val chunk = minOf(16384, len - got)  // usbfs 한 번에 16KB 제한 대비
            val n = conn.bulkTransfer(epIn, out, got, chunk, TIMEOUT)
            if (n < 0) { clearHalt(epIn); break }
            got += n
            if (n < chunk) break
        }
        return if (got == len) out else out.copyOf(got)
    }

    private fun csw(t: Int): Int {
        repeat(2) {
            val buf = ByteArray(13)
            val n = conn.bulkTransfer(epIn, buf, 13, TIMEOUT)
            if (n < 0) { clearHalt(epIn); return@repeat }
            if (n != 13) throw FatalException("CSW 길이 이상")
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val sig = bb.int
            val rtag = bb.int
            bb.int
            val st = bb.get().toInt()
            if (sig != 0x53425355 || rtag != t) throw FatalException("CSW 불일치 (동기화 깨짐)")
            return st
        }
        throw FatalException("CSW 읽기 실패")
    }

    @Synchronized
    fun cmd(cdb: IntArray, dataLen: Int = 0): BotResult {
        val t = tag++
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.put("USBC".toByteArray()).putInt(t).putInt(dataLen)
        cbw.put(if (dataLen > 0) 0x80.toByte() else 0.toByte())
        cbw.put(0.toByte())
        cbw.put(cdb.size.toByte())
        cdb.forEach { cbw.put(it.toByte()) }
        if (conn.bulkTransfer(epOut, cbw.array(), 31, TIMEOUT) != 31) throw FatalException("CBW 전송 실패")
        val data = if (dataLen > 0) bulkIn(dataLen) else ByteArray(0)
        val status = csw(t)
        if (status == 2) throw FatalException("BOT Phase Error")
        return BotResult(data, status)
    }

    fun sense(): Triple<Int, Int, Int>? {
        val d = cmd(intArrayOf(0x03, 0, 0, 0, 18, 0), 18).data
        return if (d.size >= 14) Triple(d[2].toInt() and 0x0F, d[12].toInt() and 255, d[13].toInt() and 255) else null
    }

    fun inquiry(): String {
        val d = cmd(intArrayOf(0x12, 0, 0, 0, 36, 0), 36).data
        return if (d.size >= 36) String(d, 8, 24, Charsets.US_ASCII).trim() else ""
    }

    /** ready / nodisc / unsupported / loading */
    fun probe(): String {
        if (cmd(intArrayOf(0, 0, 0, 0, 0, 0)).status == 0) return "ready"
        val sn = sense()
        return when {
            sn != null && sn.first == 2 && sn.second == 0x3A -> "nodisc"
            sn != null && sn.second == 0x30 -> "unsupported"
            else -> "loading"
        }
    }

    fun readToc(): Pair<List<TocEntry>, Int> {
        val r = cmd(intArrayOf(0x43, 0, 0, 0, 0, 0, 1, 0x03, 0x24, 0), 804)
        val d = r.data
        if (r.status != 0 || d.size < 12) throw FatalException("READ TOC 실패 ${sense()}")
        val total = be16(d, 0) + 2
        val tracks = ArrayList<TocEntry>()
        var leadout: Int? = null
        var off = 4
        while (off + 8 <= minOf(total, d.size)) {
            val lba = ByteBuffer.wrap(d, off + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            val no = d[off + 2].toInt() and 255
            if (no == 0xAA) leadout = lba else tracks += TocEntry(no, d[off + 1].toInt() and 0x0F, lba)
            off += 8
        }
        if (leadout == null || tracks.isEmpty()) throw FatalException("TOC 해석 실패")
        return tracks to leadout
    }

    fun readCdAudio(lba: Int, n: Int, stype: Int): BotResult {
        val cdb = intArrayOf(0xBE, stype) + u32(lba.toLong()) +
            intArrayOf((n shr 16) and 255, (n shr 8) and 255, n and 255, 0x10, 0, 0)
        return cmd(cdb, n * AUDIO_SECTOR)
    }

    fun read10(lba: Long, n: Int): ByteArray? {
        val cdb = intArrayOf(0x28, 0) + u32(lba) + intArrayOf(0) + u16(n) + intArrayOf(0)
        val r = cmd(cdb, n * 2048)
        return if (r.status == 0 && r.data.size == n * 2048) r.data else null
    }

    override fun read(lba: Long, n: Int): ByteArray? = read10(lba, n)

    fun setSpeed(kbps: Int) {
        cmd(intArrayOf(0xBB, 0) + u16(kbps) + intArrayOf(0xFF, 0xFF, 0, 0, 0, 0))
    }

    fun mediaName(): String {
        val r = cmd(intArrayOf(0x46, 0, 0, 0, 0, 0, 0, 0, 8, 0), 8)
        if (r.status != 0 || r.data.size < 8) { sense(); return "" }
        val cur = be16(r.data, 6)
        return if (cur != 0) PROFILE_NAMES[cur] ?: "" else ""
    }

    /** (마지막 LBA, 블록 크기) */
    fun capacity(): Pair<Long, Int>? {
        val r = cmd(intArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), 8)
        if (r.status != 0 || r.data.size < 8) return null
        val bb = ByteBuffer.wrap(r.data)
        return (bb.int.toLong() and 0xFFFFFFFFL) to bb.int
    }

    fun eject() {
        cmd(intArrayOf(0x1E, 0, 0, 0, 0, 0))
        cmd(intArrayOf(0x1B, 0, 0, 0, 0x02, 0))
    }

    fun load() {
        cmd(intArrayOf(0x1B, 0, 0, 0, 0x03, 0))
    }
}
