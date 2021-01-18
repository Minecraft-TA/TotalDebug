package com.github.minecraft_ta.totaldebug.util;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.*;

public class MethodReferenceSearcher {

    private static final int POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private static boolean RUNNING = false;

    @Nullable
    public static CompletableFuture<Collection<String>> findMethodReferences(String methodSignature) {
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
                    return Collections.emptyList();
                }

                int partSize = allClasses.size() / POOL_SIZE;
                if (allClasses.size() < POOL_SIZE)
                    partSize = allClasses.size();

                List<Callable<List<String>>> tasks = new ArrayList<>(POOL_SIZE);

                //create tasks
                for (int i = 0; i < (allClasses.size() < POOL_SIZE ? 1 : POOL_SIZE); i++) {
                    int startIndex = i * partSize;
                    int endIndex = i == POOL_SIZE - 1 ? allClasses.size() - 1 : startIndex + partSize;

                    if (allClasses.size() < POOL_SIZE)
                        endIndex = allClasses.size() - 1;

                    int finalEndIndex = endIndex;
                    tasks.add(() -> {
                        List<String> subResults = new ArrayList<>();

                        for (int j = startIndex; j <= finalEndIndex; j++) {
                            Class<?> clazz;
                            try {
                                clazz = allClasses.get(j).loadClass();
                            } catch (IllegalArgumentException ignored) {
                                //class can't be loaded
                                continue;
                            }

                            RemappingUtil.getRemappedClass(clazz, (methodName, signature) -> {
                                if (signature.endsWith(methodSignature))
                                    subResults.add(RemappingUtil.tryFindClassWithMappings(clazz.getName())
                                            .getName().replace('.', '/') + "#" + methodName);
                            });
                        }

                        return subResults;
                    });
                }

                Set<String> results = new HashSet<>();
                for (Future<List<String>> future : EXECUTOR.invokeAll(tasks)) {
                    results.addAll(future.get());
                }

                RUNNING = false;

                return results;
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                return Collections.emptyList();
            }
        });
    }

    public static void cancel() {
        EXECUTOR.shutdownNow();
        EXECUTOR = Executors.newCachedThreadPool();

        RUNNING = false;
    }
}
