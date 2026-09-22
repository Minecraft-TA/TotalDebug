package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import com.google.gson.JsonParser;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

/**
 * Discovers and renders item-model files without starting Companion or Minecraft.
 *
 * <pre>
 * ./gradlew :companion:itemRenderHarness --args="--root=minecraft-resources.jar --mods=mods --output=build/reports/items"
 * </pre>
 *
 * <p>The harness writes a human summary, structured JSON, and a row per model in CSV form. It
 * lives in {@code src/test} so local modpack paths and diagnostic output stay out of the shipped
 * Companion jar.</p>
 */
public final class ItemRenderDevHarness {

    private static final int DEFAULT_SIZE = 32;
    private static final int DEFAULT_PROGRESS_INTERVAL = 250;

    private ItemRenderDevHarness() {
    }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        if (options.help()) {
            printHelp();
            return;
        }

        System.out.println("Item render harness: archive-backed software rendering, no Minecraft process");
        System.out.println("Resource roots: " + options.resourceRoots().size());
        System.out.println("Output: " + options.outputDirectory().toAbsolutePath());

        Instant generatedAt = Instant.now();
        List<ItemModelId> models;
        ItemRenderBatchResult batch;
        try (ItemRenderBackend backend = ItemRenderBackend.openResourceRoots(options.resourceRoots())) {
            models = options.models().isEmpty() ? backend.discoverItemModels() : options.models();
            if (!options.namespaces().isEmpty()) {
                models = models.stream()
                        .filter(model -> options.namespaces().contains(model.namespace()))
                        .toList();
            }
            System.out.println("Selected " + models.size() + " item-model files");

            List<ItemRenderRequest> requests = models.stream()
                    .map(model -> new ItemRenderRequest(model, options.size()))
                    .toList();
            int[] completed = {0};
            int[] succeeded = {0};
            ItemRenderBatchOptions batchOptions = options.writeImages()
                    ? ItemRenderBatchOptions.RETAIN_IMAGES
                    : ItemRenderBatchOptions.DIAGNOSTICS;
            batch = backend.renderBatch(requests, batchOptions, entry -> {
                completed[0]++;
                if (entry.succeeded()) {
                    succeeded[0]++;
                }
                if (options.progressInterval() > 0
                        && (completed[0] % options.progressInterval() == 0 || completed[0] == requests.size())) {
                    System.out.println("  " + completed[0] + "/" + requests.size()
                            + " rendered=" + succeeded[0]
                            + " failed=" + (completed[0] - succeeded[0]));
                }
            });
        }

        writeReport(
                options.outputDirectory(),
                generatedAt,
                options.resourceRoots(),
                options.namespaces(),
                options.size(),
                batch
        );
        if (options.writeImages()) {
            writeImages(options.outputDirectory().resolve("images"), batch);
        }

