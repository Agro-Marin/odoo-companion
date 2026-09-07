package com.odoocompanion.sync

internal fun String.loggableDirectory(): String =
    substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "an unknown folder" }
