package com.github.minecraft_ta.totaldebug.util.mappings;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BytecodeReferenceSearcher {

    private static final int POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private static boolean RUNNING = false;

    /**
     * @param searchMethod true if you want to search for methods; false otherwise
     */
    @Nullable
    public static CompletableFuture<Pair<Collection<String>, Integer>> findReferences(String signature, boolean searchMethod) {
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
                        InternalRemappingContext context = new InternalRemappingContext(subResults, signature, searchMethod);

                        for (int j = startIndex; j <= finalEndIndex; j++) {
                            Class<?> clazz = allClasses.get(j);
                            context.currentClass = clazz;

                            //remap and search
                            RemappingUtil.getRemappedClass(clazz, context);
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

    private static final class InternalRemappingContext extends RemappingUtil.RemappingContext {

        private final List<String> results;
        private final String signatureToMatch;

        private Class<?> currentClass;

        public InternalRemappingContext(List<String> results, String signatureToMatch, boolean method) {
            this.results = results;
            this.signatureToMatch = signatureToMatch;
            write = false;
            mapMethodInsn = method;
            mapFieldInsn = !method;
            mapTypeAndLdcInsn = false;
            mapFields = false;
            mapLocals = false;
        }

        @Override
        public void onMethodInsnMapping(@Nonnull String containedMethodName, @Nonnull String newMethodSignature) {
            if (newMethodSignature.endsWith(signatureToMatch)) {
                Class<?> foundClass = RemappingUtil.tryFindClassWithMappings(currentClass.getName());
                if (foundClass == null)
                    return;

                results.add(foundClass.getName().replace('.', '/') + "#" + containedMethodName);
            }
        }

        @Override
        public void onFieldInsnMapping(@Nonnull String containedMethodName, @Nonnull String newFieldSignature) {
            if (newFieldSignature.endsWith(signatureToMatch)) {
                Class<?> foundClass = RemappingUtil.tryFindClassWithMappings(currentClass.getName());
                if (foundClass == null)
                    return;

                results.add(foundClass.getName().replace('.', '/') + "#" + containedMethodName);
            }
        }
    }
}
