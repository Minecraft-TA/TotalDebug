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
                    + "Minecraft JVM. Poll jobs_get until the job reaches a terminal state.";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final List<McpSchema.Tool> TOOLS = List.of(
            tool(
                    "status",
                    "Report whether Companion and Minecraft are connected.",
                    objectSchema(Map.of(), List.of())
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
                    "Search the exact class index generated from the connected Minecraft runtime.",
                    objectSchema(
                            Map.of(
                                    "query", stringSchema("Class name fragment."),
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
            Throwable failure
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("companion_available", false);
        value.put("minecraft_connected", false);
        value.put("error", safeMessage(failure));
        if ("status".equals(tool)) {
            return result(value, false);
        }
        value.put("hint", "Start TotalDebug Companion, then call the tool again in this Codex task.");
        return result(value, true);
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

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