        System.out.println("Rendered " + batch.succeededCount() + "/" + batch.entries().size()
                + " models in " + formatSeconds(batch.elapsedNanos()));
        System.out.println("Report: " + options.outputDirectory().resolve("summary.txt").toAbsolutePath());
    }

    static void writeReport(
            Path outputDirectory,
            Instant generatedAt,
            List<ItemRenderResourceRoot> resourceRoots,
            Set<String> namespaceFilter,
            int size,
            ItemRenderBatchResult batch
    ) throws IOException {
        Files.createDirectories(outputDirectory);
        ReportStats stats = ReportStats.from(batch);
        Files.writeString(
                outputDirectory.resolve("summary.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(toJson(
                        generatedAt,
                        resourceRoots,
                        namespaceFilter,
                        size,
                        batch,
                        stats
                )),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                outputDirectory.resolve("summary.txt"),
                toText(generatedAt, resourceRoots, namespaceFilter, size, batch, stats),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                outputDirectory.resolve("models.csv"),
                toCsv(batch),
                StandardCharsets.UTF_8
        );
    }

    private static JsonObject toJson(
            Instant generatedAt,
            List<ItemRenderResourceRoot> resourceRoots,
            Set<String> namespaceFilter,
            int size,
            ItemRenderBatchResult batch,
            ReportStats stats
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("measurement", "model-file requests, not ItemStack coverage or pixel parity");
        root.addProperty("generatedAt", generatedAt.toString());
        root.addProperty("renderSize", size);

        JsonArray roots = new JsonArray();
        resourceRoots.forEach(resource -> roots.add(resource.description()));
        root.add("resourceRoots", roots);
        JsonArray namespaces = new JsonArray();
        namespaceFilter.stream().sorted().forEach(namespaces::add);
        root.add("namespaceFilter", namespaces);

        JsonObject totals = new JsonObject();
        totals.addProperty("models", batch.entries().size());
        totals.addProperty("rendered", batch.succeededCount());
        totals.addProperty("failed", batch.failedCount());
        totals.addProperty("visible", batch.visibleCount());
        totals.addProperty("empty", batch.succeededCount() - batch.visibleCount());
        totals.addProperty("coveragePercent", percentage(batch.succeededCount(), batch.entries().size()));
        root.add("totals", totals);

        JsonObject timing = new JsonObject();
        timing.addProperty("elapsedMillis", nanosToMillis(batch.elapsedNanos()));
        timing.addProperty("modelsPerSecond", throughput(batch.entries().size(), batch.elapsedNanos()));
        timing.addProperty("medianModelMicros", nanosToMicros(percentile(stats.elapsedNanos(), 0.50)));
        timing.addProperty("p95ModelMicros", nanosToMicros(percentile(stats.elapsedNanos(), 0.95)));
        timing.addProperty("slowestModelMicros", nanosToMicros(percentile(stats.elapsedNanos(), 1.0)));
        root.add("timing", timing);

        root.add("failureKinds", countedValues(stats.failureKinds()));
        root.add("unsupportedFeatures", countedValues(stats.unsupportedFeatures()));
        root.add("failureDetails", detailedFailures(stats.failureDetails()));

        JsonArray namespaceStats = new JsonArray();
        stats.namespaces().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    NamespaceStats namespace = entry.getValue();
                    JsonObject json = new JsonObject();
                    json.addProperty("namespace", entry.getKey());
                    json.addProperty("models", namespace.total());
                    json.addProperty("rendered", namespace.succeeded());
                    json.addProperty("failed", namespace.failed());
                    json.addProperty("coveragePercent", percentage(namespace.succeeded(), namespace.total()));
                    namespaceStats.add(json);
                });
        root.add("namespaces", namespaceStats);
        return root;
    }

    private static String toText(
            Instant generatedAt,
            List<ItemRenderResourceRoot> resourceRoots,
            Set<String> namespaceFilter,
            int size,
            ItemRenderBatchResult batch,
            ReportStats stats
    ) {
        StringBuilder output = new StringBuilder();
        output.append("Item render diagnostic\n");
        output.append("Counts describe model-file requests, not ItemStack coverage or pixel parity.\n");
        output.append("Generated: ").append(generatedAt).append('\n');
        output.append("Render size: ").append(size).append(" px\n");
        output.append("Resource roots: ").append(resourceRoots.size()).append("\n\n");
        if (!namespaceFilter.isEmpty()) {
            output.append("Namespace filter: ")
                    .append(String.join(", ", namespaceFilter.stream().sorted().toList()))
                    .append("\n\n");
        }
        output.append("Models: ").append(batch.entries().size()).append('\n');
        output.append("Rendered: ").append(batch.succeededCount()).append('\n');
        output.append("Failed: ").append(batch.failedCount()).append('\n');
        output.append("Models without failure: ").append(formatPercentage(
                batch.succeededCount(),
                batch.entries().size()
        )).append("\n");
        output.append("With visible pixels: ").append(batch.visibleCount()).append('\n');
        output.append("Empty successful images: ").append(batch.succeededCount() - batch.visibleCount()).append('\n');
        output.append("Elapsed: ").append(formatSeconds(batch.elapsedNanos())).append('\n');
        output.append("Throughput: ").append(String.format(
                Locale.ROOT,
                "%.1f models/s",
                throughput(batch.entries().size(), batch.elapsedNanos())
        )).append("\n\n");

        output.append("Failure kinds\n");
        appendCounts(output, stats.failureKinds());
        output.append("\nUnsupported features\n");
        appendCounts(output, stats.unsupportedFeatures());
        output.append("\nTop failure details\n");
        sortedFailureDetails(stats.failureDetails()).stream()
                .limit(30)
                .forEach(entry -> output.append("  ")
                        .append(entry.getValue()).append("  ")
                        .append(entry.getKey().kind()).append("  ")
                        .append(entry.getKey().detail()).append('\n'));
        output.append("\nNamespaces with failures\n");
        stats.namespaces().entrySet().stream()
                .filter(entry -> entry.getValue().failed() > 0)
                .sorted(Comparator
                        .<Map.Entry<String, NamespaceStats>>comparingInt(entry -> entry.getValue().failed())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> output.append("  ")
                        .append(entry.getKey()).append(": ")
                        .append(entry.getValue().succeeded()).append('/')
                        .append(entry.getValue().total()).append(" rendered, ")
                        .append(entry.getValue().failed()).append(" failed\n"));
        return output.toString();
    }

    private static String toCsv(ItemRenderBatchResult batch) {
        StringBuilder output = new StringBuilder(
                "model,namespace,status,failure_kind,failure_detail,message,visible_pixels,elapsed_micros\n"
        );
        for (ItemRenderBatchResult.Entry entry : batch.entries()) {
            FailureInfo failure = FailureInfo.from(entry.failure());
            output.append(csv(entry.request().modelId().toString())).append(',')
                    .append(csv(entry.request().modelId().namespace())).append(',')
                    .append(entry.succeeded() ? "rendered" : "failed").append(',')
                    .append(csv(failure.kind())).append(',')
                    .append(csv(failure.detail())).append(',')
                    .append(csv(failure.message())).append(',')
                    .append(entry.visiblePixels()).append(',')
                    .append(nanosToMicros(entry.elapsedNanos())).append('\n');
        }
        return output.toString();
    }

    private static JsonArray countedValues(Map<String, Integer> counts) {
        JsonArray values = new JsonArray();
        sortedCounts(counts).forEach(entry -> {
            JsonObject value = new JsonObject();
            value.addProperty("name", entry.getKey());
            value.addProperty("count", entry.getValue());
            values.add(value);
        });
        return values;
    }

    private static JsonArray detailedFailures(Map<FailureSignature, Integer> counts) {
        JsonArray values = new JsonArray();
        sortedFailureDetails(counts).forEach(entry -> {
            JsonObject value = new JsonObject();
            value.addProperty("kind", entry.getKey().kind());
            value.addProperty("detail", entry.getKey().detail());
            value.addProperty("count", entry.getValue());
            values.add(value);
        });
        return values;
    }

    private static void appendCounts(StringBuilder output, Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            output.append("  none\n");
            return;
        }
        sortedCounts(counts).forEach(entry -> output.append("  ")
                .append(entry.getValue()).append("  ")
                .append(entry.getKey()).append('\n'));
    }

    private static List<Map.Entry<String, Integer>> sortedCounts(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .toList();
    }

    private static List<Map.Entry<FailureSignature, Integer>> sortedFailureDetails(
            Map<FailureSignature, Integer> counts
    ) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<FailureSignature, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(entry -> entry.getKey().kind())
                        .thenComparing(entry -> entry.getKey().detail()))
                .toList();
    }

    private static void writeImages(Path imageDirectory, ItemRenderBatchResult batch) throws IOException {
        for (ItemRenderBatchResult.Entry entry : batch.entries()) {
            if (!entry.succeeded() || entry.image() == null) {
                continue;
            }
            ItemModelId model = entry.request().modelId();
            Path target = imageDirectory.resolve(model.namespace()).resolve(model.path() + ".png");
            Files.createDirectories(target.getParent());
            if (!ImageIO.write(entry.image(), "png", target.toFile())) {
                throw new IOException("No PNG writer is available for " + target);
            }
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0L;
        }
        int index = Math.max(0, (int) Math.ceil(sortedValues.size() * percentile) - 1);
        return sortedValues.get(Math.min(index, sortedValues.size() - 1));
    }

    private static double percentage(long part, long whole) {
        return whole == 0 ? 100.0 : part * 100.0 / whole;
    }

    private static String formatPercentage(long part, long whole) {
        return String.format(Locale.ROOT, "%.2f%%", percentage(part, whole));
    }

    private static double throughput(long count, long elapsedNanos) {
        return elapsedNanos == 0 ? 0.0 : count * 1_000_000_000.0 / elapsedNanos;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static long nanosToMicros(long nanos) {
        return nanos / 1_000L;
    }

    private static String formatSeconds(long nanos) {
        return String.format(Locale.ROOT, "%.3f s", nanos / 1_000_000_000.0);
    }

    private static void printHelp() {
        System.out.println("Usage: ./gradlew :companion:itemRenderHarness --args=\"OPTIONS\"");
        System.out.println("  --root=PATH[!/PREFIX]  Add a pack root in low-to-high priority order");
        System.out.println("  --roots-file=PATH JSON array of ordered resource roots, inserted at this position");
        System.out.println("  --mods=PATH       Add every .jar in a mod directory, sorted by filename");
        System.out.println("  --output=PATH     Report directory, default build/reports/item-render");
        System.out.println("  --namespace=ID    Include one namespace, repeat to include more");
        System.out.println("  --model=ID        Render an exact model ID instead of scanning, repeat for more");
        System.out.println("  --size=PIXELS     Render size from 1 to 512, default 32");
        System.out.println("  --progress=N      Print progress every N models, 0 disables it");
        System.out.println("  --write-images    Retain and write every successful image");
    }

    private record FailureInfo(String kind, String detail, String message) {

        static FailureInfo from(Exception failure) {
            if (failure == null) {
                return new FailureInfo("", "", "");
            }
            if (failure instanceof ItemRenderException renderFailure) {
                return new FailureInfo(
                        renderFailure.kind().name(),
                        renderFailure.detail(),
                        renderFailure.getMessage()
                );
            }
            return new FailureInfo(
                    "INTERNAL_ERROR",
                    failure.getClass().getName(),
                    failure.getMessage() == null ? failure.toString() : failure.getMessage()
            );
        }
    }

    private record NamespaceStats(int total, int succeeded) {

        int failed() {
            return this.total - this.succeeded;
        }
    }

    private record FailureSignature(String kind, String detail) {
    }

    private record ReportStats(
            Map<String, Integer> failureKinds,
            Map<String, Integer> unsupportedFeatures,
            Map<FailureSignature, Integer> failureDetails,
            Map<String, NamespaceStats> namespaces,
            List<Long> elapsedNanos
    ) {

        static ReportStats from(ItemRenderBatchResult batch) {
            Map<String, Integer> failureKinds = new TreeMap<>();
            Map<String, Integer> unsupportedFeatures = new TreeMap<>();
            Map<FailureSignature, Integer> failureDetails = new LinkedHashMap<>();
            Map<String, int[]> namespaceCounts = new TreeMap<>();
            List<Long> elapsed = new ArrayList<>(batch.entries().size());
            for (ItemRenderBatchResult.Entry entry : batch.entries()) {
                int[] namespace = namespaceCounts.computeIfAbsent(
                        entry.request().modelId().namespace(),
                        ignored -> new int[2]
                );
                namespace[0]++;
                if (entry.succeeded()) {
                    namespace[1]++;
                } else {
                    FailureInfo failure = FailureInfo.from(entry.failure());
                    failureKinds.merge(failure.kind(), 1, Integer::sum);
                    failureDetails.merge(
                            new FailureSignature(failure.kind(), failure.detail()),
                            1,
                            Integer::sum
                    );
                    if (failure.kind().equals(ItemRenderException.Kind.UNSUPPORTED_FEATURE.name())) {
                        unsupportedFeatures.merge(failure.detail(), 1, Integer::sum);
                    }
                }
                elapsed.add(entry.elapsedNanos());
            }
            elapsed.sort(Long::compareTo);

            Map<String, NamespaceStats> namespaces = new LinkedHashMap<>();
            namespaceCounts.forEach((name, counts) -> namespaces.put(
                    name,
                    new NamespaceStats(counts[0], counts[1])
            ));
            return new ReportStats(
                    Map.copyOf(failureKinds),
                    Map.copyOf(unsupportedFeatures),
                    Map.copyOf(failureDetails),
                    Map.copyOf(namespaces),
                    List.copyOf(elapsed)
            );
        }
    }

    private record Options(
            List<ItemRenderResourceRoot> resourceRoots,
            Set<String> namespaces,
            List<ItemModelId> models,
            Path outputDirectory,
            int size,
            int progressInterval,
            boolean writeImages,
            boolean help
    ) {

        private static Options parse(String[] arguments) throws IOException {
            List<ItemRenderResourceRoot> roots = new ArrayList<>();
            Set<String> namespaces = new LinkedHashSet<>();
            Set<ItemModelId> models = new LinkedHashSet<>();
            Path output = Path.of("build", "reports", "item-render");
            int size = DEFAULT_SIZE;
            int progress = DEFAULT_PROGRESS_INTERVAL;
            boolean writeImages = false;
            boolean help = false;

            for (String argument : arguments) {
                if (argument.equals("--help")) {
                    help = true;
                } else if (argument.equals("--write-images")) {
                    writeImages = true;
                } else if (argument.startsWith("--root=")) {
                    roots.add(parseRoot(value(argument)));
                } else if (argument.startsWith("--roots-file=")) {
                    for (var root : JsonParser.parseString(
                            Files.readString(Path.of(value(argument)), StandardCharsets.UTF_8)).getAsJsonArray()) {
                        roots.add(parseRoot(root.getAsString()));
                    }
                } else if (argument.startsWith("--mods=")) {
                    addModArchives(roots, Path.of(value(argument)));
                } else if (argument.startsWith("--output=")) {
                    output = Path.of(value(argument));
                } else if (argument.startsWith("--namespace=")) {
                    String namespace = value(argument);
                    new ItemModelId(namespace, "item/validation");
                    namespaces.add(namespace);
                } else if (argument.startsWith("--model=")) {
                    models.add(ItemModelId.parse(value(argument)));
                } else if (argument.startsWith("--size=")) {
                    size = Integer.parseInt(value(argument));
                } else if (argument.startsWith("--progress=")) {
                    progress = Integer.parseInt(value(argument));
                } else {
                    throw new IllegalArgumentException("Unknown item render harness option: " + argument);
                }
            }
            if (!help && roots.isEmpty()) {
                throw new IllegalArgumentException("At least one --root, --roots-file or --mods option is required");
            }
            if (size < 1 || size > ItemRenderRequest.MAXIMUM_SIZE) {
                throw new IllegalArgumentException("--size must be between 1 and " + ItemRenderRequest.MAXIMUM_SIZE);
            }
            if (progress < 0) {
                throw new IllegalArgumentException("--progress must be non-negative");
            }
            return new Options(
                    List.copyOf(roots),
                    Set.copyOf(namespaces),
                    List.copyOf(models),
                    output,
                    size,
                    progress,
                    writeImages,
                    help
            );
        }

        private static String value(String argument) {
            String value = argument.substring(argument.indexOf('=') + 1);
            if (value.isBlank()) {
                throw new IllegalArgumentException("Missing value for " + argument.substring(0, argument.indexOf('=')));
            }
            return value;
        }

        private static ItemRenderResourceRoot parseRoot(String value) {
            int separator = value.lastIndexOf("!/");
            return separator < 0
                    ? new ItemRenderResourceRoot(Path.of(value))
                    : ItemRenderResourceRoot.nested(
                            Path.of(value.substring(0, separator)),
                            value.substring(separator + 2)
                    );
        }

        private static void addModArchives(List<ItemRenderResourceRoot> roots, Path modDirectory) throws IOException {
            if (!Files.isDirectory(modDirectory)) {
                throw new IOException("Mod directory does not exist: " + modDirectory.toAbsolutePath());
            }
            try (var files = Files.list(modDirectory)) {
                roots.addAll(files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .map(ItemRenderResourceRoot::new)
                        .toList());
            }
        }
    }
}
