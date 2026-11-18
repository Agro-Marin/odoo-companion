package com.odoocompanion.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Callers serialize writes per history. Report a failed rename to the caller
// so the outbox can hold its receipt until the replacement is on disk.
internal fun File.writeHistory(json: String) {
    parentFile?.let { Files.createDirectories(it.toPath()) }
    val partial = File("$path.new")
    try {
        partial.outputStream().use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(
            partial.toPath(),
            toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } finally {
        partial.delete()
    }
}
