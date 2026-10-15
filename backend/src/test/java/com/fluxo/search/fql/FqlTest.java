package com.fluxo.search.fql;

import com.fluxo.search.fql.Fql.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FqlTest {

    @Test
    void parsesPrecedenceAndOrderBy() {
        Query q = Fql.parse("assignee = currentUser() AND statusCategory != Done OR labels in (a, \"b c\") ORDER BY priority DESC, key");
        assertThat(q.where()).isInstanceOf(Or.class);
        Or or = (Or) q.where();
        assertThat(or.left()).isInstanceOf(And.class);
        Clause in = (Clause) or.right();
        assertThat(in.op()).isEqualTo("in");
        assertThat(in.values()).extracting(Value::text).containsExactly("a", "b c");
        Clause first = (Clause) ((And) or.left()).left();
        assertThat(first.values().getFirst().function()).isEqualTo("currentuser");
        assertThat(q.orderBy()).containsExactly(new Order("priority", true), new Order("key", false));
    }

    @Test
    void parsesNotParenthesesEmptyWasChanged() {
        Query q = Fql.parse("NOT (status WAS \"En revue\" AND resolved >= -7d) AND assignee IS NOT EMPTY AND status CHANGED FROM \"À faire\" TO \"En cours\" BY alice AFTER startOfWeek()");
        Clause changed = (Clause) ((And) q.where()).right();
        assertThat(changed.op()).isEqualTo("changed");
        assertThat(changed.changes()).extracting(ChangeCond::kind).containsExactly("from", "to", "by", "after");
        assertThat(changed.changes().getLast().value().function()).isEqualTo("startofweek");
        Clause notEmpty = (Clause) ((And) ((And) q.where()).left()).right();
        assertThat(notEmpty.op()).isEqualTo("is not empty");
        assertThat(((And) ((And) q.where()).left()).left()).isInstanceOf(Not.class);
    }

    @Test
    void functionCanStandForTheInList() {
        Clause c = (Clause) Fql.parse("key in linkedTickets(PAY-1, \"bloque\")").where();
        assertThat(c.op()).isEqualTo("in");
        assertThat(c.values().getFirst().function()).isEqualTo("linkedtickets");
        assertThat(c.values().getFirst().args()).containsExactly("PAY-1", "bloque");
    }

    @Test
    void emptyQueryAndOrderOnly() {
        assertThat(Fql.parse("  ").where()).isNull();
        assertThat(Fql.parse("ORDER BY created DESC").orderBy()).containsExactly(new Order("created", true));
    }

    @Test
    void errorsArePositioned() {
        assertThatThrownBy(() -> Fql.parse("assignee = "))
                .isInstanceOf(Fql.ParseException.class).hasMessageContaining("Valeur attendue").hasMessageContaining("position 12");
        assertThatThrownBy(() -> Fql.parse("status foo bar"))
                .hasMessageContaining("Opérateur attendu au lieu de « foo »");
        assertThatThrownBy(() -> Fql.parse("summary ~ \"oops"))
                .hasMessageContaining("Chaîne non terminée");
        assertThatThrownBy(() -> Fql.parse("(status = a")).hasMessageContaining("« ) » attendu");
        assertThatThrownBy(() -> Fql.parse("status = a b")).hasMessageContaining("Élément inattendu « b »");
    }
}
