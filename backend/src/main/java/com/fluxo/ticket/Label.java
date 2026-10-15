package com.fluxo.ticket;

import com.fluxo.space.Space;
import jakarta.persistence.*;

import java.util.UUID;

/** Space-scoped label, unique case-insensitively (spec §4.3). */
@Entity
@Table(name = "labels")
public class Label {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Space space;

    @Column(nullable = false)
    private String name;

    private String color;

    protected Label() {
    }

    public Label(Space space, String name) {
        this.space = space;
        this.name = name;
    }

    public UUID getId() { return id; }
    public Space getSpace() { return space; }
    public String getName() { return name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public void setName(String name) { this.name = name; }
}
