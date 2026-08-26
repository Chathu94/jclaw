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
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "team_membership",
        indexes = {
                @Index(name = "idx_team_membership_user", columnList = "user_id"),
                @Index(name = "idx_team_membership_team", columnList = "team_id")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_team_membership_user_team",
                columnNames = {"user_id", "team_id"}))
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class TeamMembership extends TimestampedModel {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Team team;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    public UserRole role = UserRole.USER;

    public static TeamMembership findByUserAndTeam(UserAccount user, Team team) {
        if (user == null || team == null) return null;
        return TeamMembership.find("user = ?1 AND team = ?2", user, team).first();
    }
}
