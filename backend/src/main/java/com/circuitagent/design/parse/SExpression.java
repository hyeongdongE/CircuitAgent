package com.circuitagent.design.parse;

import java.util.*;

/**
 * KiCad 괄호 구조를 Atom(문자열)과 Node(목록)로 읽는 재귀 파서. 따옴표 안의 괄호는 데이터로 보존하며 문자열 escape와 구조 구분자를 따로 처리한다. 순환
 * 구조 대신 중첩 깊이와 전체 토큰 수를 제한해 비정상 입력을 거절한다.
 */
public final class SExpression {
  public sealed interface Value permits Atom, Node {}

  public record Atom(String text) implements Value {}

  // 목록의 첫 Atom은 tag, 하위 Node는 child, 다음 Atom은 scalar 값이다.
  public record Node(List<Value> values) implements Value {
    public Node {
      values = List.copyOf(values);
    }

    public String tag() {
      return values.isEmpty() || !(values.getFirst() instanceof Atom a) ? "" : a.text();
    }

    public List<Node> children(String tag) {
      return values.stream()
          .filter(Node.class::isInstance)
          .map(Node.class::cast)
          .filter(n -> n.tag().equals(tag))
          .toList();
    }

    public Optional<Node> child(String tag) {
      return children(tag).stream().findFirst();
    }

    public String field(String tag) {
      return child(tag).map(Node::scalar).orElse(null);
    }

    public String scalar() {
      return values.size() > 1 && values.get(1) instanceof Atom a ? a.text() : null;
    }
  }

  private final String input;
  private int offset;
  private int valueCount;

  private SExpression(String input) {
    this.input = input;
  }

  public static Node parse(String input) {
    var parser = new SExpression(input);
    Value root = parser.read(0);
    parser.space();
    if (parser.offset != input.length() || !(root instanceof Node))
      throw parser.error("Expected one root expression");
    return (Node) root;
  }

  private Value read(int depth) {
    if (++valueCount > ParserLimits.MAX_VALUES) throw error("Maximum expression count exceeded");
    if (depth > 256) throw error("Maximum nesting exceeded");
    space();
    if (offset == input.length()) throw error("Unexpected EOF");
    char c = input.charAt(offset++);
    // 다음 문자를 기준으로 목록/문자열/일반 Atom을 구분한다. 문자열 내부 괄호는 구조를 바꾸지 않는다.
    if (c == '(') {
      var values = new ArrayList<Value>();
      while (true) {
        space();
        if (offset == input.length()) throw error("Unclosed list");
        if (input.charAt(offset) == ')') {
          offset++;
          return new Node(values);
        }
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
          value.append(
              switch (c) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case '"' -> '"';
                case '\\' -> '\\';
                default -> throw error("Unknown escape");
              });
        } else value.append(c);
      }
      throw error("Unclosed string");
    }
    int start = offset - 1;
    while (offset < input.length()
        && !Character.isWhitespace(input.charAt(offset))
        && input.charAt(offset) != '('
        && input.charAt(offset) != ')') offset++;
    return new Atom(input.substring(start, offset));
  }

  // 공백·UTF-8 BOM·세미콜론 행 주석을 건너뛰되 quoted string의 내용에는 적용하지 않는다.
  private void space() {
    while (offset < input.length()) {
      if (Character.isWhitespace(input.charAt(offset)) || input.charAt(offset) == '\uFEFF')
        offset++;
      else if (input.charAt(offset) == ';') {
        while (offset < input.length() && input.charAt(offset) != '\n') offset++;
      } else break;
    }
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException(message + " at character " + offset);
  }
}
