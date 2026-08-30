package com.github.minecraft_ta.totalDebugCompanion.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionMcpToolCatalogTest {
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

        CompanionMcpToolCatalog.validateRequest(request(
                "find_usages",
                Map.of("target", Map.of("kind", "class", "owner", "example.Owner"))
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
}
