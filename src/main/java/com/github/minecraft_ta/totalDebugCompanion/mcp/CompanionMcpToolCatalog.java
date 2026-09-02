package com.github.minecraft_ta.totalDebugCompanion.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

final class CompanionMcpToolCatalog {
    static final String SERVER_NAME = "totaldebug-companion";
    static final String SERVER_VERSION = "2.0.0";
    static final String INSTRUCTIONS =
            "Resolve exact symbols before finding usages. Read the relevant source_target values with "
                    + "runtime_source, and request class scope only when a member does not provide enough context. "
                    + "Use client_code_execute for client runtime facts and server_code_execute for server runtime "
                    + "facts. Both execute unrestricted Java. Return maps, collections, arrays, records, and scalar "
                    + "values directly so the result remains structured. Debugger tools control the same session as "
                    + "Companion's UI. Carry the current pause_id into inspections and continue/step commands; use "
                    + "debugger_wait to wait for state changes.";

    static final Map<String, Object> EXECUTE_INPUT_SCHEMA = objectSchema(
            Map.of(
                    "code", stringSchema("Body of a Java method that returns the structured result."),
                    "imports", arraySchema(
                            stringSchema("Java import target, for example java.util.Map or static java.lang.Math.*.")
                    ),
                    "environment", enumSchema(
                            "Execution thread. Tick jobs cannot be interrupted safely after starting.",
                            "thread", "pre_tick", "post_tick"
                    ),
                    "wait_ms", integerSchema(
                            "How long to wait before returning the current job state.",
                            0,
                            CodeModeJobService.MAX_WAIT_MILLISECONDS
                    )
            ),
            List.of("code")
    );
    private static final List<McpSchema.Tool> TOOLS = List.of(
            tool(
                    "status",
                    "Report Companion, Minecraft, and debugger connectivity.",
                    emptySchema(),
                    statusOutputSchema()
            ),
            tool(
                    "client_code_execute",
                    "Execute a value-returning Java body in the connected Minecraft client JVM.",
                    EXECUTE_INPUT_SCHEMA,
                    jobOutputSchema()
            ),
            tool(
                    "server_code_execute",
                    "Execute a value-returning Java body with server authority.",
                    EXECUTE_INPUT_SCHEMA,
                    jobOutputSchema()
            ),
            tool(
                    "job_wait",
                    "Wait for one code job to finish or return its current state at the timeout.",
                    objectSchema(
                            Map.of(
                                    "job_id", stringSchema("Code job identifier."),
                                    "wait_ms", integerSchema(
                                            "Maximum wait in milliseconds.",
                                            0,
                                            CodeModeJobService.MAX_WAIT_MILLISECONDS
                                    )
                            ),
                            List.of("job_id")
                    ),
                    jobOutputSchema()
            ),
            tool(
                    "job_cancel",
                    "Request cancellation of one code job.",
                    jobIdSchema(),
                    jobCancellationOutputSchema()
            ),
            tool(
                    "job_source",
                    "Return the exact generated Java source for one code job.",
                    jobIdSchema(),
                    sourceOutputSchema()
            ),
            tool(
                    "search_classes",
                    "Resolve an exact binary name first, otherwise search full binary names by literal text.",
                    objectSchema(
                            Map.of("query", stringSchema("Exact binary name or case-insensitive name fragment.")),
                            List.of("query")
                    ),
                    boundedListOutputSchema("classes", classOutputSchema())
            ),
            tool(
                    "runtime_source",
                    "Return one exact class or member source scope from a runtime class.",
                    objectSchema(
                            Map.of("target", runtimeSourceTargetSchema()),
                            List.of("target")
                    ),
                    runtimeSourceOutputSchema()
            ),
            tool(
                    "search_symbols",
                    "Search field and method declarations, or list declarations owned by one class.",
                    searchSymbolsSchema(),
                    boundedListOutputSchema("symbols", symbolOutputSchema())
            ),
            tool(
                    "find_usages",
                    "Find declaration sites that reference one exact class, field, or method.",
                    objectSchema(
                            Map.of("target", usageTargetSchema()),
                            List.of("target")
                    ),
                    boundedListOutputSchema("usages", usageOutputSchema())
            ),
            tool(
                    "search_literals",
                    "Search indexed Java string literal values by case-sensitive text.",
                    objectSchema(Map.of("query", stringSchema("Literal text fragment.")), List.of("query")),
                    boundedListOutputSchema("literals", literalOutputSchema())
            )
    );
    private static final Map<String, McpSchema.Tool> TOOLS_BY_NAME = indexTools();

