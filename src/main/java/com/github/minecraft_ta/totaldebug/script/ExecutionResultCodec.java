package com.github.minecraft_ta.totaldebug.script;

import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** JSON wire codec and byte-budget adapter for the canonical execution result. */
public final class ExecutionResultCodec {
    private static final int DIRECT_MESSAGE_OVERHEAD_BYTES = Integer.BYTES * 2;
    public static final int MAX_WIRE_BYTES = Math.min(
            DefaultMessageProcessor.DEFAULT_MAX_STRING_LENGTH,
            DefaultMessageProcessor.DEFAULT_MAX_FRAME_SIZE - DIRECT_MESSAGE_OVERHEAD_BYTES
    );
    private static final int SEARCH_STEPS = 12;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ExecutionResultCodec() {
    }

    public static Encoded encode(ExecutionResult result) {
        return encode(result, MAX_WIRE_BYTES);
    }

    public static Encoded encode(ExecutionResult result, int maxBytes) {
        Objects.requireNonNull(result, "result");
        if (maxBytes < 1_024) {
            throw new IllegalArgumentException("Execution-result budget must be at least 1024 bytes");
        }
        result = retainTextFairly(result, maxBytes);

        int valueNodes = countNodes(result.value());
        int retainedNodes = valueNodes;
        Encoded best = encoded(retain(result, 0.0D, retainedNodes));
        if (best.utf8Bytes() > maxBytes) {
            if (result.value() == null) {
                throw metadataTooLarge(maxBytes);
            }
            int lowNodes = 1;
            int highNodes = valueNodes - 1;
            Encoded structuralBest = encoded(retain(result, 0.0D, lowNodes));
            if (structuralBest.utf8Bytes() > maxBytes) {
                throw metadataTooLarge(maxBytes);
            }
            retainedNodes = lowNodes;
            best = structuralBest;
            lowNodes = 2;
            while (lowNodes <= highNodes) {
                int candidateNodes = lowNodes + (highNodes - lowNodes) / 2;
                Encoded candidate = encoded(retain(result, 0.0D, candidateNodes));
                if (candidate.utf8Bytes() <= maxBytes) {
                    retainedNodes = candidateNodes;
                    best = candidate;
                    lowNodes = candidateNodes + 1;
                } else {
                    highNodes = candidateNodes - 1;
                }
            }
        }

        long textBytes = textBytes(result);
        long completeUpperBound = add(add(best.utf8Bytes(), textBytes), textFieldCount(result));
        if (retainedNodes == valueNodes && completeUpperBound <= maxBytes) {
            return encoded(result);
        }
        long availableTextBytes = Math.max(0L, maxBytes - best.utf8Bytes());
        double low = 0.0D;
        double high = Math.min(1.0D, availableTextBytes / (double) Math.max(1L, textBytes));
        for (int index = 0; index < SEARCH_STEPS; index++) {
            double ratio = (low + high) / 2.0D;
            Encoded candidate = encoded(retain(result, ratio, retainedNodes));
            if (candidate.utf8Bytes() <= maxBytes) {
                best = candidate;
                low = ratio;
            } else {
                high = ratio;
            }
        }
        return best;
    }

    private static ExecutionResult retainTextFairly(ExecutionResult result, int maxCharacters) {
        List<ExecutionText> texts = new ArrayList<>();
        texts.add(result.logs());
        collectTexts(result.value(), texts);
        texts.add(result.error());

        long totalCharacters = 0;
        int longest = 0;
        for (ExecutionText text : texts) {
            int length = text.text().length();
            totalCharacters = add(totalCharacters, length);
            longest = Math.max(longest, length);
        }
        if (totalCharacters <= maxCharacters) {
            return result;
        }

        int low = 0;
        int high = longest;
        while (low < high) {
            int candidate = low + (int) (((long) high - low + 1) / 2);
            if (retainedCharacters(texts, candidate) <= maxCharacters) {
                low = candidate;
            } else {
                high = candidate - 1;
            }
        }
        long used = retainedCharacters(texts, low);
        TextRetention retention = new TextRetention(low, (int) (maxCharacters - used));
        ExecutionText logs = retention.retain(result.logs());
        ExecutionValue value = retainText(result.value(), retention);
        ExecutionText error = retention.retain(result.error());
        return new ExecutionResult(result.status(), logs, value, error);
    }

    private static long retainedCharacters(List<ExecutionText> texts, int perFieldLimit) {
        long retained = 0;
        for (ExecutionText text : texts) {
            retained = add(retained, Math.min(text.text().length(), perFieldLimit));
        }
        return retained;
    }

    private static void collectTexts(ExecutionValue value, List<ExecutionText> texts) {
        if (value == null) {
            return;
        }
        texts.add(value.type());
        texts.add(value.value());
        texts.add(value.preview());
        for (ExecutionValue.Child child : value.children()) {
            texts.add(child.name());
            collectTexts(child.key(), texts);
            collectTexts(child.value(), texts);
        }
    }

