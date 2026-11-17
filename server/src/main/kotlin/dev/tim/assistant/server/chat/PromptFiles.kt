package dev.tim.assistant.server.chat

private object PromptFilesAnchor

/** Reads a text file from the server's resources (trimmed); a missing file is a build error, not a silent empty prompt. */
fun resourceText(name: String): String =
    PromptFilesAnchor::class.java.getResourceAsStream("/$name")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.trim()
        ?: error("$name is missing from the server")

/** The one voice every brain shares (B30). */
fun voiceText(): String = resourceText("wally-voice.md")

/** Every system prompt is the voice, a blank line, then the brain's own file. */
fun assemblePrompt(brainFile: String): String = voiceText() + "\n\n" + resourceText(brainFile)

/** The laptop model's persona: the voice plus "How you act". */
fun laptopPersona(): String = assemblePrompt("wally-persona.md")
