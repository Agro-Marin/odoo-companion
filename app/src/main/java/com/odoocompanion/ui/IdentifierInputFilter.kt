package com.odoocompanion.ui

import android.text.InputFilter
import android.text.Spanned
import com.odoocompanion.config.isIdentifierChar

class IdentifierInputFilter(private val onDropped: () -> Unit) : InputFilter {
    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int,
    ): CharSequence? {
        val typed = source.subSequence(start, end)
        if (typed.all(::isIdentifierChar)) return null
        onDropped()
        return typed.filter(::isIdentifierChar)
    }
}
