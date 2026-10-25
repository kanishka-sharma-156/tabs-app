package com.example.tab_able.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The itemised split: each line item is divided among exactly the people who had
 * it. This is the part of the app where a mistake silently moves money between
 * friends, so it is pinned down carefully.
 */
public class ItemizedSplitTest {

    private static final String YOU = "you";
    private static final String MEHER = "meher";
    private static final String ARJUN = "arjun";

    private static List<String> everyone() {
        return Arrays.asList(YOU, MEHER, ARJUN);
    }

    private static ItemizedSplit.Item item(String name, long paise, String... uids) {
        return new ItemizedSplit.Item(name, paise, Arrays.asList(uids));
    }

    /**
     * The case from the screenshot: a pizza shared by all three, then a garlic
     * bread and several drinks that only some people had.
     */
    @Test
    public void splitsEachItemAmongOnlyThePeopleWhoHadIt() {
        List<ItemizedSplit.Item> items = new ArrayList<>();
        items.add(item("Large pizza", 65000, YOU, MEHER, ARJUN));   // 650 / 3
        items.add(item("Garlic bread", 18000, YOU, MEHER));         // 180 / 2
        items.add(item("Cold coffee", 16000, YOU));                 // 160 / 1
        items.add(item("Mojito", 22000, MEHER));                    // 220 / 1
        items.add(item("Coke", 9000, ARJUN));                       //  90 / 1

        ItemizedSplit.Result result = ItemizedSplit.compute(items, everyone());

        long total = 65000 + 18000 + 16000 + 22000 + 9000;   // 130000 paise = 1300
        assertEquals(130000L, result.getTotalPaise());
        assertEquals(5, result.getUsableItems());
        assertTrue("every item has someone assigned", result.isComplete());
        assertFalse(result.hasUnassignedItems());

        // The shares must add up to exactly the bill - no paise lost or invented.
        long sum = 0;
        for (Long share : result.getSharesPerUid().values()) sum += share;
        assertEquals("shares must equal the bill total exactly", total, sum);

        // Nobody who had only cheap items should be charged for the pizza.
        long you = result.getSharesPerUid().get(YOU);
        long meher = result.getSharesPerUid().get(MEHER);
        long arjun = result.getSharesPerUid().get(ARJUN);

        // you: 650/3 + 180/2 + 160   meher: 650/3 + 180/2 + 220   arjun: 650/3 + 90
        assertTrue("you should owe the most here", you > arjun);
        assertTrue("meher had a mojito so owes more than arjun", meher > arjun);
        assertEquals(total, you + meher + arjun);
    }

