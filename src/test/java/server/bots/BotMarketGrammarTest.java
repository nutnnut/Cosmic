package server.bots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import server.bots.BotMarketGrammar.Kind;
import server.bots.BotMarketGrammar.Offer;

/** Pure structural + resolution tests for the shout grammar — a fake name catalog is injected so no
 *  WZ data is needed (design sec 8.4; ASCII invariant 3). */
class BotMarketGrammarTest {

    private static final Map<String, Integer> CATALOG = Map.of(
            "brown work glove", 1082002,
            "ilbis", 1332006,
            "fish spear", 1442003,
            "red whip", 1372005);

    private ToIntFunction<String> savedResolver;

    @BeforeEach
    void injectFakeCatalog() {
        savedResolver = BotMarketGrammar.nameResolver;
        BotMarketGrammar.nameResolver = name -> CATALOG.getOrDefault(name.toLowerCase(), -1);
    }

    @AfterEach
    void restoreResolver() {
        BotMarketGrammar.nameResolver = savedResolver;
    }

    @Test
    void parsesSellOfferByName() {
        Offer o = BotMarketGrammar.parse("S> brown work glove 1m");
        assertEquals(new Offer(Kind.SELL, 1082002, 1, 1_000_000), o);
    }

    @Test
    void parsesBuyOfferByName() {
        Offer o = BotMarketGrammar.parse("B> ilbis 300k");
        assertEquals(new Offer(Kind.BUY, 1332006, 1, 300_000), o);
    }

    @Test
    void parsesPriceCheck() {
        Offer o = BotMarketGrammar.parse("PC> fish spear");
        assertEquals(new Offer(Kind.PRICE_CHECK, 1442003, 1, 0), o);
    }

    @Test
    void parsesQuantityAndItemIdToken() {
        Offer o = BotMarketGrammar.parse("S> #2000000 x3 500k");
        assertEquals(new Offer(Kind.SELL, 2000000, 3, 500_000), o);
    }

    @Test
    void caseInsensitivePrefixAndName() {
        Offer o = BotMarketGrammar.parse("  s>  Red Whip  5m ");
        assertEquals(new Offer(Kind.SELL, 1372005, 1, 5_000_000), o);
    }

    @Test
    void rejectsUnresolvedName() {
        assertNull(BotMarketGrammar.parse("S> nonexistent thing 1m"));
    }

    @Test
    void rejectsMissingPrice() {
        assertNull(BotMarketGrammar.parse("S> ilbis"));
    }

    @Test
    void rejectsNonShoutLine() {
        assertNull(BotMarketGrammar.parse("hey anyone selling ilbis?"));
        assertNull(BotMarketGrammar.parse(null));
    }

    @Test
    void looksLikeShoutIsCheapPrefixGate() {
        assertTrue(BotMarketGrammar.looksLikeShout("S> x 1m"));
        assertTrue(BotMarketGrammar.looksLikeShout("  pc> y"));
        assertTrue(!BotMarketGrammar.looksLikeShout("selling stuff"));
    }

    @Test
    void parsesStyledPrefixVariants() {
        assertEquals(new Offer(Kind.SELL, 1332006, 1, 300_000), BotMarketGrammar.parse("SELL> ilbis 300k"));
        assertEquals(new Offer(Kind.SELL, 1332006, 1, 300_000), BotMarketGrammar.parse("Selling> ilbis 300k"));
        assertEquals(new Offer(Kind.BUY, 1332006, 1, 300_000), BotMarketGrammar.parse("BUY> ilbis 300k"));
        assertEquals(new Offer(Kind.BUY, 1332006, 1, 300_000), BotMarketGrammar.parse("Buying> ilbis 300k"));
        assertEquals(new Offer(Kind.PRICE_CHECK, 1442003, 1, 0), BotMarketGrammar.parse("price> fish spear"));
        assertTrue(BotMarketGrammar.looksLikeShout("SELL> x 1m"));
        assertTrue(BotMarketGrammar.looksLikeShout("Buying> y 1m"));
        assertTrue(!BotMarketGrammar.looksLikeShout("selling stuff")); // no '>' -> not a shout
    }

    @Test
    void styledSellShoutsStayRecognizableShouts() {
        // Every styled chatter line (varied prefix/suffix/case) must still read as a shout.
        for (int bot = 0; bot < 200; bot++) {
            String line = BotMarketChatter.sellShout("red whip", 5_000_000, bot);
            assertTrue(BotMarketGrammar.looksLikeShout(line), "not a recognizable shout: " + line);
        }
    }

    @Test
    void formatRoundTripsAndStaysAscii() {
        String sell = BotMarketGrammar.format(Kind.SELL, "red whip", 1, 5_000_000);
        assertEquals("S> red whip 5m", sell);
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(sell));

        String buyQty = BotMarketGrammar.format(Kind.BUY, "ilbis", 4, 300_000);
        assertEquals("B> ilbis x4 300k", buyQty);
        assertEquals(new Offer(Kind.BUY, 1332006, 4, 300_000), BotMarketGrammar.parse(buyQty));

        assertEquals("PC> fish spear", BotMarketGrammar.format(Kind.PRICE_CHECK, "fish spear", 1, 0));
        assertEquals("1500k", BotMarketGrammar.mesoShort(1_500_000));
        assertEquals("1234567", BotMarketGrammar.mesoShort(1_234_567));
    }
}
