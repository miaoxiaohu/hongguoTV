// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 运行时取值表：协议档案里的 `${名字}` 在这里查。
 *
 * 名字有两类，一类是协议自己算出来的（时间戳、随机数、哈希），一类是身份带来的
 * （device_id、version_name 那些）。两类混在一张表里，档案写起来简单。
 */
class ProtoValues {
    private val values = HashMap<String, Any>()

    fun put(name: String, value: Any) {
        values[name] = value
    }

    fun get(name: String): Any =
        values[name] ?: throw IllegalStateException("协议档案引用了未知的运行时取值：$name")

    fun contains(name: String): Boolean = name in values
}

/**
 * protobuf（varint 编码）的最小实现：只做签名协议用到的那几种字段类型。
 *
 * 这部分是**算法**，不外置：站方一次都没动过它，外置了反而多一个出错的地方。
 * 外置的只是「写哪几个字段、写成什么值」。
 */
internal object ProtoCodec {
    /** protobuf 的 varint。 */
    fun varint(value: Long): ByteArray {
        var n = value
        return ByteArrayOutputStream().apply {
            while (n > 127) {
                write(((n and 127) or 128).toInt())
                n = n ushr 7
            }
            write(n.toInt())
        }.toByteArray()
    }

    /**
     * 按字段表编码一段 protobuf。
     *
     * 字段的**顺序就是字节顺序**，所以档案里的字段表不能重排。
     */
    fun encode(fields: List<PlaybackProfile.ProtoField>, values: ProtoValues): ByteArray =
        ByteArrayOutputStream().apply {
            for (field in fields) {
                val value = field.resolve(values)
                when (field.type) {
                    "sint" -> {
                        val n = (value as Number).toLong()
                        write(varint(field.tag.toLong() shl 3))
                        write(varint(if (n < 0) -n * 2 - 1 else n * 2))
                    }
                    "float" -> {
                        write(varint((field.tag.toLong() shl 3) or 5))
                        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat((value as Number).toFloat()).array())
                    }
                    "bytes" -> {
                        val bytes = value as ByteArray
                        if (bytes.isNotEmpty()) {
                            write(varint((field.tag.toLong() shl 3) or 2))
                            write(varint(bytes.size.toLong()))
                            write(bytes)
                        }
                    }
                    else -> {
                        val bytes = (value as String).toByteArray(Charsets.UTF_8)
                        if (bytes.isNotEmpty()) {
                            write(varint((field.tag.toLong() shl 3) or 2))
                            write(varint(bytes.size.toLong()))
                            write(bytes)
                        }
                    }
                }
            }
        }.toByteArray()
}
