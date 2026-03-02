package com.aaria.app.intelligence.incoming

class IncomingTextProcessor(
    private val emojiProcessor: EmojiProcessor = EmojiProcessor(),
    private val abbreviationExpander: AbbreviationExpander = AbbreviationExpander(),
    private val languageDetector: LanguageDetector = LanguageDetector(),
    private val wordTagger: WordTagger = WordTagger(),
    private val transliterator: Transliterator = Transliterator(),
    private val ssmlBuilder: SsmlBuilder = SsmlBuilder()
) {

    fun process(rawText: String): ProcessedMessage {
        val afterEmoji = emojiProcessor.process(rawText)
        val afterAbbrev = abbreviationExpander.expand(afterEmoji)
        val langProfile = languageDetector.detect(afterAbbrev)
        val taggedWords = wordTagger.tag(afterAbbrev, langProfile)
        val transliterated = transliterator.transliterate(taggedWords)
        val ssml = ssmlBuilder.build(transliterated)

        return ProcessedMessage(
            original = rawText,
            plainText = afterAbbrev,
            ssml = ssml,
            languageProfile = langProfile
        )
    }

    data class ProcessedMessage(
        val original: String,
        val plainText: String,
        val ssml: String,
        val languageProfile: LanguageDetector.LanguageProfile
    )
}
