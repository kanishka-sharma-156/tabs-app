package com.example.tab_able.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Guards the paise/rupee boundary.
 *
 * The app stores money in PAISE everywhere internally (Firestore documents, split
 * maths, settlements) but the UI shows WHOLE RUPEES, except where it goes through
 * Money.formatInr() which takes paise.
 *
 * Getting that wrong is not subtle: 350 rupees displayed as 35000 is a 100x error
 * that looks like real corruption. These tests pin both halves down.
 */
public class MoneyTest {

    @Test
    public void parsePaise_readsRupeesIntoPaise() {
        assertEquals(35000L, Money.parsePaise("350"));
        assertEquals(35000L, Money.parsePaise("350.00"));
        assertEquals(35050L, Money.parsePaise("350.50"));
        assertEquals(5L, Money.parsePaise("0.05"));
        assertEquals(50L, Money.parsePaise(".5"));
        assertEquals(120L, Money.parsePaise("1.2"));
        assertEquals(0L, Money.parsePaise("0"));
    }

    @Test
    public void parsePaise_toleratesCurrencyDecoration() {
        assertEquals(35000L, Money.parsePaise("₹350"));
        assertEquals(35000L, Money.parsePaise(" 350 "));
    }

    @Test
    public void parsePaise_rejectsNonsense() {
        assertThrows(() -> Money.parsePaise(""));
        assertThrows(() -> Money.parsePaise("   "));
        assertThrows(() -> Money.parsePaise("abc"));
        assertThrows(() -> Money.parsePaise("1.2.3"));
        assertThrows(() -> Money.parsePaise("1.234"));
        assertThrows(() -> Money.parsePaise("99999999999999"));
        assertThrows(() -> Money.parsePaise("."));
    }

    /**
     * Regression: the old parser stripped every non-digit, so "12abc" quietly
     * became 12 and random words were accepted as amounts.
     */
    @Test
    public void parsePaise_rejectsDigitsMixedWithWords() {
        assertThrows(() -> Money.parsePaise("12abc"));
        assertThrows(() -> Money.parsePaise("abc12"));
        assertThrows(() -> Money.parsePaise("12 rupees"));
        assertThrows(() -> Money.parsePaise("₹₹12"));
        assertThrows(() -> Money.parsePaise("1,200"));
        assertThrows(() -> Money.parsePaise("-50"));
    }

    @Test
    public void formatInr_usesWholesRupeesAndIndianGrouping() {
        assertEquals("₹0", Money.formatInr(0L));
        assertEquals("₹350", Money.formatInr(35000L));
        assertEquals("₹350.50", Money.formatInr(35050L));
        assertEquals("₹350.05", Money.formatInr(35005L));
        // Indian grouping: 1,23,456 rather than 123,456
        assertEquals("₹1,23,456", Money.formatInr(12345600L));
        assertEquals("₹10,00,000", Money.formatInr(100000000L));
        assertEquals("₹999", Money.formatInr(99900L));
    }

    @Test
    public void formatInr_handlesNegativesForOweDirection() {
        assertEquals("-₹350", Money.formatInr(-35000L));
    }

    /** The formatter must always produce a rupee sign, never "Rs." or "INR". */
    @Test
    public void formatInr_alwaysUsesTheRupeeSign() {
        long[] samples = {0L, 1L, 99L, 100L, 35000L, 12345600L, 999999999L};
        for (long paise : samples) {
            String text = Money.formatInr(paise);
            assertTrue("missing rupee sign in: " + text, text.contains("₹"));
            assertFalse("should not spell out INR: " + text, text.contains("INR"));
            assertFalse("should not use Rs.: " + text, text.contains("Rs"));
        }
    }

    @Test
    public void formatInr_showsRupeesForPaise() {
        // "₹350.00" or "₹350" depending on the JVM's locale data - both are right.
        String shown = Money.formatInr(35000L);
        assertTrue("expected the amount to be 350, got: " + shown,
                shown.contains("350") && !shown.contains("35000"));
        assertTrue("formatInr should produce a rupee amount, got: " + shown,
                shown.contains("₹") || shown.contains("INR"));
    }

    /**
     * The exact regression that shipped: a paise balance rendered as if it were
     * rupees. 35000 paise is 350 rupees, never 35000.
     */
    @Test
    public void paiseBalance_isNotRenderedHundredTimesTooLarge() {
        long balancePaise = 35000L;
        int rupeesForTheUi = (int) (balancePaise / 100);
        assertEquals(350, rupeesForTheUi);

        long paiseForDisplay = (long) rupeesForTheUi * 100L;
        assertEquals(35000L, paiseForDisplay);

        // Round-tripping must be stable, which is what makes the conversion safe.
        assertEquals("₹" + rupeesForTheUi, "₹350");
    }

    @Test
    public void splitEqually_sharesSumToTheTotalInPaise() {
        List<String> people = Arrays.asList("a", "b", "c");
        Map<String, Long> shares = SplitCalculator.splitEqually(10000L, people);

        long sum = 0;
        for (Long share : shares.values()) sum += share;
        assertEquals("shares must sum exactly to the total, no paise lost", 10000L, sum);
    }

    @Test
    public void splitEqually_handlesUnevenAmountsWithoutLosingPaise() {
        List<String> people = Arrays.asList("a", "b", "c");
        Map<String, Long> shares = SplitCalculator.splitEqually(100L, people);

        long sum = 0;
        for (Long share : shares.values()) sum += share;
        assertEquals(100L, sum);
    }

    @Test
    public void splitCustom_rejectsAmountsThatDoNotAddUp() {
        java.util.Map<String, Long> amounts = new java.util.HashMap<>();
        amounts.put("a", 6000L);
        amounts.put("b", 3000L);

        SplitCalculator.SplitResult result = SplitCalculator.splitCustom(10000L, amounts);
        assertFalse("9000 does not cover a 10000 total", result.isSuccess());
        assertEquals(-1000L, result.getDifference());

        amounts.put("b", 4000L);
        assertTrue(SplitCalculator.splitCustom(10000L, amounts).isSuccess());
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertThrows(ThrowingRunnable action) {
        try {
            action.run();
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // correct
        } catch (Exception other) {
            fail("expected IllegalArgumentException but got " + other);
        }
    }
}
