package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.SearchOptions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

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

/** Authenticated loopback MCP host for Companion code mode. */
public final class CompanionMcpServer implements AutoCloseable {
    private static final String MCP_ENDPOINT = "/mcp";
    static final int MCP_PORT = 32_123;
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

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
                .serverInfo("totaldebug-companion", "1.0.0")
                .instructions(
                        "Use code_execute for runtime facts. It executes unrestricted Java inside the connected "
                                + "Minecraft JVM. Poll jobs_get until the job reaches a terminal state."
                )
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(toolSpecifications())
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

    private List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
        return List.of(
                tool(
                        "status",
                        "Report the Companion, transport, and connected runtime state.",
                        objectSchema(Map.of(), List.of()),
                        request -> status()
                ),
                tool(
                        "code_execute",
                        "Submit unrestricted Java statements for execution inside the connected Minecraft JVM. "
                                + "Use log or logln in the code to return observed values.",
                        objectSchema(
                                Map.of(
                                        "code", stringSchema("Java statements inserted into BaseScript.run()."),
                                        "imports", arraySchema(
                                                stringSchema("Java import target, for example java.util.Map or static java.lang.Math.*.")
                                        ),
                                        "side", enumSchema("Minecraft execution side.", "client", "server"),
                                        "environment", enumSchema(
                                                "Execution thread. Tick environments cannot be interrupted safely after starting.",
                                                "thread",
                                                "pre_tick",
                                                "post_tick"
                                        )
                                ),
                                List.of("code")
                        ),
                        request -> execute(request.arguments())
                ),
                tool(
                        "jobs_get",
                        "Get one code job, including its terminal output or error and source provenance.",
                        objectSchema(
                                Map.of("job_id", stringSchema("UUID returned by code_execute.")),
                                List.of("job_id")
                        ),
                        request -> this.jobs.get(requiredString(request.arguments(), "job_id"))
                                .map(CodeModeJobService.JobSnapshot::asMap)
                                .orElseThrow(() -> new IllegalArgumentException("Unknown job"))
                ),
                tool(
                        "jobs_list",
                        "List recent code jobs in newest-first order.",
                        objectSchema(
                                Map.of("limit", integerSchema("Maximum jobs to return, from 1 to 256.", 1, 256)),
                                List.of()
                        ),
                        request -> Map.of(
                                "jobs",
                                this.jobs.list(optionalInteger(request.arguments(), "limit", 20)).stream()
                                        .map(CodeModeJobService.JobSnapshot::asMap)
                                        .toList()
                        )
                ),
                tool(
                        "jobs_cancel",
                        "Request cooperative cancellation of a running code job.",
                        objectSchema(
                                Map.of("job_id", stringSchema("UUID returned by code_execute.")),
                                List.of("job_id")
                        ),
                        request -> Map.of(
                                "job_id",
                                requiredString(request.arguments(), "job_id"),
                                "cancellation_requested",
                                this.jobs.cancel(requiredString(request.arguments(), "job_id"))
                        )
                ),
                tool(
                        "search_classes",
                        "Search the exact class index generated from the connected Minecraft runtime.",
                        objectSchema(
                                Map.of(
                                        "query", stringSchema("Class name fragment."),
                                        "limit", integerSchema("Maximum matches to return, from 1 to 200.", 1, 200)
                                ),
                                List.of("query")
                        ),
                        request -> searchClasses(
                                requiredString(request.arguments(), "query"),
                                optionalInteger(request.arguments(), "limit", 50)
                        )
                ),
                tool(
                        "artifacts_read",
                        "Read the exact generated Java source or current job record retained by Companion.",
                        objectSchema(
                                Map.of(
                                        "job_id", stringSchema("UUID returned by code_execute."),
                                        "artifact", enumSchema("Artifact to read.", "source", "job")
                                ),
                                List.of("job_id", "artifact")
                        ),
                        request -> readArtifact(request.arguments())
                )
        );
    }

    private McpServerFeatures.SyncToolSpecification tool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            ToolHandler handler
    ) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> result = handler.handle(request);
                        return toolResult(result, false);
                    } catch (RuntimeException | IOException exception) {
                        return toolResult(
                                Map.of("error", Objects.requireNonNullElse(exception.getMessage(), exception.toString())),
                                true
                        );
                    }
                })
                .build();
    }

    private Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("companion_process_id", ProcessHandle.current().pid());
        status.put("mcp_transport", "streamable-http");
        status.put("mcp_url", this.endpointUrl);
        status.put("minecraft_connected", this.jobs.isAvailable());
        status.put("companion_protocol_version", CompanionProtocol.VERSION);
        status.put("runtime_boundary", "Minecraft JVM through authenticated SCNet");
        status.put("execution_security", "unrestricted Java with full game-process access");
        Path workspace = this.workspaceDirectory.get();
        Path index = this.indexFile.get();
        if (workspace != null) {
            status.put("workspace_directory", normalize(workspace).toString());
        }
        if (index != null) {
            status.put("class_index", normalize(index).toString());
        }
        status.put("retained_jobs", this.jobs.list(CodeModeJobService.MAX_RETAINED_JOBS).size());
        status.put("runtime", this.jobs.currentRuntimeContext());
        return status;
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
        return this.jobs.submit(code, imports, side, environment).asMap();
    }

    private Map<String, Object> searchClasses(String query, int limit) {
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        IndexedClass[] matches = CompanionClassIndex.get().findClasses(
                query,
                SearchOptions.with(SearchOptions.SearchMode.CONTAINS, SearchOptions.MatchMode.IGNORE_CASE, limit)
        );
        List<Map<String, Object>> classes = new ArrayList<>(matches.length);
        for (IndexedClass match : matches) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("binary_name", match.getNameWithPackageDot());
            value.put("source_name", match.getSourceName());
            value.put("access_flags", match.getAccessFlags());
            if (match.getInnerClassType() != null) {
                value.put("inner_class_type", match.getInnerClassType().name().toLowerCase(Locale.ROOT));
            }
            classes.add(value);
        }
        return Map.of("query", query, "count", classes.size(), "classes", classes);
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

    private static McpSchema.CallToolResult toolResult(Map<String, Object> result, boolean error) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(GSON.toJson(result))
                .structuredContent(result)
                .isError(error)
                .build();
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties,
            List<String> required
    ) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        return Map.of("type", "array", "items", items, "default", List.of());
    }

    private static Map<String, Object> enumSchema(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }

    private static Map<String, Object> integerSchema(String description, int minimum, int maximum) {
        return Map.of(
                "type", "integer",
                "description", description,
                "minimum", minimum,
                "maximum", maximum
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

    @FunctionalInterface
    private interface ToolHandler {
        Map<String, Object> handle(McpSchema.CallToolRequest request) throws IOException;
    }
}
