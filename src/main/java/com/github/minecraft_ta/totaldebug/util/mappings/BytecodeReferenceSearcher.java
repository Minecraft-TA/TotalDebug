package com.github.minecraft_ta.totaldebug.util.mappings;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.*;

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
            try (ScanResult result = new ClassGraph()
                    .enableClassInfo()
                    .ignoreClassVisibility()
                    .disableRuntimeInvisibleAnnotations()
                    .disableNestedJarScanning()
                    //try to filter as much as possible
                    .rejectPackages("com.google", "com.typesafe", "org.apache", "org.scala-lang", "org.jline",
                            "org.ow2", "org.objectweb", "net.sf", "net.minecraft", "net.minecraftforge", "javax.vecmath",
                            "lzma", "org.stringtemplate", "nonapi.io.github.classgraph", "com.mojang", "paulscode",
                            "io.netty", "com.ibm", "it.unimi", "net.java", "org.lwjgl", "org.codehaus", "org.glassfish",
                            "org.abego", "com.github.minecraft_ta", "LZMA", "akka", "com.intellij", "baubles",
                            "com.jcraft", "com.strobel", "com.sun", "com.oracle", "gnu.trove", "ibxm",
                            "io.github.classgraph", "javafx", "jdk", "javax", "sun", "org.antlr", "joptsimple",
                            "netscape", "org.jetbrains", "oshi", "scala", "org.relaxng", "org.groovy")
                    .rejectJars("minecraft*.jar", "*forge*.jar")
                    .scan(EXECUTOR, POOL_SIZE)) {

                ClassInfoList allClasses = result.getAllClasses();
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
                            Class<?> clazz;
                            try {
                                clazz = allClasses.get(j).loadClass();
                            } catch (Throwable ignored) {
                                //class can't be loaded
                                continue;
                            }

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
            } catch (InterruptedException | ExecutionException e) {
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
