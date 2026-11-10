package com.odoocompanion.data

internal fun CompanionDatabase.rowsOf(kind: String): Int =
    query("SELECT COUNT(*) FROM outbox WHERE kind = ?", arrayOf(kind))
        .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
