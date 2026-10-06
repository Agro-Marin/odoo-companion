package com.odoocompanion.data

// Every row of a kind, live or dead: what a test asks to prove a row is gone
// rather than set aside. The app never needs the question, so it is not a DAO
// query that ships in the APK.
internal fun CompanionDatabase.rowsOf(kind: String): Int =
    query("SELECT COUNT(*) FROM outbox WHERE kind = ?", arrayOf(kind))
        .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
