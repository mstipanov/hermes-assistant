package hr.sting.hermes.voice

/**
 * Turns model output into text that sounds right when read by a TTS engine.
 *
 * The chat transcript keeps the original Markdown; only the spoken copy is
 * cleaned. Brackets, asterisks, table pipes and link targets are removed, not
 * the words inside them.
 */
object SpeechText {
    private val fencedCode = Regex("```[\\s\\S]*?```")
    private val openFence = Regex("```[\\s\\S]*$")
    private val inlineCode = Regex("`([^`]*)`")
    private val image = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
    private val angleUrl = Regex("<https?://[^>]*>")
    private val bareUrl = Regex("https?://\\S+")
    private val htmlTag = Regex("<[^>]+>")
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val bullet = Regex("(?m)^\\s*[-*+•]\\s+")
    private val numbered = Regex("(?m)^\\s*\\d+[.)]\\s+")
    private val thinkBlock = Regex("(?is)<(think|redacted_reasoning)[^>]*>.*?</\\1>")
    private val openThink = Regex("(?is)<(think|redacted_reasoning)[^>]*>.*$")
    private val formatting = Regex("[*_~`#>|]+")
    private val brackets = Regex("[\\[\\]{}()]")
    private val symbols = Regex("[\\p{So}\\p{Cs}]")
    private val whitespace = Regex("\\s+")

    fun sanitize(raw: String): String {
        var text = raw
        text = thinkBlock.replace(text, " ")
        text = openThink.replace(text, " ")
        text = fencedCode.replace(text, " ")
        text = openFence.replace(text, " ")
        text = inlineCode.replace(text, "$1")
        text = image.replace(text, "$1")
        text = link.replace(text, "$1")
        text = angleUrl.replace(text, " ")
        text = bareUrl.replace(text, " ")
        text = htmlTag.replace(text, " ")
        text = heading.replace(text, "")
        text = bullet.replace(text, "")
        text = numbered.replace(text, "")
        text = formatting.replace(text, " ")
        text = brackets.replace(text, " ")
        text = text.replace("&", " i ")
        text = text.replace("%", " posto ")
        text = symbols.replace(text, " ")
        return whitespace.replace(text, " ").trim()
    }

    /**
     * Pull complete sentences out of a streaming buffer, keeping the tail for
     * the next delta. Long unpunctuated text is cut at a word boundary so the
     * first audio does not wait for the end of a paragraph.
     */
    fun extract(buffer: StringBuilder, flush: Boolean): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val text = buffer.toString()
            if (text.isBlank()) {
                buffer.clear()
                break
            }
            val end = boundary(text, flush)
            if (end <= 0) break
            val chunk = text.substring(0, end)
            buffer.delete(0, end)
            val clean = sanitize(chunk)
            if (clean.isNotBlank()) out += clean
        }
        return out
    }

    private fun boundary(text: String, flush: Boolean): Int {
        for (index in text.indices) {
            val char = text[index]
            if (char == '\n') return index + 1
            if (char == '.' || char == '!' || char == '?') {
                val next = text.getOrNull(index + 1)
                if (next == null || next.isWhitespace()) return index + 1
            }
        }
        if (flush) return text.length
        if (text.length >= 180) {
            val cut = text.lastIndexOf(' ', 170)
            if (cut >= 120) return cut + 1
        }
        return -1
    }
}
