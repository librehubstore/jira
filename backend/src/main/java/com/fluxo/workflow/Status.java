package com.fluxo.workflow;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "statuses")
public class Status {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Workflow workflow;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusCategory category;

    @Column(nullable = false)
    private int position;

    protected Status() {
    }

    Status(Workflow workflow, String name, StatusCategory category, int position) {
        this.workflow = workflow;
        this.name = name;
        this.category = category;
        this.position = position;
    }

    public UUID getId() { return id; }
    public Workflow getWorkflow() { return workflow; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public StatusCategory getCategory() { return category; }
    public void setCategory(StatusCategory category) { this.category = category; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
