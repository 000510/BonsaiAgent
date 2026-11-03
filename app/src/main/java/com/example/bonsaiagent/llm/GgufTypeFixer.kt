package com.example.bonsaiagent.llm

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 兼容修复器。
 *
 * 背景：官方仓库里 `Ternary-Bonsai-8B-Q2_0.gguf` 和 `Ternary-Bonsai-8B-PQ2_0.gguf`
 * 是同一份数据（g128 三值布局，2.03 GiB），只是前者把张量类型标成了上游旧编号
 * 42（Q2_0/g64）、41（Q1_0/g64），后者才是 prism 分支认识的 142（PQ2_0/g128）、
 * 141。标错之后 llama.cpp 会按 g64 去算大小，读到最后数据不够，于是报
 * "you are probably using the wrong GGUF ... failed to read tensor data"。
 *
 * 这里在加载前把标签改对（只动那几个 4 字节整数，文件大小不变），
 * 免得重新下 2GB。
 */
object GgufTypeFixer {

    private val LEGACY_TO_NEW = mapOf(42 to 142, 41 to 141)

    private const val KV_U8 = 0
    private const val KV_I8 = 1
    private const val KV_U16 = 2
    private const val KV_I16 = 3
    private const val KV_U32 = 4
    private const val KV_I32 = 5
    private const val KV_F32 = 6
    private const val KV_BOOL = 7
    private const val KV_STRING = 8
    private const val KV_ARRAY = 9
    private const val KV_U64 = 10
    private const val KV_I64 = 11
    private const val KV_F64 = 12

    private fun fixedSize(t: Int): Int = when (t) {
        KV_U8, KV_I8, KV_BOOL -> 1
        KV_U16, KV_I16 -> 2
        KV_U32, KV_I32, KV_F32 -> 4
        KV_U64, KV_I64, KV_F64 -> 8
        else -> -1
    }

    /**
     * @return 修复说明；null 表示不需要修（或不是 GGUF）
     */
    fun fixIfNeeded(path: String): String? {
        val file = File(path)
        if (!file.isFile || file.length() < 1024) return null

        RandomAccessFile(file, "rw").use { raf ->
            val total = raf.length()
            var cap = 8L * 1024 * 1024
            while (true) {
                val take = minOf(cap, total).toInt()
                val raw = ByteArray(take)
                raf.seek(0)
                raf.readFully(raw)

                try {
                    return patch(raf, raw, total)
                } catch (e: BufferUnderflowException) {
                    if (cap >= total) return null
                    cap *= 4
                } catch (e: IndexOutOfBoundsException) {
                    if (cap >= total) return null
                    cap *= 4
                }
            }
        }
    }

    private class BufferUnderflowException : RuntimeException()

    private fun patch(raf: RandomAccessFile, raw: ByteArray, totalSize: Long): String? {
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        // magic
        if (raw.size < 8) return null
        val m0 = raw[0].toInt()
        val m1 = raw[1].toInt()
        val m2 = raw[2].toInt()
        val m3 = raw[3].toInt()
        if (m0 != 'G'.code || m1 != 'G'.code || m2 != 'U'.code || m3 != 'F'.code) return null

        buf.position(4)
        buf.int                                  // version
        val nTensors = buf.long
        val nKv = buf.long
        if (nTensors <= 0 || nTensors > 1_000_000L || nKv < 0 || nKv > 1_000_000L) return null

        fun readStr() {
            val n = buf.long
            if (n < 0 || n > buf.remaining()) throw BufferUnderflowException()
            buf.position(buf.position() + n.toInt())
        }

        val patches = ArrayList<Pair<Int, Int>>()   // 位置 -> 新值

        // ---- metadata ----
        var i = 0L
        while (i < nKv) {
            val keyStart = buf.position()
            val keyLen = buf.long
            if (keyLen < 0 || keyLen > buf.remaining()) throw BufferUnderflowException()
            val key = String(raw, buf.position(), keyLen.toInt(), Charsets.UTF_8)
            buf.position(buf.position() + keyLen.toInt())
            val vt = buf.int
            when {
                vt == KV_STRING -> readStr()

                vt == KV_ARRAY -> {
                    val et = buf.int
                    val n = buf.long
                    if (n < 0 || n > 200_000_000L) throw BufferUnderflowException()
                    if (et == KV_STRING) {
                        var k = 0L
                        while (k < n) {
                            readStr(); k++
                        }
                    } else {
                        val es = fixedSize(et)
                        if (es < 0) return null
                        val bytes = n * es
                        if (bytes > buf.remaining()) throw BufferUnderflowException()
                        buf.position(buf.position() + bytes.toInt())
                    }
                }

                else -> {
                    val vs = fixedSize(vt)
                    if (vs < 0) return null
                    if (key == "general.file_type" && vt == KV_U32) {
                        val old = buf.getInt(buf.position())
                        LEGACY_TO_NEW[old]?.let { patches.add(buf.position() to it) }
                    }
                    if (vs > buf.remaining()) throw BufferUnderflowException()
                    buf.position(buf.position() + vs)
                }
            }
            if (buf.position() <= keyStart) return null
            i++
        }

        // ---- tensor info ----
        var triple = 0
        var q2Elems = 0L          // type 42 张量的元素总数
        var f32Bytes = 0L
        i = 0L
        while (i < nTensors) {
            readStr()                            // name
            val nd = buf.int
            if (nd < 0 || nd > 8) return null
            val need = nd * 8
            if (need > buf.remaining()) throw BufferUnderflowException()
            var numel = 1L
            for (k in 0 until nd) {
                val d = buf.getLong(buf.position() + k * 8)
                numel *= d
            }
            buf.position(buf.position() + need)  // dims
            val tPos = buf.position()
            val t = buf.getInt(tPos)
            if (t == 42 || t == 41) q2Elems += numel
            if (t == 0) f32Bytes += numel * 4    // F32
            LEGACY_TO_NEW[t]?.let {
                patches.add(tPos to it)
                triple++
            }
            if (4 + 8 > buf.remaining()) throw BufferUnderflowException()
            buf.position(tPos + 4 + 8)           // type + offset
            i++
        }

        if (patches.isEmpty()) return null

        // 关键判据：按旧标签（g64，每 64 个权重 18 字节）算，数据区放不下这个文件，
        // 说明文件里其实是 g128 的紧凑数据，只是标签错了 —— 才可以改。
        // 如果真装得下，那它本来就是合法的 g64 文件，别碰。
        val dataStart = ((buf.position() + 31) / 32) * 32
        val avail = totalSize - dataStart
        val needAsG64 = q2Elems / 64 * 18 + f32Bytes
        if (needAsG64 <= avail) return null

        for ((pos, v) in patches) {
            raf.seek(pos.toLong())
            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }
        raf.fd.sync()

        return "已自动把模型格式标签从旧版修正为 prism 版（$triple 个张量），无需重新下载"
    }
}
