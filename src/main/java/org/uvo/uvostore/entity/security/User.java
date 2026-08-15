package org.uvo.uvostore.entity.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.uvo.uvostore.entity.order.OrderStatusHistory;

/**
 * Admin principal — a separate authentication guard from the storefront
 * {@link org.uvo.uvostore.entity.customer.Customer}. In the source app, roles
 * come from Spatie's HasRoles trait rather than an explicit relation method;
 * here they're a plain @ManyToMany (see Role/Permission javadoc for why the
 * polymorphic pivot wasn't ported verbatim).
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    private String phone;

    private String avatar;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "is_admin", nullable = false)
    @Builder.Default
    private boolean isAdmin = false;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "invitation_token", unique = true)
    private String invitationToken;

    @Column(name = "invitation_sent_at")
    private Instant invitationSentAt;

    @Column(name = "invitation_accepted_at")
    private Instant invitationAcceptedAt;

    // Source verification: present in the real schema but looks vestigial for
    // an admin user (no Stripe checkout flow touches this table) — confirm
    // with the app owner before relying on it for anything.
    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @OneToMany(mappedBy = "user")
    @Builder.Default
    private List<OrderStatusHistory> statusHistoryEntries = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
