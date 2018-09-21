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

import com.cybozu.labs.langdetect.util.NGram;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.Character.UnicodeBlock;
import java.util.*;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringEscapeUtils.escapeJava;

/**
 * <p>
 * {@link com.cybozu.labs.langdetect.Detector} class is to detect language from specified text.
 * Its instance is able to be constructed via the factory class {@link DetectorFactory}.
 * </p>
 * <p>
 * After appending a target text to the {@link com.cybozu.labs.langdetect.Detector} instance with {@link #append(String)},
 * the detector provides the language detection results for target text via {@link #detect()} or {@link #getProbabilities()}.
 * {@link #detect()} method returns a single language name which has the highest probability.
 * {@link #getProbabilities()} methods returns a list of multiple languages and their probabilities.
 * </p>
 * <p>
 * The detector has some parameters for language detection.
 * See {@link #setAlpha(double)}, {@link #setMaxTextLength(int)} and {@link #setPriorMap(java.util.Map)}.
 * </p>
 * <pre>
 * {@code
 * import java.util.List;
 * import com.cybozu.labs.langdetect.Detector;
 * import com.cybozu.labs.langdetect.DetectorFactory;
 * import com.cybozu.labs.langdetect.Language;
 *
 * class LangDetectSample {
 *     public String detect(String text) throws LangDetectException {
 *         Detector detector = DetectorFactory.create();
 *         detector.append(text);
 *         return detector.detect();
 *     }
 *
 *     public List<Language> detectLangs(String text) throws LangDetectException {
 *         Detector detector = DetectorFactory.create();
 *         detector.append(text);
 *         return detector.getProbabilities();
 *     }
 * }
 * }
 * </pre>
 * <ul>
 *  <li>4x faster improvement based on Elmer Garduno's code. Thanks!</li>
 * </ul>
 *
 * @author Nakatani Shuyo
 * @author Konstantin Gusarov
 * @see DetectorFactory
 */
public class Detector {
    private static final Logger LOGGER = LoggerFactory.getLogger(Detector.class);

    private static final double DEFAULT_ALHPA = 0.5;
    private static final double ALPHA_WIDTH = 0.05;

    private static final int TRIAL_COUNT = 7;
    private static final int ITERATION_LIMIT = 1000;
    private static final double PROB_THRESHOLD = 0.1;
    private static final double CONV_THRESHOLD = 0.99999;
    private static final int BASE_FREQ = 10000;
    private static final String UNKNOWN_LANG = "unknown";

    private static final Pattern URL_REGEX = Pattern.compile("https?://[-_.?&~;+=/#0-9A-Za-z]{1,2076}");
    private static final Pattern MAIL_REGEX = Pattern.compile("[-_.0-9A-Za-z]{1,64}@[-_0-9A-Za-z]{1,255}[-_.0-9A-Za-z]{1,255}");
    private static final int DEFAULT_MAX_TEXT_LENGTH = 10000;
    private static final int CHECK_TRESHOLDS_ITERATION = 5;
    private static final double MINIMAL_PROBABILITY_TO_DISPLAY = 0.00001;

    private final Map<String, double[]> languageProbabilityMap;
    private final List<String> languageList;
    private final Random rand = new Random();

    private String text;
    private double[] languageProbabilities;

    private double alpha = DEFAULT_ALHPA;
    private int maxTextLength = DEFAULT_MAX_TEXT_LENGTH;
    private double[] priorMap;
    private boolean verbose;

    Detector(final Map<String, double[]> languageProbabilityMap, List<String> languages) {
        this.languageProbabilityMap = languageProbabilityMap;
        this.languageList = languages;
    }