    private static ExecutionValue retainText(ExecutionValue value, TextRetention retention) {
        if (value == null) {
            return null;
        }
        ExecutionText type = retention.retain(value.type());
        ExecutionText scalar = retention.retain(value.value());
        ExecutionText preview = retention.retain(value.preview());
        List<ExecutionValue.Child> children = new ArrayList<>(value.children().size());
        for (ExecutionValue.Child child : value.children()) {
            ExecutionText name = retention.retain(child.name());
            ExecutionValue key = retainText(child.key(), retention);
            ExecutionValue childValue = retainText(child.value(), retention);
            children.add(new ExecutionValue.Child(name, child.kind(), key, childValue));
        }
        return new ExecutionValue(
                type,
                scalar,
                preview,
                value.kind(),
                value.identity(),
                value.totalChildren(),
                value.truncated(),
                children
        );
    }

    public static ExecutionResult decode(String json) {
        Objects.requireNonNull(json, "json");
        ExecutionResult result;
        try {
            result = GSON.fromJson(json, ExecutionResult.class);
        } catch (RuntimeException exception) {
            throw new JsonParseException("Invalid execution result", exception);
        }
        if (result == null) {
            throw new JsonParseException("Execution result is null");
        }
        validateText(result.logs(), "logs");
        validateText(result.error(), "error");
        if (result.status() == null) {
            throw new JsonParseException("Execution result has no status");
        }
        if (result.value() != null) {
            validateValue(result.value(), 0, new int[1]);
        }
        return result;
    }

    private static Encoded encoded(ExecutionResult result) {
        String json = GSON.toJson(result);
        return new Encoded(result, json, json.getBytes(StandardCharsets.UTF_8).length);
    }

    private static ExecutionResult retain(ExecutionResult result, double ratio, int retainedNodes) {
        int retainedLogs = retainedCharacters(result.logs().text().length(), ratio);
        int retainedError = retainedCharacters(result.error().text().length(), ratio);
        ExecutionValue value = result.value();
        if (value != null) {
            value = retain(value, new NodeBudget(retainedNodes), ratio);
        }
        return new ExecutionResult(
                result.status(),
                result.logs().retain(retainedLogs),
                value,
                result.error().retain(retainedError)
        );
    }

    private static int retainedCharacters(int length, double ratio) {
        return Math.min(length, (int) Math.floor(length * ratio));
    }

    private static ExecutionValue retain(ExecutionValue value, NodeBudget budget, double ratio) {
        if (!budget.take()) {
            return null;
        }
        List<ExecutionValue.Child> retained = new ArrayList<>();
        for (ExecutionValue.Child child : value.children()) {
            if (child.kind() == ExecutionValue.ChildKind.MAP_ENTRY) {
                if (budget.remaining() < 2) {
                    break;
                }
                int checkpoint = budget.remaining();
                ExecutionValue key = retain(child.key(), budget, ratio);
                ExecutionValue childValue = retain(child.value(), budget, ratio);
                if (key == null || childValue == null) {
                    budget.restore(checkpoint);
                    break;
                }
                retained.add(ExecutionValue.Child.mapEntry(key, childValue));
            } else {
                if (budget.remaining() < 1) {
                    break;
                }
                ExecutionValue childValue = retain(child.value(), budget, ratio);
                if (childValue == null) {
                    break;
                }
                retained.add(new ExecutionValue.Child(
                        child.name().retain(retainedCharacters(child.name().text().length(), ratio)),
                        child.kind(),
                        null,
                        childValue
                ));
            }
        }
        return new ExecutionValue(
                value.type().retain(retainedCharacters(value.type().text().length(), ratio)),
                value.value().retain(retainedCharacters(value.value().text().length(), ratio)),
                value.preview().retain(retainedCharacters(value.preview().text().length(), ratio)),
                value.kind(),
                value.identity(),
                value.totalChildren(),
                value.truncated() || retained.size() < value.children().size(),
                retained
        );
    }

    private static int countNodes(ExecutionValue value) {
        if (value == null) {
            return 0;
        }
        int nodes = 1;
        for (ExecutionValue.Child child : value.children()) {
            nodes += countNodes(child.key());
            nodes += countNodes(child.value());
        }
        return nodes;
    }

    private static long textBytes(ExecutionResult result) {
        long bytes = jsonStringBytes(result.logs().text());
        bytes = add(bytes, jsonStringBytes(result.error().text()));
        return add(bytes, textBytes(result.value()));
    }

    private static long textFieldCount(ExecutionResult result) {
        return add(2, textFieldCount(result.value()));
    }

    private static long textFieldCount(ExecutionValue value) {
        if (value == null) {
            return 0;
        }
        long fields = 3;
        for (ExecutionValue.Child child : value.children()) {
            fields = add(fields, 1);
            fields = add(fields, textFieldCount(child.key()));
            fields = add(fields, textFieldCount(child.value()));
        }
        return fields;
    }

