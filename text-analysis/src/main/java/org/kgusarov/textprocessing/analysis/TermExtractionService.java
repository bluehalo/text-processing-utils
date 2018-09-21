package org.kgusarov.textprocessing.analysis;

/*
 * Copyright (C) 2016 Konstantin Gusarov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.cybozu.labs.langdetect.Detector;
import com.cybozu.labs.langdetect.DetectorFactory;
import com.cybozu.labs.langdetect.LangDetectException;
import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.bg.BulgarianAnalyzer;
import org.apache.lucene.analysis.br.BrazilianAnalyzer;
import org.apache.lucene.analysis.ca.CatalanAnalyzer;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.da.DanishAnalyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.el.GreekAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.es.SpanishAnalyzer;
import org.apache.lucene.analysis.fa.PersianAnalyzer;
import org.apache.lucene.analysis.fi.FinnishAnalyzer;
import org.apache.lucene.analysis.fr.FrenchAnalyzer;
import org.apache.lucene.analysis.hi.HindiAnalyzer;
import org.apache.lucene.analysis.hu.HungarianAnalyzer;
import org.apache.lucene.analysis.id.IndonesianAnalyzer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.lv.LatvianAnalyzer;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.analysis.no.NorwegianAnalyzer;
import org.apache.lucene.analysis.pt.PortugueseAnalyzer;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.sv.SwedishAnalyzer;
import org.apache.lucene.analysis.th.ThaiAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tr.TurkishAnalyzer;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter.GENERATE_WORD_PARTS;
import static org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter.PRESERVE_ORIGINAL;

/**
 * <p>This service is meant for term extraction from arbitary text by using {@code com.cybozu.labs.langdetect.Detector}
 * and {@code org.apache.lucene.analysis.Analyzer}</p>
 * <p>It performs multiple actions in the following order:</p>
 * <ul>
 *     <li>Tries to identify the language text is written in by using {@code com.cybozu.labs.langdetect.Detector}</li>
 *     <li>
 *          In case language was successfully detected and appropriate {@code org.apache.lucene.analysis.Analyzer}
 *          can be found it is being used to produce term stream that is considered result of the term extraction
 *     </li>
 *     <li>
 *          Otherwise {@code org.apache.lucene.analysis.standard.StandardAnalyzer} is being used for term stream production
 *     </li>
 * </ul>
 *
 * @author Konstantin Gusarov
 */
public class TermExtractionService {
    private static final Map<String, Supplier<Analyzer>> ANALYZERS_BY_LANGUAGE = Maps.newHashMap();
    static {
        ANALYZERS_BY_LANGUAGE.put("ar", ArabicAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("bg", BulgarianAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("br", BrazilianAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("ca", CatalanAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("cs", CzechAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("da", DanishAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("de", GermanAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("el", GreekAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("en", EnglishAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("es", SpanishAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("fa", PersianAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("fi", FinnishAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("fr", FrenchAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("hi", HindiAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("hu", HungarianAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("id", IndonesianAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("it", ItalianAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("lv", LatvianAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("nl", DutchAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("no", NorwegianAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("pt", PortugueseAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("ro", RomanianAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("ru", RussianAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("sv", SwedishAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("th", ThaiAnalyzer::new);
        ANALYZERS_BY_LANGUAGE.put("tr", TurkishAnalyzer::new);
    }

    private final DetectorFactory detectorFactory;
    private final Map<String, Supplier<Analyzer>> analyzers;

    /**
     * Create new {@code TermExtractionService}
     *
     * @param shortMessages             Should this term extraction service use short message profiles
     * @throws TextAnalysisException    In case language {@code com.cybozu.labs.langdetect.DetectorFactory} fails to initialize
     */
    public TermExtractionService(final boolean shortMessages) {
        try {
            detectorFactory = DetectorFactory.fromProfilesOnClasspath(shortMessages);
            analyzers = initializeAnalyzers();
        } catch (final LangDetectException e) {
            throw new TextAnalysisException("Failed to create language detector factory", e);
        }
    }

    /**
     * Extracts terms from the given text
     *
     * @param text                      Text to extract terms from
     * @return                          List of extracted terms
     * @throws TextAnalysisException    In case term extraction failed which can be due to language detection or analysis
     *                                  itself
     */
    public List<String> getTerms(final String text) {
        final String language;

        try {
            final Detector detector = detectorFactory.create();
            detector.append(text);

            language = detector.detect();
        } catch (final LangDetectException e) {
            throw new TextAnalysisException("Failed to detect language for " + text, e);
        }

        final Supplier<Analyzer> analyzerSupplier = analyzers.getOrDefault(language, StandardAnalyzer::new);
        return tokenizeString(analyzerSupplier, text);
    }

    private static List<String> tokenizeString(final Supplier<Analyzer> analyzerSupplier, final String string) {
        final List<String> result = Lists.newArrayList();

        try (
                final Reader sr = new StringReader(string);
                final Analyzer analyzer = analyzerSupplier.get();
                final TokenStream s = analyzer.tokenStream(null, sr);
                final TokenStream stream = new WordDelimiterFilter(s, GENERATE_WORD_PARTS | PRESERVE_ORIGINAL, null);
        ) {
            stream.reset();
            while (stream.incrementToken()) {
                final CharTermAttribute attr = stream.getAttribute(CharTermAttribute.class);
                final String term = attr.toString();

                result.add(term);
            }
        } catch (final IOException e) {
            throw new TextAnalysisException("Failed to tokenize string: " + string, e);
        }

        return result;
    }


    private Map<String, Supplier<Analyzer>> initializeAnalyzers() {
        final Map<String, Supplier<Analyzer>> result = Maps.newHashMap();
        final Set<String> languages = detectorFactory.getLangList();

        for (final String language : languages) {
            final Supplier<Analyzer> analyzer = ANALYZERS_BY_LANGUAGE.getOrDefault(language, StandardAnalyzer::new);
            result.put(language, analyzer);
        }

        return Collections.unmodifiableMap(result);
    }
}
