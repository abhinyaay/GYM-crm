package com.gymops.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.support.Api;
import com.gymops.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression tests for updates that replace child collections guarded by unique constraints. */
class MasterDataIT extends IntegrationTestBase {

    private static List<Map<String, Object>> week(String open, String close) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (int d = 1; d <= 6; d++) {
            days.add(Map.of("dayOfWeek", d, "openTime", open, "closeTime", close, "closed", false));
        }
        days.add(Map.of("dayOfWeek", 7, "closed", true));
        return days;
    }

    @Test
    void operatingHoursCanBeReplacedRepeatedly() {
        String admin = admin();
        long branchId = createBranch(admin);
        for (String open : List.of("06:00", "05:30", "07:00")) {
            Api.Resp r = api.send("PUT", "/api/v1/branches/" + branchId + "/operating-hours", admin,
                    Map.of("days", week(open, "22:00")), Map.of());
            assertThat(r.status()).as(r.toString()).isEqualTo(200);
            assertThat(r.body().get("operatingHours").get(0).get("openTime").asText()).startsWith(open);
        }
        // shrinking the schedule removes days that are no longer present
        Api.Resp partial = api.send("PUT", "/api/v1/branches/" + branchId + "/operating-hours", admin,
                Map.of("days", List.of(Map.of("dayOfWeek", 1, "openTime", "08:00", "closeTime", "20:00"))), Map.of());
        assertThat(partial.status()).isEqualTo(200);
        assertThat(partial.body().get("operatingHours")).hasSize(1);
    }

    @Test
    void addedFacilityIsReturnedWithItsId() {
        String admin = admin();
        long branchId = createBranch(admin);
        Api.Resp r = api.post("/api/v1/branches/" + branchId + "/facilities", admin, Map.of("name", "Sauna"));
        assertThat(r.status()).isEqualTo(201);
        long facilityId = r.body().get("facilities").get(0).get("id").asLong();
        assertThat(facilityId).isPositive();
        assertThat(api.send("DELETE", "/api/v1/branches/" + branchId + "/facilities/" + facilityId, admin, null, Map.of())
                .status()).isEqualTo(204);
    }

    @Test
    void memberEmergencyContactsCanBeReplacedRepeatedly() {
        String admin = admin();
        long branchId = createBranch(admin);
        String desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
        String email = unique("contacts") + "@example.com";
        Api.Resp created = api.post("/api/v1/members", desk, Map.of("homeBranchId", branchId, "firstName", "C",
                "lastName", "T", "email", email, "phone", "+91 9000000000",
                "emergencyContacts", List.of(Map.of("name", "Mom", "relationship", "PARENT", "phone", "+91 9111111111",
                        "primary", true))));
        assertThat(created.status()).isEqualTo(201);
        for (String phone : List.of("+91 9222222222", "+91 9333333333")) {
            Api.Resp updated = api.send("PUT", "/api/v1/members/" + created.id(), desk, Map.of("homeBranchId", branchId,
                    "firstName", "C", "lastName", "T", "email", email, "phone", phone,
                    "emergencyContacts", List.of(Map.of("name", "Dad", "relationship", "PARENT", "phone", phone,
                            "primary", true))), Map.of());
            assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
            // read back through the Redis-cached profile: must reflect the update
            Api.Resp read = api.get("/api/v1/members/" + created.id(), desk);
            assertThat(read.body().get("phone").asText()).isEqualTo(phone);
            assertThat(read.body().get("emergencyContacts")).hasSize(1);
        }
    }
}