    @Test
    public void remainderPaiseAreDistributedNotLost() {
        // 100 paise across 3 people cannot divide evenly.
        List<ItemizedSplit.Item> items = Collections.singletonList(
                item("chai", 100, YOU, MEHER, ARJUN));

        ItemizedSplit.Result result = ItemizedSplit.compute(items, everyone());

        long sum = 0;
        for (Long share : result.getSharesPerUid().values()) sum += share;
        assertEquals("100 paise in must equal 100 paise out", 100L, sum);

        // 33/33/34 in some order - every share is within one paisa of the others.
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Long share : result.getSharesPerUid().values()) {
            min = Math.min(min, share);
            max = Math.max(max, share);
        }
        assertTrue("shares should differ by at most 1 paisa, got " + min + ".." + max,
                max - min <= 1);
    }

    /**
     * An item with a price but nobody selected cannot be charged to anyone. It must
     * be reported, not quietly spread over everyone.
     */
    @Test
    public void reportsItemsThatNobodyIsAssignedTo() {
        List<ItemizedSplit.Item> items = new ArrayList<>();
        items.add(item("pizza", 30000, YOU, MEHER));
        items.add(item("mystery side", 10000));   // nobody selected

        ItemizedSplit.Result result = ItemizedSplit.compute(items, everyone());

        assertEquals(30000L, result.getTotalPaise());
        assertEquals(10000L, result.getUnassignedPaise());
        assertTrue(result.hasUnassignedItems());
        assertFalse("must block submit while an item is unassigned", result.isComplete());

        long sum = 0;
        for (Long share : result.getSharesPerUid().values()) sum += share;
        assertEquals("the unassigned item must not be charged to anyone", 30000L, sum);
    }

    @Test
    public void itemWithNobodyAssignedIsNotCountedAsUsable() {
        List<ItemizedSplit.Item> items = Collections.singletonList(item("ghost", 5000));
        ItemizedSplit.Result result = ItemizedSplit.compute(items, everyone());

        assertEquals(0, result.getUsableItems());
        assertEquals(0L, result.getTotalPaise());
        assertEquals(5000L, result.getUnassignedPaise());
    }

    @Test
    public void zeroOrNegativePricedItemsAreIgnored() {
        List<ItemizedSplit.Item> items = new ArrayList<>();
        items.add(item("free water", 0, YOU));
        items.add(item("pizza", 20000, YOU, MEHER));

        ItemizedSplit.Result result = ItemizedSplit.compute(items, everyone());

        assertEquals(20000L, result.getTotalPaise());
        assertEquals(1, result.getUsableItems());
        assertTrue(result.isComplete());
    }

    @Test
    public void everyoneAppearsEvenWithNoItems() {
        List<ItemizedSplit.Item> items = Collections.singletonList(item("pizza", 10000, YOU));
        ItemizedSplit.Result result = ItemizedSplit.compute(items, everyone());

        assertEquals(0L, (long) result.getSharesPerUid().get(MEHER));
        assertEquals(0L, (long) result.getSharesPerUid().get(ARJUN));
        assertEquals(10000L, (long) result.getSharesPerUid().get(YOU));
    }

    /** Someone outside the participant list who consumed an item still gets charged. */
    @Test
    public void consumerNotInParticipantListIsStillCharged() {
        List<ItemizedSplit.Item> items = Collections.singletonList(
                item("pizza", 30000, YOU, "stranger"));

        ItemizedSplit.Result result = ItemizedSplit.compute(items, everyone());

        assertEquals(15000L, (long) result.getSharesPerUid().get(YOU));
        assertEquals(15000L, (long) result.getSharesPerUid().get("stranger"));
    }

    @Test
    public void emptyInputIsSafe() {
        ItemizedSplit.Result result = ItemizedSplit.compute(new ArrayList<>(), everyone());
        assertEquals(0L, result.getTotalPaise());
        assertEquals(0, result.getUsableItems());
        assertTrue("an empty bill has nothing unassigned", result.isComplete());
        assertEquals(3, result.getSharesPerUid().size());

        ItemizedSplit.Result nulls = ItemizedSplit.compute(null, null);
        assertEquals(0L, nulls.getTotalPaise());
        assertTrue(nulls.getSharesPerUid().isEmpty());
    }

    @Test
    public void singlePersonPaysForEverythingTheyHad() {
        List<ItemizedSplit.Item> items = new ArrayList<>();
        items.add(item("pizza", 50000, YOU));
        items.add(item("coke", 9000, YOU));

        ItemizedSplit.Result result = ItemizedSplit.compute(items, everyone());

        assertEquals(59000L, result.getTotalPaise());
        assertEquals(59000L, (long) result.getSharesPerUid().get(YOU));
        assertEquals(0L, (long) result.getSharesPerUid().get(MEHER));
    }

    /** Independent cross-check: portions must sum to the item price, per item. */
    @Test
    public void everyItemIsFullyAccountedFor() {
        List<ItemizedSplit.Item> items = new ArrayList<>();
        items.add(item("a", 1000, YOU, MEHER, ARJUN));
        items.add(item("b", 777, YOU, ARJUN));
        items.add(item("c", 1, MEHER));

        Map<String, Long> portions;
        for (ItemizedSplit.Item it : items) {
            portions = SplitCalculator.splitEqually(it.getAmountPaise(), it.getConsumerUids());
            long sum = 0;
            for (Long p : portions.values()) sum += p;
            assertEquals("item '" + it.getName() + "' must be fully split",
                    it.getAmountPaise(), sum);
        }
    }

    /** Model round-trip, since these are written to Firestore as maps. */
    @Test
    public void itemModelKeepsItsData() {
        ItemizedSplit.Item it = new ItemizedSplit.Item();
        it.setName("Pizza");
        it.setAmountPaise(65000);
        it.setConsumerUids(Arrays.asList(YOU, MEHER));

        assertEquals("Pizza", it.getName());
        assertEquals(65000L, it.getAmountPaise());
        assertEquals(2, it.getConsumerUids().size());
        assertTrue(it.isUsable());

        it.setConsumerUids(null);
        assertTrue(it.getConsumerUids().isEmpty());
        assertFalse("an item with no consumers is not usable", it.isUsable());
    }
}
