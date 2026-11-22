package com.madclock.ipc

import android.net.Uri

/** Bounds for local data, not a command language. Shared code does not share package storage. */
object InputChecks {
    const val MAX_BARCODE_CHARS = 8192
    const val MAX_URI_CHARS = 8192
    fun session(id: String?) = id != null && id.matches(Regex("[A-Za-z0-9-]{1,80}"))
    fun barcode(value: String?) = !value.isNullOrBlank() && value.length <= MAX_BARCODE_CHARS
    fun song(value: String?): Boolean {
        if (value.isNullOrBlank() || value.length > MAX_URI_CHARS) return false
        val uri = Uri.parse(value)
        return uri.scheme == "content" && !uri.authority.isNullOrBlank()
    }
}