    private CompanionMcpToolCatalog() {
    }

    static List<McpServerFeatures.SyncToolSpecification> specifications(
            Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> handler
    ) {
        Objects.requireNonNull(handler, "handler");
        return allTools().stream()
                .map(tool -> McpServerFeatures.SyncToolSpecification.builder()
                        .tool(tool)
                        .callHandler((exchange, request) -> handler.apply(request))
                        .build())
                .toList();
    }

    static McpSchema.CallToolResult result(Map<String, Object> value, boolean error) {
        return McpSchema.CallToolResult.builder()
                .structuredContent(value)
                .isError(error)
                .build();
    }

    static void validateRequest(McpSchema.CallToolRequest request) {
        Objects.requireNonNull(request, "request");
        McpSchema.Tool tool = TOOLS_BY_NAME.get(request.name());
        if (tool == null) {
            throw new IllegalArgumentException("Unknown MCP tool: " + request.name());
        }
        validateSchema(tool.inputSchema(), request.arguments(), "arguments");
    }

    static McpSchema.CallToolResult companionUnavailable(String tool, ConnectionFailure failure) {
        if ("status".equals(tool)) {
            return result(Map.of(
                    "companion_available", false,
                    "minecraft_connected", false,
                    "debugger_connected", false
            ), false);
        }
        return result(Map.of("error", failure.asMap()), true);
    }

