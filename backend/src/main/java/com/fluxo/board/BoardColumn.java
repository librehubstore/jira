package com.fluxo.board;

import com.fluxo.workflow.Status;
import jakarta.persistence.*;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "board_columns")
public class BoardColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Board board;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int position;

    private Integer wipMin;

    private Integer wipMax;

    @ManyToMany
    @JoinTable(name = "board_column_statuses",
            joinColumns = @JoinColumn(name = "column_id"),
            inverseJoinColumns = @JoinColumn(name = "status_id"))
    private Set<Status> statuses = new LinkedHashSet<>();

    protected BoardColumn() {
    }

    BoardColumn(Board board, String name, int position) {
        this.board = board;
        this.name = name;
        this.position = position;
    }

    public UUID getId() { return id; }
    public Board getBoard() { return board; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public Integer getWipMin() { return wipMin; }
    public void setWipMin(Integer wipMin) { this.wipMin = wipMin; }
    public Integer getWipMax() { return wipMax; }
    public void setWipMax(Integer wipMax) { this.wipMax = wipMax; }
    public Set<Status> getStatuses() { return statuses; }
}
