package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(name = "user_account", indexes = {
        @Index(name = "idx_user_account_tenant", columnList = "tenant_id"),
        @Index(name = "idx_user_account_username", columnList = "username")
})
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class UserAccount extends TimestampedModel {

    @Column(nullable = false, unique = true, length = 120)
    public String username;

    @Column(name = "display_name", length = 160)
    public String displayName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    public Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_team_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    public Team primaryTeam;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    public UserRole role = UserRole.USER;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "password_hash", length = 512)
    public String passwordHash;

    @Column(name = "credential_version")
    public Long credentialVersion = 0L;

    public long credentialVersionValue() {
        return credentialVersion == null ? 0L : credentialVersion;
    }

    public void bumpCredentialVersion() {
        credentialVersion = credentialVersionValue() + 1L;
    }

    @Column(name = "approved_at")
    public Instant approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_user_id")
    public UserAccount approvedBy;

    public boolean isApprovedForAccess() {
        return role == UserRole.USER || approvedAt != null;
    }

    public static UserAccount findByUsername(String username) {
        return UserAccount.find("username", username).first();
    }
}