    static McpSchema.Tool tool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema
    ) {
        return McpSchema.Tool.builder(name, inputSchema)
                .description(description)
                .outputSchema(outputSchema)
                .build();
    }

    private static Map<String, Object> statusOutputSchema() {
        return toolOutputSchema(objectSchema(
                Map.of(
                        "companion_available", booleanSchema("Whether Companion is reachable."),
                        "minecraft_connected", booleanSchema("Whether Minecraft is connected to Companion."),
                        "debugger_connected", booleanSchema("Whether the debugger is attached.")
                ),
                List.of("companion_available", "minecraft_connected", "debugger_connected")
        ));
    }

    private static Map<String, Object> jobOutputSchema() {
        return toolOutputSchema(objectSchema(
                Map.of(
                        "job_id", stringSchema("Code job identifier."),
                        "state", enumSchema(
                                "Current code job state.",
                                "compiling", "running", "cancelling", "succeeded", "failed", "cancelled",
                                "disconnected"
                        ),
                        "logs", stringValueSchema("Output written by the code body."),
                        "logs_truncated", booleanSchema("Whether the returned logs are incomplete."),
                        "logs_total_characters", nonNegativeIntegerSchema(
                                "Complete log length before transport truncation."
                        ),
                        "result", Map.of(),
                        "error", stringValueSchema("Failure diagnostic for a completed job."),
                        "error_truncated", booleanSchema("Whether the returned diagnostic is incomplete."),
                        "error_total_characters", nonNegativeIntegerSchema(
                                "Complete diagnostic length before transport truncation."
                        )
                ),
                List.of("job_id", "state")
        ));
    }

    private static Map<String, Object> jobCancellationOutputSchema() {
        return toolOutputSchema(objectSchema(
                Map.of(
                        "job_id", stringSchema("Code job identifier."),
                        "cancellation_requested", booleanSchema("Whether cancellation was requested.")
                ),
                List.of("job_id", "cancellation_requested")
        ));
    }

    private static Map<String, Object> sourceOutputSchema() {
        return toolOutputSchema(objectSchema(
                Map.of("source", stringSchema("Complete Java source.")),
                List.of("source")
        ));
    }

    private static Map<String, Object> runtimeSourceOutputSchema() {
        return toolOutputSchema(objectSchema(
                Map.of(
                        "target", runtimeSourceTargetSchema(),
                        "package", stringSchema("Declared Java package."),
                        "imports", arraySchema(stringSchema(
                                "Java import target, prefixed with static when applicable."
                        )),
                        "start_line", integerSchema("First line of the returned scope.", 1, Integer.MAX_VALUE),
                        "source", stringSchema("Exact decompiled source text for the requested scope.")
                ),
                List.of("target", "imports", "start_line", "source")
        ));
    }

    private static Map<String, Object> boundedListOutputSchema(String key, Map<String, Object> itemSchema) {
        return toolOutputSchema(objectSchema(
                Map.of(
                        key, arraySchema(itemSchema),
                        "truncated", Map.of("type", "boolean", "const", true)
                ),
                List.of(key)
        ));
    }

    private static Map<String, Object> classOutputSchema() {
        return objectSchema(
                Map.of(
                        "binary_name", stringSchema("Java binary name."),
                        "kind", enumSchema(
                                "Java class kind.",
                                "annotation", "enum", "record", "interface", "module", "class"
                        ),
                        "modifiers", modifiersOutputSchema(),
                        "module", moduleOutputSchema()
                ),
                List.of("binary_name", "kind", "module")
        );
    }

    private static Map<String, Object> symbolOutputSchema() {
        return objectSchema(
                Map.of(
                        "kind", enumSchema("Member kind.", "field", "method"),
                        "owner", stringSchema("Owning class binary name."),
                        "name", stringSchema("Member name."),
                        "descriptor", stringSchema("JVM member descriptor."),
                        "modifiers", modifiersOutputSchema(),
                        "module", moduleOutputSchema()
                ),
                List.of("kind", "owner", "name", "descriptor", "module")
        );
    }

    private static Map<String, Object> usageOutputSchema() {
        return objectSchema(
                Map.of(
                        "source_target", runtimeSourceTargetSchema(),
                        "relationships", arraySchema(enumSchema(
                                "Relationship to the requested target.",
                                "class_hierarchy", "class_declaration", "class_annotation_or_metadata",
                                "class_runtime_type", "class_member_usage", "field_read", "field_write",
                                "field_handle", "method_invoke", "method_handle", "string_literal"
                        )),
                        "occurrences", integerSchema("Number of matching references.", 1, Integer.MAX_VALUE),
                        "module", moduleOutputSchema()
                ),
                List.of("source_target", "relationships", "occurrences", "module")
        );
    }

    private static Map<String, Object> literalOutputSchema() {
        return objectSchema(
                Map.of(
                        "value", stringValueSchema("Indexed Java string literal."),
                        "modules", arraySchema(moduleOutputSchema())
                ),
                List.of("value", "modules")
        );
    }

    private static Map<String, Object> moduleOutputSchema() {
        return objectSchema(
                Map.of(
                        "id", stringSchema("Runtime module identifier."),
                        "name", stringSchema("Runtime module display name."),
                        "kind", enumSchema("Runtime module kind.", "platform", "mod", "library", "java_runtime")
                ),
                List.of("id", "name", "kind")
        );
    }

    private static Map<String, Object> modifiersOutputSchema() {
        return arraySchema(enumSchema(
                "Decoded Java modifier.",
                "public", "protected", "private", "abstract", "static", "final", "synthetic", "deprecated",
                "volatile", "transient", "enum", "synchronized", "bridge", "varargs", "native", "strictfp"
        ));
    }

    static Map<String, Object> toolOutputSchema(Map<String, Object> successSchema) {
        return Map.of("oneOf", List.of(successSchema, errorOutputSchema()));
    }

    private static Map<String, Object> errorOutputSchema() {
        return objectSchema(
                Map.of("error", Map.of("oneOf", List.of(
                        stringSchema("Tool failure diagnostic."),
                        objectSchema(
                                Map.of(
                                        "code", stringSchema("Stable connection failure code."),
                                        "stage", stringSchema("Connection stage that failed."),
                                        "endpoint_health", stringSchema("Observed Companion endpoint health."),
                                        "retryable", booleanSchema("Whether retrying can succeed.")
                                ),
                                List.of("code", "stage", "endpoint_health", "retryable")
                        )
                ))),
                List.of("error")
        );
    }

    private static Map<String, Object> emptySchema() {
        return objectSchema(Map.of(), List.of());
    }

    private static Map<String, Object> jobIdSchema() {
        return objectSchema(Map.of("job_id", stringSchema("Code job identifier.")), List.of("job_id"));
    }

    private static Map<String, Object> searchSymbolsSchema() {
        Map<String, Object> schema = objectSchema(
                Map.of(
                        "query", stringSchema("Case-insensitive literal member-name fragment."),
                        "owner", stringSchema("Exact owning class binary name.")
                ),
                List.of()
        );
        schema.put("anyOf", List.of(
                Map.of("required", List.of("query")),
                Map.of("required", List.of("owner"))
        ));
        return schema;
    }

    private static Map<String, Object> usageTargetSchema() {
        return Map.of("oneOf", List.of(
                targetVariant(
                        "class",
                        Map.of("owner", stringSchema("Exact class binary name.")),
                        List.of("owner")
                ),
                targetVariant("field", memberTargetProperties(), List.of("owner", "name", "descriptor")),
                targetVariant("method", memberTargetProperties(), List.of("owner", "name", "descriptor"))
        ));
    }

    private static Map<String, Object> runtimeSourceTargetSchema() {
        return Map.of("oneOf", List.of(
                targetVariant(
                        "class",
                        Map.of("binary_name", stringSchema("Exact class binary name.")),
                        List.of("binary_name")
                ),
                targetVariant("field", memberTargetProperties(), List.of("owner", "name", "descriptor")),
                targetVariant("method", memberTargetProperties(), List.of("owner", "name", "descriptor")),
                targetVariant(
                        "record_component",
                        memberTargetProperties(),
                        List.of("owner", "name", "descriptor")
                )
        ));
    }

    private static Map<String, Object> memberTargetProperties() {
        return Map.of(
                "owner", stringSchema("Exact owning class binary name."),
                "name", stringSchema("Exact member name."),
                "descriptor", stringSchema("Exact JVM member descriptor.")
        );
    }

    private static Map<String, Object> targetVariant(
            String kind,
            Map<String, Object> properties,
            List<String> required
    ) {
        Map<String, Object> variantProperties = new LinkedHashMap<>();
        variantProperties.put("kind", Map.of("type", "string", "const", kind));
        variantProperties.putAll(properties);

        List<String> variantRequired = new ArrayList<>(required.size() + 1);
        variantRequired.add("kind");
        variantRequired.addAll(required);
        return objectSchema(variantProperties, List.copyOf(variantRequired));
    }

    static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    static Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description, "minLength", 1);
    }

    static Map<String, Object> stringValueSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> booleanSchema(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    static Map<String, Object> arraySchema(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    static Map<String, Object> enumSchema(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }

    static Map<String, Object> integerSchema(String description, int minimum, int maximum) {
        return Map.of(
                "type", "integer",
                "description", description,
                "minimum", minimum,
                "maximum", maximum
        );
    }

    private static Map<String, Object> nonNegativeIntegerSchema(String description) {
        return Map.of(
                "type", "integer",
                "description", description,
                "minimum", 0
        );
    }

    private static List<McpSchema.Tool> allTools() {
        return java.util.stream.Stream.concat(TOOLS.stream(), DebuggerMcpToolCatalog.tools().stream()).toList();
    }

    private static Map<String, McpSchema.Tool> indexTools() {
        Map<String, McpSchema.Tool> indexed = new LinkedHashMap<>();
        for (McpSchema.Tool tool : allTools()) {
            indexed.put(tool.name(), tool);
        }
        return Map.copyOf(indexed);
    }

    private static void validateSchema(Map<String, Object> schema, Object value, String path) {
        Object expected = schema.get("const");
        if (schema.containsKey("const") && !Objects.equals(expected, value)) {
            throw new IllegalArgumentException(path + " must be " + expected);
        }

        if (schema.get("enum") instanceof List<?> values && !values.contains(value)) {
            throw new IllegalArgumentException(path + " must be one of " + values);
        }

        Object type = schema.get("type");
        if (type instanceof String typeName) {
            switch (typeName) {
                case "object" -> requireObject(value, path);
                case "array" -> requireArray(value, path);
                case "string" -> requireString(value, schema, path);
                case "integer" -> requireInteger(value, schema, path);
                case "boolean" -> {
                    if (!(value instanceof Boolean)) throw new IllegalArgumentException(path + " must be a boolean");
                }
                default -> throw new IllegalStateException("Unsupported input schema type: " + typeName);
            }
        }

        if (schema.containsKey("properties") || schema.containsKey("required")) {
            validateObject(schema, requireObject(value, path), path);
        }
        if (schema.get("items") instanceof Map<?, ?> itemSchema) {
            List<?> items = requireArray(value, path);
            for (int index = 0; index < items.size(); index++) {
                validateSchema(schemaMap(itemSchema), items.get(index), path + "[" + index + "]");
            }
        }
        validateAlternatives(schema, value, path, "anyOf", false);
        validateAlternatives(schema, value, path, "oneOf", true);
    }

    private static void validateObject(Map<String, Object> schema, Map<?, ?> value, String path) {
        Map<String, Object> properties = schemaMap(schema.getOrDefault("properties", Map.of()));
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            for (Object key : value.keySet()) {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException(path + " must have string keys");
                }
                if (!properties.containsKey(stringKey)) {
                    throw new IllegalArgumentException(path + "." + stringKey + " is not allowed");
                }
            }
        }
        if (schema.get("required") instanceof List<?> required) {
            for (Object name : required) {
                if (!(name instanceof String stringName) || !value.containsKey(stringName)) {
                    throw new IllegalArgumentException(path + "." + name + " is required");
                }
            }
        }
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            if (value.containsKey(property.getKey())) {
                validateSchema(
                        schemaMap(property.getValue()),
                        value.get(property.getKey()),
                        path + "." + property.getKey()
                );
            }
        }
    }

    private static void validateAlternatives(
            Map<String, Object> schema,
            Object value,
            String path,
            String keyword,
            boolean exactlyOne
    ) {
        if (!(schema.get(keyword) instanceof List<?> alternatives)) {
            return;
        }
        int matches = 0;
        for (Object alternative : alternatives) {
            try {
                validateSchema(schemaMap(alternative), value, path);
                matches++;
            } catch (IllegalArgumentException ignored) {
            }
        }
        if ((exactlyOne && matches != 1) || (!exactlyOne && matches == 0)) {
            throw new IllegalArgumentException(
                    path + (exactlyOne
                            ? " must match exactly one allowed shape"
                            : " must match at least one allowed shape")
            );
        }
    }

    private static Map<?, ?> requireObject(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return map;
    }

    private static List<?> requireArray(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        return list;
    }

    private static void requireString(Object value, Map<String, Object> schema, String path) {
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        if (schema.get("minLength") instanceof Number minimum && string.length() < minimum.intValue()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }

    private static void requireInteger(Object value, Map<String, Object> schema, String path) {
        if (!(value instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        double integer = number.doubleValue();
        if (schema.get("minimum") instanceof Number minimum && integer < minimum.doubleValue()) {
            throw new IllegalArgumentException(path + " must be at least " + minimum);
        }
        if (schema.get("maximum") instanceof Number maximum && integer > maximum.doubleValue()) {
            throw new IllegalArgumentException(path + " must be at most " + maximum);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Input schema must contain an object");
        }
        return (Map<String, Object>) map;
    }

    record ConnectionFailure(String code, String stage, String endpointHealth, boolean retryable) {
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
