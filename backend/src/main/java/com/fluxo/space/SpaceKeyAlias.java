package com.fluxo.space;

import jakarta.persistence.*;

/** Old space key kept after a rename so that OLD-12 still resolves (spec §4.2). */
@Entity
@Table(name = "space_key_aliases")
public class SpaceKeyAlias {

    @Id
    private String oldKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Space space;

    protected SpaceKeyAlias() {
    }

    public SpaceKeyAlias(String oldKey, Space space) {
        this.oldKey = oldKey;
        this.space = space;
    }

    public Space getSpace() { return space; }
}
