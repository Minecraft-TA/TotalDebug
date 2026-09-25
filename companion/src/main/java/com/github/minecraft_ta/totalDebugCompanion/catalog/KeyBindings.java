package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The pack's key bindings with their current keys, and which of them collide. Keys come from {@code options.txt},
 * where Minecraft saves each binding as {@code key_<name>:<key>} with an optional {@code :<MODIFIER>}. Collisions
 * follow NeoForge's rules, split into those that act on the same key press and those NeoForge reports only because
 * the keys are equal while their contexts never conflict.
 * <p>
 * Minecraft only presses bindings while no screen is open, so its own bindings act in the world, except the few its
 * screens also check and the few it handles anywhere. NeoForge still gives many of them the anywhere context; those
 * are compared as acting in the game.
 */
public final class KeyBindings {
    public static final String UNBOUND = "key.keyboard.unknown";
    static final String IN_GAME = "net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME";
    static final String UNIVERSAL = "net.neoforged.neoforge.client.settings.KeyConflictContext.UNIVERSAL";
    /** Minecraft's bindings that its screens also act on, or that it handles anywhere. */
    private static final Set<String> BEYOND_THE_WORLD = Set.of("key.inventory", "key.drop", "key.chat", "key.pickItem",
            "key.socialInteractions", "key.swapOffhand", "key.advancements", "key.saveToolbarActivator",
            "key.loadToolbarActivator", "key.screenshot", "key.fullscreen", "key.hotbar.1", "key.hotbar.2", "key.hotbar.3",
            "key.hotbar.4", "key.hotbar.5", "key.hotbar.6", "key.hotbar.7", "key.hotbar.8", "key.hotbar.9");

    /** A key and the modifier held with it, such as {@code key.keyboard.g} with {@code CONTROL}. */
    public record Assignment(String key, String modifier) {
        public Assignment {
            Objects.requireNonNull(key, "key");
            modifier = modifier == null || modifier.isBlank() ? "NONE" : modifier;
        }

        /** An assignment as {@code options.txt} writes it, such as {@code key.keyboard.g:CONTROL}. */
        public static Assignment decode(String value) {
            String[] parts = value.split(":", 2);
            return new Assignment(parts[0], parts.length > 1 ? parts[1] : "NONE");
        }

        /** The assignment as {@code options.txt} writes it: the key, with {@code :} and the modifier when there is one. */
        public String encode() {
            return this.modifier.equals("NONE") ? this.key : this.key + ":" + this.modifier;
        }

        public boolean unbound() {
            return this.key.equals(UNBOUND);
        }
    }

    /** How two bindings overlap. */
    public enum Overlap {
        /** Both act on the same key press in contexts that conflict. */
        COLLISION,
        /** One binding's key is the other's modifier, so pressing the combination also presses the bare key. */
        MODIFIER,
        /** The keys are equal, but the contexts never conflict; NeoForge still reports them as the same. */
        SEPARATE_CONTEXTS
    }

    /** A binding as captured, with the key it has now. */
    public record Binding(PackCatalog.KeyBinding spec, Assignment current) {
        /** Whether this is one of Minecraft's bindings that act only in the world, whatever context NeoForge gives it. */
        public boolean worldOnly() {
            return this.spec.modId().equals("minecraft") && !BEYOND_THE_WORLD.contains(this.spec.name());
        }

        /** The context collisions are judged by: in the game for an anywhere binding that acts only in the world. */
        public String effectiveContext() {
            return worldOnly() && this.spec.context().equals(UNIVERSAL) ? IN_GAME : this.spec.context();
        }

        public Assignment defaults() {
            return new Assignment(this.spec.defaultKey(), this.spec.defaultModifier());
        }

        public boolean changed() {
            return !this.current.equals(defaults());
        }

        public String name() {
            return this.spec.displayName().isEmpty() ? this.spec.name() : this.spec.displayName();
        }
    }

    /** Another binding that overlaps with one, and how. */
    public record Clash(Binding other, Overlap overlap) {
    }

    private final List<Binding> bindings;
    private final Map<String, String> names;
    private final Map<String, Set<String>> conflicts = new HashMap<>();
    private final Map<Binding, List<Clash>> clashes = new LinkedHashMap<>();

    /**
     * The captured bindings with the keys {@code current} assigns; a binding it lacks keeps its default. {@code names}
     * are the key names the game shows, which follow the keyboard layout.
     */
    public KeyBindings(List<PackCatalog.KeyBinding> specs, List<PackCatalog.KeyContext> contexts,
                       Map<String, Assignment> current, Map<String, String> names) {
        this.names = Map.copyOf(names);
        List<Binding> bindings = new ArrayList<>();
        for (PackCatalog.KeyBinding spec : specs) {
            bindings.add(new Binding(spec, current.getOrDefault(spec.name(),
                    new Assignment(spec.defaultKey(), spec.defaultModifier()))));
        }
        this.bindings = List.copyOf(bindings);
        for (PackCatalog.KeyContext context : contexts) this.conflicts.put(context.id(), Set.copyOf(context.conflicts()));
        for (Binding binding : this.bindings) this.clashes.put(binding, new ArrayList<>());
        for (int first = 0; first < this.bindings.size(); first++) {
            for (int second = first + 1; second < this.bindings.size(); second++) {
                Binding a = this.bindings.get(first);
                Binding b = this.bindings.get(second);
                Overlap overlap = overlap(a, b);
                if (overlap == null) continue;
                this.clashes.get(a).add(new Clash(b, overlap));
                this.clashes.get(b).add(new Clash(a, overlap));
            }
        }
    }

