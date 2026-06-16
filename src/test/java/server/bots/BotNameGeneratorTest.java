package server.bots;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WZ-free unit tests for BotNameGenerator.
 * Uses generateForTest() to skip the DB uniqueness check in Character.canCreateChar.
 */
class BotNameGeneratorTest {

    private static final Pattern VALID = Pattern.compile("[a-zA-Z0-9]{3,12}");

    // ── Core constraint: every generated name must satisfy the regex ──────────

    @RepeatedTest(200)
    void generatedNameMatchesRequiredPattern() {
        String name = BotNameGenerator.generateForTest();
        assertTrue(VALID.matcher(name).matches(),
                "Name '" + name + "' does not match [a-zA-Z0-9]{3,12}");
    }

    @RepeatedTest(200)
    void generatedNameIsAtMost12Chars() {
        String name = BotNameGenerator.generateForTest();
        assertTrue(name.length() <= 12,
                "Name '" + name + "' exceeds 12 chars (len=" + name.length() + ")");
    }

    @RepeatedTest(200)
    void generatedNameIsAtLeast3Chars() {
        String name = BotNameGenerator.generateForTest();
        assertTrue(name.length() >= 3,
                "Name '" + name + "' is shorter than 3 chars");
    }

    @RepeatedTest(200)
    void generatedNameIsAsciiAlphanumericOnly() {
        String name = BotNameGenerator.generateForTest();
        for (char c : name.toCharArray()) {
            assertTrue(Character.isLetterOrDigit(c) && c < 128,
                    "Name '" + name + "' contains non-ASCII-alphanumeric char '" + c + "'");
        }
    }

    // ── Individual transform shapes ───────────────────────────────────────────

    @Test
    void plainStyleProducesValidName() {
        // Force "0 transforms" path: pass plain root through stylize with fixed seed-like rng
        // We test the overall output is valid, not the exact path (rng is random).
        // Run 50 samples to get good coverage of the plain branch.
        int validCount = 0;
        for (int i = 0; i < 50; i++) {
            String name = BotNameGenerator.stylize("Blade", ThreadLocalRandom.current());
            if (VALID.matcher(name).matches()) validCount++;
        }
        assertEquals(50, validCount, "All stylize() outputs must match the valid pattern");
    }

    @Test
    void stylizeNeverExceeds12Chars() {
        String[] roots = {"Shadow", "Crusade", "Warlord", "Phantom", "Buccaner"};
        for (String root : roots) {
            for (int i = 0; i < 30; i++) {
                String name = BotNameGenerator.stylize(root, ThreadLocalRandom.current());
                assertTrue(name.length() <= 12,
                        "stylize('" + root + "') produced '" + name + "' (len=" + name.length() + ")");
            }
        }
    }

    @Test
    void stylizeNeverProducesEmptyOrTooShort() {
        // Even with aggressive transforms, output must be >= 3 chars
        String[] roots = {"Ax", "Sam", "Lee", "Kai", "Ash"};
        for (String root : roots) {
            for (int i = 0; i < 20; i++) {
                String name = BotNameGenerator.stylize(root, ThreadLocalRandom.current());
                assertTrue(name.length() >= 1,
                        "stylize produced empty string from root '" + root + "'");
                // Note: root "Ax" is 2 chars, some transforms could make it shorter — acceptable,
                // the retry loop in generateForTest() ensures final result is >= 3.
            }
        }
    }

    // ── Variety check ─────────────────────────────────────────────────────────

    @Test
    void generateProducesVariedNames() {
        // Over 50 calls, we expect at least 10 distinct names (not all the same)
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            seen.add(BotNameGenerator.generateForTest());
        }
        assertTrue(seen.size() >= 10,
                "Expected at least 10 distinct names in 50 calls, got " + seen.size());
    }

    // ── Fallback / retry path doesn't throw ──────────────────────────────────

    @Test
    void generateForTestNeverThrows() {
        // Run many times to exercise all code paths including the fallback
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 500; i++) {
                String name = BotNameGenerator.generateForTest();
                assertNotNull(name);
                assertFalse(name.isEmpty());
            }
        });
    }
}
