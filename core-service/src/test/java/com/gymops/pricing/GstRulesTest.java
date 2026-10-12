package com.gymops.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymops.common.exception.BusinessRuleException;
import com.gymops.pricing.document.TaxDocumentFactory;
import com.gymops.pricing.gst.AmountInWords;
import com.gymops.pricing.gst.GstCalculator;
import com.gymops.pricing.gst.GstCalculator.TaxComponent;
import com.gymops.pricing.gst.GstRegistration;
import com.gymops.pricing.gst.GstSnapshot;
import com.gymops.pricing.gst.Gstin;
import com.gymops.pricing.gst.IndianStates;
import com.gymops.support.Fixtures;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GstRulesTest {

    static final BigDecimal RATE = new BigDecimal("0.18");

    @ParameterizedTest
    @ValueSource(strings = {"27AAPFU0939F1ZV", "29AAGCB7383J1Z4", "07AAGFF2194N1Z1", "24aaacc1206d1zm"})
    void validGstins(String gstin) {
        assertThat(Gstin.isValid(gstin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"27AAPFU0939F1ZX", "33AAACI1195H1ZK", "99AAPFU0939F1ZV", "27AAPFU0939F1Z", "27AAPFU0939F0ZV",
        "27AAPFU0939F1YV", "", "GSTIN"})
    void invalidGstins(String gstin) {
        assertThat(Gstin.isValid(gstin)).isFalse();
    }

    @Test
    void registrationTakesTheStateFromTheGstinAndRejectsAMismatch() {
        assertThat(GstRegistration.resolve(" 27aapfu0939f1zv ", null, "Karnataka"))
                .isEqualTo(new GstRegistration("27AAPFU0939F1ZV", "27"));
        assertThat(GstRegistration.resolve(null, null, "Karnataka")).isEqualTo(new GstRegistration(null, "29"));
        assertThat(GstRegistration.resolve(null, "07", "Pune")).isEqualTo(new GstRegistration(null, "07"));
        assertThatThrownBy(() -> GstRegistration.resolve("27AAPFU0939F1ZV", "29", null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("registered in state 27");
        assertThatThrownBy(() -> GstRegistration.resolve("27AAPFU0939F1ZX", null, null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> GstRegistration.resolve(null, "28", null)).isInstanceOf(BusinessRuleException.class);
    }

    @ParameterizedTest
    @CsvSource({"Maharashtra,27", "MH,27", "IN-MH,27", "maharashtra ,27", "Orissa,21", "New Delhi,07", "Tamil Nadu,33",
        "Jammu & Kashmir,01", "29,29", "7,07"})
    void stateLookup(String state, String code) {
        assertThat(IndianStates.codeFor(state)).contains(code);
    }

    @Test
    void unknownStateHasNoCode() {
        assertThat(IndianStates.codeFor("Atlantis")).isEmpty();
        assertThat(IndianStates.codeFor(null)).isEmpty();
        assertThat(IndianStates.label("27")).isEqualTo("Maharashtra (27)");
    }

    @Test
    void intraStateSplitsIntoEqualCgstAndSgst() {
        List<TaxComponent> taxes = GstCalculator.split(new BigDecimal("360.00"), RATE, false);
        assertThat(taxes).extracting(TaxComponent::name).containsExactly("CGST", "SGST");
        assertThat(taxes).extracting(t -> t.ratePct().toPlainString()).containsExactly("9", "9");
        assertThat(taxes.get(0).amount()).isEqualByComparingTo("180.00");
        assertThat(taxes.get(1).amount()).isEqualByComparingTo("180.00");
    }

    @Test
    void oddPaisaGoesToCgstAndHalvesAlwaysAddUp() {
        List<TaxComponent> taxes = GstCalculator.split(new BigDecimal("360.01"), RATE, false);
        assertThat(taxes.get(0).amount()).isEqualByComparingTo("180.01");
        assertThat(taxes.get(1).amount()).isEqualByComparingTo("180.00");
        assertThat(taxes.get(0).amount().add(taxes.get(1).amount())).isEqualByComparingTo("360.01");
    }

    @Test
    void interStateIsIgstAtTheFullRate() {
        List<TaxComponent> taxes = GstCalculator.split(new BigDecimal("360.00"), RATE, true);
        assertThat(taxes).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("IGST");
            assertThat(t.ratePct().toPlainString()).isEqualTo("18");
            assertThat(t.amount()).isEqualByComparingTo("360.00");
        });
    }

    @Test
    void placeOfSupplyIsTheMembersStateOrElseTheBranchState() {
        var branch = Fixtures.branch(1);
        branch.setState("Maharashtra");
        var member = Fixtures.member(1, branch);
        assertThat(GstSnapshot.of(branch, member).placeOfSupplyCode()).as("no member state on record").isEqualTo("27");
        assertThat(GstSnapshot.of(branch, member).interState()).isFalse();
        member.setState("Karnataka");
        assertThat(GstSnapshot.of(branch, member).interState()).isTrue();
        branch.setGstin("27AAPFU0939F1ZV");
        branch.setGstStateCode("27");
        assertThat(GstSnapshot.of(branch, member).gstin()).isEqualTo("27AAPFU0939F1ZV");
    }

    @Test
    void taxInclusiveRefundIsSplitIntoTaxableValueAndTax() {
        BigDecimal[] parts = GstCalculator.fromInclusive(new BigDecimal("2360.00"), RATE);
        assertThat(parts[0]).isEqualByComparingTo("2000.00");
        assertThat(parts[1]).isEqualByComparingTo("360.00");
        BigDecimal[] odd = GstCalculator.fromInclusive(new BigDecimal("1000.00"), RATE);
        assertThat(odd[0].add(odd[1])).isEqualByComparingTo("1000.00");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "2360.00|Indian Rupees Two Thousand Three Hundred Sixty Only",
        "0.50|Indian Rupees Zero and Fifty Paise Only",
        "101.05|Indian Rupees One Hundred One and Five Paise Only",
        "123456.78|Indian Rupees One Lakh Twenty Three Thousand Four Hundred Fifty Six and Seventy Eight Paise Only",
        "10000000|Indian Rupees One Crore Only",
        "21500019|Indian Rupees Two Crore Fifteen Lakh Nineteen Only"})
    void amountInWords(String amount, String words) {
        assertThat(AmountInWords.rupees(new BigDecimal(amount))).isEqualTo(words);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"2360|2,360.00", "999.5|999.50", "123456.78|1,23,456.78", "12345678|1,23,45,678.00",
        "-1500|-1,500.00"})
    void indianDigitGrouping(String amount, String formatted) {
        assertThat(TaxDocumentFactory.money(new BigDecimal(amount))).isEqualTo(formatted);
    }
}