    /**
     * Set Verbose Mode (use for debug).
     *
     * @param verbose       {@code true} if mode should be set to verbose
     */
    public void setVerbose(final boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Set smoothing parameter.
     * The default value is 0.5 (i.e. Expected Likelihood Estimate).
     *
     * @param alpha         The smoothing parameter
     */
    public void setAlpha(final double alpha) {
        this.alpha = alpha;
    }

    /**
     * Set priority information about language probabilities.
     *
     * @param priorMap                  The priority map to set
     * @throws LangDetectException      In case one of the probabilities defined in the input map
     *                                  is not non-negative
     */
    public void setPriorMap(final Map<String, Double> priorMap) throws LangDetectException {
        this.priorMap = new double[languageList.size()];

        double sump = 0;
        for (int i = 0; i < this.priorMap.length; i++) {
            final String lang = languageList.get(i);
            if (priorMap.containsKey(lang)) {
                final double p = priorMap.get(lang);
                if (p < 0) {
                    throw new LangDetectException(ErrorCode.INIT_PARAM, "Prior probability must be non-negative.");
                }

                this.priorMap[i] = p;
                sump += p;
            }
        }

        if (sump <= 0) {
            throw new LangDetectException(ErrorCode.INIT_PARAM, "More one of prior probability must be non-zero.");
        }

        for (int i = 0; i < this.priorMap.length; i++) {
            this.priorMap[i] /= sump;
        }
    }

    /**
     * Specify max size of target text to use for language detection.
     * The default value is 10000(10KB).
     *
     * @param maxTextLength         Max size of target text
     */
    public void setMaxTextLength(final int maxTextLength) {
        this.maxTextLength = maxTextLength;
    }

    /**
     * Append the target text for language detection.
     * If the total size of target text exceeds the limit size specified by {@link com.cybozu.labs.langdetect.Detector#setMaxTextLength(int)},
     * the rest is cut down.
     *
     * @param text                  The target text to append
     */
    public void append(final String text) {
        String sanitized = URL_REGEX.matcher(text).replaceAll(" ");
        sanitized = MAIL_REGEX.matcher(sanitized).replaceAll(" ");
        sanitized = NGram.normalizeVietnamese(sanitized);

        final StringBuilder sb = new StringBuilder((this.text == null)? "" : this.text);

        char pre = 0;
        for (int i = 0; i < sanitized.length() && i < maxTextLength; i++) {
            final char c = sanitized.charAt(i);
            if ((c != ' ') || (pre != ' ')) {
                sb.append(c);
            }

            pre = c;
        }

        this.text = sb.toString();
        languageProbabilities = null;
    }

    /**
     * Perform text cleanup
     * (eliminate URL, e-mail address and Latin sentence if it is not written in Latin alphabet)
     */
    private void cleanupText() {
        int latinCount = 0;
        int nonLatinCount = 0;

        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c <= 'z' && c >= 'A') {
                latinCount++;
            } else if ((c >= '\u0300') && !Objects.equals(UnicodeBlock.of(c), UnicodeBlock.LATIN_EXTENDED_ADDITIONAL)) {
                nonLatinCount++;
            }
        }

        if ((latinCount << 1) < nonLatinCount) {
            final StringBuilder textWithoutLatin = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);

