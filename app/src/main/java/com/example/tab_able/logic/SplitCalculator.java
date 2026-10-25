package com.example.tab_able.logic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SplitCalculator {

    public static class SplitResult {
        private final Map<String, Long> shares;
        private final boolean success;
        private final long difference; // sum - total

        public SplitResult(Map<String, Long> shares, boolean success, long difference) {
            this.shares = shares;
            this.success = success;
            this.difference = difference;
        }

        public Map<String, Long> getShares() { return shares; }
        public boolean isSuccess() { return success; }
        public long getDifference() { return difference; }
    }

    /**
     * Split totalPaise equally among uids.
     * base = total / n, first (total % n) get one extra paisa.
     */
    public static Map<String, Long> splitEqually(long totalPaise, List<String> uids) {
        Map<String, Long> shares = new HashMap<>();
        if (uids == null || uids.isEmpty()) return shares;

        int n = uids.size();
        long base = totalPaise / n;
        long remainder = totalPaise % n;

        for (int i = 0; i < n; i++) {
            long share = base + (i < remainder ? 1L : 0L);
            shares.put(uids.get(i), share);
        }
        return shares;
    }

    /**
     * Split custom amounts. Returns SplitResult containing shares if they sum exactly to totalPaise,
     * otherwise success = false and difference = sum - totalPaise.
     */
    public static SplitResult splitCustom(long totalPaise, Map<String, Long> amounts) {
        if (amounts == null) {
            return new SplitResult(new HashMap<>(), false, -totalPaise);
        }
        long sum = 0;
        for (Long amt : amounts.values()) {
            if (amt != null) sum += amt;
        }

        boolean success = (sum == totalPaise);
        long diff = sum - totalPaise;
        return new SplitResult(amounts, success, diff);
    }
}
