package com.github.minecraft_ta.totaldebug.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;

public class ForkJoinUtils {

    private static final int POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setName("TotalDebug ForkJoinHelper Thread");
        thread.setDaemon(true);
        return thread;
    });

    public static <IN, OUT> List<OUT> parallelMap(List<IN> input, Function<List<IN>, List<OUT>> mapper) throws InterruptedException, ExecutionException {
        if (input == null || input.isEmpty())
            return Collections.emptyList();

        int partSize = input.size() / POOL_SIZE;
        if (input.size() < POOL_SIZE)
            partSize = 1;

        List<Callable<List<OUT>>> tasks = new ArrayList<>(POOL_SIZE);

        // Create tasks
        for (int i = 0; i < Math.min(input.size(), POOL_SIZE); i++) {
            int startIndex = i * partSize;
            int endIndex = i == POOL_SIZE - 1 ? input.size() - 1 : startIndex + partSize;
            tasks.add(() -> mapper.apply(input.subList(startIndex, endIndex)));
        }

        // Execute tasks and merge results
        List<OUT> results = new ArrayList<>();
        for (Future<List<OUT>> future : EXECUTOR.invokeAll(tasks)) {
            results.addAll(future.get());
        }

        return results;
    }
}
