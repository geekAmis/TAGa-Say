package com.tagasay.core.crypto

/**
 * Кадрирование открытого текста перед шифрованием.
 *
 * Формат: `"TGF1"` (magic) + 4-байтная длина (big-endian) + payload + нулевой
 * padding до ближайшего класса размера. Классы совпадают с `sizeClass`
 * конверта (256 B … 1 MiB), поэтому relay видит только класс, а не точную
 * длину сообщения.
 *
 * Если magic отсутствует, [unpad] возвращает вход без изменений — так старые
 * клиенты и e2e-peer, которые шлют сырой текст, остаются совместимыми.
 */
object MessageFraming {

    /** Классы размера в байтах (индекс = sizeClass 0..6). */
    val SIZE_CLASSES = intArrayOf(
        256,
        1024,
        4096,
        16 * 1024,
        64 * 1024,
        256 * 1024,
        1024 * 1024,
    )

    /** Больше этого паддинг не применяется — сервер всё равно отклонит конверт. */
    const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024 - 8

    private val MAGIC = byteArrayOf(0x54, 0x47, 0x46, 0x31) // "TGF1"
    private const val HEADER = 8

    /** Индекс класса для уже зашифрованного конверта. */
    fun sizeClassFor(ciphertextSize: Int): Int = when {
        ciphertextSize <= 256 -> 0
        ciphertextSize <= 1024 -> 1
        ciphertextSize <= 4096 -> 2
        ciphertextSize <= 16 * 1024 -> 3
        ciphertextSize <= 64 * 1024 -> 4
        ciphertextSize <= 256 * 1024 -> 5
        ciphertextSize <= 1024 * 1024 -> 6
        else -> 7
    }

    /**
     * Добавляет заголовок и padding. Для payload больше [MAX_PAYLOAD_BYTES]
     * оставляет только заголовок (без выравнивания), чтобы данные не потерялись.
     */
    fun pad(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "payload too large" }
        val needed = HEADER + payload.size
        val target = SIZE_CLASSES.firstOrNull { it >= needed } ?: needed
        val framed = ByteArray(target)
        MAGIC.copyInto(framed, 0)
        writeInt(framed, 4, payload.size)
        payload.copyInto(framed, HEADER)
        return framed
    }

    /**
     * Снимает заголовок и padding. Если кадр не наш — возвращает данные как есть
     * (совместимость с сырым текстом старых клиентов).
     */
    fun unpad(framed: ByteArray): ByteArray {
        if (framed.size < HEADER) return framed
        for (index in MAGIC.indices) {
            if (framed[index] != MAGIC[index]) return framed
        }
        val length = readInt(framed, 4)
        if (length < 0 || length > framed.size - HEADER) return framed
        return framed.copyOfRange(HEADER, HEADER + length)
    }

    /** Размер открытого текста без снятия копии (для тестов/диагностики). */
    fun framedLength(framed: ByteArray): Int? {
        if (framed.size < HEADER) return null
        for (index in MAGIC.indices) {
            if (framed[index] != MAGIC[index]) return null
        }
        val length = readInt(framed, 4)
        return length.takeIf { it in 0..(framed.size - HEADER) }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)
}
