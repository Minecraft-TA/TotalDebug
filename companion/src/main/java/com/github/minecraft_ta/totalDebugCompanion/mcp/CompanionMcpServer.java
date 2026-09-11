package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
    private final Path endpointDescriptor;
    private final CodeModeJobService jobs;
    private final CompanionMcpRuntimeSource runtimeSource;
    private final CompanionMcpSearchService search;
    private final DebuggerMcpService debugger;
    private final int port;
    private final Supplier<ProjectScope> project;
    private HttpServletStreamableServerTransportProvider transportProvider;
    private McpSyncServer mcpServer;
    private Tomcat tomcat;
    private String endpointUrl;
    private boolean closed;

    public CompanionMcpServer(Path dataDirectory, CodeModeJobService jobs) {
        this(dataDirectory, jobs, MCP_PORT);
    }

    CompanionMcpServer(Path dataDirectory, CodeModeJobService jobs, int port) {
        this(dataDirectory, jobs, port, new DebuggerMcpService(CompanionApp::getDebuggerController,
                name -> CompanionApp.getDecompilationService().loadDebugSource(name)), CompanionApp::requireProject);
    }

    CompanionMcpServer(Path dataDirectory, CodeModeJobService jobs, int port, DebuggerMcpService debugger, Supplier<ProjectScope> project) {
        this.project = Objects.requireNonNull(project);
        this.dataDirectory = normalize(dataDirectory);
        this.endpointDescriptor = new com.github.minecraft_ta.totaldebug.storage.AppPaths(this.dataDirectory).mcpEndpoint();
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.debugger = Objects.requireNonNull(debugger, "debugger");
        this.runtimeSource = new CompanionMcpRuntimeSource(CompanionApp::getDecompilationService);
        this.search = new CompanionMcpSearchService(
                CompanionClassIndex::get,
                sourceId -> CompanionApp.getRuntimeSourceCatalog().moduleFor(sourceId)
        );
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
                .validateToolInputs(false)
                .tools(CompanionMcpToolCatalog.specifications(this::callTool))
                .build();

        Path tomcatDirectory = new com.github.minecraft_ta.totaldebug.storage.AppPaths(this.dataDirectory).mcpCache();
        Files.createDirectories(tomcatDirectory);

        Tomcat embeddedTomcat = new Tomcat();
        embeddedTomcat.setBaseDir(tomcatDirectory.toString());
        embeddedTomcat.setHostname("127.0.0.1");
        Connector connector = new Connector();
        connector.setPort(this.port);
        connector.setProperty("address", "127.0.0.1");
        embeddedTomcat.setConnector(connector);

        Context context = embeddedTomcat.addContext("", tomcatDirectory.toString());
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

    public void prepareProjectSwitch() { this.jobs.prepareProjectSwitch(); }

    private McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
        try {
            CompanionMcpToolCatalog.validateRequest(request);
            ProjectScope project = CompanionMcpToolCatalog.projectBound(request.name()) ? this.project.get() : null;
            Map<String, Object> result = switch (request.name()) {
                case "status" -> status();
                case "client_code_execute" -> execute(request.arguments(), CodeModeJobService.ExecutionSide.CLIENT, project);
                case "server_code_execute" -> execute(request.arguments(), CodeModeJobService.ExecutionSide.SERVER, project);
                case "job_wait" -> this.jobs.waitFor(
                        requiredString(request.arguments(), "job_id"),
                        optionalInteger(request.arguments(), "wait_ms", 30_000)
                ).responseMap();
                case "job_cancel" -> Map.of(
                        "job_id",
                        requiredString(request.arguments(), "job_id"),
                        "cancellation_requested",
                        this.jobs.cancel(requiredString(request.arguments(), "job_id"))
                );
                case "job_source" -> Map.of(
                        "source",
                        this.jobs.source(requiredString(request.arguments(), "job_id"))
                );
                case "search_classes" -> this.search.searchClasses(requiredString(request.arguments(), "query"));
                case "runtime_source" -> this.runtimeSource.source(
                        requiredObject(request.arguments(), "target")
                );
                case "search_symbols" -> this.search.searchSymbols(
                        optionalString(request.arguments(), "query"),
                        optionalString(request.arguments(), "owner")
                );
                case "find_usages" -> this.search.findUsages(requiredObject(request.arguments(), "target"));
                case "search_literals" -> this.search.searchLiterals(requiredString(request.arguments(), "query"));
                case "debugger_status", "debugger_wait", "debugger_control", "debugger_threads",
                     "debugger_breakpoints", "debugger_breakpoint_set", "debugger_breakpoint_remove",
                     "debugger_frames", "debugger_variables", "debugger_evaluate", "debugger_evaluation_wait", "debugger_evaluation_cancel" ->
                        this.debugger.call(request.name(), request.arguments(), project);
                default -> throw new IllegalArgumentException("Unknown MCP tool: " + request.name());
            };
            if (project != null) project.requireActive();
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
                "minecraft_connected", this.jobs.isAvailable(),
                "debugger_connected", CompanionApp.isDebuggerConnected()
        );
    }

    private Map<String, Object> execute(
            Map<String, Object> arguments,
            CodeModeJobService.ExecutionSide side, ProjectScope project
    ) {
        String code = requiredString(arguments, "code");
        List<String> imports = optionalStringList(arguments, "imports");
        CodeModeJobService.ExecutionEnvironment environment = parseEnum(
                CodeModeJobService.ExecutionEnvironment.class,
                Objects.requireNonNullElse(optionalString(arguments, "environment"), "thread")
        );
        CodeModeJobService.JobSnapshot submitted = project.admit(() -> this.jobs.submit(code, imports, side, environment));
        return this.jobs.waitFor(
                submitted.jobId(),
                optionalInteger(arguments, "wait_ms", 10_000)
        ).responseMap();
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return string;
    }

    private static String optionalString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return string;
    }

    private static Map<String, Object> requiredObject(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(name + " must have string keys");
            }
            result.put(key, entry.getValue());
        }
        return Map.copyOf(result);
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
