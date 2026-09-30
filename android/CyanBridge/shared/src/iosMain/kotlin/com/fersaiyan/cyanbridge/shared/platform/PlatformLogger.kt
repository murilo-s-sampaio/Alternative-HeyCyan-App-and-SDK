package com.fersaiyan.cyanbridge.shared.platform

import platform.Foundation.NSLog

actual object PlatformLogger {
    actual fun d(tag: String, message: String) = log("DEBUG", tag, message)
    actual fun i(tag: String, message: String) = log("INFO", tag, message)
    actual fun w(tag: String, message: String) = log("WARN", tag, message)
    actual fun w(tag: String, message: String, throwable: Throwable?) {
        log("WARN", tag, "$message (${throwable?.message ?: "unknown"})")
    }
    actual fun e(tag: String, message: String) = log("ERROR", tag, message)
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        log("ERROR", tag, "$message (${throwable?.message ?: "unknown"})")
    }

    // Kotlin/Native passes String varargs to NSLog as raw pointers (SIGSEGV on launch),
    // so format in Kotlin and hand NSLog a single escaped format string.
    private fun log(level: String, tag: String, message: String) {
        NSLog("[$level] $tag: $message".replace("%", "%%"))
    }
}
