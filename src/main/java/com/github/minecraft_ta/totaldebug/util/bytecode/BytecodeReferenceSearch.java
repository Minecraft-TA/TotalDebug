package com.github.minecraft_ta.totaldebug.util.bytecode;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.util.ForkJoinUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.*;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BytecodeReferenceSearch {

    private final InsnMatcher matcher;

    private Consumer<Pair<Collection<ReferenceLocation>, Integer>> resultHandler;
    private Consumer<Throwable> throwableHandler;
    private BiConsumer<Integer, Integer> progressHandler;
    private Runnable cancellationHandler;
    private boolean running;
    private boolean cancelled;

    private BytecodeReferenceSearch(InsnMatcher matcher) {
        this.matcher = matcher;
    }

    public BytecodeReferenceSearch withResultHandler(Consumer<Pair<Collection<ReferenceLocation>, Integer>> resultHandler) {
        this.resultHandler = resultHandler;
        return this;
    }

    public BytecodeReferenceSearch withProgressHandler(BiConsumer<Integer, Integer> progressHandler) {
        this.progressHandler = progressHandler;
        return this;
    }

    public BytecodeReferenceSearch withCancellationHandler(Runnable cancellationHandler) {
        this.cancellationHandler = cancellationHandler;
        return this;
    }

    public BytecodeReferenceSearch withThrowableHandler(Consumer<Throwable> throwableHandler) {
        this.throwableHandler = throwableHandler;
        return this;
    }

    public void cancelIfRunning() {
        this.cancelled = true;
    }

    public BytecodeReferenceSearch run() {
        if (this.running)
            throw new IllegalStateException("Already running");

        CompletableFuture.supplyAsync(() -> {
            try {
                List<Class<?>> allClasses = getFilteredClassesList();

                if (allClasses.isEmpty()) {
                    this.resultHandler.accept(Pair.of(Collections.emptyList(), 0));
                    return null;
                }

                long progressUpdateSize = allClasses.size() / 5;
                AtomicInteger progress = new AtomicInteger(0);

                this.progressHandler.accept(0, allClasses.size());

                Set<ReferenceLocation> results = new HashSet<>(ForkJoinUtils.parallelMap(allClasses, (input) -> {
                    List<ReferenceLocation> subResults = new ArrayList<>();
                    ReferenceVisitor context = new ReferenceVisitor(subResults, matcher);

                    for (int i = 0, uncommitedProgress = 0, inputSize = input.size(); i < inputSize && !this.cancelled; i++, uncommitedProgress++) {
                        Class<?> clazz = input.get(i);
                        byte[] bytes = ClassUtil.getBytecodeFromLaunchClassLoader(clazz.getName(), true, true);
                        if (bytes == null)
                            continue;
                        new ClassReader(bytes).accept(context, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);

                        if ((i & 0x80) == 0x80) {
                            int p = progress.getAndAdd(uncommitedProgress);
                            if ((p + uncommitedProgress) / progressUpdateSize != p / progressUpdateSize && this.progressHandler != null) {
                                //noinspection SynchronizeOnNonFinalField
                                synchronized (this.progressHandler) {
                                    this.progressHandler.accept(p, allClasses.size());
                                }
                            }

                            uncommitedProgress = 0;
                        }
                    }

                    return subResults;
                }));

                if (this.cancelled) {
                    this.cancellationHandler.run();
                } else {
                    this.progressHandler.accept(allClasses.size(), allClasses.size());
                    this.resultHandler.accept(Pair.of(results, allClasses.size()));
                }
            } catch (Throwable e) {
                this.throwableHandler.accept(e);
            } finally {
                this.running = false;
            }
            return null;
        });
        return this;
    }

    public static BytecodeReferenceSearch forMethod(@Nullable String owner, String name, String desc) {
        return new BytecodeReferenceSearch(new InsnMatcher() {
            @Override
            public boolean matchMethodInsn(String iname, String idesc) {
                return iname.endsWith(name) && idesc.equals(desc);
            }

            @Override
            public boolean matchMemberOwner(String iowner) {
                return owner == null || iowner.endsWith(owner);
            }
        });
    }

    public static BytecodeReferenceSearch forField(@Nullable String owner, String name) {
        return new BytecodeReferenceSearch(new InsnMatcher() {
            @Override
            public boolean matchFieldInsn(String iname, String idesc) {
                return iname.endsWith(name);
            }

            @Override
            public boolean matchMemberOwner(String iowner) {
                return owner == null || iowner.endsWith(owner);
            }
        });
    }

    public static BytecodeReferenceSearch forClass(String clazz) {
        return new BytecodeReferenceSearch(new InsnMatcher() {
            @Override
            public boolean matchMethodInsn(String iname, String idesc) {
                return true;
            }

            @Override
            public boolean matchFieldInsn(String name, String desc) {
                return true;
            }

            @Override
            public boolean matchMemberOwner(String iowner) {
                return iowner.endsWith(clazz);
            }

            @Override
            public boolean matchLdcTypeInsn(String type) {
                return type.endsWith(clazz);
            }
        });
    }

    private static List<Class<?>> getFilteredClassesList() {
        List<String> packageBlacklist = Arrays.asList(
                "com.google", "com.typesafe", "org.apache", "org.scala-lang", "org.jline",
                "org.ow2", "org.objectweb", "net.sf", "net.minecraft", "net.minecraftforge", "cpw.mods",
                "javax.vecmath", "lzma", "org.stringtemplate", "nonapi.io.github.classgraph", "com.mojang",
                "paulscode", "io.netty", "com.ibm", "it.unimi", "net.java", "org.lwjgl", "org.codehaus",
                "org.glassfish", "org.abego", "com.github.minecraft_ta.totaldebug", "LZMA", "akka", "com.intellij",
                "baubles", "com.jcraft", "com.strobel", "com.sun", "com.oracle", "gnu.trove", "ibxm",
                "io.github.classgraph", "javafx", "jdk", "javax", "sun", "org.antlr", "joptsimple",
                "netscape", "org.jetbrains", "oshi", "scala", "org.relaxng", "org.groovy");

        try {
            return ClassUtil.getCachedClassesFromLaunchClassLoader()
                    .values().stream()
                    .filter(c -> packageBlacklist.stream().noneMatch(s -> c.getName().startsWith(s)))
                    .collect(Collectors.toList());
        } catch (Throwable t) {
            TotalDebug.LOGGER.error("Error while trying to get the class list", t);
            return Collections.emptyList();
        }
    }

    public interface InsnMatcher {

        default boolean matchMemberOwner(String owner) {
            return false;
        }

        default boolean matchMethodInsn(String name, String desc) {
            return false;
        }

        default boolean matchFieldInsn(String name, String desc) {
            return false;
        }

        default boolean matchLdcTypeInsn(String type) {
            return false;
        }
    }

    public static final class ReferenceLocation {

        private final String owner;
        private final String enclosingMethod;

        public ReferenceLocation(String owner, String enclosingMethod) {
            this.owner = owner;
            this.enclosingMethod = enclosingMethod;
        }

        public String getOwner() {
            return owner;
        }

        public String getEnclosingMethod() {
            return enclosingMethod;
        }

        @Override
        public String toString() {
            return this.owner + "#" + this.enclosingMethod;
        }
    }

    private static final class ReferenceVisitor extends ClassVisitor {

        private final List<ReferenceLocation> results;
        private final InsnMatcher matcher;

        private String currentClassName;
        private String enclosingMethodName;

        private final MethodVisitor methodVisitor = new MethodVisitor(Opcodes.ASM9, null) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                if (!matcher.matchMethodInsn(name, desc))
                    return;

                boolean valid;
                if (matcher.matchMemberOwner(owner)) {
                    valid = true;
                } else { //Try finding actual owner of method
                    String actualOwner = findOwnerOf(owner, name, desc, true);
                    valid = actualOwner != null && matcher.matchMemberOwner(actualOwner);
                }

                if (valid)
                    ReferenceVisitor.this.results.add(new ReferenceLocation(currentClassName, enclosingMethodName));
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                if (!matcher.matchFieldInsn(name, desc))
                    return;

                boolean valid;
                if (matcher.matchMemberOwner(owner)) {
                    valid = true;
                } else { //Try finding actual owner of method
                    String actualOwner = findOwnerOf(owner, name, desc, false);
                    valid = actualOwner != null && matcher.matchMemberOwner(actualOwner);
                }

                if (valid)
                    ReferenceVisitor.this.results.add(new ReferenceLocation(currentClassName, enclosingMethodName));
            }

            @Override
            public void visitLdcInsn(Object value) {
                if (!(value instanceof Type) || ((Type) value).getSort() != Type.OBJECT)
                    return;

                String name = ((Type) value).getInternalName();
                if (matcher.matchLdcTypeInsn(name))
                    ReferenceVisitor.this.results.add(new ReferenceLocation(currentClassName, enclosingMethodName));
            }
        };

        public ReferenceVisitor(List<ReferenceLocation> results, InsnMatcher matcher) {
            super(Opcodes.ASM9, null);
            this.results = results;
            this.matcher = matcher;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.currentClassName = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String enclosingMethodName, String desc, String signature, String[] exceptions) {
            this.enclosingMethodName = enclosingMethodName;
            return methodVisitor;
        }

        private ObjectList<Class<?>> tempInterfacesList = new ObjectArrayList<>();
        private String findOwnerOf(String currentOwner, String name, String desc, boolean method) {
            try {
                Class<?> clazz = Class.forName(currentOwner.replace('/', '.'));
                if (method) {
                    tempInterfacesList.clear();
                    tempInterfacesList.addElements(0, clazz.getInterfaces());
                }

                Function<Class<?>, String> checkClass = (c) -> {
                    if (method) {
                        for (Method declaredMethod : c.getDeclaredMethods()) {
                            if (declaredMethod.getName().endsWith(name) && getMethodDesc(declaredMethod).equals(desc)) {
                                return c.getName().replace('.', '/');
                            }
                        }
                    } else {
                        for (Field field : c.getFields()) {
                            if (field.getName().endsWith(name)) {
                                return c.getName().replace('.', '/');
                            }
                        }
                    }

                    return null;
                };

                while ((clazz = clazz.getSuperclass()) != null) {
                    String result = checkClass.apply(clazz);
                    if (result != null)
                        return result;
                    if (method)
                        tempInterfacesList.addElements(tempInterfacesList.size(), clazz.getInterfaces());
                }

                for (Class<?> i : tempInterfacesList) {
                    String result = checkClass.apply(i);
                    if (result != null)
                        return result;
                }

                return null;
            } catch (Exception e) {
                return null;
            }
        }

        private final StringBuilder tempDescBuilder = new StringBuilder(40);
        private String getMethodDesc(Method declaredMethod) {
            tempDescBuilder.setLength(0);
            tempDescBuilder.append('(');
            for (Class<?> parameterType : declaredMethod.getParameterTypes())
                appendDescType(tempDescBuilder, parameterType.getName());

            tempDescBuilder.append(")");
            appendDescType(tempDescBuilder, declaredMethod.getReturnType().getName());
            return tempDescBuilder.toString().replace('.', '/');
        }

        private void appendDescType(StringBuilder builder, String type) {
            switch (type) {
                case "byte":
                    builder.append("B");
                    break;
                case "char":
                    builder.append("C");
                case "double":
                    builder.append("D");
                    break;
                case "float":
                    builder.append("F");
                    break;
                case "int":
                    builder.append("I");
                    break;
                case "long":
                    builder.append("J");
                    break;
                case "short":
                    builder.append("S");
                    break;
                case "void":
                    builder.append("V");
                    break;
                default:
                    builder.append("L").append(type).append(";");
                    break;
            }
        }
    }
}
