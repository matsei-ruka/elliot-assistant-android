package com.openclaw.assistant.voice

import android.content.Context
import java.io.File
import java.util.UUID

/** App-private, disposable files for the current CTB voice turn. */
class CtbVoiceFileStore(private val directory: File) {
    constructor(context: Context) : this(File(context.cacheDir, DIRECTORY_NAME))

    fun newInputFile(): File = newFile("input")
    fun newOutputFile(): File = newFile("reply")

    fun delete(file: File?) {
        if (file != null && file.parentFile == directory) file.delete()
    }

    fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
        if (directory.exists() && directory.listFiles().isNullOrEmpty()) directory.delete()
    }

    private fun newFile(prefix: String): File {
        directory.mkdirs()
        return File(directory, "$prefix-${UUID.randomUUID()}.ogg")
    }

    companion object {
        private const val DIRECTORY_NAME = "ctb_voice"
    }
}
