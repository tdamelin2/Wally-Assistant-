package dev.tim.assistant.server.study

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

const val DEFAULT_STUDY_DIR = "/var/lib/assistant/study/"

/** ASSISTANT_STUDY_DIR overrides the default folder for study files. */
fun studyDirPath(env: Map<String, String> = System.getenv()): File =
    File(env["ASSISTANT_STUDY_DIR"]?.takeIf { it.isNotBlank() } ?: DEFAULT_STUDY_DIR)

fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Study file storage. The disk name of a file is the SHA-256 of its validated id (`<stem>` the original,
 * `<stem>.txt` the text), so no upload name ever becomes a path. Files are published with an atomic move.
 */
class StudyFiles(private val root: File) {
    private fun ensureRoot(): File {
        root.mkdirs()
        return root
    }

    fun stem(fileId: String): String = sha256Hex(fileId)

    fun original(fileId: String): File = File(root, stem(fileId))

    fun text(fileId: String): File = File(root, stem(fileId) + ".txt")

    /** A fresh temp file in the study folder (same volume, so [publish] can move it atomically). */
    fun newTemp(): File = Files.createTempFile(ensureRoot().toPath(), "tmp-", ".part").toFile()

    fun publish(temp: File, target: File) {
        ensureRoot()
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun writeText(fileId: String, text: String) {
        val tmp = newTemp()
        try {
            tmp.writeText(text, StandardCharsets.UTF_8)
            publish(tmp, text(fileId))
        } finally {
            tmp.delete()
        }
    }

    fun hasOriginal(fileId: String): Boolean = original(fileId).isFile

    fun hasAll(fileId: String): Boolean = original(fileId).isFile && text(fileId).isFile

    /** Removes the original and the text of [fileId]. */
    fun delete(fileId: String) {
        original(fileId).delete()
        text(fileId).delete()
    }

    /** Removes temp files left behind by a crash. */
    fun sweepTemps() {
        root.listFiles { f -> f.isFile && f.name.startsWith("tmp-") && f.name.endsWith(".part") }?.forEach { it.delete() }
    }
}
