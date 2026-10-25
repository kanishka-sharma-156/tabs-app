package com.example.tab_able.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a bill by line item.
 *
 * Each item has a price and the set of people who consumed it, and its price is
 * divided equally among exactly those people. A person's total is the sum of their
 * portions across all items.
 *
 * This is the honest way to split a restaurant bill: whoever shared the garlic
 * bread pays for the garlic bread, and the person who only had a drink does not.
 *
 * All amounts are paise. The per-item split uses {@link SplitCalculator}, so
 * remainders are distributed one paisa at a time and no money is ever lost.
 */
public final class ItemizedSplit {

    private ItemizedSplit() {
    }

    /** One line on the bill. */
    public static final class Item {
        private String name;
        private long amountPaise;
        private final List<String> consumerUids = new ArrayList<>();

        public Item() {
        }

        public Item(String name, long amountPaise, List<String> consumerUids) {
            this.name = name != null ? name : "";
            this.amountPaise = amountPaise;
            if (consumerUids != null) this.consumerUids.addAll(consumerUids);
        }

        public String getName() { return name != null ? name : ""; }
        public void setName(String name) { this.name = name; }

        public long getAmountPaise() { return amountPaise; }
        public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }

        public List<String> getConsumerUids() { return consumerUids; }
        public void setConsumerUids(List<String> uids) {
            consumerUids.clear();
            if (uids != null) consumerUids.addAll(uids);
        }

        /** An item counts only when it has a price and at least one consumer. */
        public boolean isUsable() {
            return amountPaise > 0 && !consumerUids.isEmpty();
        }
    }

    /** Per-person totals plus the running bill total. */
    public static final class Result {
        private final Map<String, Long> sharesPerUid;
        private final long totalPaise;
        private final long unassignedPaise;
        private final int usableItems;

        Result(Map<String, Long> sharesPerUid, long totalPaise, long unassignedPaise, int usableItems) {
            this.sharesPerUid = sharesPerUid;
            this.totalPaise = totalPaise;
            this.unassignedPaise = unassignedPaise;
            this.usableItems = usableItems;
        }

        public Map<String, Long> getSharesPerUid() { return sharesPerUid; }
        public long getTotalPaise() { return totalPaise; }
        public int getUsableItems() { return usableItems; }

        /** Items with a price but nobody selected: nobody can be charged for them. */
        public long getUnassignedPaise() { return unassignedPaise; }

        public boolean hasUnassignedItems() { return unassignedPaise > 0; }

        /** True when every usable item has at least one consumer. */
        public boolean isComplete() { return unassignedPaise == 0; }
    }

    /**
     * Computes what each person owes.
     *
     * @param items          the line items; items without a price or without any
     *                       consumer are skipped
     * @param participantUids everyone who could be charged, used so a person with
     *                        no items still appears with a zero share
     */
    public static Result compute(List<Item> items, List<String> participantUids) {
        Map<String, Long> shares = new LinkedHashMap<>();
        if (participantUids != null) {
            for (String uid : participantUids) {
                if (uid != null && !uid.isEmpty()) shares.put(uid, 0L);
            }
        }

        long totalPaise = 0;
        long unassignedPaise = 0;
        int usableItems = 0;

        if (items != null) {
            for (Item item : items) {
                if (item == null || item.getAmountPaise() <= 0) continue;

                // An item nobody is assigned to cannot be charged to anyone. Report it
                // rather than silently dropping the money.
                if (item.getConsumerUids().isEmpty()) {
                    unassignedPaise += item.getAmountPaise();
                    continue;
                }

                usableItems++;
                totalPaise += item.getAmountPaise();

                Map<String, Long> portions =
                        SplitCalculator.splitEqually(item.getAmountPaise(), item.getConsumerUids());

                for (Map.Entry<String, Long> portion : portions.entrySet()) {
                    String uid = portion.getKey();
                    Long existing = shares.get(uid);
                    shares.put(uid, (existing != null ? existing : 0L) + portion.getValue());
                }
            }
        }

        return new Result(shares, totalPaise, unassignedPaise, usableItems);
    }

    /** Sum of every item that carries a price, regardless of who consumed it. */
    public static long sumOfPricedItems(List<Item> items) {
        long sum = 0;
        if (items == null) return 0;
        for (Item item : items) {
            if (item != null && item.getAmountPaise() > 0) sum += item.getAmountPaise();
        }
        return sum;
    }
}
