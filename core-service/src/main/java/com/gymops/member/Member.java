package com.gymops.member;

import com.gymops.branch.GymBranch;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Setter
@Entity
@Table(name = "members")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

    @Setter(AccessLevel.NONE)
    @Column(name = "member_code", nullable = false, unique = true, updatable = false)
    private String memberCode;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_branch_id")
    private GymBranch homeBranch;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Column(name = "address_line1")
    private String addressLine1;

    private String city;
    private String state;

    @Column(name = "postal_code")
    private String postalCode;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status = MemberStatus.ACTIVE;

    @Setter(AccessLevel.NONE)
    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    private String notes;

    @Setter(AccessLevel.NONE)
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EmergencyContact> emergencyContacts = new ArrayList<>();

    public Member(String memberCode, GymBranch homeBranch, String firstName, String lastName, String email,
                  String phone, Instant registeredAt) {
        this.memberCode = memberCode;
        this.homeBranch = homeBranch;
        this.firstName = firstName.trim();
        this.lastName = lastName.trim();
        this.email = email.trim().toLowerCase();
        this.phone = phone;
        this.registeredAt = registeredAt;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    public void changeStatus(MemberStatus newStatus) {
        this.status = newStatus;
    }

    public void replaceEmergencyContacts(List<EmergencyContact> contacts) {
        emergencyContacts.clear();
        contacts.forEach(c -> {
            c.attachTo(this);
            emergencyContacts.add(c);
        });
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
        this.status = MemberStatus.INACTIVE;
    }
}
