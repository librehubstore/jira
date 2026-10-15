package com.fluxo.board;

import com.fluxo.space.Space;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "boards")
public class Board {

    public enum SwimlaneMode { NONE, ASSIGNEE, PARENT, PRIORITY, TYPE, QUERIES, REPORTER, LABEL }

    /** A swimlane defined by an FQL query; tickets go to the first query they match (spec §4.10). */
    public record LaneQuery(String name, String fql) {}

    public enum WipMode { OFF, COUNT, COUNT_EXCL_SUBTASKS }

    public enum ColorMode { NONE, TYPE, PRIORITY, ASSIGNEE, QUERIES }

    /** Cards matching {@code fql} get {@code color} (first match wins). */
    public record ColorQuery(String fql, String color) {}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Space space;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean isDefault;

    /** Hide tickets resolved more than N days ago; null = never hide. */
    private Integer hideDoneDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SwimlaneMode swimlaneMode = SwimlaneMode.NONE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WipMode wipMode = WipMode.COUNT;

    /** Up to 3 extra fields shown on cards. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> cardFields = new ArrayList<>();

    @Column(nullable = false)
    private boolean showSubtasks = true;

    /** Optional FQL narrowing the board's tickets (spec §4.10 « filtre de base »). */
    private String baseFql;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<LaneQuery> swimlaneQueries = new ArrayList<>();

    @OneToMany(mappedBy = "board", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<BoardColumn> columns = new ArrayList<>();

    /** Other spaces covered by the board, besides its own (spec §4.10 « board multi-espaces »). */
    @ManyToMany
    @JoinTable(name = "board_spaces", joinColumns = @JoinColumn(name = "board_id"), inverseJoinColumns = @JoinColumn(name = "space_id"))
    private Set<Space> extraSpaces = new HashSet<>();

    /** ISO days of week (1 = Monday) counted by « jours dans la colonne ». */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Integer> workingDays = new ArrayList<>(List.of(1, 2, 3, 4, 5));

    @Column(nullable = false)
    private boolean backlogEnabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ColorMode cardColorMode = ColorMode.NONE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<ColorQuery> cardColorQueries = new ArrayList<>();

    @Column(nullable = false)
    private boolean wipBlocking;

    protected Board() {
    }

    public Board(Space space, String name, boolean isDefault) {
        this.space = space;
        this.name = name;
        this.isDefault = isDefault;
    }

    public BoardColumn addColumn(String name) {
        BoardColumn c = new BoardColumn(this, name, columns.size());
        columns.add(c);
        return c;
    }

    public UUID getId() { return id; }
    public Space getSpace() { return space; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public Set<Space> getExtraSpaces() { return extraSpaces; }
    /** Own space first, then the others by key. */
    public List<Space> allSpaces() {
        List<Space> all = new ArrayList<>(List.of(space));
        extraSpaces.stream().sorted(java.util.Comparator.comparing(Space::getKey)).forEach(all::add);
        return all;
    }
    public List<Integer> getWorkingDays() { return workingDays; }
    public void setWorkingDays(List<Integer> workingDays) { this.workingDays = workingDays; }
    public boolean isBacklogEnabled() { return backlogEnabled; }
    public void setBacklogEnabled(boolean backlogEnabled) { this.backlogEnabled = backlogEnabled; }
    public ColorMode getCardColorMode() { return cardColorMode; }
    public void setCardColorMode(ColorMode cardColorMode) { this.cardColorMode = cardColorMode; }
    public List<ColorQuery> getCardColorQueries() { return cardColorQueries; }
    public void setCardColorQueries(List<ColorQuery> cardColorQueries) { this.cardColorQueries = cardColorQueries; }
    public boolean isWipBlocking() { return wipBlocking; }
    public void setWipBlocking(boolean wipBlocking) { this.wipBlocking = wipBlocking; }
    public Integer getHideDoneDays() { return hideDoneDays; }
    public void setHideDoneDays(Integer hideDoneDays) { this.hideDoneDays = hideDoneDays; }
    public SwimlaneMode getSwimlaneMode() { return swimlaneMode; }
    public void setSwimlaneMode(SwimlaneMode swimlaneMode) { this.swimlaneMode = swimlaneMode; }
    public WipMode getWipMode() { return wipMode; }
    public void setWipMode(WipMode wipMode) { this.wipMode = wipMode; }
    public List<String> getCardFields() { return cardFields; }
    public void setCardFields(List<String> cardFields) { this.cardFields = cardFields; }
    public boolean isShowSubtasks() { return showSubtasks; }
    public void setShowSubtasks(boolean showSubtasks) { this.showSubtasks = showSubtasks; }
    public List<BoardColumn> getColumns() { return columns; }
    public String getBaseFql() { return baseFql; }
    public void setBaseFql(String baseFql) { this.baseFql = baseFql; }
    public List<LaneQuery> getSwimlaneQueries() { return swimlaneQueries; }
    public void setSwimlaneQueries(List<LaneQuery> swimlaneQueries) { this.swimlaneQueries = swimlaneQueries; }
}
