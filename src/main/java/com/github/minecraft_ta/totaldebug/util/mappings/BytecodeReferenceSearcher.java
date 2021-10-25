package com.github.minecraft_ta.totaldebug.util.mappings;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BytecodeReferenceSearcher {

    private static final int POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private static boolean RUNNING = false;

    /**
     * @param searchMethod true if you want to search for methods; false otherwise
     */
    @Nullable
    public static CompletableFuture<Pair<Collection<String>, Integer>> findReferences(String owner, String signature, boolean searchMethod) {
        if (RUNNING)
            return null;

        RUNNING = true;

        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Class<?>> allClasses = getFilteredClassesList();

                if (allClasses.isEmpty()) {
                    RUNNING = false;
                    return Pair.of(Collections.emptyList(), 0);
                }

                int partSize = allClasses.size() / POOL_SIZE;
                if (allClasses.size() < POOL_SIZE)
                    partSize = allClasses.size();

                List<Callable<List<String>>> tasks = new ArrayList<>(POOL_SIZE);

                //create tasks
                for (int i = 0; i < (allClasses.size() < POOL_SIZE ? 1 : POOL_SIZE); i++) {
                    int startIndex = i * partSize;
                    int endIndex = i == POOL_SIZE - 1 ? allClasses.size() - 1 : startIndex + partSize;

                    //supply full list to one thread if we have less than POOL_SIZE results
                    if (allClasses.size() < POOL_SIZE)
                        endIndex = allClasses.size() - 1;

                    int finalEndIndex = endIndex;
                    tasks.add(() -> {
                        List<String> subResults = new ArrayList<>();
                        ReferenceVisitor context = new ReferenceVisitor(subResults, owner, signature, searchMethod);

                        for (int j = startIndex; j <= finalEndIndex; j++) {
                            byte[] bytes = ClassUtil.getBytecodeFromLaunchClassLoader(allClasses.get(j).getName());
                            if (bytes == null)
                                continue;
                            new ClassReader(bytes).accept(context, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                        }

                        return subResults;
                    });
                }

                //execute tasks and merge results
                Set<String> results = new HashSet<>();
                for (Future<List<String>> future : EXECUTOR.invokeAll(tasks)) {
                    results.addAll(future.get());
                }

                RUNNING = false;

                return Pair.of(results, allClasses.size());
            } catch (Exception e) {
                e.printStackTrace();
                return Pair.of(Collections.emptyList(), 0);
            }
        });
    }

    public static void cancel() {
        EXECUTOR.shutdownNow();
        EXECUTOR = Executors.newCachedThreadPool();

        RUNNING = false;
    }

    private static List<Class<?>> getFilteredClassesList() {
        List<String> packageBlacklist = Arrays.asList(
                "com.google", "com.typesafe", "org.apache", "org.scala-lang", "org.jline",
                "org.ow2", "org.objectweb", "net.sf", "net.minecraft", "net.minecraftforge", "javax.vecmath",
                "lzma", "org.stringtemplate", "nonapi.io.github.classgraph", "com.mojang", "paulscode",
                "io.netty", "com.ibm", "it.unimi", "net.java", "org.lwjgl", "org.codehaus", "org.glassfish",
                "org.abego", "com.github.minecraft_ta.totaldebug", "LZMA", "akka", "com.intellij", "baubles",
                "com.jcraft", "com.strobel", "com.sun", "com.oracle", "gnu.trove", "ibxm",
                "io.github.classgraph", "javafx", "jdk", "javax", "sun", "org.antlr", "joptsimple",
                "netscape", "org.jetbrains", "oshi", "scala", "org.relaxng", "org.groovy");

        try {
            Field f = LaunchClassLoader.class.getDeclaredField("cachedClasses");
            f.setAccessible(true);
            return ((Map<String, Class<?>>) f.get(BytecodeReferenceSearcher.class.getClassLoader()))
                    .values().stream()
                    .filter(c -> packageBlacklist.stream().noneMatch(s -> c.getName().startsWith(s)))
                    .collect(Collectors.toList());
        } catch (Throwable t) {
            TotalDebug.LOGGER.error("Error while trying to get the class list", t);
            return Collections.emptyList();
        }
    }

    private static final class ReferenceVisitor extends ClassVisitor {

        private final List<String> results;
        private final String owner;
        private final String signatureToMatch;
        private final boolean method;

        private String currentClassName;

        public ReferenceVisitor(List<String> results, String owner, String signatureToMatch, boolean method) {
            super(Opcodes.ASM5, null);
            this.results = results;
            this.owner = owner;
            this.signatureToMatch = signatureToMatch;
            this.method = method;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.currentClassName = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String enclosingMethodName, String desc, String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM5, null) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                    if (!method || !(name + desc).endsWith(ReferenceVisitor.this.signatureToMatch))
                        return;
                    if (ReferenceVisitor.this.owner == null) {
                        ReferenceVisitor.this.results.add(ReferenceVisitor.this.currentClassName + "#" + enclosingMethodName);
                        return;
                    }

                    boolean valid;
                    if (owner.endsWith(ReferenceVisitor.this.owner)) {
                        valid = true;
                    } else { //Try finding actual owner of method
                        String actualOwner = findOwnerOf(owner, name, desc, true);
                        valid = actualOwner != null && actualOwner.endsWith(ReferenceVisitor.this.owner);
                    }

                    if (valid)
                        ReferenceVisitor.this.results.add(ReferenceVisitor.this.currentClassName + "#" + enclosingMethodName);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                    if (method || !name.endsWith(ReferenceVisitor.this.signatureToMatch))
                        return;

                    if (ReferenceVisitor.this.owner == null) {
                        ReferenceVisitor.this.results.add(ReferenceVisitor.this.currentClassName + "#" + enclosingMethodName);
                        return;
                    }

                    boolean valid;
                    if (owner.endsWith(ReferenceVisitor.this.owner)) {
                        valid = true;
                    } else { //Try finding actual owner of field
                        String actualOwner = findOwnerOf(owner, name, desc, false);
                        valid = actualOwner != null && actualOwner.endsWith(ReferenceVisitor.this.owner);
                    }

                    if (valid)
                        ReferenceVisitor.this.results.add(ReferenceVisitor.this.currentClassName + "#" + enclosingMethodName);
                }
            };
        }

        private String findOwnerOf(String currentOwner, String name, String desc, boolean method) {
            try {
                List<Class<?>> interfaces = new ArrayList<>();
                Class<?> clazz = Class.forName(currentOwner.replace('/', '.'));
                if (method)
                    interfaces.addAll(Arrays.asList(clazz.getInterfaces()));

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
                        interfaces.addAll(Arrays.asList(clazz.getInterfaces()));
                }

                for (Class<?> i : interfaces) {
                    String result = checkClass.apply(i);
                    if (result != null)
                        return result;
                }

                return null;
            } catch (Exception e) {
                return null;
            }
        }

        private String getMethodDesc(Method declaredMethod) {
            StringBuilder descBuilder = new StringBuilder("(");
            for (Class<?> parameterType : declaredMethod.getParameterTypes()) {
                appendDescType(descBuilder, parameterType.getName());
            }

            descBuilder.append(")");
            appendDescType(descBuilder, declaredMethod.getReturnType().getName());
            return descBuilder.toString().replace('.', '/');
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
