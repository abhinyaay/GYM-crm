package com.gymops.growth.campaign;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message templates with {@code {firstName}}, {@code {planName}}, {@code {endDate}} and
 * {@code {offerCode}} placeholders. Rendering is a single left-to-right pass: substituted values are
 * never scanned again, so a member whose name contains "{offerCode}" cannot pull in another value.
 * {@code {{} and {@code }}} produce literal braces. Values are reduced to one line of printable text
 * (control characters stripped, length capped). The output is plain text; channels that render HTML
 * (email) must escape it like any other notification body.
 */
public final class TemplateRenderer {

    public static final String FIRST_NAME = "firstName";
    public static final String PLAN_NAME = "planName";
    public static final String END_DATE = "endDate";
    public static final String OFFER_CODE = "offerCode";
    public static final Set<String> PLACEHOLDERS = Set.of(FIRST_NAME, PLAN_NAME, END_DATE, OFFER_CODE);

    private static final Pattern TOKEN = Pattern.compile("\\{\\{|}}|\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final int MAX_VALUE_LENGTH = 100;

    private TemplateRenderer() {
    }

    /** Placeholders used in a template (in order of first use). */
    public static Set<String> placeholders(String template) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(template == null ? "" : template);
        while (m.find()) {
            if (m.group(1) != null) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    /** Problems with a template; empty when it is valid. */
    public static List<String> validate(String template, boolean offerEnabled) {
        List<String> errors = new ArrayList<>();
        for (String name : placeholders(template)) {
            if (!PLACEHOLDERS.contains(name)) {
                errors.add("Unknown placeholder {" + name + "}; use " + String.join(", ", PLACEHOLDERS.stream()
                        .sorted().map(p -> "{" + p + "}").toList()));
            } else if (OFFER_CODE.equals(name) && !offerEnabled) {
                errors.add("{offerCode} needs a personal offer; enable the offer or remove the placeholder");
            }
        }
        return errors;
    }

    /** Renders a template; unknown placeholders are kept verbatim, missing values render as empty text. */
    public static String render(String template, Map<String, String> values, int maxLength) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(template, last, m.start());
            String token = m.group();
            if ("{{".equals(token)) {
                out.append('{');
            } else if ("}}".equals(token)) {
                out.append('}');
            } else if (PLACEHOLDERS.contains(m.group(1))) {
                out.append(sanitize(values.get(m.group(1))));
            } else {
                out.append(token);
            }
            last = m.end();
        }
        out.append(template.substring(last));
        String rendered = out.toString().strip();
        return rendered.length() <= maxLength ? rendered : rendered.substring(0, maxLength - 1) + "…";
    }

    /** One line of printable text: control characters removed, whitespace collapsed, length capped. */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("\\p{Cntrl}", " ").replaceAll("[\\p{Z}\\s]+", " ").strip();
        return clean.length() <= MAX_VALUE_LENGTH ? clean : clean.substring(0, MAX_VALUE_LENGTH);
    }

    /** Variables for one recipient. */
    public static Map<String, String> values(String firstName, String planName, String endDate, String offerCode) {
        return Map.of(FIRST_NAME, firstName == null ? "" : firstName,
                PLAN_NAME, planName == null ? "your membership" : planName,
                END_DATE, endDate == null ? "" : endDate,
                OFFER_CODE, offerCode == null ? "" : offerCode);
    }
}
