package com.gymops.member.dto;

import com.gymops.member.EmergencyContact;
import com.gymops.member.Gender;
import com.gymops.member.Member;
import com.gymops.member.MemberStatus;
import com.gymops.subscription.dto.MembershipSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record MemberResponse(
        Long id, String memberCode, Long userId, Long homeBranchId, String homeBranchName,
        String firstName, String lastName, String email, String phone, LocalDate dateOfBirth, Gender gender,
        String addressLine1, String city, String state, String postalCode, MemberStatus status,
        Instant registeredAt, String notes, List<Contact> emergencyContacts, MembershipSummary membership,
        long version) {

    public record Contact(Long id, String name, String relationship, String phone, String email, boolean primary) {
        static Contact from(EmergencyContact c) {
            return new Contact(c.getId(), c.getName(), c.getRelationship(), c.getPhone(), c.getEmail(), c.isPrimary());
        }
    }

    public static MemberResponse from(Member m, MembershipSummary membership) {
        return new MemberResponse(m.getId(), m.getMemberCode(), m.getUser() == null ? null : m.getUser().getId(),
                m.getHomeBranch().getId(), m.getHomeBranch().getName(), m.getFirstName(), m.getLastName(),
                m.getEmail(), m.getPhone(), m.getDateOfBirth(), m.getGender(), m.getAddressLine1(), m.getCity(),
                m.getState(), m.getPostalCode(), m.getStatus(), m.getRegisteredAt(), m.getNotes(),
                m.getEmergencyContacts().stream().map(Contact::from).toList(), membership, m.getVersion());
    }

    /** Members must not see internal staff notes about themselves. */
    public MemberResponse withoutStaffNotes() {
        return new MemberResponse(id, memberCode, userId, homeBranchId, homeBranchName, firstName, lastName, email,
                phone, dateOfBirth, gender, addressLine1, city, state, postalCode, status, registeredAt, null,
                emergencyContacts, membership, version);
    }
}
