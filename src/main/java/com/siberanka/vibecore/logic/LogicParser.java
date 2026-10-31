package com.siberanka.vibecore.logic;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import com.siberanka.vibecore.variable.VariableValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads action chains and conditions from parsed YAML or JSON trees.
 *
 * <pre>
 * - set: {var: coins, value: 5}          # value may be a literal or {var: other}
 * - add: {var: coins, amount: -3}
 * - toggle: afk
 * - reset: coins
 * - if: {condition: ..., then: [...], else: [...]}
 * - fail: not-enough-coins
 * - emit: {type: message, key: shop.bought}
 *
 * compare: {left: {var: coins}, op: "&gt;=", right: 10}
 * all: [...]   any: [...]   not: {...}   chance: 0.25
 * </pre>
 *
 * Every defect is reported with its path; the tree size and nesting depth are
 * bounded so a hostile document cannot exhaust memory or the stack.
 */
public final class LogicParser {

    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 1024;
    private static final int MAX_TEXT = 256;

    private final List<Problem> problems = new ArrayList<>();
    private int nodes;

    private LogicParser() {
    }

    public static Result<List<Action>> actions(String path, Object raw) {
        LogicParser parser = new LogicParser();
        List<Action> chain = parser.chain(path, raw, 1);
        return parser.problems.isEmpty() ? Result.accepted(chain) : Result.rejected(parser.problems);
    }

    public static Result<Condition> condition(String path, Object raw) {
        LogicParser parser = new LogicParser();
        Condition condition = parser.condition(path, raw, 1);
        return parser.problems.isEmpty() && condition != null
                ? Result.accepted(condition) : Result.rejected(parser.problems.isEmpty()
                ? List.of(new Problem(path, "invalid", "invalid condition")) : parser.problems);
    }

    private boolean enter(String path, int depth) {
        if (depth > MAX_DEPTH) {
            problems.add(new Problem(path, "depth-limit", "nested deeper than " + MAX_DEPTH));
            return false;
        }
        if (++nodes > MAX_NODES) {
            if (nodes == MAX_NODES + 1) {
                problems.add(new Problem(path, "size-limit", "more than " + MAX_NODES + " logic nodes"));
            }
            return false;
        }
        return true;
    }

