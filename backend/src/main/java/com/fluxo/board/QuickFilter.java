package com.fluxo.board;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * Custom board filter. Criteria are structured (assignee, type, priority, labels, parent, updatedWithinDays, text)
 * and evaluated client-side on the loaded cards; FQL replaces them in V1.
 */
@Entity
@Table(name = "quick_filters")
public class QuickFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Board board;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> criteria;

    @Column(nullable = false)
    private int position;

    protected QuickFilter() {
    }

    public QuickFilter(Board board, String name, Map<String, Object> criteria, int position) {
        this.board = board;
        this.name = name;
        this.criteria = criteria;
        this.position = position;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Map<String, Object> getCriteria() { return criteria; }
    public int getPosition() { return position; }
}
