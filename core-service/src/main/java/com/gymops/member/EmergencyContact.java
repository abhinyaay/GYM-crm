package com.gymops.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "emergency_contacts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmergencyContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String relationship;

    @Column(nullable = false)
    private String phone;

    private String email;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    public EmergencyContact(String name, String relationship, String phone, String email, boolean primary) {
        this.name = name;
        this.relationship = relationship;
        this.phone = phone;
        this.email = email;
        this.primary = primary;
    }

    void attachTo(Member member) {
        this.member = member;
    }
}
