package com.fluxo.space;

import com.fluxo.user.Group;
import com.fluxo.user.User;
import jakarta.persistence.*;

import java.util.UUID;

/** A user or a group holding a role in a space. */
@Entity
@Table(name = "space_memberships")
public class SpaceMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private SpaceRole role;

    protected SpaceMembership() {
    }

    public SpaceMembership(Space space, User user, Group group, SpaceRole role) {
        this.space = space;
        this.user = user;
        this.group = group;
        this.role = role;
    }

    public UUID getId() { return id; }
    public Space getSpace() { return space; }
    public User getUser() { return user; }
    public Group getGroup() { return group; }
    public SpaceRole getRole() { return role; }
    public void setRole(SpaceRole role) { this.role = role; }
}
