package com.gymops.notification.email;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the branded e-mail layout (inline CSS, table based: works in Gmail, Outlook and Apple Mail) plus the
 * plain-text alternative. Every piece of dynamic content is HTML-escaped, so member names, class titles or
 * campaign text can never inject markup; links are only ever ones we build ourselves (http/https).
 */
public final class EmailTemplate {

    /**
     * @param brandName       shown in the header and footer
     * @param brandColor      header accent ({@code #rrggbb}, validated by configuration)
     * @param title           heading (also the subject line)
     * @param body            plain text; blank lines separate paragraphs, single newlines become line breaks
     * @param preferencesUrl  "manage notification preferences" link (nullable)
     * @param unsubscribeUrl  one-click unsubscribe page (nullable; marketing only)
     */
    public record Content(String brandName, String brandColor, String title, String body, String preferencesUrl,
                          String unsubscribeUrl) {
    }

    public record Rendered(String subject, String html, String text) {
    }

    private EmailTemplate() {
    }

    public static Rendered render(Content c) {
        return new Rendered(c.title(), html(c), text(c));
    }

    static String html(Content c) {
        String color = c.brandColor() != null && c.brandColor().matches("#[0-9a-fA-F]{6}") ? c.brandColor() : "#1c7ed6";
        StringBuilder paragraphs = new StringBuilder();
        for (String p : paragraphs(c.body())) {
            paragraphs.append("<p style=\"margin:0 0 16px;font-size:15px;line-height:1.6;color:#343a40;\">")
                    .append(escape(p).replace("\n", "<br>"))
                    .append("</p>");
        }
        StringBuilder footer = new StringBuilder();
        footer.append("You are receiving this e-mail as a member of ").append(escape(c.brandName())).append('.');
        if (safeUrl(c.preferencesUrl())) {
            footer.append(" <a href=\"").append(escape(c.preferencesUrl()))
                    .append("\" style=\"color:#868e96;\">Notification preferences</a>");
        }
        if (safeUrl(c.unsubscribeUrl())) {
            footer.append(" &middot; <a href=\"").append(escape(c.unsubscribeUrl()))
                    .append("\" style=\"color:#868e96;\">Unsubscribe from offers &amp; news</a>");
        }
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">\
                <title>%1$s</title></head>
                <body style="margin:0;padding:0;background:#f1f3f5;\
                font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f1f3f5;padding:24px 12px;">
                <tr><td align="center">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;\
                border-radius:8px;overflow:hidden;border:1px solid #e9ecef;">
                <tr><td style="background:%2$s;padding:18px 28px;color:#ffffff;font-size:18px;font-weight:700;\
                letter-spacing:.3px;">%3$s</td></tr>
                <tr><td style="padding:28px 28px 12px;">
                <h1 style="margin:0 0 18px;font-size:21px;line-height:1.3;color:#212529;">%1$s</h1>
                %4$s
                </td></tr>
                <tr><td style="padding:16px 28px 24px;border-top:1px solid #f1f3f5;font-size:12px;line-height:1.5;\
                color:#868e96;">%5$s</td></tr>
                </table>
                </td></tr>
                </table>
                </body></html>
                """.formatted(escape(c.title()), color, escape(c.brandName()), paragraphs, footer);
    }

    static String text(Content c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.title()).append("\n\n");
        for (String p : paragraphs(c.body())) {
            sb.append(p).append("\n\n");
        }
        sb.append("--\n").append(c.brandName()).append('\n');
        if (safeUrl(c.preferencesUrl())) {
            sb.append("Notification preferences: ").append(c.preferencesUrl()).append('\n');
        }
        if (safeUrl(c.unsubscribeUrl())) {
            sb.append("Unsubscribe from offers & news: ").append(c.unsubscribeUrl()).append('\n');
        }
        return sb.toString();
    }

    private static List<String> paragraphs(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (String p : body.replace("\r\n", "\n").split("\n\\s*\n")) {
            if (!p.isBlank()) {
                out.add(p.strip());
            }
        }
        return out;
    }

    private static boolean safeUrl(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    /** HTML-escapes text and attribute values (&amp; &lt; &gt; &quot; &#39;). */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
