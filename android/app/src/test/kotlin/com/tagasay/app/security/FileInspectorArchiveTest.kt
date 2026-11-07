package com.tagasay.app.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileInspectorArchiveTest {

    @Test
    fun detectsPathTraversalEntry() {
        val archive = zipOf(
            "../evil.sh" to "echo pwned".toByteArray(),
            "ok.txt" to "safe".toByteArray(),
        )

        val findings = inspectArchiveStream(ByteArrayInputStream(archive))

        assertTrue(findings.any { it.dangerous && it.reason.contains("../evil.sh") })
    }

    @Test
    fun classifiesUnsafePaths() {
        assertTrue(isUnsafeArchivePath("..\\evil.exe"))
        assertTrue(isUnsafeArchivePath("/etc/passwd"))
        assertTrue(isUnsafeArchivePath("C:\\Windows\\evil.dll"))
        assertTrue(isUnsafeArchivePath("folder/../../escape"))
        assertFalse(isUnsafeArchivePath("folder/file.txt"))
        assertFalse(isUnsafeArchivePath("фото.jpg"))
    }

    @Test
    fun stopsWhenEntryLimitExceeded() {
        val entries = Array(6) { index -> "file-$index.txt" to ByteArray(0) }
        val archive = zipOf(*entries)

        val findings = inspectArchiveStream(ByteArrayInputStream(archive), maxEntries = 4)

        assertTrue(findings.any { it.dangerous && it.reason.contains("Слишком много файлов") })
    }

    @Test
    fun stopsWhenUncompressedLimitExceeded() {
        val archive = zipOf("big.bin" to ByteArray(8192) { 0 })

        val findings = inspectArchiveStream(ByteArrayInputStream(archive), maxUncompressed = 1024)

        assertTrue(findings.any { it.dangerous && it.reason.contains("бомба") })
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
