package com.example.bonsaiagent.llm

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v10.29：轻量 ELF 解析器，只为回答一个问题 ——
 * `libneuron_runtime.7.so` 到底导出了哪些函数？
 *
 * 为什么需要：手机上的 NPU 入口库（NeuronAdapter）被 vivo 裁成了空符号链接，
 * 但真正的执行引擎 runtime 还在、还能 dlopen。要想自己写一个 adapter 顶替它，
 * 前提是 runtime 的 .dynsym（动态符号表）里还留有可用的 C API。
 * 如果被 strip 得只剩几个符号，这条路直接判死，不必再花时间。
 *
 * 只支持 64 位 ELF（Android arm64 全是），够用。
 */
object ElfDump {

    data class Sym(val name: String, val value: Long, val size: Long, val type: Int)

    private const val SHT_DYNSYM = 11L
    private const val STT_OBJECT = 1
    private const val STT_FUNC = 2

    /** 解析失败时返回单条说明，调用方不用 try */
    fun dynamicSymbols(f: File, maxOut: Int = 600): List<Sym> {
        if (!f.exists() || f.length() < 64) return emptyList()
        val out = ArrayList<Sym>()
        try {
            RandomAccessFile(f, "r").use { raf ->
                val ident = ByteArray(16)
                raf.readFully(ident)
                if (ident[0] != 0x7F.toByte() || ident[1] != 'E'.code.toByte()) return emptyList()
                if (ident[4].toInt() != 2) return emptyList()           // 只要 64 位
                val order = if (ident[5].toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

                fun bytes(off: Long, n: Int): ByteArray {
                    val b = ByteArray(n)
                    raf.seek(off)
                    raf.readFully(b)
                    return b
                }
                fun u16(off: Long) = ByteBuffer.wrap(bytes(off, 2)).order(order).short.toInt() and 0xFFFF
                fun u32(off: Long) = ByteBuffer.wrap(bytes(off, 4)).order(order).int.toLong() and 0xFFFFFFFFL
                fun u64(off: Long) = ByteBuffer.wrap(bytes(off, 8)).order(order).long

                val shoff = u64(0x28)
                val shentsize = u16(0x3A)
                val shnum = u16(0x3C)
                if (shoff <= 0 || shnum == 0) return emptyList()

                // 先收集所有 section 头
                class Sec(val type: Long, val off: Long, val size: Long, val link: Int)
                val secs = ArrayList<Sec>(shnum)
                for (i in 0 until shnum) {
                    val base = shoff + i.toLong() * shentsize
                    secs.add(Sec(u32(base + 4), u64(base + 24), u64(base + 32), u32(base + 40).toInt()))
                }

                for (symSec in secs) {
                    if (symSec.type != SHT_DYNSYM) continue
                    if (symSec.link < 0 || symSec.link >= secs.size) continue
                    val strSec = secs[symSec.link]
                    if (strSec.size <= 0 || strSec.size > 8L * 1024 * 1024) continue

                    val strTab = ByteArray(strSec.size.toInt())
                    raf.seek(strSec.off)
                    raf.readFully(strTab)

                    val count = (symSec.size / 24).toInt()
                    var i = 0
                    while (i < count && out.size < maxOut) {
                        val b = symSec.off + i.toLong() * 24
                        val nameOff = u32(b).toInt()
                        val info = bytes(b + 4, 1)[0].toInt() and 0xFF
                        val value = u64(b + 8)
                        val size = u64(b + 16)
                        i++
                        if (nameOff <= 0 || nameOff >= strTab.size) continue
                        var end = nameOff
                        while (end < strTab.size && strTab[end] != 0.toByte()) end++
                        if (end <= nameOff) continue
                        val name = String(strTab, nameOff, end - nameOff, Charsets.UTF_8)
                        val type = info and 0xF
                        if (type != STT_FUNC && type != STT_OBJECT) continue
                        if (name.isEmpty()) continue
                        out.add(Sym(name, value, size, type))
                    }
                }
            }
        } catch (_: Throwable) {
            return out
        }
        return out
    }

    /** 找出这些符号里像是给外部用的 C API（无 C++ mangling 前缀 _Z）*/
    fun cApiOnly(list: List<Sym>): List<Sym> = list.filter { !it.name.startsWith("_Z") }

    fun looksLikeNeuron(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("neuron") || n.contains("apu") || n.contains("nir") ||
            n.contains("mdw") || n.contains("dla") || n.contains("accel")
    }
}
