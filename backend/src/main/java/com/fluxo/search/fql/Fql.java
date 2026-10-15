package com.fluxo.search.fql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * FQL: the Fluxo query language (spec §4.11). Hand-written recursive-descent parser producing a small AST.
 * <pre>
 * query   := expr? ("ORDER BY" order ("," order)*)?
 * expr    := term ("OR" term)*
 * term    := factor ("AND" factor)*
 * factor  := "NOT" factor | "(" expr ")" | clause
 * clause  := field op value | field "IN" list | field "NOT IN" list | field "IS" ["NOT"] "EMPTY"
 *          | field "WAS" ["NOT"] ["IN"] value-or-list | field "CHANGED" changeCond*
 * value   := string | word | number | function "(" args ")"
 * </pre>
 */
public final class Fql {

    private Fql() {
    }

    // --- AST -------------------------------------------------------------------------------------

    public sealed interface Expr permits And, Or, Not, Clause {}

    public record And(Expr left, Expr right) implements Expr {}

    public record Or(Expr left, Expr right) implements Expr {}

    public record Not(Expr expr) implements Expr {}

    /** A comparison; {@code values} holds one value (or several for IN), empty for IS [NOT] EMPTY. */
    public record Clause(String field, String op, List<Value> values, List<ChangeCond> changes, int position) implements Expr {}

    /** A literal (text or number as typed) or a function call such as currentUser() or startOfDay(-1). */
    public record Value(String text, String function, List<String> args, int position) {
        public boolean isFunction() {
            return function != null;
        }
    }

    /** CHANGED FROM x / TO y / BY user / AFTER date / BEFORE date. */
    public record ChangeCond(String kind, Value value) {}

    public record Order(String field, boolean desc) {}

    public record Query(Expr where, List<Order> orderBy) {}

    public static final class ParseException extends RuntimeException {
        private final int position;

        ParseException(String message, int position) {
            super(message);
            this.position = position;
        }

        public int position() {
            return position;
        }
    }

    public static Query parse(String text) {
        return new Parser(new Lexer(text == null ? "" : text).tokens()).query();
    }

    // --- Lexer -----------------------------------------------------------------------------------

    enum Kind { WORD, STRING, OP, LPAREN, RPAREN, COMMA, EOF }

    record Token(Kind kind, String text, int position) {
        boolean keyword(String k) {
            return kind == Kind.WORD && text.equalsIgnoreCase(k);
        }
    }

    static final class Lexer {
        private final String s;
        private int i;

        Lexer(String s) {
            this.s = s;
        }

        List<Token> tokens() {
            List<Token> out = new ArrayList<>();
            while (true) {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                if (i >= s.length()) {
                    out.add(new Token(Kind.EOF, "", i));
                    return out;
                }
                int start = i;
                char c = s.charAt(i);
                switch (c) {
                    case '(' -> { i++; out.add(new Token(Kind.LPAREN, "(", start)); }
                    case ')' -> { i++; out.add(new Token(Kind.RPAREN, ")", start)); }
                    case ',' -> { i++; out.add(new Token(Kind.COMMA, ",", start)); }
                    case '"', '\'' -> out.add(new Token(Kind.STRING, string(c), start));
                    case '=', '~' -> { i++; out.add(new Token(Kind.OP, String.valueOf(c), start)); }
                    case '!' -> {
                        if (i + 1 < s.length() && (s.charAt(i + 1) == '=' || s.charAt(i + 1) == '~')) {
                            out.add(new Token(Kind.OP, s.substring(i, i + 2), start));
                            i += 2;
                        } else {
                            throw new ParseException("Opérateur inconnu « ! »", start);
                        }
                    }
                    case '<', '>' -> {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '=') {
                            out.add(new Token(Kind.OP, s.substring(i, i + 2), start));
                            i += 2;
                        } else {
                            i++;
                            out.add(new Token(Kind.OP, String.valueOf(c), start));
                        }
                    }
                    default -> {
                        while (i < s.length() && !Character.isWhitespace(s.charAt(i)) && "()=,~!<>\"'".indexOf(s.charAt(i)) < 0) i++;
                        out.add(new Token(Kind.WORD, s.substring(start, i), start));
                    }
                }
            }
        }

