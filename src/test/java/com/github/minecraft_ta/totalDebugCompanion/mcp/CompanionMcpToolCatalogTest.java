package com.github.minecraft_ta.totalDebugCompanion.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionMcpToolCatalogTest {
    @Test
    void everyToolAdvertisesAnOutputSchemaForItsSuccessAndErrorResults() {
        Map<String, Object> module = Map.of(
                "id", "fixture-mod",
                "name", "Fixture Mod",
                "kind", "mod"
        );
        Map<String, Object> connectionFailure = Map.of(
                "code", "companion_unreachable",
                "stage", "tcp_connect",
                "endpoint_health", "unreachable",
                "retryable", true
        );
        Map<String, Object> job = Map.of(
                "job_id", "job-1",
                "state", "succeeded",
                "logs", "proof\n",
                "result", Map.of("answer", 42)
        );
        Map<String, Object> source = Map.of("source", "class Fixture {}");
        Map<String, Object> methodSourceTarget = Map.of(
                "kind", "method",
                "owner", "example.Fixture",
                "name", "run",
                "descriptor", "()V"
        );
        Map<String, Map<String, Object>> samples = Map.ofEntries(
                Map.entry("status", Map.of(
                        "companion_available", true,
                        "minecraft_connected", true,
                        "debugger_connected", false
                )),
                Map.entry("client_code_execute", job),
                Map.entry("server_code_execute", job),
                Map.entry("job_wait", job),
                Map.entry("job_cancel", Map.of(
                        "job_id", "job-1",
                        "cancellation_requested", true
                )),
                Map.entry("job_source", source),
                Map.entry("search_classes", Map.of("classes", List.of(Map.of(
                        "binary_name", "example.Fixture",
                        "kind", "class",
                        "modifiers", List.of("public", "final"),
                        "module", module
                )))),
                Map.entry("runtime_source", Map.of(
                        "target", methodSourceTarget,
                        "package", "example",
                        "imports", List.of("java.util.List"),
                        "start_line", 12,
                        "source", "public void run() {}"
                )),
                Map.entry("search_symbols", Map.of("symbols", List.of(Map.of(
                        "kind", "method",
                        "owner", "example.Fixture",
                        "name", "run",
                        "descriptor", "()V",
                        "modifiers", List.of("public"),
                        "module", module
                )))),
                Map.entry("find_usages", Map.of("usages", List.of(Map.of(
                        "source_target", Map.of(
                                "kind", "method",
                                "owner", "example.Caller",
                                "name", "call",
                                "descriptor", "()V"
                        ),
                        "relationships", List.of("method_invoke"),
                        "occurrences", 1,
                        "module", module
                )))),
                Map.entry("search_literals", Map.of(
                        "literals", List.of(Map.of("value", "proof", "modules", List.of(module))),
                        "truncated", true
                ))
        );

        List<McpSchema.Tool> tools = CompanionMcpToolCatalog.specifications(request -> null).stream()
                .map(specification -> specification.tool())
                .filter(tool -> !tool.name().startsWith("debugger_"))
                .toList();
        assertEquals(samples.keySet(), tools.stream().map(McpSchema.Tool::name).collect(java.util.stream.Collectors.toSet()));

        var validator = McpJsonDefaults.getSchemaValidator();
        for (McpSchema.Tool tool : tools) {
            assertNotNull(tool.outputSchema(), tool.name());
            validator.assertConforms(tool.name() + " outputSchema", tool.outputSchema());
            assertValid(tool, samples.get(tool.name()));
            assertValid(tool, Map.of("error", "fixture failure"));
            assertValid(tool, Map.of("error", connectionFailure));
        }
    }

    @Test
    void symbolSearchRequiresQueryOrOwner() {
        Map<String, Object> schema = tool("search_symbols").inputSchema();

        assertEquals(
                List.of(
                        Map.of("required", List.of("query")),
                        Map.of("required", List.of("owner"))
                ),
                schema.get("anyOf")
        );
    }

    @Test
    void usageTargetsAdvertiseTheirExactIdentityRequirements() {
        Map<?, ?> target = (Map<?, ?>) ((Map<?, ?>) tool("find_usages").inputSchema().get("properties"))
                .get("target");
        List<?> variants = (List<?>) target.get("oneOf");

        assertEquals(3, variants.size());
        assertEquals(List.of("kind", "owner"), ((Map<?, ?>) variants.get(0)).get("required"));
        assertEquals(
                List.of("kind", "owner", "name", "descriptor"),
                ((Map<?, ?>) variants.get(1)).get("required")
        );
        assertEquals(
                List.of("kind", "owner", "name", "descriptor"),
                ((Map<?, ?>) variants.get(2)).get("required")
        );
    }

    @Test
    void runtimeSourceAdvertisesClassAndMemberScopes() {
        Map<?, ?> target = (Map<?, ?>) ((Map<?, ?>) tool("runtime_source").inputSchema().get("properties"))
                .get("target");
        List<?> variants = (List<?>) target.get("oneOf");

        assertEquals(4, variants.size());
        assertEquals(List.of("kind", "binary_name"), ((Map<?, ?>) variants.getFirst()).get("required"));
        for (int index = 1; index < variants.size(); index++) {
            assertEquals(
                    List.of("kind", "owner", "name", "descriptor"),
                    ((Map<?, ?>) variants.get(index)).get("required")
            );
        }
    }

    @Test
    void handlerValidationEnforcesTheAdvertisedSchemas() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CompanionMcpToolCatalog.validateRequest(request("search_symbols", Map.of()))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CompanionMcpToolCatalog.validateRequest(request(
                        "client_code_execute",
                        Map.of("code", "return 1;", "unexpected", true)
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CompanionMcpToolCatalog.validateRequest(request(
                        "find_usages",
                        Map.of("target", Map.of("kind", "method", "owner", "example.Owner"))
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CompanionMcpToolCatalog.validateRequest(request(
                        "runtime_source",
                        Map.of("target", Map.of("kind", "method", "owner", "example.Owner"))
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CompanionMcpToolCatalog.validateRequest(request(
                        "runtime_source",
                        Map.of("target", Map.of(
                                "kind", "class",
                                "binary_name", "example.Owner",
                                "owner", "example.Owner"
                        ))
                ))
        );

        CompanionMcpToolCatalog.validateRequest(request(
                "find_usages",
                Map.of("target", Map.of("kind", "class", "owner", "example.Owner"))
        ));
        CompanionMcpToolCatalog.validateRequest(request(
                "runtime_source",
                Map.of("target", Map.of("kind", "class", "binary_name", "example.Owner"))
        ));
    }

    private static McpSchema.CallToolRequest request(String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }

    private static McpSchema.Tool tool(String name) {
        return CompanionMcpToolCatalog.specifications(request -> null).stream()
                .map(specification -> specification.tool())
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertValid(McpSchema.Tool tool, Map<String, Object> value) {
        var validation = McpJsonDefaults.getSchemaValidator().validate(tool.outputSchema(), value);
        assertTrue(validation.valid(), tool.name() + ": " + validation.errorMessage());
    }
}
