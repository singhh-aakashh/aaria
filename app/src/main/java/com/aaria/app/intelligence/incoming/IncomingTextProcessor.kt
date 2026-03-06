package com.aaria.app.intelligence.incoming

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IncomingTextProcessor(
    private val emojiProcessor: EmojiProcessor = EmojiProcessor(),
    private val abbreviationExpander: AbbreviationExpander = AbbreviationExpander(),
    private val languageDetector: LanguageDetector = LanguageDetector(),
    private val wordTagger: WordTagger = WordTagger(),
    private val transliterator: Transliterator = Transliterator(),
    private val ssmlBuilder: SsmlBuilder = SsmlBuilder()
) {

    /**
     * Full text intelligence pipeline for an incoming WhatsApp message.
     *
     * Runs on [Dispatchers.IO] because [LanguageDetector.detect] blocks on the
     * ML Kit Tasks API (must not run on the main thread).
     */
    suspend fun process(rawText: String): ProcessedMessage = withContext(Dispatchers.IO) {
        val afterEmoji = emojiProcessor.process(rawText)
        val afterAbbrev = abbreviationExpander.expand(afterEmoji)
        val langProfile = languageDetector.detect(afterAbbrev)
        val taggedWords = wordTagger.tag(afterAbbrev, langProfile)
        val transliterated = transliterator.transliterate(taggedWords)
        val ssml = ssmlBuilder.build(transliterated)

        ProcessedMessage(
            original = rawText,
            plainText = afterAbbrev,
            ssml = ssml,
            languageProfile = langProfile
        )
    }

    fun close() {
        languageDetector.close()
    }

    data class ProcessedMessage(
        val original: String,
        val plainText: String,
        val ssml: String,
        val languageProfile: LanguageDetector.LanguageProfile
    )
}