        private String string(char quote) {
            int start = i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length() && s.charAt(i) != quote) {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length()) c = s.charAt(i++);
                sb.append(c);
            }
            if (i >= s.length()) throw new ParseException("Chaîne non terminée", start);
            i++;
            return sb.toString();
        }
    }

    // --- Parser ----------------------------------------------------------------------------------

    private static final Set<String> COMPARISONS = Set.of("=", "!=", "<", "<=", ">", ">=", "~", "!~");
    private static final Set<String> RESERVED = Set.of("and", "or", "not", "order", "in", "is", "was", "changed", "empty", "null");

    static final class Parser {
        private final List<Token> tokens;
        private int p;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Query query() {
            Expr where = peek().kind == Kind.EOF || peek().keyword("order") ? null : expr();
            List<Order> order = new ArrayList<>();
            if (peek().keyword("order")) {
                next();
                expectKeyword("by");
                do {
                    Token f = expectWord("champ de tri");
                    boolean desc = false;
                    if (peek().keyword("desc")) { next(); desc = true; }
                    else if (peek().keyword("asc")) next();
                    order.add(new Order(f.text().toLowerCase(Locale.ROOT), desc));
                } while (accept(Kind.COMMA));
            }
            if (peek().kind != Kind.EOF) throw error("Élément inattendu « " + peek().text() + " »", peek());
            return new Query(where, order);
        }

        private Expr expr() {
            Expr left = term();
            while (peek().keyword("or")) {
                next();
                left = new Or(left, term());
            }
            return left;
        }

        private Expr term() {
            Expr left = factor();
            while (peek().keyword("and")) {
                next();
                left = new And(left, factor());
            }
            return left;
        }

        private Expr factor() {
            if (peek().keyword("not")) {
                next();
                return new Not(factor());
            }
            if (accept(Kind.LPAREN)) {
                Expr e = expr();
                expect(Kind.RPAREN, "« ) » attendu");
                return e;
            }
            return clause();
        }

        private Expr clause() {
            Token field = peek();
            if (field.kind != Kind.WORD && field.kind != Kind.STRING) throw error("Champ attendu", field);
            if (field.kind == Kind.WORD && RESERVED.contains(field.text().toLowerCase(Locale.ROOT))) {
                throw error("Champ attendu avant « " + field.text() + " »", field);
            }
            next();
            String name = field.text().toLowerCase(Locale.ROOT);
            Token t = peek();
            if (t.kind == Kind.OP && COMPARISONS.contains(t.text())) {
                next();
                return new Clause(name, t.text(), List.of(value()), List.of(), field.position());
            }
            if (t.keyword("in")) {
                next();
                return new Clause(name, "in", list(), List.of(), field.position());
            }
            if (t.keyword("not") && peek(1).keyword("in")) {
                next();
                next();
                return new Clause(name, "not in", list(), List.of(), field.position());
            }
            if (t.keyword("is")) {
                next();
                boolean not = false;
                if (peek().keyword("not")) { next(); not = true; }
                Token e = next();
                if (!e.keyword("empty") && !e.keyword("null")) throw error("EMPTY attendu après IS", e);
                return new Clause(name, not ? "is not empty" : "is empty", List.of(), List.of(), field.position());
            }
            if (t.keyword("was")) {
                next();
                boolean not = false;
                if (peek().keyword("not")) { next(); not = true; }
                if (peek().keyword("in")) {
                    next();
                    return new Clause(name, not ? "was not in" : "was in", list(), List.of(), field.position());
                }
                return new Clause(name, not ? "was not" : "was", List.of(value()), List.of(), field.position());
            }
            if (t.keyword("changed")) {
                next();
                List<ChangeCond> conds = new ArrayList<>();
                while (peek().kind == Kind.WORD && Set.of("from", "to", "by", "after", "before").contains(peek().text().toLowerCase(Locale.ROOT))) {
                    String kind = next().text().toLowerCase(Locale.ROOT);
                    conds.add(new ChangeCond(kind, value()));
                }
                return new Clause(name, "changed", List.of(), conds, field.position());
            }
            throw error(t.kind == Kind.EOF ? "Opérateur attendu après « " + field.text() + " »" : "Opérateur attendu au lieu de « " + t.text() + " »", t);
        }

        private List<Value> list() {
            if (peek().kind == Kind.WORD && peek(1).kind == Kind.LPAREN) return List.of(value()); // IN linkedTickets(…)
            expect(Kind.LPAREN, "« ( » attendu");
            List<Value> values = new ArrayList<>();
            if (peek().kind != Kind.RPAREN) {
                do values.add(value()); while (accept(Kind.COMMA));
            }
            expect(Kind.RPAREN, "« ) » attendu");
            if (values.isEmpty()) throw error("Liste vide", peek());
            return values;
        }

        private Value value() {
            Token t = peek();
            if (t.kind == Kind.STRING) {
                next();
                return new Value(t.text(), null, List.of(), t.position());
            }
            if (t.kind != Kind.WORD || RESERVED.contains(t.text().toLowerCase(Locale.ROOT)) && !t.keyword("empty") && !t.keyword("null")) {
                throw error(t.kind == Kind.EOF ? "Valeur attendue" : "Valeur attendue au lieu de « " + t.text() + " »", t);
            }
            next();
            if (peek().kind == Kind.LPAREN) { // function call
                next();
                List<String> args = new ArrayList<>();
                if (peek().kind != Kind.RPAREN) {
                    do {
                        Token a = next();
                        if (a.kind != Kind.WORD && a.kind != Kind.STRING) throw error("Argument attendu", a);
                        args.add(a.text());
                    } while (accept(Kind.COMMA));
                }
                expect(Kind.RPAREN, "« ) » attendu");
                return new Value(null, t.text().toLowerCase(Locale.ROOT), args, t.position());
            }
            return new Value(t.text(), null, List.of(), t.position());
        }

        private Token peek() {
            return tokens.get(p);
        }

        private Token peek(int ahead) {
            return tokens.get(Math.min(p + ahead, tokens.size() - 1));
        }

        private Token next() {
            Token t = tokens.get(p);
            if (t.kind != Kind.EOF) p++;
            return t;
        }

        private boolean accept(Kind k) {
            if (peek().kind == k) {
                next();
                return true;
            }
            return false;
        }

        private void expect(Kind k, String message) {
            if (!accept(k)) throw error(message, peek());
        }

        private void expectKeyword(String k) {
            if (!peek().keyword(k)) throw error(k.toUpperCase(Locale.ROOT) + " attendu", peek());
            next();
        }

        private Token expectWord(String what) {
            Token t = peek();
            if (t.kind != Kind.WORD && t.kind != Kind.STRING) throw error(what + " attendu", t);
            return next();
        }

        private static ParseException error(String message, Token at) {
            return new ParseException(message + " (position " + (at.position() + 1) + ")", at.position());
        }
    }
}
