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
    private static final Pattern CONFIG = Pattern.compile("(?m)^\\s*config\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)+)\"|'([^']+)')");
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
     * One change a mixin makes: how, such as {@code Inject}, what it selects, the target class its selector names by
     * binary name, or empty for every target of the mixin, and whether its handler method is static.
     */
    public record Change(String kind, MixinSelector selector, String owner, boolean staticHandler) {
        public Change {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(owner, "owner");
        }

        public Change(String kind, MixinSelector selector, String owner) {
            this(kind, selector, owner, false);
        }

        public Change(String kind, MixinSelector selector) {
            this(kind, selector, "");
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
                Optional<byte[]> json;
                try {
                    json = archive.read(config, MAXIMUM_CONFIG_BYTES);
                } catch (IOException unreadable) {
                    // One entry that cannot be read leaves the file's others readable.
                    problems.add(name(file) + ": " + message(unreadable));
                    continue;
                }
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
                        try {
                            Optional<byte[]> bytes = archive.read(className.replace('.', '/') + ".class", MAXIMUM_CLASS_BYTES);
                            // A listed class the file does not hold is one the configuration names for another version.
                            if (bytes.isEmpty()) continue;
                            mixin(owner, config, className, side, priority, bytes.get()).ifPresent(mixins::add);
                        } catch (IOException | RuntimeException malformed) {
                            problems.add(name(file) + ": " + className + " could not be read: " + message(malformed));
                        }
                    }
                }
            }
        }
        return new Read(mixins, problems);
    }

    /**
     * Whether any mod file of {@code index} names a mixin configuration, reading only each file's
     * {@code neoforge.mods.toml} and manifest; a file that cannot be read may, so it counts. Blocking.
     */
    public static boolean declared(CatalogIndex index) {
        Set<URI> files = new LinkedHashSet<>();
        for (PackCatalog.Mod mod : index.mods()) files.add(mod.file());
        for (URI file : files) {
            try {
                Optional<ModFiles.Archive> opened = ModFiles.open(file);
                // A file that is gone may have named some; the page names it.
                if (opened.isEmpty()) return true;
                try (ModFiles.Archive archive = opened.get()) {
                    if (!configs(archive).isEmpty()) return true;
                }
            } catch (IOException | RuntimeException unreadable) {
                // Whether it declares mixins is unknown: the page names why it could not be read.
                return true;
            }
        }
        return false;
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
                if (config.find()) configs.add(config.group(1) != null ? basicString(config.group(1)) : config.group(2));
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

    /** The text of a TOML basic string's contents, with its escapes such as {@code \\u002E} decoded. */
    static String basicString(String escaped) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < escaped.length(); index++) {
            char character = escaped.charAt(index);
            if (character != '\\' || index + 1 >= escaped.length()) {
                text.append(character);
                continue;
            }
            char code = escaped.charAt(++index);
            switch (code) {
                case 'b' -> text.append('\b');
                case 't' -> text.append('\t');
                case 'n' -> text.append('\n');
                case 'f' -> text.append('\f');
                case 'r' -> text.append('\r');
                case 'u', 'U' -> {
                    int digits = code == 'u' ? 4 : 8;
                    // A short or malformed escape, which TOML refuses, stays as written.
                    if (index + digits >= escaped.length() || !escaped.substring(index + 1, index + 1 + digits).matches("[0-9A-Fa-f]+")) {
                        text.append('\\').append(code);
                        continue;
                    }
                    text.appendCodePoint(Integer.parseInt(escaped.substring(index + 1, index + 1 + digits), 16));
                    index += digits;
                }
                default -> text.append(code);
            }
        }
        return text.toString();
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
                        return new SelectorVisitor(kind, methodName, descriptor, (access & Opcodes.ACC_STATIC) != 0, changes);
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        if (!annotated[0] || targets.isEmpty()) return Optional.empty();
        if (changes.isEmpty()) changes.add(new Change("Adds", new MixinSelector.Whole()));
        return Optional.of(new Mixin(modId, config, className, targets, side, effective[0], changes.stream().distinct().toList()));
    }

    /**
     * Reads the members an injector, overwrite, accessor or invoker names: its {@code method} or {@code value} selectors,
     * its {@code target} descriptors, each with the owner it names, if any, and an overwrite's {@code aliases}.
     */
    private static final class SelectorVisitor extends AnnotationVisitor {
        private final String kind;
        private final String methodName;
        /** The annotated method's own descriptor, which an overwrite shares with the member it replaces. */
        private final String methodDescriptor;
        private final boolean staticHandler;
        private final List<Change> changes;
        private final List<Selected> named = new ArrayList<>();
        /** Other names an overwrite replaces its method by, where the target has one of those instead. */
        private final List<String> aliases = new ArrayList<>();

        SelectorVisitor(String kind, String methodName, String methodDescriptor, boolean staticHandler, List<Change> changes) {
            super(Opcodes.ASM9);
            this.kind = kind;
            this.methodName = methodName;
            this.methodDescriptor = methodDescriptor;
            this.staticHandler = staticHandler;
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
            if ("aliases".equals(name)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String ignored, Object value) {
                        if (value instanceof String alias) SelectorVisitor.this.aliases.add(alias);
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

        /**
         * A {@code @Desc}: the member's name as its value, the descriptor its {@code args} and {@code ret} make, and its
         * owner when it names one.
         */
        private AnnotationVisitor described() {
            String[] member = {""};
            String[] owner = {""};
            List<Type> arguments = new ArrayList<>();
            Type[] returned = {Type.VOID_TYPE};
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if ("value".equals(name) && value instanceof String text) member[0] = text;
                    if ("owner".equals(name) && value instanceof Type type) owner[0] = type.getClassName();
                    if ("ret".equals(name) && value instanceof Type type) returned[0] = type;
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    if (!"args".equals(name)) return null;
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String ignored, Object value) {
                            if (value instanceof Type type) arguments.add(type);
                        }
                    };
                }

                @Override
                public void visitEnd() {
                    if (member[0].isEmpty()) return;
                    String descriptor = Type.getMethodDescriptor(returned[0], arguments.toArray(Type[]::new));
                    SelectorVisitor.this.named.add(new Selected(new MixinSelector.Method(member[0], descriptor), owner[0]));
                }
            };
        }

        private Selected selected(String selector) {
            return selector(selector);
        }

        @Override
        public void visitEnd() {
            switch (this.kind) {
                // An overwrite replaces the method of its own name and signature, or else of its first alias the target has.
                case "Overwrite" -> {
                    List<MixinSelector> choices = new ArrayList<>();
                    choices.add(new MixinSelector.Method(this.methodName, this.methodDescriptor));
                    for (String alias : this.aliases) choices.add(new MixinSelector.Method(alias, this.methodDescriptor));
                    this.changes.add(new Change(this.kind, choices.size() == 1 ? choices.getFirst() : new MixinSelector.First(choices)));
                }
                // An accessor reaches a field; an invoker the method of its own signature, or a constructor.
                case "Accessor" -> this.changes.add(new Change(this.kind, new MixinSelector.Field(accessedName()), namedOwner()));
                case "Invoker" -> {
                    String name = accessedName();
                    this.changes.add(new Change(this.kind, new MixinSelector.Method(name, invoked(name, this.methodDescriptor)), namedOwner()));
                }
                default -> {
                    for (Selected target : this.named) {
                        this.changes.add(new Change(this.kind, target.selector(), target.owner(), this.staticHandler));
                    }
                }
            }
        }

        /** The member an accessor or invoker names in its annotation, or else by its own name. */
        private String accessedName() {
            return !this.named.isEmpty() && this.named.getFirst().selector() instanceof MixinSelector.Method method && !method.name().isEmpty()
                    ? method.name() : accessed(this.kind, this.methodName);
        }

        /** The owner the annotation's own name gives, or empty. */
        private String namedOwner() {
            return this.named.isEmpty() ? "" : this.named.getFirst().owner();
        }
    }

    /** What a selector names, and the owner it names, or empty for any target. */
    record Selected(MixinSelector selector, String owner) {
    }

    /** A quantifier such as {@code {2}}, {@code {1,}}, {@code {,3}} or {@code {1,3}}. */
    private static final Pattern QUANTIFIER = Pattern.compile("\\{\\s*(\\d*)\\s*(,?)\\s*(\\d*)\\s*}");

    /** Mixin's regular-expression selector: {@code /pattern/}, or parts such as {@code name=/pattern/}. */
    private static final Pattern MATCHER = Pattern.compile("((owner|name|desc)\\s*=\\s*)?/(.*?)(?<!\\\\)/");

    /**
     * What a target selector names, read as Mixin's {@code MemberInfo.parse} reads it: whitespace dropped, an owner
     * before the last dot or as {@code Lowner;}, the descriptor from {@code (} or after {@code :}, and a quantifier at the
     * end of the name ({@code *}, {@code +} or {@code {1,3}}), which limits how many methods it selects and is not part
     * of the name; without one it selects the first match. An empty name, as {@code *} leaves, names every method. A
     * selector ending in a slash is a set of patterns, and one starting with {@code @} is dynamic.
     */
    static Selected selector(String selector) {
        String trimmed = selector.strip();
        // As Mixin's TargetSelector: only a regular-expression selector ends with a slash, and a dynamic one starts with @,
        // whose member only the game resolves, so it is named as written.
        if (trimmed.endsWith("/")) {
            String[] parts = {"", "", ""};
            Matcher patterns = MATCHER.matcher(trimmed);
            while (patterns.find()) {
                String part = patterns.group(2);
                parts["owner".equals(part) ? 0 : "desc".equals(part) ? 2 : 1] = patterns.group(3);
            }
            return new Selected(new MixinSelector.Matching(parts[0], parts[1], parts[2]), "");
        }
        if (trimmed.startsWith("@")) return new Selected(new MixinSelector.Dynamic(trimmed), "");
        String text = trimmed.replaceAll("\\s", "");
        int arrow = text.indexOf("->");
        if (arrow >= 0) text = text.substring(0, arrow);
        String owner = "";
        String name = text;
        int dot = name.lastIndexOf('.');
        int semicolon = name.indexOf(';');
        if (dot >= 0) {
            owner = name.substring(0, dot);
            name = name.substring(dot + 1);
        } else if (semicolon >= 0 && name.startsWith("L")) {
            owner = name.substring(1, semicolon).replace('/', '.');
            name = name.substring(semicolon + 1);
        }
        String descriptor = "";
        int paren = name.indexOf('(');
        int colon = name.indexOf(':');
        if (paren >= 0) {
            descriptor = name.substring(paren);
            name = name.substring(0, paren);
        } else if (colon >= 0) {
            name = name.substring(0, colon);
        }
        if ((name.indexOf('/') >= 0 || name.indexOf('.') >= 0) && owner.isEmpty()) {
            owner = name.replace('/', '.');
            name = "";
        }
        int limit = 1;
        if (name.endsWith("*") || name.endsWith("+")) {
            name = name.substring(0, name.length() - 1);
            limit = Integer.MAX_VALUE;
        } else if (name.indexOf('{') >= 0) {
            // A malformed quantifier selects nothing, as Mixin refuses the selector.
            Matcher quantifier = QUANTIFIER.matcher(name.substring(name.indexOf('{')));
            limit = quantifier.matches() ? maximum(quantifier) : 0;
            name = name.substring(0, name.indexOf('{'));
        }
        return new Selected(new MixinSelector.Method(name, descriptor, limit), owner);
    }

    /** The most matches a quantifier allows: its upper bound, or its one count, or any number without an upper bound. */
    private static int maximum(Matcher quantifier) {
        String upper = quantifier.group(2).isEmpty() ? quantifier.group(1) : quantifier.group(3);
        if (upper.isEmpty()) return quantifier.group(2).isEmpty() ? 0 : Integer.MAX_VALUE;
        try {
            return Integer.parseInt(upper);
        } catch (NumberFormatException tooLarge) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * The descriptor of the method an invoker with the descriptor {@code method} calls: its own, or for a constructor,
     * which returns nothing, the one taking its arguments.
     */
    static String invoked(String name, String method) {
        return name.equals("<init>") ? Type.getMethodDescriptor(Type.VOID_TYPE, Type.getArgumentTypes(method)) : method;
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
