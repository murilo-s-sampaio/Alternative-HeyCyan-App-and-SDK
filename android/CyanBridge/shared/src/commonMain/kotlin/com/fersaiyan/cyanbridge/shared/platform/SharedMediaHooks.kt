package com.fersaiyan.cyanbridge.shared.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Platform hooks for the shared media destination. The iOS host installs them;
 * Android keeps its own gallery Activity and leaves them unset.
 */
object SharedMediaHooks {
    var loadThumbnail: (suspend (path: String) -> ImageBitmap?)? = null
    var openMedia: ((path: String) -> Unit)? = null
    var shareMedia: ((paths: List<String>) -> Unit)? = null

    /** Deletes the local files; the shared screen removes the records afterwards. */
    var deleteMediaFiles: (suspend (paths: List<String>) -> Unit)? = null
}