                if (c > 'z' || c < 'A') {
                    textWithoutLatin.append(c);
                }
            }

            text = textWithoutLatin.toString();
        }
    }

    /**
     * Detect language of the target text and return the language name which has the highest probability.
     *
     * @return                      Detected language name which has most probability.
     * @throws LangDetectException  Can't detect because of no valid features in text
     */
    public String detect() throws LangDetectException {
        final List<Language> probabilities = getProbabilities();
        final Optional<Language> result = probabilities.stream().findFirst();

        return result.isPresent()? result.get().getCode() : UNKNOWN_LANG;
    }

    /**
     * Get language candidates which have high probabilities
     *
     * @return                      Possible languages list (whose probabilities are over PROB_THRESHOLD, ordered by probabilities descendently)
     * @throws LangDetectException  Can't detect because of no valid features in text
     */
    public List<Language> getProbabilities() throws LangDetectException {
        if (languageProbabilities == null) {
            detectBlock();
        }

        return sortProbabilities(languageProbabilities);
    }
    
    private void detectBlock() throws LangDetectException {
        cleanupText();

        final List<String> ngrams = extractNGrams();
        if (ngrams.isEmpty()) {
            throw new LangDetectException(ErrorCode.CANNOT_DETECT, "no features in text");
        }

        languageProbabilities = new double[languageList.size()];
        final int nGramCount = ngrams.size();

        for (int t = 0; t < TRIAL_COUNT; t++) {
            performTrial(ngrams, nGramCount);
        }
    }

    private void performTrial(final List<String> ngrams, final int nGramCount) {
        final double[] prob = initProbabilities();
        final double a = this.alpha + rand.nextGaussian() * ALPHA_WIDTH;

        for (int i = 0; ; i++) {
            final int r = rand.nextInt(nGramCount);
            final String nGram = ngrams.get(r);
            updateLanguageProbilities(prob, nGram, a);

            if ((i % CHECK_TRESHOLDS_ITERATION) == 0) {
                if ((normalizeProbabilities(prob) > CONV_THRESHOLD) || (i >= ITERATION_LIMIT)){
                    break;
                }

                if (verbose) {
                    final List<Language> sortedProbs = sortProbabilities(prob);
                    LOGGER.debug("> {}", sortedProbs);
                }
            }
        }

        for (int i = 0; i < languageProbabilities.length; i++) {
            languageProbabilities[i] += prob[i] / TRIAL_COUNT;
        }

        if (verbose) {
            final List<Language> sortedProbs = sortProbabilities(prob);
            LOGGER.debug("==> {}", sortedProbs);
        }
    }

    private double[] initProbabilities() {
        final double[] prob = new double[languageList.size()];

        if (priorMap != null) {
            System.arraycopy(priorMap, 0, prob, 0, prob.length);
        } else {
            for (int i = 0; i < prob.length; i++) {
                prob[i] = 1.0 / languageList.size();
            }
        }

        return prob;
    }

    private List<String> extractNGrams() {
        final List<String> list = Lists.newArrayList();
        final NGram ngram = new NGram();

        final int l = text.length();
        for (int i = 0; i < l; i++) {
            final char ch = text.charAt(i);
            ngram.addChar(ch);

            for (int n = 1; n <= NGram.MAX_NGRAM_LENGTH; n++) {
                final String w = ngram.get(n);

                if ((w != null) && languageProbabilityMap.containsKey(w)) {
                    list.add(w);
                }
            }
        }

        return list;
    }

    private boolean updateLanguageProbilities(final double[] prob, final String word, final double alpha) {
        if ((word == null) || !languageProbabilityMap.containsKey(word)) {
            return false;
        }

        final double[] langProbMap = languageProbabilityMap.get(word);
        if (verbose) {
            final String escaped = escapeJava(word);
            final String probs = wordProbToString(langProbMap);

            LOGGER.debug("{} ({}): {}", word, escaped, probs);
        }

        final double weight = alpha / BASE_FREQ;
        for (int i = 0; i < prob.length; i++) {
            prob[i] *= weight + langProbMap[i];
        }

        return true;
    }

    private String wordProbToString(final double[] prob) {
        try (final Formatter formatter = new Formatter()) {
            for (int j = 0; j < prob.length; j++) {
                final double p = prob[j];
                if (p >= MINIMAL_PROBABILITY_TO_DISPLAY) {
                    final String s = languageList.get(j);
                    formatter.format(" %s:%.5f", s, p);
                }
            }

            return formatter.toString();
        }
    }

    private static double normalizeProbabilities(final double[] prob) {
        double probabilitySum = 0;
        for (final double d : prob) {
            probabilitySum += d;
        }

        double maxProbability = 0;
        for (int i = 0; i < prob.length; i++) {
            final double p = prob[i] / probabilitySum;
            if (maxProbability < p) {
                maxProbability = p;
            }

            prob[i] = p;
        }

        return maxProbability;
    }

    private List<Language> sortProbabilities(final double[] prob) {
        final List<Language> list = Lists.newArrayList();
        for (int j = 0; j < prob.length; j++) {
            processProbability(prob[j], list, j);
        }

        return list;
    }

    private void processProbability(final double probability, final List<Language> list, final int j) {
        final String jth = languageList.get(j);

        if (probability > PROB_THRESHOLD) {
            for (int i = 0; i <= list.size(); i++) {
                if ((i == list.size()) || (list.get(i).getProbability() < probability)) {
                    list.add(i, new Language(jth, probability));
                    break;
                }
            }
        }
    }
}
