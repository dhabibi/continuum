package ml.docilealligator.infinityforreddit.multireddit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyMultiredditLinkTest {
    @Test
    void importsTheRequestedExample() {
        assertEquals(List.of("LocalLLaMA", "MachineLearning", "singularity", "ArtificialInteligence"),
                LegacyMultiredditLink.parse("https://reddit.com/r/LocalLLaMA+MachineLearning+singularity+ArtificialInteligence"));
    }

    @Test
    void acceptsRedditHostsAndShorthand() {
        for (String value : List.of("https://www.reddit.com/r/a+b+c/", "http://old.reddit.com/r/a+b+c",
                "old.reddit.com/r/a+b+c", "m.reddit.com/r/a+b+c", "/r/a+b+c", "r/a+b+c", "a+b+c")) {
            assertEquals(List.of("a", "b", "c"), LegacyMultiredditLink.parse(value), value);
        }
    }

    @Test
    void preservesOrderAndFirstSpellingWhileRemovingDuplicates() {
        assertEquals(List.of("LocalLLaMA", "MachineLearning", "u_some_user"),
                LegacyMultiredditLink.parse(" /r/LocalLLaMA+localllama++MachineLearning+u_some_user+MACHINELEARNING+ "));
    }

    @Test
    void decodesEncodedSeparatorsAndIgnoresSortQueryAndFragment() {
        assertEquals(List.of("a", "b", "c"),
                LegacyMultiredditLink.parse("https://old.reddit.com/r/a%2Bb+c/top/?t=all#posts"));
    }

    @Test
    void rejectsForeignHostsSchemesAndInvalidNamesWithoutPartialImport() {
        for (String value : List.of("https://reddit.com.evil.test/r/a+b", "https://evilreddit.com/r/a+b",
                "https://evil.test/r/a+b", "ftp://reddit.com/r/a+b", "https://user@reddit.com/r/a+b",
                "https://reddit.com/user/r/a+b", "/r/good+bad-name", "/r/a%2Bbad%20name", "/r/a%2Fb+c",
                "/r/a+" + "x".repeat(51), "/r/++", "https://reddit.com", "", "a", "/r/%broken")) {
            assertTrue(LegacyMultiredditLink.parse(value).isEmpty(), value);
        }
        assertTrue(LegacyMultiredditLink.parse(null).isEmpty());
    }
}
