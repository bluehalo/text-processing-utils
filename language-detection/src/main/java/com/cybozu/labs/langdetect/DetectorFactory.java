package com.cybozu.labs.langdetect;

/*
 * Copyright (C) 2010-2014 Cybozu Labs, 2016 Konstantin Gusarov
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

import com.cybozu.labs.langdetect.util.LangProfile;
import com.cybozu.labs.langdetect.util.NGram;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import com.google.common.io.Resources;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.kgusarov.textprocessing.langdetect.LangProfileDocument;
import org.reflections.Reflections;
import org.reflections.scanners.ResourcesScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Language Detector Factory Class</p>
 * <p>This class manages an initialization and constructions of {@link Detector}.</p>
 * <p>When the language detection,
 * construct Detector instance via {@link DetectorFactory#create()}. See also {@link Detector}'s sample
 * code.</p>
 * <ul>
 * <li>4x faster improvement based on Elmer Garduno's code. Thanks!</li>
 * </ul>
 *
 * @author Nakatani Shuyo
 * @author Konstantin Gusarov
 * @author Scott Tomaszewski
 * @see Detector
 */
@SuppressWarnings("unchecked")
public class DetectorFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(DetectorFactory.class);
    private static final ObjectMapper JACKSON = new ObjectMapper();
    private static final Pattern SHORT_MESSAGE_RESOURCES = Pattern.compile("^sm/(.*)\\.json$");
    private static final Pattern LONG_MESSAGE_RESOURCES = Pattern.compile("^nr/(.*)\\.json$");
    private static final Pattern JSON_MATCHER = Pattern.compile("^(.*)\\.json$");

    /**
     * Create new {@code DetectorFactory} by loading resources from the classpath
     *
     * @param shortMessages Should this detector factory use short message profiles
     * @throws LangDetectException In case language profiles weren't read for some reason
     */
    public static DetectorFactory fromProfilesOnClasspath(final boolean shortMessages) {
        final Pattern resourceFilter = shortMessages ? SHORT_MESSAGE_RESOURCES : LONG_MESSAGE_RESOURCES;
        final Reflections reflections = new Reflections(null, new ResourcesScanner());
        final List<String> resources = reflections.getResources(JSON_MATCHER).stream()
                .filter(s -> resourceFilter.matcher(s).matches())
                .collect(Collectors.toList());

        final int languageCount = resources.size();
        final ClassLoader cl = Thread.currentThread().getContextClassLoader();
        final DetectorFactory detector = new DetectorFactory();
        for (int i = 0; i < languageCount; i++) {
            final String profile = resources.get(i);
            try (final InputStream is = cl.getResourceAsStream(profile)) {
                detector.addProfile(is);
            } catch (final IOException e) {
                throw new LangDetectException(ErrorCode.FAILED_TO_INITIALIZE,
                        "Failed to read language profile", e);
            }
        }
        return detector;
    }

    private final Table<String, String, Double> nGramToLanguageToProbability = HashBasedTable.create();

    /**
     * Adds a profile to this DetectorFactory from a URL resource.
     */
    public DetectorFactory addProfile(URL profileToAdd) throws IOException {
        try (InputStream in = Resources.asByteSource(profileToAdd).openStream()) {
            return addProfile(in);
        }
    }

    /**
     * Adds a profile to this DetectorFactory.  Does not close or flush the stream.
     */
    public DetectorFactory addProfile(InputStream profileToAdd) {
        try {
            LangProfileDocument lpd = JACKSON.readValue(profileToAdd, LangProfileDocument.class);
            LangProfile langProfile = new LangProfile(lpd);
            addProfile(langProfile);
        } catch (final IOException e) {
            throw new LangDetectException(ErrorCode.FAILED_TO_INITIALIZE,
                    "Failed to read language profile", e);
        }
        return this;
    }

    /**
     * Construct Detector instance
     *
     * @return Detector instance
     * @throws LangDetectException In case factory contains no language profiles
     */
    public Detector create() {
        return createDetector();
    }

    /**
     * Construct Detector instance with smoothing parameter
     *
     * @param alpha Smoothing parameter (default value = 0.5)
     * @return Detector instance
     * @throws LangDetectException In case factory contains no language profiles
     */
    public Detector create(final double alpha) {
        final Detector detector = createDetector();
        detector.setAlpha(alpha);
        return detector;
    }

    private Detector createDetector() {
        if (nGramToLanguageToProbability.columnKeySet().isEmpty()) {
            throw new LangDetectException(ErrorCode.PROFILE_NOT_LOADED,
                    "No language profile classes found");
        }

        // pick a "sorted" list of languages
        List<String> languages = new ArrayList(getLangList());
        Map<String, double[]> nGramToProbabilities = new HashMap<>();

        for (Entry<String, Map<String, Double>> row : nGramToLanguageToProbability.rowMap().entrySet()) {
            double[] sortedProbabilities = languages.stream()
                    .map(lang -> row.getValue().get(lang))
                    .map(prob -> prob != null ? prob : 0)
                    .mapToDouble(Double::doubleValue)
                    .toArray();
            nGramToProbabilities.put(row.getKey(), sortedProbabilities);
        }
        return new Detector(nGramToProbabilities, languages);
    }

    public Set<String> getLangList() {
        return ImmutableSet.copyOf(nGramToLanguageToProbability.columnKeySet());
    }

    @VisibleForTesting
    void addProfile(final LangProfile profile) {
        String language = profile.getName();

        if (nGramToLanguageToProbability.containsColumn(language)) {
            throw new LangDetectException(ErrorCode.DUPLICATE_LANGUAGE,
                    language + " language profile is already defined");
        }

        for (final Map.Entry<String, Integer> entry : profile.getFrequencies().entrySet()) {
            String ngram = entry.getKey();
            int nGramLength = ngram.length();
            int[] nGramCount = profile.getNGramCount();
            if (nGramLength >= 1 && nGramLength <= NGram.MAX_NGRAM_LENGTH) {
                double count = entry.getValue().doubleValue();
                double probability = count / nGramCount[nGramLength - 1];
                nGramToLanguageToProbability.put(ngram, language, probability);
            } else {
                LOGGER.warn("Invalid n-gram in language profile: {}", ngram);
            }
        }
    }
}
