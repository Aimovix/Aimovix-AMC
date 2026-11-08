package com.agent.mobile.agent

import java.io.File

/**
 * A small, user-editable text file in app-private storage, such as the agent's persona and
 * standing instructions (SOUL.md). Reads never throw; writes are atomic.
 */
class TextFileStore(
    private val file: File,
    private val maxChars: Int = 8_000
) {
    private val lock = Any()

    fun read(): String = synchronized(lock) {
        try {
            if (file.exists()) file.readText(Charsets.UTF_8).take(maxChars) else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun write(text: String) = synchronized(lock) {
        val clean = text.trim().take(maxChars)
        if (clean.isEmpty()) {
            if (file.exists()) file.delete()
            return@synchronized
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(clean + "\n", Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(clean + "\n", Charsets.UTF_8)
            tmp.delete()
        }
    }
}