    private List<Action> chain(String path, Object raw, int depth) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            problems.add(new Problem(path, "type", "expected a list of actions"));
            return List.of();
        }
        List<Action> chain = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            Action action = action(path + "[" + index + "]", list.get(index), depth);
            if (action != null) {
                chain.add(action);
            }
        }
        return chain;
    }

    private Map.Entry<String, Object> single(String path, Object raw, String what) {
        if (!(raw instanceof Map<?, ?> map) || map.size() != 1) {
            problems.add(new Problem(path, "type", "expected a single-key " + what));
            return null;
        }
        var entry = map.entrySet().iterator().next();
        return Map.entry(String.valueOf(entry.getKey()), entry.getValue() == null ? "" : entry.getValue());
    }

    private Map<String, Object> section(String path, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            problems.add(new Problem(path, "type", "expected a section"));
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private Identifier variable(String path, Object raw) {
        if (!(raw instanceof String text)) {
            problems.add(new Problem(path, "type", "expected a variable identifier"));
            return null;
        }
        Result<Identifier> parsed = Identifier.parse(path, text);
        problems.addAll(parsed.problems());
        return parsed.isAccepted() ? parsed.orThrow() : null;
    }

    private Action action(String path, Object raw, int depth) {
        if (!enter(path, depth)) {
            return null;
        }
        var entry = single(path, raw, "action");
        if (entry == null) {
            return null;
        }
        String at = path + "." + entry.getKey();
        Object body = entry.getValue();
        switch (entry.getKey()) {
            case "set" -> {
                Map<String, Object> fields = section(at, body);
                if (fields == null) {
                    return null;
                }
                Identifier key = variable(at + ".var", fields.get("var"));
                Operand value = operand(at + ".value", fields.get("value"));
                return key != null && value != null ? new Action.SetVariable(key, value) : null;
            }
            case "add" -> {
                Map<String, Object> fields = section(at, body);
                if (fields == null) {
                    return null;
                }
                Identifier key = variable(at + ".var", fields.get("var"));
                Object amount = fields.get("amount");
                if (!(amount instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    problems.add(new Problem(at + ".amount", "type", "expected a finite number"));
                    return null;
                }
                return key != null ? new Action.AddVariable(key, number.doubleValue()) : null;
            }
            case "toggle" -> {
                Identifier key = variable(at, body);
                return key != null ? new Action.ToggleVariable(key) : null;
            }
            case "reset" -> {
                Identifier key = variable(at, body);
                return key != null ? new Action.ResetVariable(key) : null;
            }
            case "if" -> {
                Map<String, Object> fields = section(at, body);
                if (fields == null) {
                    return null;
                }
                Condition condition = condition(at + ".condition", fields.get("condition"), depth + 1);
                List<Action> then = chain(at + ".then", fields.get("then"), depth + 1);
                List<Action> otherwise = chain(at + ".else", fields.get("else"), depth + 1);
                return condition != null ? new Action.If(condition, then, otherwise) : null;
            }
            case "fail" -> {
                String reason = text(at, body);
                return reason != null ? new Action.Fail(reason) : null;
            }
            case "emit" -> {
                Map<String, Object> fields = section(at, body);
                if (fields == null) {
                    return null;
                }
                String type = text(at + ".type", fields.remove("type"));
                Map<String, String> arguments = new LinkedHashMap<>();
                fields.forEach((name, value) -> {
                    if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                        String rendered = String.valueOf(value);
                        if (rendered.length() > MAX_TEXT) {
                            problems.add(new Problem(at + "." + name, "length", "longer than " + MAX_TEXT));
                        }
                        arguments.put(name, rendered);
                    } else {
                        problems.add(new Problem(at + "." + name, "type", "expected a plain value"));
                    }
                });
                return type != null ? new Action.Emit(type, arguments) : null;
            }
            default -> {
                problems.add(new Problem(at, "unknown-action", "unknown action"));
                return null;
            }
        }
    }

    /** Largest money amount accepted in a document. */
    public static final BigDecimal MAX_MONEY = new BigDecimal("1000000000000");

    /**
     * Parses a positive money amount with at most two decimals, up to
     * {@link #MAX_MONEY}.
     *
     * @return the amount, or null when the value is not such an amount
     */
    public static BigDecimal parseMoney(Object raw) {
        BigDecimal amount;
        try {
            amount = raw instanceof Number || raw instanceof String
                    ? new BigDecimal(String.valueOf(raw).trim()) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_MONEY) > 0
                || amount.stripTrailingZeros().scale() > 2) {
            return null;
        }
        return amount.stripTrailingZeros();
    }

    private String money(String path, Object raw) {
        BigDecimal amount = parseMoney(raw);
        if (amount == null) {
            problems.add(new Problem(path, "range",
                    "expected a positive amount up to " + MAX_MONEY.toPlainString() + " with at most two decimals"));
            return null;
        }
        return amount.toPlainString();
    }

    /** Validates {@code {material: key, amount: n}} or a bare material key; amount defaults to 1. */
    private Map<String, String> item(String path, Object raw) {
        Object material = raw instanceof Map<?, ?> map ? map.get("material") : raw;
        Object amount = raw instanceof Map<?, ?> map ? map.get("amount") : null;
        if (raw instanceof Map<?, ?> map && !Set.of("material", "amount").containsAll(
                map.keySet().stream().map(String::valueOf).toList())) {
            problems.add(new Problem(path, "unknown-key", "expected only material and amount"));
            return null;
        }
        if (!(material instanceof String key) || !key.matches("[a-z0-9_]{1,64}")) {
            problems.add(new Problem(path + ".material", "format", "expected a lower-case material key"));
            return null;
        }
        int count = 1;
        if (amount != null) {
            if (!(amount instanceof Integer value) || value < 1 || value > 2304) {
                problems.add(new Problem(path + ".amount", "range", "expected a whole number from 1 to 2304"));
                return null;
            }
            count = value;
        }
        return Map.of("material", key, "amount", Integer.toString(count));
    }

    private String text(String path, Object raw) {
        if (!(raw instanceof String text) || text.isBlank() || text.length() > MAX_TEXT) {
            problems.add(new Problem(path, "type", "expected text of 1 to " + MAX_TEXT + " characters"));
            return null;
        }
        return text;
    }

    private Operand operand(String path, Object raw) {
        if (raw instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.containsKey("var")) {
                Identifier key = variable(path + ".var", map.get("var"));
                return key != null ? new Operand.Variable(key) : null;
            }
            problems.add(new Problem(path, "type", "expected a literal or {var: id}"));
            return null;
        }
        return switch (raw) {
            case Boolean flag -> new Operand.Literal(VariableValue.bool(flag));
            case Number number when Double.isFinite(number.doubleValue()) ->
                    new Operand.Literal(VariableValue.number(number.doubleValue()));
            case String text when text.length() <= 1024 -> new Operand.Literal(VariableValue.text(text));
            case null, default -> {
                problems.add(new Problem(path, "type", "expected a finite number, boolean, text or {var: id}"));
                yield null;
            }
        };
    }

    private Condition condition(String path, Object raw, int depth) {
        if (!enter(path, depth)) {
            return null;
        }
        var entry = single(path, raw, "condition");
        if (entry == null) {
            return null;
        }
        String at = path + "." + entry.getKey();
        Object body = entry.getValue();
        switch (entry.getKey()) {
            case "compare" -> {
                Map<String, Object> fields = section(at, body);
                if (fields == null) {
                    return null;
                }
                Operand left = operand(at + ".left", fields.get("left"));
                Operand right = operand(at + ".right", fields.get("right"));
                Condition.Comparison comparison = fields.get("op") instanceof String symbol
                        ? Condition.Comparison.fromSymbol(symbol) : null;
                if (comparison == null) {
                    problems.add(new Problem(at + ".op", "unknown-operator", "use ==, !=, <, <=, > or >="));
                }
                return left != null && right != null && comparison != null
                        ? new Condition.Compare(left, comparison, right) : null;
            }
            case "all", "any" -> {
                if (!(body instanceof List<?> list)) {
                    problems.add(new Problem(at, "type", "expected a list of conditions"));
                    return null;
                }
                List<Condition> children = new ArrayList<>();
                boolean complete = true;
                for (int index = 0; index < list.size(); index++) {
                    Condition child = condition(at + "[" + index + "]", list.get(index), depth + 1);
                    complete &= child != null;
                    if (child != null) {
                        children.add(child);
                    }
                }
                if (!complete) {
                    return null;
                }
                return entry.getKey().equals("all") ? new Condition.All(children) : new Condition.Any(children);
            }
            case "not" -> {
                Condition child = condition(at, body, depth + 1);
                return child != null ? new Condition.Not(child) : null;
            }
            case "has-money" -> {
                String amount = money(at, body);
                return amount != null ? new Condition.Fact("money", Map.of("amount", amount)) : null;
            }
            case "has-item" -> {
                Map<String, String> item = item(at, body);
                return item != null ? new Condition.Fact("item", item) : null;
            }
            case "has-permission" -> {
                if (!(body instanceof String node) || !node.matches("[a-z0-9_.-]{1,128}")) {
                    problems.add(new Problem(at, "format", "expected a permission node"));
                    return null;
                }
                return new Condition.Fact("permission", Map.of("node", node));
            }
            case "chance" -> {
                if (!(body instanceof Number number) || !(number.doubleValue() >= 0 && number.doubleValue() <= 1)) {
                    problems.add(new Problem(at, "range", "expected a probability between 0 and 1"));
                    return null;
                }
                return new Condition.Chance(number.doubleValue());
            }
            default -> {
                problems.add(new Problem(at, "unknown-condition", "unknown condition"));
                return null;
            }
        }
    }
}
