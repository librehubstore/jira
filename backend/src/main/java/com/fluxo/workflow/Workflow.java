package com.fluxo.workflow;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** MVP: simplified workflow — every status can move to every other status (spec §4.9). */
@Entity
@Table(name = "workflows")
public class Workflow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean shared;

    @OneToMany(mappedBy = "workflow", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<Status> statuses = new ArrayList<>();

    protected Workflow() {
    }

    public Workflow(String name) {
        this.name = name;
    }

    public Status addStatus(String name, StatusCategory category) {
        Status s = new Status(this, name, category, statuses.size());
        statuses.add(s);
        return s;
    }

    /** The status new tickets start in: first status of category TODO, else the first one. */
    @OneToMany(mappedBy = "workflow", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<Transition> transitions = new ArrayList<>();

    public Transition addTransition(String name, List<UUID> from, Status to, Transition.Rules rules) {
        Transition t = new Transition(this, name, from, to, transitions.size(), rules);
        transitions.add(t);
        return t;
    }

    /** No transition defined: the simplified workflow, any status to any status (spec §4.9). */
    public boolean isSimplified() {
        return transitions.isEmpty();
    }

    public List<Transition> getTransitions() { return transitions; }

    public void setShared(boolean shared) { this.shared = shared; }

    public Status initialStatus() {
        return statuses.stream().filter(s -> s.getCategory() == StatusCategory.TODO).findFirst()
                .orElse(statuses.getFirst());
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isShared() { return shared; }
    public List<Status> getStatuses() { return statuses; }
}