    public List<Binding> bindings() {
        return this.bindings;
    }

    /** The bindings that overlap with {@code binding}. */
    public List<Clash> clashes(Binding binding) {
        return this.clashes.getOrDefault(binding, List.of());
    }

    /**
     * How two bindings overlap, the way NeoForge's {@code KeyMapping.same} decides it, or null. Unbound bindings never
     * overlap.
     */
    Overlap overlap(Binding a, Binding b) {
        Assignment first = a.current();
        Assignment second = b.current();
        if (first.unbound() || second.unbound()) return null;
        String firstContext = a.effectiveContext();
        String secondContext = b.effectiveContext();
        if (conflicts(firstContext, secondContext) || conflicts(secondContext, firstContext)) {
            if (modifierMatches(first.modifier(), second.key()) || modifierMatches(second.modifier(), first.key())) {
                return Overlap.MODIFIER;
            }
            if (!first.key().equals(second.key())) return null;
            // NeoForge asks this for the binding it compares from; the controls screen compares in both directions.
            boolean inGame = conflicts(firstContext, IN_GAME) || conflicts(secondContext, IN_GAME);
            boolean clash = first.modifier().equals(second.modifier())
                    || inGame && (first.modifier().equals("NONE") || second.modifier().equals("NONE"));
            return clash ? Overlap.COLLISION : null;
        }
        return first.key().equals(second.key()) && first.modifier().equals(second.modifier())
                ? Overlap.SEPARATE_CONTEXTS : null;
    }

    private boolean conflicts(String context, String other) {
        return this.conflicts.getOrDefault(context, Set.of()).contains(other);
    }

    /** Whether holding {@code modifier} means pressing {@code key}, such as CONTROL for the left control key. */
    static boolean modifierMatches(String modifier, String key) {
        return switch (modifier) {
            case "CONTROL" -> key.equals("key.keyboard.left.control") || key.equals("key.keyboard.right.control");
            case "SHIFT" -> key.equals("key.keyboard.left.shift") || key.equals("key.keyboard.right.shift");
            case "ALT" -> key.equals("key.keyboard.left.alt") || key.equals("key.keyboard.right.alt");
            default -> false;
        };
    }

    /**
     * A key's name as the game shows it, such as {@code Y} for {@code key.keyboard.z} on a German layout. A name the
     * game could not decode, such as the replacement character GLFW gives some umlaut keys, falls back to the id.
     */
    public String keyName(String key) {
        String name = this.names.get(key);
        return name == null || name.isBlank() || name.indexOf('\uFFFD') >= 0 ? fallbackName(key) : name;
    }

    /** The keycaps of an assignment, such as {@code Ctrl} and {@code G}; none when it is not bound. */
    public List<String> caps(Assignment assignment) {
        if (assignment.unbound()) return List.of();
        List<String> caps = new ArrayList<>();
        switch (assignment.modifier()) {
            case "CONTROL" -> caps.add("Ctrl");
            case "SHIFT" -> caps.add("Shift");
            case "ALT" -> caps.add("Alt");
            default -> {
            }
        }
        caps.add(keyName(assignment.key()));
        return caps;
    }

    /** How an assignment reads, such as {@code Ctrl + G}, or {@code Not bound}. */
    public String display(Assignment assignment) {
        return assignment.unbound() ? "Not bound" : String.join(" + ", caps(assignment));
    }

    /**
     * Whether {@code query} names exactly the assignment's key, such as {@code ctrl+g}, {@code Ctrl G} or
     * {@code left alt}. Spaces, plus signs and case do not matter.
     */
    public boolean namesKey(Assignment assignment, String query) {
        if (assignment.unbound()) return false;
        String typed = keyText(query);
        return !typed.isEmpty() && typed.equals(keyText(display(assignment)));
    }

    private static String keyText(String text) {
        return text.toLowerCase(Locale.ROOT).replace("control", "ctrl").replaceAll("[\\s+]", "");
    }

    /**
     * A key's name derived from its id, for keys the game did not name: {@code key.keyboard.left.control} is
     * {@code Left Control}, {@code key.mouse.left} {@code Left Button} and {@code key.mouse.4} {@code Mouse Button 4}.
     */
    static String fallbackName(String key) {
        if (key.startsWith("key.mouse.")) {
            String button = key.substring("key.mouse.".length());
            return switch (button) {
                case "left" -> "Left Button";
                case "right" -> "Right Button";
                case "middle" -> "Middle Button";
                default -> "Mouse Button " + button;
            };
        }
        String name = key.startsWith("key.keyboard.") ? key.substring("key.keyboard.".length()) : key;
        StringBuilder readable = new StringBuilder();
        for (String part : name.split("\\.")) {
            if (part.isEmpty()) continue;
            if (!readable.isEmpty()) readable.append(' ');
            readable.append(part.length() == 1 ? part.toUpperCase(Locale.ROOT)
                    : Character.toUpperCase(part.charAt(0)) + part.substring(1));
        }
        return readable.toString();
    }

    /** The keys {@code options.txt} assigns, by binding name. Blocking; empty when the file does not exist yet. */
    public static Map<String, Assignment> readOptions(Path options) throws IOException {
        if (!Files.isRegularFile(options)) return Map.of();
        Map<String, Assignment> assignments = new HashMap<>();
        for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
            if (!line.startsWith("key_")) continue;
            int separator = line.indexOf(':');
            if (separator < 0) continue;
            assignments.put(line.substring("key_".length(), separator), Assignment.decode(line.substring(separator + 1)));
        }
        return assignments;
    }
}
