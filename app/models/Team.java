package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "team",
        indexes = @Index(name = "idx_team_tenant", columnList = "tenant_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_team_tenant_slug", columnNames = {"tenant_id", "slug"}))
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Team extends TimestampedModel {

    public static final String DEFAULT_SLUG = "default";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Tenant tenant;

    @Column(nullable = false, length = 80)
    public String slug;

    @Column(nullable = false, length = 120)
    public String name;

    @Column(nullable = false)
    public boolean enabled = true;

    public static Team findBySlug(Tenant tenant, String slug) {
        if (tenant == null) return null;
        return Team.find("tenant = ?1 AND slug = ?2", tenant, slug).first();
    }
}
