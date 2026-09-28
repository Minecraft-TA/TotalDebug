package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mixins the mods' files declare, read without the game: the configurations each file names in its
 * {@code neoforge.mods.toml} or its manifest's {@code MixinConfigs}, the classes each configuration lists, and what each
 * class changes, from its {@code @Mixin} targets and the annotations of its methods. A configuration's plugin can still
 * leave a mixin out when the game applies them; this is what the files declare. Blocking.
 */
public final class Mixins {
    private static final int MAXIMUM_CONFIG_BYTES = 1024 * 1024;
    private static final int MAXIMUM_CLASS_BYTES = 4 * 1024 * 1024;
    private static final int DEFAULT_PRIORITY = 1000;
    /** A {@code [[mixins]]} table header, which a comment may follow. */
    private static final Pattern MIXIN_TABLE = Pattern.compile("(?m)^\\s*\\[\\[\\s*mixins\\s*]]\\s*(#.*)?$");
    /** A table's {@code config} key, as a basic or a literal string. */
    private static final Pattern CONFIG = Pattern.compile("(?m)^\\s*config\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)')");
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String DESC = "Lorg/spongepowered/asm/mixin/injection/Desc;";
    /** What a method annotation of Mixin or MixinExtras does to its target, by the annotation's descriptor. */
    private static final Map<String, String> KINDS = Map.ofEntries(
            Map.entry("Lorg/spongepowered/asm/mixin/injection/Inject;", "Inject"),
            Map.entry("Lorg/spongepowered/asm/mixin/injection/Redirect;", "Redirect"),
            Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "ModifyArg"),
            Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", "ModifyArgs"),
            Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", "ModifyVariable"),
            Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;", "ModifyConstant"),
            Map.entry("Lorg/spongepowered/asm/mixin/Overwrite;", "Overwrite"),
            Map.entry("Lorg/spongepowered/asm/mixin/gen/Accessor;", "Accessor"),
            Map.entry("Lorg/spongepowered/asm/mixin/gen/Invoker;", "Invoker"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", "ModifyExpressionValue"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", "ModifyReturnValue"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/ModifyReceiver;", "ModifyReceiver"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;", "WrapWithCondition"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/WrapWithCondition;", "WrapWithCondition"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "WrapOperation"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;", "WrapMethod"));

    /** Which side a mixin applies on, by the list of its configuration that names it. */
    public enum Side {
        BOTH("Client and server"),
        CLIENT("Client"),
        SERVER("Server");

        private final String label;

        Side(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    /**
     * One change a mixin makes: how, such as {@code Inject}, to which member, empty for the class itself, the descriptor
     * that picks one of its overloads, or empty for all of them, and the target class its selector names by binary name,
     * or empty for every target of the mixin.
     */
    public record Change(String kind, String member, String descriptor, String owner) {
        public Change {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(member, "member");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(owner, "owner");
        }

        public Change(String kind, String member) {
            this(kind, member, "", "");
        }

        public Change(String kind, String member, String owner) {
            this(kind, member, "", owner);
        }

        /** Whether the change applies to {@code target}, one of the mixin's targets. */
        public boolean appliesTo(String target) {
            return this.owner.isEmpty() || this.owner.equals(target);
        }
    }

    /**
     * A mixin class of a mod: the mod, its configuration file, its class name, the classes it targets by binary name,
     * its side and priority, and what it changes; a mixin that only adds members or interfaces changes the class itself.
     */
    public record Mixin(String modId, String config, String className, List<String> targets, Side side, int priority,
                        List<Change> changes) {
        public Mixin {
            targets = List.copyOf(targets);
            changes = List.copyOf(changes);
        }
    }

    /** The mixins read, and why a file or class could not be read, each naming it. */
    public record Read(List<Mixin> mixins, List<String> problems) {
        public Read {
            mixins = List.copyOf(mixins);
            problems = List.copyOf(problems);
        }
    }

    private Mixins() {
    }

    /** Every mixin of the mods {@code index} knows, file by file; a file or class that cannot be read is named. */
    public static Read read(CatalogIndex index) {
        List<Mixin> mixins = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<URI, List<String>> byFile = new LinkedHashMap<>();
        for (PackCatalog.Mod mod : index.mods()) byFile.computeIfAbsent(mod.file(), ignored -> new ArrayList<>()).add(mod.id());
        byFile.forEach((file, mods) -> {
            try {
                Read read = read(file, mods);
                mixins.addAll(read.mixins());
                problems.addAll(read.problems());
            } catch (IOException | RuntimeException unreadable) {
                problems.add(name(file) + ": " + message(unreadable));
            }
        });
        return new Read(mixins, problems);
    }

    /** The mixins of one mod file, credited to the mod whose id the configuration's name starts with, or its first mod. */
    static Read read(URI file, List<String> mods) throws IOException {
        Optional<ModFiles.Archive> opened = ModFiles.open(file);
        if (opened.isEmpty()) throw new IOException("the file is gone");
        List<Mixin> mixins = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try (ModFiles.Archive archive = opened.get()) {
            for (String config : configs(archive)) {
                Optional<byte[]> json = archive.read(config, MAXIMUM_CONFIG_BYTES);
                if (json.isEmpty()) {
                    problems.add(name(file) + ": " + config + " is missing");
                    continue;
                }
                JsonObject root;
                try {
                    if (!(JsonParser.parseString(new String(json.get(), StandardCharsets.UTF_8)) instanceof JsonObject object)) {
                        throw new IllegalArgumentException("not a JSON object");
                    }
                    root = object;
                } catch (RuntimeException malformed) {
                    problems.add(name(file) + ": " + config + " could not be read: " + message(malformed));
                    continue;
                }
                String owner = owner(config, mods);
                String pkg = root.get("package") instanceof JsonElement element && element.isJsonPrimitive() ? element.getAsString() : "";
                // A class without its own priority takes its configuration's.
                int priority = root.get("mixinPriority") instanceof JsonElement element && element.isJsonPrimitive()
                        && element.getAsJsonPrimitive().isNumber() ? element.getAsInt() : DEFAULT_PRIORITY;
                for (Side side : Side.values()) {
                    String list = switch (side) {
                        case BOTH -> "mixins";
                        case CLIENT -> "client";
                        case SERVER -> "server";
                    };
                    if (!(root.get(list) instanceof JsonArray names)) continue;
                    for (JsonElement name : names) {
                        if (!name.isJsonPrimitive()) continue;
                        String className = pkg.isEmpty() ? name.getAsString() : pkg + "." + name.getAsString();
                        Optional<byte[]> bytes = archive.read(className.replace('.', '/') + ".class", MAXIMUM_CLASS_BYTES);
                        // A listed class the file does not hold is one the configuration names for another version.
                        if (bytes.isEmpty()) continue;
                        try {
                            mixin(owner, config, className, side, priority, bytes.get()).ifPresent(mixins::add);
                        } catch (RuntimeException malformed) {
                            problems.add(name(file) + ": " + className + " could not be read: " + message(malformed));
                        }
                    }
                }
            }
        }
        return new Read(mixins, problems);
    }

    /** The mixin configurations a file names, in its {@code neoforge.mods.toml} and its manifest. */
    private static Set<String> configs(ModFiles.Archive archive) throws IOException {
        Set<String> configs = new LinkedHashSet<>();
        Optional<byte[]> toml = archive.read("META-INF/neoforge.mods.toml", MAXIMUM_CONFIG_BYTES);
        if (toml.isPresent()) {
            String text = new String(toml.get(), StandardCharsets.UTF_8);
            Matcher table = MIXIN_TABLE.matcher(text);
            while (table.find()) {
                // The table's keys run to the next table.
                int end = text.indexOf("\n[", table.end());
                Matcher config = CONFIG.matcher(text.substring(table.end(), end < 0 ? text.length() : end));
                if (config.find()) configs.add(config.group(1) != null ? config.group(1) : config.group(2));
            }
        }
        Optional<byte[]> manifest = archive.read("META-INF/MANIFEST.MF", MAXIMUM_CONFIG_BYTES);
        if (manifest.isPresent()) {
            String listed = new Manifest(new ByteArrayInputStream(manifest.get())).getMainAttributes().getValue("MixinConfigs");
            if (listed != null) {
                for (String config : listed.split(",")) {
                    if (!config.isBlank()) configs.add(config.strip());
                }
            }
        }
        return configs;
    }

    private static String owner(String config, List<String> mods) {
        String lower = config.toLowerCase(Locale.ROOT);
        for (String mod : mods) {
            if (lower.startsWith(mod + ".") || lower.startsWith(mod + "-") || lower.startsWith(mod + "_")) return mod;
        }
        return mods.getFirst();
    }

    /** The mixin a class declares, or empty for a class without {@code @Mixin}; {@code priority} is its configuration's. */
    static Optional<Mixin> mixin(String modId, String config, String className, Side side, int priority, byte[] bytes) {
        List<String> targets = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        int[] effective = {priority};
        boolean[] annotated = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN.equals(descriptor)) return null;
                annotated[0] = true;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("priority".equals(name) && value instanceof Integer number) effective[0] = number;
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                if ("value".equals(name) && value instanceof Type type) targets.add(type.getClassName());
                                if ("targets".equals(name) && value instanceof String target) targets.add(target.replace('/', '.'));
                            }
                        };
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        String kind = KINDS.get(annotation);
                        if (kind == null) return null;
                        return new SelectorVisitor(kind, methodName, changes);
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        if (!annotated[0] || targets.isEmpty()) return Optional.empty();
        if (changes.isEmpty()) changes.add(new Change("Adds", ""));
        return Optional.of(new Mixin(modId, config, className, targets, side, effective[0], changes.stream().distinct().toList()));
    }

    /**
     * Reads the members an injector, overwrite, accessor or invoker names: its {@code method} or {@code value} selectors,
     * and its {@code target} descriptors, each with the owner it names, if any.
     */
    private static final class SelectorVisitor extends AnnotationVisitor {
        private final String kind;
        private final String methodName;
        private final List<Change> changes;
        private final List<Change> named = new ArrayList<>();

        SelectorVisitor(String kind, String methodName, List<Change> changes) {
            super(Opcodes.ASM9);
            this.kind = kind;
            this.methodName = methodName;
            this.changes = changes;
        }

        @Override
        public void visit(String name, Object value) {
            if (("value".equals(name) || "method".equals(name)) && value instanceof String text) this.named.add(selected(text));
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if ("target".equals(name)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
                        return DESC.equals(descriptor) ? described() : null;
                    }
                };
            }
            if (!"method".equals(name) && !"value".equals(name)) return null;
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String ignored, Object value) {
                    if (value instanceof String text) SelectorVisitor.this.named.add(selected(text));
                }
            };
        }

        /** A {@code @Desc}: the member's name as its value, and its owner when it names one. */
        private AnnotationVisitor described() {
            String[] member = {""};
            String[] owner = {""};
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if ("value".equals(name) && value instanceof String text) member[0] = text;
                    if ("owner".equals(name) && value instanceof Type type) owner[0] = type.getClassName();
                }

                @Override
                public void visitEnd() {
                    if (!member[0].isEmpty()) SelectorVisitor.this.named.add(new Change("", member[0], "", owner[0]));
                }
            };
        }

        private Change selected(String selector) {
            return new Change("", member(selector), descriptor(selector), owner(selector));
        }

        @Override
        public void visitEnd() {
            switch (this.kind) {
                case "Overwrite" -> this.changes.add(new Change(this.kind, this.methodName));
                case "Accessor", "Invoker" -> this.changes.add(this.named.isEmpty() ? new Change(this.kind, accessed(this.kind, this.methodName))
                        : new Change(this.kind, this.named.getFirst().member(), this.named.getFirst().descriptor(), this.named.getFirst().owner()));
                default -> {
                    for (Change target : this.named) {
                        this.changes.add(new Change(this.kind, target.member(), target.descriptor(), target.owner()));
                    }
                }
            }
        }
    }

    /**
     * The member a target selector names, without its owner or descriptor: {@code tick}, {@code tick()V} and
     * {@code Lnet/minecraft/world/level/Level;tick()V} are all {@code tick}. A wildcard such as {@code *} or
     * {@code get*} stays as written, since it names several members.
     */
    static String member(String selector) {
        String member = selector.strip();
        int owner = member.indexOf(';');
        if (member.startsWith("L") && owner > 0) member = member.substring(owner + 1);
        int descriptor = member.indexOf('(');
        if (descriptor >= 0) member = member.substring(0, descriptor);
        int colon = member.indexOf(':');
        if (colon >= 0) member = member.substring(0, colon);
        return member.strip();
    }

    /** The descriptor a selector such as {@code tick(I)V} gives, which picks one overload, or empty for all of them. */
    static String descriptor(String selector) {
        String text = selector.strip();
        int start = text.indexOf('(');
        return start < 0 ? "" : text.substring(start);
    }

    /** The class a selector such as {@code Lnet/minecraft/world/level/Level;tick()V} names by binary name, or empty. */
    static String owner(String selector) {
        String text = selector.strip();
        int end = text.indexOf(';');
        return text.startsWith("L") && end > 0 ? text.substring(1, end).replace('/', '.') : "";
    }

    /**
     * The member an accessor or invoker without a name reaches: {@code getSpeed} reaches {@code speed}, and an invoker
     * named {@code newWidget} or {@code createWidget} makes an object, so it reaches the constructor.
     */
    private static String accessed(String kind, String methodName) {
        if (kind.equals("Invoker")) {
            for (String factory : new String[]{"new", "create"}) {
                if (methodName.startsWith(factory) && methodName.length() > factory.length()
                        && Character.isUpperCase(methodName.charAt(factory.length()))) return "<init>";
            }
        }
        String[] prefixes = kind.equals("Accessor") ? new String[]{"get", "set", "is"} : new String[]{"call", "invoke"};
        for (String prefix : prefixes) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                String rest = methodName.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return methodName;
    }

    /** A file's name for a problem: its file name, or the nested file's for a file inside another. */
    private static String name(URI file) {
        String text = file.toString();
        return text.substring(text.lastIndexOf('/') + 1).replaceFirst("%23\\d+$", "");
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
