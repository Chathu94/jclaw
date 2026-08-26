package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

@Entity
@Table(name = "tenant")
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Tenant extends TimestampedModel {

    public static final String DEFAULT_SLUG = "default";

    @Column(nullable = false, unique = true, length = 80)
    public String slug;

    @Column(nullable = false, length = 120)
    public String name;

    @Column(nullable = false)
    public boolean enabled = true;

    public static Tenant findBySlug(String slug) {
        return Tenant.find("slug", slug).first();
    }
}
