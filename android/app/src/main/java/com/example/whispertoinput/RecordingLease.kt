package com.example.whispertoinput

import java.io.File

/** Per-path ownership prevents a finishing/cancelled operation deleting a newer upload's recording. */
internal class RecordingLease private constructor(private val file: File) {
    private val path = file.absoluteFile.normalize().path
    private val length = file.length()
    private val modified = file.lastModified()

    fun deleteAfterSuccess(isActive: () -> Boolean) = synchronized(owners) {
        if (owners[path] === this && isActive() &&
            file.length() == length && file.lastModified() == modified) {
            file.delete()
        }
    }

    fun release() = synchronized(owners) {
        if (owners[path] === this) owners.remove(path)
        Unit
    }

    companion object {
        private val owners = mutableMapOf<String, RecordingLease>()
        fun acquire(file: File): RecordingLease = synchronized(owners) {
            RecordingLease(file).also { owners[it.path] = it }
        }
    }
}
