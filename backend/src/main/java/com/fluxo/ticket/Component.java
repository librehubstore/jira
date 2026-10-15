package com.fluxo.ticket;

import com.fluxo.space.Space;
import com.fluxo.user.User;
import jakarta.persistence.*;

import java.util.UUID;

/** Space component with an optional lead, who becomes the default assignee (spec §4.13). */
@Entity
@Table(name = "components")
public class Component {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Space space;

    @Column(nullable = false)
    private String name;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    private User lead;

    @Column(nullable = false)
    private boolean archived;

    protected Component() {
    }

    public Component(Space space, String name) {
        this.space = space;
        this.name = name;
    }

    public UUID getId() { return id; }
    public Space getSpace() { return space; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public User getLead() { return lead; }
    public void setLead(User lead) { this.lead = lead; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
}
