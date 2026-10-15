package com.fluxo.workflow;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A named move from some statuses (none = any) to a target status, with its rules (spec §4.9). */
@Entity
@Table(name = "transitions")
public class Transition {

    /**
     * One condition, validator or action. {@code field} names a ticket field or a permission, {@code value} a role id,
     * group id, JSON value or URL depending on {@code type}.
     */
    public record Rule(String type, String field, String value) {}

    /** Fields asked by the transition screen. */
    public record Screen(Boolean resolution, Boolean comment, Boolean attachment) {
        public boolean any() {
            return Boolean.TRUE.equals(resolution) || Boolean.TRUE.equals(comment) || Boolean.TRUE.equals(attachment);
        }
    }

    /** {@code conditionMode}: ALL (default) or ANY. */
    public record Rules(String conditionMode, List<Rule> conditions, List<Rule> validators, List<Rule> actions, Screen screen) {
        public static final Rules NONE = new Rules("ALL", List.of(), List.of(), List.of(), new Screen(false, false, false));

        public List<Rule> conditions() { return conditions == null ? List.of() : conditions; }
        public List<Rule> validators() { return validators == null ? List.of() : validators; }
        public List<Rule> actions() { return actions == null ? List.of() : actions; }
        public Screen screen() { return screen == null ? new Screen(false, false, false) : screen; }
        public boolean any() { return "ANY".equals(conditionMode); }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Workflow workflow;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<UUID> fromStatusIds = new ArrayList<>();

    @ManyToOne(optional = false)
    @JoinColumn(name = "to_status_id")
    private Status to;

    @Column(nullable = false)
    private int position;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Rules rules = Rules.NONE;

    protected Transition() {
    }

    Transition(Workflow workflow, String name, List<UUID> fromStatusIds, Status to, int position, Rules rules) {
        this.workflow = workflow;
        this.name = name;
        this.fromStatusIds = new ArrayList<>(fromStatusIds);
        this.to = to;
        this.position = position;
        this.rules = rules == null ? Rules.NONE : rules;
    }

    public boolean startsFrom(Status s) {
        return fromStatusIds.isEmpty() || fromStatusIds.contains(s.getId());
    }

    public UUID getId() { return id; }
    public Workflow getWorkflow() { return workflow; }
    public String getName() { return name; }
    public List<UUID> getFromStatusIds() { return fromStatusIds; }
    public Status getTo() { return to; }
    public int getPosition() { return position; }
    public Rules getRules() { return rules; }
}
