package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.SearchOptions;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Loopback MCP host for Companion code mode. */
public final class CompanionMcpServer implements AutoCloseable {
    private static final String MCP_ENDPOINT = "/mcp";
    static final int MCP_PORT = 32_123;
    private static final int MAX_REQUEST_BYTES = 1_048_576;

    private final Path dataDirectory;
    private final Supplier<Path> workspaceDirectory;
    private final Supplier<Path> indexFile;
    private final Path endpointDescriptor;
    private final CodeModeJobService jobs;
    private final int port;
    private HttpServletStreamableServerTransportProvider transportProvider;
    private McpSyncServer mcpServer;
    private Tomcat tomcat;
    private String endpointUrl;
    private boolean closed;

    public CompanionMcpServer(
            Path dataDirectory,
            Supplier<Path> workspaceDirectory,
            Supplier<Path> indexFile,
            CodeModeJobService jobs
    ) {
        this(dataDirectory, workspaceDirectory, indexFile, jobs, MCP_PORT);
    }

    CompanionMcpServer(
            Path dataDirectory,
            Supplier<Path> workspaceDirectory,
            Supplier<Path> indexFile,
            CodeModeJobService jobs,
            int port
    ) {
        this.dataDirectory = normalize(dataDirectory);
        this.workspaceDirectory = Objects.requireNonNull(workspaceDirectory, "workspaceDirectory");
        this.indexFile = Objects.requireNonNull(indexFile, "indexFile");
        this.endpointDescriptor = this.dataDirectory.resolve(McpEndpointDescriptor.FILE_NAME);
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port is out of range");
        }
        this.port = port;
    }

    public synchronized void start() throws IOException, LifecycleException {
        if (this.closed) {
            throw new IllegalStateException("MCP server is closed");
        }
        if (this.tomcat != null) {
            throw new IllegalStateException("MCP server is already started");
        }

        this.transportProvider = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .mcpEndpoint(MCP_ENDPOINT)
                .securityValidator(new McpLoopbackSecurityValidator())
                .maxRequestSize(MAX_REQUEST_BYTES)
                .build();
        this.mcpServer = McpServer.sync(this.transportProvider)
                .serverInfo(CompanionMcpToolCatalog.SERVER_NAME, CompanionMcpToolCatalog.SERVER_VERSION)
                .instructions(CompanionMcpToolCatalog.INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(CompanionMcpToolCatalog.specifications(this::callTool))
                .build();

        Path mcpDirectory = this.dataDirectory.resolve("mcp");
        Path tomcatDirectory = mcpDirectory.resolve("tomcat");
        Files.createDirectories(tomcatDirectory);

        Tomcat embeddedTomcat = new Tomcat();
        embeddedTomcat.setBaseDir(tomcatDirectory.toString());
        embeddedTomcat.setHostname("127.0.0.1");
        Connector connector = new Connector();
        connector.setPort(this.port);
        connector.setProperty("address", "127.0.0.1");
        embeddedTomcat.setConnector(connector);

        Context context = embeddedTomcat.addContext("", mcpDirectory.toString());
        Wrapper servlet = Tomcat.addServlet(context, "mcp", this.transportProvider);
        servlet.setAsyncSupported(true);
        servlet.setLoadOnStartup(1);
        context.addServletMappingDecoded(MCP_ENDPOINT, "mcp");

        try {
            embeddedTomcat.start();
            int port = connector.getLocalPort();
            if (port < 1) {
                throw new IOException("Embedded MCP server did not publish a TCP port");
            }
            this.endpointUrl = "http://127.0.0.1:" + port + MCP_ENDPOINT;
            new McpEndpointDescriptor(this.endpointUrl, Instant.now())
                    .writeAtomically(this.endpointDescriptor);
            this.tomcat = embeddedTomcat;
        } catch (IOException | LifecycleException | RuntimeException exception) {
            try {
                embeddedTomcat.stop();
            } catch (LifecycleException suppressed) {
                exception.addSuppressed(suppressed);
            }
            try {
                embeddedTomcat.destroy();
            } catch (LifecycleException suppressed) {
                exception.addSuppressed(suppressed);
            }
            closeMcpServer();
            throw exception;
        }
    }

    public synchronized String endpointUrl() {
        if (this.endpointUrl == null) {
            throw new IllegalStateException("MCP server has not started");
        }
        return this.endpointUrl;
    }

    Path endpointDescriptor() {
        return this.endpointDescriptor;
    }

    public void runtimeDisconnected() {
        this.jobs.runtimeDisconnected();
    }

    private McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> result = switch (request.name()) {
                case "status" -> status();
                case "code_execute" -> execute(request.arguments());
                case "jobs_get" -> this.jobs.get(requiredString(request.arguments(), "job_id"))
                        .map(CodeModeJobService.JobSnapshot::responseMap)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown job"));
                case "jobs_wait" -> this.jobs.waitFor(
                        requiredString(request.arguments(), "job_id"),
                        optionalInteger(request.arguments(), "wait_ms", 30_000)
                ).responseMap();
                case "jobs_list" -> Map.of(
                        "jobs",
                        this.jobs.list(optionalInteger(request.arguments(), "limit", 20)).stream()
                                .map(CodeModeJobService.JobSnapshot::responseMap)
                                .toList()
                );
                case "jobs_cancel" -> Map.of(
                        "job_id",
                        requiredString(request.arguments(), "job_id"),
                        "cancellation_requested",
                        this.jobs.cancel(requiredString(request.arguments(), "job_id"))
                );
                case "search_classes" -> searchClasses(
                        requiredString(request.arguments(), "query"),
                        optionalInteger(request.arguments(), "limit", 50)
                );
                case "artifacts_read" -> readArtifact(request.arguments());
                default -> throw new IllegalArgumentException("Unknown MCP tool: " + request.name());
            };
            return CompanionMcpToolCatalog.result(result, false);
        } catch (RuntimeException | IOException exception) {
            return CompanionMcpToolCatalog.result(
                    Map.of("error", Objects.requireNonNullElse(exception.getMessage(), exception.toString())),
                    true
            );
        }
    }

    private Map<String, Object> status() {
        return Map.of(
                "companion_available", true,
                "minecraft_connected", this.jobs.isAvailable()
        );
    }

    private Map<String, Object> execute(Map<String, Object> arguments) {
        String code = requiredString(arguments, "code");
        List<String> imports = optionalStringList(arguments, "imports");
        CodeModeJobService.ExecutionSide side = parseEnum(
                CodeModeJobService.ExecutionSide.class,
                optionalString(arguments, "side", "client")
        );
        CodeModeJobService.ExecutionEnvironment environment = parseEnum(
                CodeModeJobService.ExecutionEnvironment.class,
                optionalString(arguments, "environment", "thread")
        );
        CodeModeJobService.JobSnapshot submitted = this.jobs.submit(code, imports, side, environment);
        return this.jobs.waitFor(
                submitted.jobId(),
                optionalInteger(arguments, "wait_ms", 10_000)
        ).responseMap();
    }

    static Map<String, Object> searchClasses(String query, int limit) {
        if (!CompanionClassIndex.isOpen()) {
            throw new IllegalStateException("The runtime class index is still being built");
        }
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        ClassIndex index = CompanionClassIndex.get();
        IndexedClass exactMatch = index.findClass(query);
        IndexedClass[] matches = exactMatch == null
                ? index.findClasses(
                        query,
                        SearchOptions.with(SearchOptions.SearchMode.CONTAINS, SearchOptions.MatchMode.IGNORE_CASE, limit)
                )
                : new IndexedClass[]{exactMatch};
        List<Map<String, Object>> classes = new ArrayList<>(matches.length);
        for (IndexedClass match : matches) {
            classes.add(describeClass(match));
        }
        return Map.of("classes", classes);
    }

    static Map<String, Object> describeClass(IndexedClass indexedClass) {
        int flags = indexedClass.getAccessFlags();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("binary_name", indexedClass.getNameWithPackageDot());
        value.put("kind", classKind(flags));
        List<String> modifiers = classModifiers(flags);
        if (!modifiers.isEmpty()) {
            value.put("modifiers", modifiers);
        }
        if (indexedClass.getInnerClassType() != null) {
            value.put("inner_class_type", indexedClass.getInnerClassType().name().toLowerCase(Locale.ROOT));
        }
        return value;
    }

    static String classKind(int flags) {
        if ((flags & Opcodes.ACC_ANNOTATION) != 0) {
            return "annotation";
        }
        if ((flags & Opcodes.ACC_ENUM) != 0) {
            return "enum";
        }
        if ((flags & Opcodes.ACC_RECORD) != 0) {
            return "record";
        }
        if ((flags & Opcodes.ACC_INTERFACE) != 0) {
            return "interface";
        }
        if ((flags & Opcodes.ACC_MODULE) != 0) {
            return "module";
        }
        return "class";
    }

    static List<String> classModifiers(int flags) {
        List<String> modifiers = new ArrayList<>(7);
        addModifier(modifiers, flags, Opcodes.ACC_PUBLIC, "public");
        addModifier(modifiers, flags, Opcodes.ACC_PROTECTED, "protected");
        addModifier(modifiers, flags, Opcodes.ACC_PRIVATE, "private");
        addModifier(modifiers, flags, Opcodes.ACC_ABSTRACT, "abstract");
        addModifier(modifiers, flags, Opcodes.ACC_STATIC, "static");
        addModifier(modifiers, flags, Opcodes.ACC_FINAL, "final");
        addModifier(modifiers, flags, Opcodes.ACC_SYNTHETIC, "synthetic");
        addModifier(modifiers, flags, Opcodes.ACC_DEPRECATED, "deprecated");
        return List.copyOf(modifiers);
    }

    private static void addModifier(List<String> modifiers, int flags, int mask, String name) {
        if ((flags & mask) != 0) {
            modifiers.add(name);
        }
    }

    private Map<String, Object> readArtifact(Map<String, Object> arguments) throws IOException {
        String jobId = requiredString(arguments, "job_id");
        String artifact = requiredString(arguments, "artifact");
        return Map.of(
                "job_id", jobId,
                "artifact", artifact,
                "content", this.jobs.readArtifact(jobId, artifact)
        );
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return string;
    }

    private static String optionalString(Map<String, Object> arguments, String name, String defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return string;
    }

    private static int optionalInteger(Map<String, Object> arguments, String name, int defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        double decimal = number.doubleValue();
        int integer = number.intValue();
        if (decimal != integer) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return integer;
    }

    private static List<String> optionalStringList(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(name + " must be an array of strings");
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String string) || string.isBlank()) {
                throw new IllegalArgumentException(name + " must contain only non-blank strings");
            }
            strings.add(string);
        }
        return List.copyOf(strings);
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported " + type.getSimpleName() + ": " + value, exception);
        }
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            McpEndpointDescriptor.deleteIfOwned(this.endpointDescriptor, this.endpointUrl);
        } catch (IOException | RuntimeException exception) {
            System.err.println("Unable to remove MCP endpoint descriptor: " + exception);
        }
        if (this.tomcat != null) {
            try {
                this.tomcat.stop();
            } catch (LifecycleException exception) {
                System.err.println("Unable to stop embedded MCP server: " + exception);
            }
            try {
                this.tomcat.destroy();
            } catch (LifecycleException exception) {
                System.err.println("Unable to destroy embedded MCP server: " + exception);
            }
            this.tomcat = null;
        }
        closeMcpServer();
        this.jobs.close();
    }

    private void closeMcpServer() {
        if (this.mcpServer != null) {
            this.mcpServer.closeGracefully();
            this.mcpServer = null;
        }
        this.transportProvider = null;
    }
}
