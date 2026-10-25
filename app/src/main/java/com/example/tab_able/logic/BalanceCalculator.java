package com.example.tab_able.logic;

import java.util.HashMap;
import java.util.Map;

public class BalanceCalculator {

    public static class ExpenseInput {
        private final String payerUid;
        private final Map<String, Long> shares; // uid -> amount in paise

        public ExpenseInput(String payerUid, Map<String, Long> shares) {
            this.payerUid = payerUid != null ? payerUid : "";
            this.shares = shares != null ? shares : new HashMap<>();
        }

        public String getPayerUid() { return payerUid; }
        public Map<String, Long> getShares() { return shares; }

        public long getTotalAmount() {
            long total = 0;
            for (Long val : shares.values()) {
                if (val != null) total += val;
            }
            return total;
        }
    }

    public static class SettlementInput {
        private final String fromUid;
        private final String toUid;
        private final long amountPaise;

        public SettlementInput(String fromUid, String toUid, long amountPaise) {
            this.fromUid = fromUid != null ? fromUid : "";
            this.toUid = toUid != null ? toUid : "";
            this.amountPaise = amountPaise;
        }

        public String getFromUid() { return fromUid; }
        public String getToUid() { return toUid; }
        public long getAmountPaise() { return amountPaise; }
    }

    /**
     * Compute net balances per user given expenses and settlements.
     * Positive balance = they are owed money (creditor).
     * Negative balance = they owe money (debtor).
     */
    public static Map<String, Long> computeNetBalances(Iterable<ExpenseInput> expenses, Iterable<SettlementInput> settlements) {
        Map<String, Long> netMap = new HashMap<>();

        if (expenses != null) {
            for (ExpenseInput exp : expenses) {
                String payer = exp.getPayerUid();
                long total = exp.getTotalAmount();

                // Credited full amount to payer
                netMap.put(payer, netMap.getOrDefault(payer, 0L) + total);

                // Debited each person's share
                for (Map.Entry<String, Long> entry : exp.getShares().entrySet()) {
                    String user = entry.getKey();
                    long share = entry.getValue() != null ? entry.getValue() : 0L;
                    netMap.put(user, netMap.getOrDefault(user, 0L) - share);
                }
            }
        }

        if (settlements != null) {
            for (SettlementInput st : settlements) {
                String from = st.getFromUid();
                String to = st.getToUid();
                long amt = st.getAmountPaise();

                // From paid To -> From's debt reduced (net increases), To's credit reduced (net decreases)
                netMap.put(from, netMap.getOrDefault(from, 0L) + amt);
                netMap.put(to, netMap.getOrDefault(to, 0L) - amt);
            }
        }

        return netMap;
    }

    /**
     * Compute pairwise balance between userA and userB.
     * Positive = userA is owed by userB.
     * Negative = userA owes userB.
     */
    public static long computePairwiseBalance(String userA, String userB, Iterable<ExpenseInput> expenses, Iterable<SettlementInput> settlements) {
        // Can be derived by computing net balances and looking at mutual transactions or tracing
        // For simplicity and accuracy, derive via filtered expenses between userA and userB
        long balanceAtoB = 0; // how much B owes A

        if (expenses != null) {
            for (ExpenseInput exp : expenses) {
                String payer = exp.getPayerUid();
                if (payer.equals(userA)) {
                    // userA paid, check what userB owes
                    Long shareB = exp.getShares().get(userB);
                    if (shareB != null) balanceAtoB += shareB;
                } else if (payer.equals(userB)) {
                    // userB paid, check what userA owes
                    Long shareA = exp.getShares().get(userA);
                    if (shareA != null) balanceAtoB -= shareA;
                }
            }
        }

        if (settlements != null) {
            for (SettlementInput st : settlements) {
                if (st.getFromUid().equals(userA) && st.getToUid().equals(userB)) {
                    balanceAtoB -= st.getAmountPaise(); // A paid B, reducing B's debt to A
                } else if (st.getFromUid().equals(userB) && st.getToUid().equals(userA)) {
                    balanceAtoB += st.getAmountPaise(); // B paid A, reducing A's debt to B
                }
            }
        }

        return balanceAtoB;
    }
}
