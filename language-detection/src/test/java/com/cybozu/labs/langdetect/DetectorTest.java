package com.cybozu.labs.langdetect;

import com.cybozu.labs.langdetect.util.LangProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Set;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.kgusarov.textprocessing.langdetect.LangProfileDocument;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class DetectorTest {
    private static final String TRAINING_EN = "a a a b b c c d e";
    private static final String TRAINING_FR = "a b b c c c d d d";
    private static final String TRAINING_JA = "\u3042 \u3042 \u3042 \u3044 \u3046 \u3048 \u3048";
    private static final String PROFILE_TEMPLATE = "{\"name\": \"%s\", \"n_words\": [0,0,0], \"freq\": {}}";

    private DetectorFactory detectorFactory;

    @Before
    public void setUp() throws Exception {
        detectorFactory = new DetectorFactory();
        final ObjectMapper mapper = new ObjectMapper();

        LangProfileDocument lpd = mapper.readValue(String.format(PROFILE_TEMPLATE, "en"), LangProfileDocument.class);
        final LangProfile en = new LangProfile(lpd);
        Arrays.stream(TRAINING_EN.split(" ")).forEach(w -> en.add(w));
        detectorFactory.addProfile(en);

        lpd = mapper.readValue(String.format(PROFILE_TEMPLATE, "fr"), LangProfileDocument.class);
        final LangProfile fr = new LangProfile(lpd);
        Arrays.stream(TRAINING_FR.split(" ")).forEach(w -> fr.add(w));
        detectorFactory.addProfile(fr);

        lpd = mapper.readValue(String.format(PROFILE_TEMPLATE, "ja"), LangProfileDocument.class);
        final LangProfile ja = new LangProfile(lpd);
        Arrays.stream(TRAINING_JA.split(" ")).forEach(w -> ja.add(w));
        detectorFactory.addProfile(ja);
    }

    @Test
    public final void testDetector1() throws LangDetectException {
        final Detector detector = detectorFactory.create();

        detector.append("a");
        assertEquals("en", detector.detect());
    }

    @Test
    public final void testDetector2() throws LangDetectException {
        final Detector detector = detectorFactory.create();

        detector.append("b d");
        assertEquals("fr", detector.detect());
    }

    @Test
    public final void testDetector3() throws LangDetectException {
        final Detector detector = detectorFactory.create();

        detector.append("d e");
        assertEquals("en", detector.detect());
    }

    @Test
    public final void testDetector4() throws LangDetectException {
        final Detector detector = detectorFactory.create();

        detector.append("\u3042\u3042\u3042\u3042a");
        assertEquals("ja", detector.detect());
    }

    @Test
    public final void testLangList() throws LangDetectException {
        final Set<String> langList = detectorFactory.getLangList();

        assertEquals(3, langList.size());
        Assert.assertTrue(langList.contains("en"));
        Assert.assertTrue(langList.contains("fr"));
        Assert.assertTrue(langList.contains("ja"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public final void testLangListException() throws LangDetectException {
        final Set<String> langList = detectorFactory.getLangList();

        langList.add("hoge");
    }
}