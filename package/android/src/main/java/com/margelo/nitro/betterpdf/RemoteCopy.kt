package com.margelo.nitro.betterpdf

/**
 * [isDownloaded]: this open downloaded the copy. Call [discard] while the
 * descriptor is open, so the file's inode cannot be reused meanwhile.
 */
class RemoteCopy(val isDownloaded: Boolean, val discard: () -> Unit)
