package com.odoocompanion.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal fun File.directoryWithoutLinks(relative: String): File? {
    var directory = this
    for (name in relative.split('/')) {
        directory = File(directory, name)
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
    }
    return directory
}
