package com.example.cdplayer

import android.hardware.usb.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Track(val number: Int, val startLba: Int, var endLba: Int = 0)

class UsbScsi(
    private val conn: UsbDeviceConnection,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint
) {
    private var tag = 0

    /** USB Bulk-Only 방식으로 SCSI 명령 1개 실행. 실패 -1, 성공 시 받은 바이트 수 */
    @Synchronized
    fun exec(cdb: ByteArray, dataLen: Int, buf: ByteArray?, timeout: Int = 10_000): Int {
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355)
        cbw.putInt(++tag)
        cbw.putInt(dataLen)
        cbw.put(if (dataLen > 0) 0x80.toByte() else 0.toByte())
        cbw.put(0.toByte())
        cbw.put(cdb.size.toByte())
        cbw.position(15); cbw.put(cdb)
        if (conn.bulkTransfer(outEp, cbw.array(), 31, timeout) != 31) return -1

        var got = 0
        while (buf != null && got < dataLen) {
            val want = dataLen - got
            val n = conn.bulkTransfer(inEp, buf, got, want, timeout)
            if (n <= 0) return -1
            got += n
            if (n < want) break   // 짧게 끝난 응답
        }
        val csw = ByteArray(13)
        var r = conn.bulkTransfer(inEp, csw, 13, timeout)
        if (r != 13) { // 한 번 더 시도
            r = conn.bulkTransfer(inEp, csw, 13, timeout)
            if (r != 13) return -1
        }
        if (csw[12].toInt() != 0) {
            if (cdb[0] != 0x03.toByte()) requestSense()
            return -1
        }
        return got
    }

    private fun requestSense() {
        val cdb = ByteArray(6).apply { this[0] = 0x03; this[4] = 18 }
        exec(cdb, 18, ByteArray(18), 3000)
    }

    fun testUnitReady() = exec(ByteArray(6), 0, null, 5000) >= 0

    fun readToc(): List<Track> {
        val len = 804
        val buf = ByteArray(len)
        val cdb = ByteArray(10).apply {
            this[0] = 0x43
            this[6] = 1
            this[7] = (len shr 8).toByte(); this[8] = len.toByte()
        }
        if (exec(cdb, len, buf) < 4) return emptyList()
        val tocLen = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
        val tracks = mutableListOf<Track>()
        var leadOut = 0
        var p = 4
        while (p + 8 <= tocLen + 2 && p + 8 <= len) {
            val isData = (buf[p + 1].toInt() and 0x04) != 0
            val num = buf[p + 2].toInt() and 0xFF
            val lba = bb.getInt(p + 4)
            if (num == 0xAA) leadOut = lba
            else if (!isData) tracks += Track(num, lba)
            p += 8
        }
        tracks.forEachIndexed { i, t -> t.endLba = tracks.getOrNull(i + 1)?.startLba ?: leadOut }
        return tracks
    }

    /** CD-DA 섹터를 raw PCM으로 읽기 (섹터당 2352바이트) */
    fun readCdda(lba: Int, sectors: Int, out: ByteArray): Boolean {
        val cdb = ByteArray(12).apply {
            this[0] = 0xBE.toByte()
            this[1] = 0x04
            this[2] = (lba shr 24).toByte(); this[3] = (lba shr 16).toByte()
            this[4] = (lba shr 8).toByte();  this[5] = lba.toByte()
            this[6] = (sectors shr 16).toByte(); this[7] = (sectors shr 8).toByte()
            this[8] = sectors.toByte()
            this[9] = 0x10
        }
        return exec(cdb, sectors * 2352, out) == sectors * 2352
    }

    fun close() { try { conn.close() } catch (_: Exception) {} }

    companion object {
        fun hasMassStorage(dev: UsbDevice): Boolean {
            for (i in 0 until dev.interfaceCount)
                if (dev.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return true
            return false
        }

        fun open(usb: UsbManager, dev: UsbDevice): UsbScsi? {
            for (i in 0 until dev.interfaceCount) {
                val intf = dev.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) continue
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
                if (inEp == null || outEp == null) continue
                val conn = usb.openDevice(dev) ?: return null
                if (!conn.claimInterface(intf, true)) { conn.close(); return null }
                return UsbScsi(conn, inEp, outEp)
            }
            return null
        }
    }
}
