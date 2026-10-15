package com.fluxo.ticket;

import com.fluxo.space.Space;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** Default value of a field when creating a ticket in a space, optionally for one ticket type (spec §4.14). */
@Entity
@Table(name = "field_defaults")
public class FieldDefault {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY)
    private TicketType ticketType;

    @Column(nullable = false)
    private String field;

    /** JSON value: an id (string), a text, a number, or a list of strings for labels. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Object value;

    protected FieldDefault() {
    }

    public FieldDefault(Space space, TicketType ticketType, String field, Object value) {
        this.space = space;
        this.ticketType = ticketType;
        this.field = field;
        this.value = value;
    }

    public UUID getId() { return id; }
    public TicketType getTicketType() { return ticketType; }
    public String getField() { return field; }
    public Object getValue() { return value; }
}
