package com.gymops.pricing.gst;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** GST state / union-territory codes, with lookup by code, name, common alias or ISO 3166-2:IN suffix. */
public final class IndianStates {

    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    private static final Map<String, String> LOOKUP = new HashMap<>();

    static {
        add("01", "Jammu and Kashmir", "JK", "J&K", "Jammu & Kashmir");
        add("02", "Himachal Pradesh", "HP");
        add("03", "Punjab", "PB");
        add("04", "Chandigarh", "CH");
        add("05", "Uttarakhand", "UK", "UT", "Uttaranchal");
        add("06", "Haryana", "HR");
        add("07", "Delhi", "DL", "New Delhi", "NCT of Delhi");
        add("08", "Rajasthan", "RJ");
        add("09", "Uttar Pradesh", "UP");
        add("10", "Bihar", "BR");
        add("11", "Sikkim", "SK");
        add("12", "Arunachal Pradesh", "AR");
        add("13", "Nagaland", "NL");
        add("14", "Manipur", "MN");
        add("15", "Mizoram", "MZ");
        add("16", "Tripura", "TR");
        add("17", "Meghalaya", "ML");
        add("18", "Assam", "AS");
        add("19", "West Bengal", "WB");
        add("20", "Jharkhand", "JH");
        add("21", "Odisha", "OD", "OR", "Orissa");
        add("22", "Chhattisgarh", "CT", "CG", "Chattisgarh");
        add("23", "Madhya Pradesh", "MP");
        add("24", "Gujarat", "GJ");
        add("26", "Dadra and Nagar Haveli and Daman and Diu", "DH", "DN", "DD", "Daman and Diu", "Dadra and Nagar Haveli");
        add("27", "Maharashtra", "MH");
        add("29", "Karnataka", "KA");
        add("30", "Goa", "GA");
        add("31", "Lakshadweep", "LD");
        add("32", "Kerala", "KL");
        add("33", "Tamil Nadu", "TN");
        add("34", "Puducherry", "PY", "Pondicherry");
        add("35", "Andaman and Nicobar Islands", "AN", "Andaman & Nicobar");
        add("36", "Telangana", "TG", "TS");
        add("37", "Andhra Pradesh", "AP");
        add("38", "Ladakh", "LA");
        add("97", "Other Territory");
    }

    private IndianStates() {
    }

    private static void add(String code, String name, String... aliases) {
        NAMES.put(code, name);
        LOOKUP.put(key(code), code);
        LOOKUP.put(key(name), code);
        for (String alias : aliases) {
            LOOKUP.put(key(alias), code);
        }
    }

    private static String key(String s) {
        String plain = Normalizer.normalize(s, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        return plain.replace("&", "and").replaceAll("^in-", "").replaceAll("[^a-z0-9]", "");
    }

    public static boolean isValidCode(String code) {
        return code != null && NAMES.containsKey(code);
    }

    public static Optional<String> name(String code) {
        return Optional.ofNullable(code == null ? null : NAMES.get(code));
    }

    /** GST state code for a free-text state ("Maharashtra", "MH", "IN-MH", "27"), if recognised. */
    public static Optional<String> codeFor(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        String k = key(state.trim());
        if (k.length() == 1 && Character.isDigit(k.charAt(0))) {
            k = "0" + k;
        }
        return Optional.ofNullable(LOOKUP.get(k));
    }

    public static String label(String code) {
        return name(code).map(n -> n + " (" + code + ")").orElse(code == null ? "-" : code);
    }
}
