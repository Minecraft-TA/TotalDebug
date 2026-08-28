package com.github.minecraft_ta.totalDebugCompanion;

import java.util.Arrays;

/** Stable names for complete Companion UI states rendered by {@link UiDevHarness}. */
enum UiRenderScenario {
    MAIN("main", "Main window with the code editor selected"),
    INACTIVE_TABS("inactive-tabs", "Main window with inactive editor tabs visible"),
    TAB_HOVER("tab-hover", "Inactive editor tab with its hover and close affordance visible"),
    EDITOR_CURRENT_LINE("editor-current-line", "Current editor line and hierarchy gutter markers"),
    BREAKPOINT_EDITOR("breakpoint-editor", "Conditional breakpoint marker and anchored editor"),
    METHOD_BREAKPOINT("method-breakpoint", "Method-entry breakpoint marker on a declaration"),
    DEBUGGER_LOCATION("debugger-location", "Breakpoint and selected debugger-frame source rows"),
    DEBUGGER("debugger", "Paused debugger window with frames and variables"),
    BREAKPOINTS("breakpoints", "Persisted breakpoint list and selected breakpoint details"),
    HIERARCHY_ONE("hierarchy-one", "Hierarchy preview with one implementation"),
    HIERARCHY_MANY("hierarchy-many", "Hierarchy preview with several implementations"),
    IMPLEMENTATION_CHOOSER("implementation-chooser", "Implementation chooser with results"),
    SEARCH_EMPTY("search-empty", "Search Everywhere before a query is entered"),
    SEARCH_RESULTS("search-results", "Search Everywhere with selected results"),
    MODULE_FILTER("module-filter", "Search Everywhere module selection popup"),
    USAGES_RESULTS("usages-results", "Find Usages with indexed results"),
    SETTINGS("settings", "Settings controls"),
    SERVICE_STATUS("service-status", "Published Game and MCP states with the MCP detail popup"),
    INDEXING("indexing", "Runtime index activity in the status bar");

    private final String id;
    private final String description;

    UiRenderScenario(String id, String description) {
        this.id = id;
        this.description = description;
    }

    String id() {
        return this.id;
    }

    String description() {
        return this.description;
    }

    static UiRenderScenario parse(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.id.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown UI scenario '" + value + "'. Available: "
                                + Arrays.stream(values()).map(UiRenderScenario::id).toList()
                ));
    }
}
