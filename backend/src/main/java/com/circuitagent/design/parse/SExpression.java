package com.circuitagent.design.parse;

import java.util.*;

/** Structural tokenizer/parser: quoted parentheses are data, not delimiters. */
public final class SExpression {
    public sealed interface Value permits Atom, Node {}
    public record Atom(String text) implements Value {}
    public record Node(List<Value> values) implements Value {
        public Node { values = List.copyOf(values); }
        public String tag() { return values.isEmpty() || !(values.getFirst() instanceof Atom a) ? "" : a.text(); }
        public List<Node> children(String tag) { return values.stream().filter(Node.class::isInstance).map(Node.class::cast).filter(n -> n.tag().equals(tag)).toList(); }
        public Optional<Node> child(String tag) { return children(tag).stream().findFirst(); }
        public String field(String tag) { return child(tag).map(Node::scalar).orElse(null); }
        public String scalar() { return values.size() > 1 && values.get(1) instanceof Atom a ? a.text() : null; }
    }
    private final String input;
    private int offset;
    private int valueCount;
    private SExpression(String input) { this.input = input; }
    public static Node parse(String input) {
        var parser = new SExpression(input);
        Value root = parser.read(0);
        parser.space();
        if (parser.offset != input.length() || !(root instanceof Node)) throw parser.error("Expected one root expression");
        return (Node) root;
    }
    private Value read(int depth) {
        if (++valueCount > ParserLimits.MAX_VALUES) throw error("Maximum expression count exceeded");
        if (depth > 256) throw error("Maximum nesting exceeded");
        space();
        if (offset == input.length()) throw error("Unexpected EOF");
        char c = input.charAt(offset++);
        if (c == '(') {
            var values = new ArrayList<Value>();
            while (true) {
                space();
                if (offset == input.length()) throw error("Unclosed list");
                if (input.charAt(offset) == ')') { offset++; return new Node(values); }
                values.add(read(depth + 1));
            }
        }
        if (c == ')') throw error("Unexpected closing parenthesis");
        if (c == '"') {
            var value = new StringBuilder();
            while (offset < input.length()) {
                c = input.charAt(offset++);
                if (c == '"') return new Atom(value.toString());
                if (c == '\\') {
                    if (offset == input.length()) throw error("Unclosed escape");
                    c = input.charAt(offset++);
                    value.append(switch (c) {
                        case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case '"' -> '"'; case '\\' -> '\\';
                        default -> throw error("Unknown escape");
                    });
                } else value.append(c);
            }
            throw error("Unclosed string");
        }
        int start = offset - 1;
        while (offset < input.length() && !Character.isWhitespace(input.charAt(offset)) && input.charAt(offset) != '(' && input.charAt(offset) != ')') offset++;
        return new Atom(input.substring(start, offset));
    }
    private void space() {
        while (offset < input.length()) {
            if (Character.isWhitespace(input.charAt(offset)) || input.charAt(offset) == '\uFEFF') offset++;
            else if (input.charAt(offset) == ';') { while (offset < input.length() && input.charAt(offset) != '\n') offset++; }
            else break;
        }
    }
    private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at character " + offset); }
}
