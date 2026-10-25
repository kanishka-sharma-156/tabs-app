package com.example.tab_able.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DebtSimplifier {

    public static class Transfer {
        private final String fromUid;
        private final String toUid;
        private final long amountPaise;

        public Transfer(String fromUid, String toUid, long amountPaise) {
            this.fromUid = fromUid != null ? fromUid : "";
            this.toUid = toUid != null ? toUid : "";
            this.amountPaise = amountPaise;
        }

        public String getFromUid() { return fromUid; }
        public String getToUid() { return toUid; }
        public long getAmountPaise() { return amountPaise; }

        @Override
        public String toString() {
            return fromUid + " pays " + toUid + " : " + Money.formatInr(amountPaise);
        }
    }

    public static class BalanceEntry implements Comparable<BalanceEntry> {
        private final String uid;
        private long amount; // positive = creditor, negative = debtor

        public BalanceEntry(String uid, long amount) {
            this.uid = uid;
            this.amount = amount;
        }

        public String getUid() { return uid; }
        public long getAmount() { return amount; }
        public void setAmount(long amount) { this.amount = amount; }

        @Override
        public int compareTo(BalanceEntry other) {
            return Long.compare(this.amount, other.amount); // ascending (debtors first, creditors last)
        }
    }

    /**
     * Simplify debts using greedy algorithm: match largest debtor to largest creditor.
     */
    public static List<Transfer> simplify(Map<String, Long> netBalances) {
        List<Transfer> transfers = new ArrayList<>();
        if (netBalances == null || netBalances.isEmpty()) return transfers;

        List<BalanceEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Long> entry : netMapCleaned(netBalances).entrySet()) {
            if (entry.getValue() != 0) {
                entries.add(new BalanceEntry(entry.getKey(), entry.getValue()));
            }
        }

        while (true) {
            Collections.sort(entries); // ascending: index 0 is most negative (debtor), last index is most positive (creditor)
            if (entries.isEmpty()) break;

            BalanceEntry debtor = entries.get(0);
            BalanceEntry creditor = entries.get(entries.size() - 1);

            if (debtor.getAmount() >= 0 || creditor.getAmount() <= 0) {
                break; // all balanced
            }

            long debt = -debtor.getAmount(); // positive magnitude
            long credit = creditor.getAmount(); // positive magnitude
            long transferAmount = Math.min(debt, credit);

            transfers.add(new Transfer(debtor.getUid(), creditor.getUid(), transferAmount));

            debtor.setAmount(debtor.getAmount() + transferAmount);
            creditor.setAmount(creditor.getAmount() - transferAmount);

            if (Math.abs(debtor.getAmount()) < 1L) {
                entries.remove(0);
            }
            if (Math.abs(creditor.getAmount()) < 1L) {
                entries.remove(entries.size() - 1);
            }
        }

        return transfers;
    }

    private static Map<String, Long> netMapCleaned(Map<String, Long> netBalances) {
        Map<String, Long> cleaned = new HashMap<>();
        for (Map.Entry<String, Long> e : netBalances.entrySet()) {
            if (e.getValue() != null && Math.abs(e.getValue()) > 0L) {
                cleaned.put(e.getKey(), e.getValue());
            }
        }
        return cleaned;
    }
}
