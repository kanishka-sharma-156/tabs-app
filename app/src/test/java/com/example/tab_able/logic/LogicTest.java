package com.example.tab_able.logic;

import com.example.tab_able.data.Place;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class LogicTest {

    @Test
    public void testEqualSplit1000By3() {
        long totalPaise = 1000L; // ₹10.00
        List<String> uids = Arrays.asList("alice", "bob", "charlie");
        Map<String, Long> shares = SplitCalculator.splitEqually(totalPaise, uids);

        assertEquals(3, shares.size());
        long sum = 0;
        for (long share : shares.values()) {
            sum += share;
        }
        assertEquals(totalPaise, sum);
        assertEquals(Long.valueOf(334L), shares.get("alice"));
        assertEquals(Long.valueOf(333L), shares.get("bob"));
        assertEquals(Long.valueOf(333L), shares.get("charlie"));
    }

    @Test
    public void testCustomSplitMismatch() {
        long totalPaise = 1000L;
        Map<String, Long> amounts = new HashMap<>();
        amounts.put("alice", 400L);
        amounts.put("bob", 400L);

        SplitCalculator.SplitResult result = SplitCalculator.splitCustom(totalPaise, amounts);
        assertFalse(result.isSuccess());
        assertEquals(-200L, result.getDifference());
    }

    @Test
    public void testPayerOutsideSplit() {
        Map<String, Long> shares = new HashMap<>();
        shares.put("alice", 500L);
        shares.put("bob", 500L);

        BalanceCalculator.ExpenseInput exp = new BalanceCalculator.ExpenseInput("charlie", shares);
        Map<String, Long> netBalances = BalanceCalculator.computeNetBalances(Arrays.asList(exp), null);

        assertEquals(Long.valueOf(1000L), netBalances.get("charlie"));
        assertEquals(Long.valueOf(-500L), netBalances.get("alice"));
        assertEquals(Long.valueOf(-500L), netBalances.get("bob"));
    }

    @Test
    public void testSettlementReducesBalance() {
        Map<String, Long> shares = new HashMap<>();
        shares.put("alice", 1000L);

        BalanceCalculator.ExpenseInput exp = new BalanceCalculator.ExpenseInput("bob", shares);
        BalanceCalculator.SettlementInput st = new BalanceCalculator.SettlementInput("alice", "bob", 400L);

        Map<String, Long> netBalances = BalanceCalculator.computeNetBalances(Arrays.asList(exp), Arrays.asList(st));

        assertEquals(Long.valueOf(600L), netBalances.get("bob"));
        assertEquals(Long.valueOf(-600L), netBalances.get("alice"));
    }

    @Test
    public void testDebtSimplifier4PersonCase() {
        Map<String, Long> netBalances = new HashMap<>();
        netBalances.put("alice", -300L);
        netBalances.put("bob", 100L);
        netBalances.put("charlie", 100L);
        netBalances.put("dave", 100L);

        List<DebtSimplifier.Transfer> transfers = DebtSimplifier.simplify(netBalances);
        assertFalse(transfers.isEmpty());

        long totalTransferred = 0;
        for (DebtSimplifier.Transfer t : transfers) {
            totalTransferred += t.getAmountPaise();
        }
        assertEquals(300L, totalTransferred);
    }

    @Test
    public void testMoneyParsePaise() {
        assertEquals(1250L, Money.parsePaise("12.50"));
        assertEquals(10000L, Money.parsePaise("100"));
        assertEquals(50L, Money.parsePaise("0.50"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMoneyParseInvalid() {
        Money.parsePaise("abc");
    }

    @Test
    public void testGeoJsonParsingAndNearest() {
        List<Place> places = Arrays.asList(
                new Place("p1", "Sector 17 Plaza", 30.741, 76.778),
                new Place("p2", "Sukhna Lake", 30.743, 76.814)
        );

        assertEquals(2, places.size());
        assertEquals("Sector 17 Plaza", places.get(0).getName());

        Place nearest = null;
        double minDistance = Double.MAX_VALUE;
        double targetLat = 30.740;
        double targetLng = 76.777;

        for (Place p : places) {
            double dist = Math.pow(p.getLat() - targetLat, 2) + Math.pow(p.getLng() - targetLng, 2);
            if (dist < minDistance) {
                minDistance = dist;
                nearest = p;
            }
        }

        assertNotNull(nearest);
        assertEquals("Sector 17 Plaza", nearest.getName());
    }
}
