# ICU sentence-break golden generator

Regenerates `jpdfium/src/test/resources/pii/sentence-goldens.tsv`, the reference
boundaries behind `SentenceSegmentationParityTest`.

The goldens capture the behaviour of the native implementation that
[PR #90](../../CHANGELOG.md) removed: `jpdfium_icu_break_sentences`, which called
`icu::BreakIterator::createSentenceInstance(icu::Locale::getDefault())` and
walked `first()`/`next()` over a UTF-16 string. Offsets are therefore UTF-16
code units in both the goldens and `java.text.BreakIterator`, so the test can
compare them directly.

```sh
cd tools/icu-sentence-golden
c++ -O2 -std=c++17 $(pkg-config --cflags icu-i18n) icu_sentence_golden.cpp \
    -o icu_sentence_golden $(pkg-config --libs icu-i18n)
./icu_sentence_golden \
    ../../jpdfium/src/test/resources/pii/sentence-corpus.tsv \
    ../../jpdfium/src/test/resources/pii/sentence-goldens.tsv
```

The generator recomputes every case with `Locale::getDefault()` as well and
exits non-zero if any entry is locale-dependent, so the committed goldens never
depend on the machine that produced them. Requires ICU >= 70; the committed
file was produced with ICU 78.3.