    private static long textBytes(ExecutionValue value) {
        if (value == null) {
            return 0;
        }
        long bytes = jsonStringBytes(value.type().text());
        bytes = add(bytes, jsonStringBytes(value.value().text()));
        bytes = add(bytes, jsonStringBytes(value.preview().text()));
        for (ExecutionValue.Child child : value.children()) {
            bytes = add(bytes, jsonStringBytes(child.name().text()));
            bytes = add(bytes, textBytes(child.key()));
            bytes = add(bytes, textBytes(child.value()));
        }
        return bytes;
    }

    private static long jsonStringBytes(String value) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\'
                    || character == '\b' || character == '\f'
                    || character == '\n' || character == '\r' || character == '\t') {
                bytes = add(bytes, 2);
            } else if (character < 0x20 || character == '\u2028' || character == '\u2029') {
                bytes = add(bytes, 6);
            } else if (character < 0x80) {
                bytes = add(bytes, 1);
            } else if (character < 0x800) {
                bytes = add(bytes, 2);
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes = add(bytes, 4);
                index++;
            } else {
                bytes = add(bytes, 3);
            }
        }
        return bytes;
    }

    private static long add(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static IllegalArgumentException metadataTooLarge(int maxBytes) {
        return new IllegalArgumentException(
                "Execution-result metadata exceeds the transport budget of " + maxBytes + " bytes"
        );
    }

    private static void validateText(ExecutionText text, String name) {
        if (text == null || text.text() == null || text.totalCharacters() < text.text().length()
                || text.truncated() != (text.totalCharacters() > text.text().length())) {
            throw new JsonParseException("Execution result contains invalid " + name + " metadata");
        }
    }

    private static void validateValue(ExecutionValue value, int depth, int[] nodes) {
        if (depth > 8 || ++nodes[0] > 5_000) {
            throw new JsonParseException("Execution value exceeds the supported graph bounds");
        }
        if (value.kind() == null || value.type() == null || value.value() == null
                || value.preview() == null || value.children() == null
                || value.identity() < 0 || value.totalChildren() < value.children().size()
                || value.truncated() != (value.totalChildren() > value.children().size())) {
            throw new JsonParseException("Execution value contains invalid metadata");
        }
        boolean identified = switch (value.kind()) {
            case OPTIONAL, ARRAY, COLLECTION, MAP, OBJECT, REFERENCE -> true;
            default -> false;
        };
        if (identified != (value.identity() > 0)) {
            throw new JsonParseException("Execution value contains invalid identity metadata");
        }
        if (!value.value().truncated() && value.kind() == ExecutionValue.Kind.BOOLEAN
                && !value.value().text().equals("true")
                && !value.value().text().equals("false")) {
            throw new JsonParseException("Execution value contains an invalid boolean");
        }
        if (!value.value().truncated() && value.kind() == ExecutionValue.Kind.NUMBER) {
            try {
                String number = value.value().text();
                if (!number.equals("NaN") && !number.equals("Infinity") && !number.equals("-Infinity")) {
                    new java.math.BigDecimal(number);
                }
            } catch (NumberFormatException exception) {
                throw new JsonParseException("Execution value contains an invalid number", exception);
            }
        }
        validateText(value.value(), "value");
        validateText(value.preview(), "preview");
        validateText(value.type(), "type");
        for (ExecutionValue.Child child : value.children()) {
            if (child == null || child.name() == null || child.kind() == null || child.value() == null
                    || (child.kind() == ExecutionValue.ChildKind.MAP_ENTRY) != (child.key() != null)
                    || !ExecutionValue.validChildKind(value.kind(), child.kind())) {
                throw new JsonParseException("Execution value contains an invalid child");
            }
            validateText(child.name(), "child name");
            if (child.key() != null) {
                validateValue(child.key(), depth + 1, nodes);
            }
            validateValue(child.value(), depth + 1, nodes);
        }
    }

    public record Encoded(ExecutionResult result, String json, int utf8Bytes) {
        public Encoded {
            result = Objects.requireNonNull(result, "result");
            json = Objects.requireNonNull(json, "json");
            if (utf8Bytes < 0) {
                throw new IllegalArgumentException("utf8Bytes must not be negative");
            }
        }
    }

    private static final class NodeBudget {
        private int remaining;

        private NodeBudget(int remaining) {
            this.remaining = remaining;
        }

        private boolean take() {
            if (this.remaining == 0) {
                return false;
            }
            this.remaining--;
            return true;
        }

        private int remaining() {
            return this.remaining;
        }

        private void restore(int remaining) {
            this.remaining = remaining;
        }
    }

    private static final class TextRetention {
        private final int perFieldLimit;
        private int extraCharacters;

        private TextRetention(int perFieldLimit, int extraCharacters) {
            this.perFieldLimit = perFieldLimit;
            this.extraCharacters = extraCharacters;
        }

        private ExecutionText retain(ExecutionText text) {
            int retained = Math.min(text.text().length(), this.perFieldLimit);
            if (retained < text.text().length() && this.extraCharacters > 0) {
                retained++;
                this.extraCharacters--;
            }
            return text.retain(retained);
        }
    }
}
