package com.gymops.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailTemplateTest {

    private static EmailTemplate.Rendered render(String title, String body, String unsubscribe) {
        return EmailTemplate.render(new EmailTemplate.Content("Iron & Co <Gym>", "#ff0000", title, body,
                "https://gym.example/me/notifications", unsubscribe));
    }

    @Test
    void escapesUserContentEverywhere() {
        EmailTemplate.Rendered r = render("Hi <b>Asha</b> & \"friends\"",
                "<script>alert('x')</script>\n<img src=x onerror=alert(1)>", null);
        assertThat(r.html()).doesNotContain("<script>").doesNotContain("<img").doesNotContain("<b>Asha");
        assertThat(r.html()).contains("Hi &lt;b&gt;Asha&lt;/b&gt; &amp; &quot;friends&quot;");
        assertThat(r.html()).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;<br>&lt;img src=x onerror=alert(1)&gt;");
        assertThat(r.html()).contains("Iron &amp; Co &lt;Gym&gt;");
        assertThat(r.subject()).isEqualTo("Hi <b>Asha</b> & \"friends\"");   // headers are not HTML
    }

    @Test
    void rendersParagraphsBrandColourAndPlainTextAlternative() {
        EmailTemplate.Rendered r = render("Membership expiring", "Your plan ends tomorrow.\n\nRenew at the desk.", null);
        assertThat(r.html()).contains("background:#ff0000").contains(">Your plan ends tomorrow.</p>").contains(">Renew at the desk.</p>");
        assertThat(r.html()).contains("href=\"https://gym.example/me/notifications\"").doesNotContain("Unsubscribe");
        assertThat(r.text()).startsWith("Membership expiring\n\nYour plan ends tomorrow.\n\nRenew at the desk.\n\n--\n")
                .contains("Notification preferences: https://gym.example/me/notifications");
    }

    @Test
    void marketingMailCarriesAnUnsubscribeLinkAndOnlyHttpLinksAreRendered() {
        EmailTemplate.Rendered r = render("Offer", "20% off", "https://gym.example/unsubscribe?token=a.b&x=1");
        assertThat(r.html()).contains("href=\"https://gym.example/unsubscribe?token=a.b&amp;x=1\"");
        assertThat(r.text()).contains("Unsubscribe from offers & news: https://gym.example/unsubscribe?token=a.b&x=1");
        EmailTemplate.Rendered evil = render("Offer", "x", "javascript:alert(1)");
        assertThat(evil.html()).doesNotContain("javascript:");
    }

    @Test
    void invalidBrandColourFallsBackToTheDefault() {
        String html = EmailTemplate.render(new EmailTemplate.Content("G", "red;background:url(x)", "T", "B", null, null)).html();
        assertThat(html).contains("background:#1c7ed6").doesNotContain("url(x)");
    }
}
