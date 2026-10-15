package com.fluxo.ticket;

import jakarta.persistence.*;

import java.util.UUID;

/** Global reference data seeded by V1__init.sql. */
public final class RefData {

    private RefData() {
    }

    @Entity(name = "Priority")
    @Table(name = "priorities")
    public static class Priority {
        @Id
        private UUID id;
        @Column(nullable = false)
        private String name;
        @Column(nullable = false)
        private int rank; // lower = more urgent
        @Column(nullable = false)
        private String color;
        @Column(nullable = false)
        private boolean isDefault;

        protected Priority() {
        }

        public UUID getId() { return id; }
        public String getName() { return name; }
        public int getRank() { return rank; }
        public String getColor() { return color; }
        public boolean isDefault() { return isDefault; }
    }

    @Entity(name = "Resolution")
    @Table(name = "resolutions")
    public static class Resolution {
        @Id
        private UUID id;
        @Column(nullable = false)
        private String name;
        @Column(nullable = false)
        private boolean isDefault;

        protected Resolution() {
        }

        public UUID getId() { return id; }
        public String getName() { return name; }
        public boolean isDefault() { return isDefault; }
    }
}
