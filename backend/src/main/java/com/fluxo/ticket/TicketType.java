package com.fluxo.ticket;

import jakarta.persistence.*;

import java.util.UUID;

/** Global ticket type; hierarchy level 1 = epic, 0 = standard, -1 = subtask (spec §4.3). */
@Entity
@Table(name = "ticket_types")
public class TicketType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String icon;

    @Column(nullable = false)
    private String color;

    @Column(nullable = false)
    private short hierarchyLevel;

    protected TicketType() {
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getIcon() { return icon; }
    public String getColor() { return color; }
    public int getHierarchyLevel() { return hierarchyLevel; }
}
