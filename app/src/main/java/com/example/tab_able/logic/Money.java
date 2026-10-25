package com.example.tab_able.logic;

/**
 * Money helpers.
 *
 * Internally all amounts are PAISE (long). The UI shows rupees, so anything
 * printed goes through {@link #formatInr(long)}.
 */
public class Money {

    /**
     * Safely parse user input string (e.g. "12.50", "100") into long paise.
     *
     * Strict on purpose. It used to strip every non-digit character, which meant
     * "12abc" silently became 12 and random words were accepted as amounts. Now
     * anything that is not a plain positive amount is rejected, so the user is
     * told rather than having a wrong number saved.
     */
    public static long parsePaise(String input) throws IllegalArgumentException {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Amount cannot be empty");
        }

        String clean = input.trim();

        // Allow a single leading rupee sign, then digits with an optional decimal part.
        if (clean.startsWith("₹")) {
            clean = clean.substring(1).trim();
        }
        if (!clean.matches("\\d*\\.?\\d*")) {
            throw new IllegalArgumentException("Amount must be a number, e.g. 250 or 99.50");
        }
        if (clean.isEmpty() || clean.equals(".")) {
            throw new IllegalArgumentException("Amount must be a number, e.g. 250 or 99.50");
        }

        // Count decimal points.
        int dotCount = 0;
        for (char c : clean.toCharArray()) {
            if (c == '.') dotCount++;
        }
        if (dotCount > 1) {
            throw new IllegalArgumentException("Too many decimal points");
        }

        try {
            if (clean.contains(".")) {
                String[] parts = clean.split("\\.", -1);
                String rupeesPart = parts[0].isEmpty() ? "0" : parts[0];
                String paisaPart = parts.length > 1 ? parts[1] : "";

                if (rupeesPart.length() > 10) {
                    throw new IllegalArgumentException("Amount too large");
                }
                if (paisaPart.length() > 2) {
                    throw new IllegalArgumentException("At most 2 decimal places allowed");
                }
                if (paisaPart.length() == 1) {
                    paisaPart = paisaPart + "0";
                } else if (paisaPart.isEmpty()) {
                    paisaPart = "00";
                }

                long rupees = Long.parseLong(rupeesPart);
                long paisas = Long.parseLong(paisaPart);
                return rupees * 100L + paisas;
            } else {
                if (clean.length() > 10) {
                    throw new IllegalArgumentException("Amount too large");
                }
                long rupees = Long.parseLong(clean);
                return rupees * 100L;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format");
        }
    }

    /**
     * Format paise as rupees for display, e.g. 35000 -> "₹350", 35050 -> "₹350.50".
     *
     * Uses Indian digit grouping (1,23,456) and emits whole rupees when there are
     * no paise, because almost every amount in this app is a whole rupee figure and
     * trailing ".00" everywhere is noise.
     *
     * Deliberately does NOT use NumberFormat.getCurrencyInstance: that depends on
     * the device's locale data, and on many devices it prints "Rs." or "INR"
     * instead of the rupee sign, which is how wrong-looking totals appear.
     */
    public static String formatInr(long paise) {
        boolean negative = paise < 0;
        long absolute = Math.abs(paise);

        long rupees = absolute / 100;
        long paisas = absolute % 100;

        StringBuilder out = new StringBuilder();
        if (negative) out.append('-');
        out.append('₹').append(groupIndian(rupees));
        if (paisas != 0) {
            out.append('.').append(paisas < 10 ? "0" + paisas : String.valueOf(paisas));
        }
        return out.toString();
    }

    /** Groups digits the Indian way: 1,23,456 rather than 123,456. */
    static String groupIndian(long value) {
        String digits = Long.toString(value);
        if (digits.length() <= 3) return digits;

        String lastThree = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);

        StringBuilder grouped = new StringBuilder();
        int count = 0;
        for (int i = rest.length() - 1; i >= 0; i--) {
            grouped.append(rest.charAt(i));
            if (++count == 2 && i > 0) {
                grouped.append(',');
                count = 0;
            }
        }
        return grouped.reverse() + "," + lastThree;
    }

    public static String formatRupees(long paise) {
        return formatInr(paise);
    }
}
