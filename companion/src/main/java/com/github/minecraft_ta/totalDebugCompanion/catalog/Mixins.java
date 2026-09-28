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
    private static final Pattern MIXIN_TABLE = Pattern.compile("(?m)^\\s*\\[\\[\\s*mixins\\s*]]\\s*$");
    private static final Pattern CONFIG = Pattern.compile("(?m)^\\s*config\\s*=\\s*\"([^\"]+)\"");
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
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

    /** One change a mixin makes: how, such as {@code Inject}, and to which member of its targets, or empty for the class. */
    public record Change(String kind, String member) {
        public Change {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(member, "member");
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

    private Mixins() {
    }

    /** Every mixin of the mods {@code index} knows, file by file; a file that cannot be read adds nothing. */
    public static List<Mixin> read(CatalogIndex index) {
        List<Mixin> mixins = new ArrayList<>();
        Map<URI, List<String>> byFile = new LinkedHashMap<>();
        for (PackCatalog.Mod mod : index.mods()) byFile.computeIfAbsent(mod.file(), ignored -> new ArrayList<>()).add(mod.id());
        byFile.forEach((file, mods) -> {
            try {
                mixins.addAll(read(file, mods));
            } catch (IOException | RuntimeException unreadable) {
                // A broken or removed file adds nothing; the others still count.
            }
        });
        return mixins;
    }

    /** The mixins of one mod file, credited to the mod whose id the configuration's name starts with, or its first mod. */
    static List<Mixin> read(URI file, List<String> mods) throws IOException {
        Optional<ModFiles.Archive> opened = ModFiles.open(file);
        if (opened.isEmpty()) return List.of();
        try (ModFiles.Archive archive = opened.get()) {
            List<Mixin> mixins = new ArrayList<>();
            for (String config : configs(archive)) {
                Optional<byte[]> json = archive.read(config, MAXIMUM_CONFIG_BYTES);
                if (json.isEmpty()) continue;
                JsonObject root;
                try {
                    if (!(JsonParser.parseString(new String(json.get(), StandardCharsets.UTF_8)) instanceof JsonObject object)) continue;
                    root = object;
                } catch (RuntimeException malformed) {
                    continue;
                }
                String owner = owner(config, mods);
                String pkg = root.get("package") instanceof JsonElement element && element.isJsonPrimitive() ? element.getAsString() : "";
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
                        if (bytes.isEmpty()) continue;
                        mixin(owner, config, className, side, bytes.get()).ifPresent(mixins::add);
                    }
                }
            }
            return mixins;
        }
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
                if (config.find()) configs.add(config.group(1));
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

    /** The mixin a class declares, or empty for a class without {@code @Mixin}. */
    static Optional<Mixin> mixin(String modId, String config, String className, Side side, byte[] bytes) {
        List<String> targets = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        int[] priority = {1000};
        boolean[] annotated = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN.equals(descriptor)) return null;
                annotated[0] = true;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("priority".equals(name) && value instanceof Integer number) priority[0] = number;
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
                        List<String> named = new ArrayList<>();
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String name, Object value) {
                                if (("value".equals(name) || "method".equals(name)) && value instanceof String text) named.add(text);
                            }

                            @Override
                            public AnnotationVisitor visitArray(String name) {
                                if (!"method".equals(name) && !"value".equals(name)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        if (value instanceof String text) named.add(text);
                                    }
                                };
                            }

                            @Override
                            public void visitEnd() {
                                switch (kind) {
                                    case "Overwrite" -> changes.add(new Change(kind, methodName));
                                    case "Accessor", "Invoker" -> changes.add(new Change(kind,
                                            named.isEmpty() ? accessed(kind, methodName) : member(named.getFirst())));
                                    default -> {
                                        for (String target : named) changes.add(new Change(kind, member(target)));
                                    }
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        if (!annotated[0] || targets.isEmpty()) return Optional.empty();
        if (changes.isEmpty()) changes.add(new Change("Adds", ""));
        return Optional.of(new Mixin(modId, config, className, targets, side, priority[0], changes.stream().distinct().toList()));
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

    /** The member an accessor or invoker without a name reaches: {@code getSpeed} reaches {@code speed}. */
    private static String accessed(String kind, String methodName) {
        String[] prefixes = kind.equals("Accessor") ? new String[]{"get", "set", "is"} : new String[]{"call", "invoke", "new", "create"};
        for (String prefix : prefixes) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                String rest = methodName.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return methodName;
    }
}
