package com.fluxo.space;

import jakarta.persistence.*;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "space_roles")
public class SpaceRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Space space;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "text[]")
    private String[] permissions;

    protected SpaceRole() {
    }

    public SpaceRole(Space space, String name, Set<Perm> permissions) {
        this.space = space;
        this.name = name;
        setPermissions(permissions);
    }

    public Set<Perm> getPermissions() {
        Set<Perm> set = EnumSet.noneOf(Perm.class);
        Arrays.stream(permissions).map(Perm::valueOf).forEach(set::add);
        return set;
    }

    public void setPermissions(Set<Perm> perms) {
        this.permissions = perms.stream().map(Enum::name).sorted().toArray(String[]::new);
    }

    public UUID getId() { return id; }
    public Space getSpace() { return space; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
