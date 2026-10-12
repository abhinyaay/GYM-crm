package com.gymops.growth.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.payment.DiscountType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    private static final Map<String, String> VALUES = TemplateRenderer.values("Asha", "Gold Annual", "12 Nov 2026", "WB7K2M");

    @Test
    void substitutesKnownPlaceholders() {
        assertThat(TemplateRenderer.render("Hi {firstName}, your {planName} ends {endDate}. Code {offerCode}.", VALUES, 500))
                .isEqualTo("Hi Asha, your Gold Annual ends 12 Nov 2026. Code WB7K2M.");
    }

    @Test
    void doubledBracesAreLiteralAndUnknownPlaceholdersStayVerbatim() {
        assertThat(TemplateRenderer.render("{{firstName}} = {firstName}; {unknown}", VALUES, 500))
                .isEqualTo("{firstName} = Asha; {unknown}");
        assertThat(TemplateRenderer.render("a {b c} d { e", VALUES, 500)).isEqualTo("a {b c} d { e");
    }

    @Test
    void valuesAreNotReinterpretedAndAreSanitised() {
        Map<String, String> hostile = TemplateRenderer.values("{offerCode}\n\tEvil\u0000", null, null, "SECRET");
        assertThat(TemplateRenderer.render("Hi {firstName}!", hostile, 500)).isEqualTo("Hi {offerCode} Evil!");
        assertThat(TemplateRenderer.render("Your {planName}", hostile, 500)).isEqualTo("Your your membership");
        assertThat(TemplateRenderer.sanitize("x".repeat(500))).hasSize(100);
    }

    @Test
    void outputIsCappedToTheChannelLimit() {
        String rendered = TemplateRenderer.render("y".repeat(300), VALUES, 200);
        assertThat(rendered).hasSize(200).endsWith("…");
    }

    @Test
    void validationRejectsUnknownPlaceholdersAndOfferCodeWithoutOffer() {
        assertThat(TemplateRenderer.validate("Hi {firstName}", false)).isEmpty();
        assertThat(TemplateRenderer.validate("Hi {name}", false)).singleElement().asString().contains("Unknown placeholder {name}");
        assertThat(TemplateRenderer.validate("Code {offerCode}", false)).singleElement().asString().contains("needs a personal offer");
        assertThat(TemplateRenderer.validate("Code {offerCode}", true)).isEmpty();
        assertThat(TemplateRenderer.validate("{{offerCode}}", false)).as("escaped braces are text").isEmpty();
        assertThat(TemplateRenderer.placeholders("{endDate} {firstName} {endDate}")).containsExactly("endDate", "firstName");
    }

    @Test
    void bodyGetsTheOfferLineWhenTheTemplateDoesNotPlaceTheCode() {
        String body = CampaignService.renderBody("Hi {firstName}, come back!", VALUES, true, DiscountType.PERCENTAGE,
                new BigDecimal("15.00"), LocalDate.of(2026, 11, 3));
        assertThat(body).isEqualTo("Hi Asha, come back!\n\nYour personal code WB7K2M: 15% off your next membership, "
                + "valid until 3 Nov 2026.");
        assertThat(CampaignService.renderBody("Use {offerCode}", VALUES, true, DiscountType.FIXED_AMOUNT,
                new BigDecimal("500"), LocalDate.of(2026, 11, 3))).isEqualTo("Use WB7K2M");
        assertThat(CampaignService.renderBody("Hi", VALUES, false, null, null, null)).isEqualTo("Hi");
    }
}
