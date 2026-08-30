package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

final class CompanionMcpToolCatalog {
    static final String SERVER_NAME = "totaldebug-companion";
    static final String SERVER_VERSION = "1.0.0";
    static final String INSTRUCTIONS =
            "Use code_execute for runtime facts. It executes unrestricted Java inside the connected "
                    + "Minecraft JVM and waits briefly for a result. Use jobs_wait for longer jobs.";

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final List<McpSchema.Tool> TOOLS = List.of(
            tool(
                    "status",
                    "Report whether Companion and Minecraft are connected.",
                    objectSchema(Map.of(), List.of())
            ),
            tool(
                    "code_execute",
                    "Submit unrestricted Java statements for execution inside the connected Minecraft JVM. "
                            + "Use result(value) for structured data and log or logln for supplemental text.",
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
                                    ),
                                    "wait_ms", integerSchema(
                                            "How long to wait for completion before returning the current state.",
                                            0,
                                            CodeModeJobService.MAX_WAIT_MILLISECONDS
                                    )
                            ),
                            List.of("code")
                    )
            ),
            tool(
                    "jobs_get",
                    "Get one code job and its terminal output or error.",
                    objectSchema(
                            Map.of("job_id", stringSchema("UUID returned by code_execute.")),
                            List.of("job_id")
                    )
            ),
            tool(
                    "jobs_wait",
                    "Wait for one code job to finish, or return its current state when the timeout expires.",
                    objectSchema(
                            Map.of(
                                    "job_id", stringSchema("UUID returned by code_execute."),
                                    "wait_ms", integerSchema(
                                            "Maximum wait in milliseconds.",
                                            0,
                                            CodeModeJobService.MAX_WAIT_MILLISECONDS
                                    )
                            ),
                            List.of("job_id")
                    )
            ),
            tool(
                    "jobs_list",
                    "List recent code jobs in newest-first order.",
                    objectSchema(
                            Map.of("limit", integerSchema("Maximum jobs to return, from 1 to 256.", 1, 256)),
                            List.of()
                    )
            ),
            tool(
                    "jobs_cancel",
                    "Request cooperative cancellation of a running code job.",
                    objectSchema(
                            Map.of("job_id", stringSchema("UUID returned by code_execute.")),
                            List.of("job_id")
                    )
            ),
            tool(
                    "search_classes",
                    "Resolve an exact binary name or search class names in the connected Minecraft runtime.",
                    objectSchema(
                            Map.of(
                                    "query", stringSchema("Binary name or class-name fragment; package-qualified queries are supported."),
                                    "limit", integerSchema("Maximum matches to return, from 1 to 200.", 1, 200)
                            ),
                            List.of("query")
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
                    )
            )
    );

    private CompanionMcpToolCatalog() {
    }

    static List<McpServerFeatures.SyncToolSpecification> specifications(
            Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> handler
    ) {
        Objects.requireNonNull(handler, "handler");
        return TOOLS.stream()
                .map(tool -> McpServerFeatures.SyncToolSpecification.builder()
                        .tool(tool)
                        .callHandler((exchange, request) -> handler.apply(request))
                        .build())
                .toList();
    }

    static McpSchema.CallToolResult result(Map<String, Object> value, boolean error) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(GSON.toJson(value))
                .structuredContent(value)
                .isError(error)
                .build();
    }

    static McpSchema.CallToolResult companionUnavailable(
            String tool,
            ConnectionFailure failure
    ) {
        if ("status".equals(tool)) {
            return result(Map.of(
                    "companion_available", false,
                    "minecraft_connected", false
            ), false);
        }
        return result(Map.of("error", failure.asMap()), true);
    }

    private static McpSchema.Tool tool(String name, String description, Map<String, Object> inputSchema) {
        return McpSchema.Tool.builder(name, inputSchema)
                .description(description)
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

    record ConnectionFailure(
            String code,
            String stage,
            String endpointHealth,
            boolean retryable
    ) {
        ConnectionFailure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(endpointHealth, "endpointHealth");
        }

        Map<String, Object> asMap() {
            return Map.of(
                    "code", this.code,
                    "stage", this.stage,
                    "endpoint_health", this.endpointHealth,
                    "retryable", this.retryable
            );
        }
    }
}
