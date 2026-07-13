package com.openclaw.assistant.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class OggOpusAndBase64Test {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `validates ogg magic and opus head`() {
        val file = write("voice.ogg", validOgg())
        OggOpusValidator.validate(file)
    }

    @Test
    fun `rejects non ogg and ogg without opus`() {
        assertThrows(InvalidVoiceAudioException::class.java) {
            OggOpusValidator.validate(write("not-ogg.bin", "RIFFdata".toByteArray()))
        }
        assertThrows(InvalidVoiceAudioException::class.java) {
            OggOpusValidator.validate(write("vorbis.ogg", "OggS-not-opus".toByteArray()))
        }
    }

    @Test
    fun `bounded decode writes exact file and removes partial`() {
        val bytes = validOgg()
        val target = File(temporaryFolder.root, "reply.ogg")
        BoundedBase64.decodeToFile(Base64.getEncoder().encodeToString(bytes), target, bytes.size)
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(temporaryFolder.root, "reply.ogg.part").exists())
    }

    @Test
    fun `oversized and malformed base64 leave no output`() {
        val target = File(temporaryFolder.root, "reply.ogg")
        assertThrows(InvalidVoiceAudioException::class.java) {
            BoundedBase64.decodeToFile(Base64.getEncoder().encodeToString(ByteArray(32)), target, 8)
        }
        assertFalse(target.exists())
        assertFalse(File(temporaryFolder.root, "reply.ogg.part").exists())

        assertThrows(InvalidVoiceAudioException::class.java) {
            BoundedBase64.decodeToFile("not@@base64", target, 64)
        }
        assertFalse(target.exists())
        assertFalse(File(temporaryFolder.root, "reply.ogg.part").exists())
    }

    @Test
    fun `input encoder rejects bytes above configured cap`() {
        val file = write("large.ogg", validOgg() + ByteArray(16))
        assertThrows(InvalidVoiceAudioException::class.java) {
            BoundedBase64.encodeFile(file, validOgg().size)
        }
    }

    @Test
    fun `voice cache cleanup is idempotent`() {
        val directory = File(temporaryFolder.root, "ctb_voice")
        val store = CtbVoiceFileStore(directory)
        val input = store.newInputFile().apply { writeBytes(validOgg()) }
        val output = store.newOutputFile().apply { writeBytes(validOgg()) }
        store.delete(input)
        store.delete(input)
        store.deleteAll()
        store.deleteAll()
        assertFalse(input.exists())
        assertFalse(output.exists())
        assertFalse(directory.exists())
    }

    private fun write(name: String, bytes: ByteArray): File =
        File(temporaryFolder.root, name).apply { writeBytes(bytes) }

    private fun validOgg(): ByteArray =
        "OggS".toByteArray() + ByteArray(24) + "OpusHead".toByteArray() + ByteArray(16)
}
