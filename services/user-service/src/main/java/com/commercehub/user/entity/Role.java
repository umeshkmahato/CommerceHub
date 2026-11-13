package com.commercehub.user.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Role — RBAC anchor. Kept as an entity (not enum) so new roles like
 * SUPPORT_AGENT or WAREHOUSE can be added as data, not DDL.
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Column(nullable = false, unique = true, length = 40)
    private String name;

    public Short getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
