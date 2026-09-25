package com.github.minecraft_ta.totalDebugCompanion;

import java.util.Arrays;

/** Stable names for complete Companion UI states rendered by {@link UiDevHarness}. */
enum UiRenderScenario {
    MAIN("main", "Main window with the code editor selected"),
    NEW_SCRIPT("new-script", "Compact script creation popup with a selected Script type"),
    NEW_SCRIPT_INVALID("new-script-invalid", "Script creation with an invalid name"),
    SCRIPT_TOOLBAR("script-toolbar", "Script toolbar with the Format action"),
    SCRIPT_PROBLEMS("script-problems", "Compiler problems with source navigation"),
    SCRIPT_PROBLEMS_OUTDATED("script-problems-outdated", "Previous compiler problems after editing the source"),
    COMPLETION_SINGLE("completion-single", "One completion with separate parameter and return-type columns"),
    COMPLETION_SHORTLIST("completion-shortlist", "Two compact completion rows"),
    COMPLETION_MODIFIERS("completion-modifiers", "Completion fields, visibility and modifier badges"),
    COMPLETION_CASTS("completion-casts", "Subtype member completions with the required cast visible"),
    SIGNATURE_HELP("signature-help", "Call overloads with the active parameter emphasized"),
    IMAGE_TOOLBAR("image-toolbar", "Image tools with visible fit selection"),
    EDITOR_FIND_TOOLBAR("editor-find-toolbar", "Editor Find with an active keyboard-operated option"),
    PROJECTS("projects", "Project switcher with current and recent projects"),
    PRISM("prism", "Prism instance library picker"),
    INACTIVE_TABS("inactive-tabs", "Main window with inactive editor tabs visible"),
    TAB_HOVER("tab-hover", "Inactive editor tab with its hover and close affordance visible"),
    TAB_MENU("tab-menu", "Context menu on an inactive editor tab"),
    TAB_REVEAL("tab-reveal", "Inactive source tab revealed in the Files tree"),
    EDITOR_CURRENT_LINE("editor-current-line", "Current editor line and hierarchy gutter markers"),
    BREAKPOINT_EDITOR("breakpoint-editor", "Conditional breakpoint marker and anchored editor"),
    BREAKPOINT_INTERACTION("breakpoint-interaction", "Rapid gutter clicks and stationary hover without a game connection"),
    METHOD_BREAKPOINT("method-breakpoint", "Method-entry breakpoint marker on a declaration"),
    DEBUGGER_LOCATION("debugger-location", "Breakpoint and selected debugger-frame source rows"),
    DEBUGGER("debugger", "Paused debugger window with frames and variables"),
    DEBUGGER_TOOLBAR("debugger-toolbar", "Debugger mute toggle activated with Space and focused"),
    DEBUGGER_FRAMES_MENU("debugger-frames-menu", "Stack-frame actions opened from the keyboard"),
    DEBUGGER_VALUES_MENU("debugger-values-menu", "Debugger value copying and navigation actions"),
    BREAKPOINTS("breakpoints", "Persisted breakpoint list and selected breakpoint details"),
    BREAKPOINTS_MENU("breakpoints-menu", "Breakpoint list with its keyboard context menu open"),
    BREAKPOINTS_SIMPLE("breakpoints-simple", "Breakpoint without an action, with action fields hidden"),
    EVALUATE_CODE("evaluate-code", "Evaluate Everywhere Java code editor and execution context"),
    EVALUATE_EXPRESSION("evaluate-expression", "Compact evaluator with inline expansion and history"),
    HIERARCHY_ONE("hierarchy-one", "Hierarchy preview with one implementation"),
    HIERARCHY_MANY("hierarchy-many", "Hierarchy preview with several implementations"),
    IMPLEMENTATION_CHOOSER("implementation-chooser", "Implementation chooser with results"),
    IMPLEMENTATION_MENU("implementation-menu", "Hierarchy result navigation and copy menu"),
    SEARCH_EMPTY("search-empty", "Search Everywhere before a query is entered"),
    SEARCH_RESULTS("search-results", "Search Everywhere with selected results"),
    SEARCH_MENU("search-menu", "Search Everywhere result navigation and copy menu"),
    FILE_MENU("file-menu", "Runtime class source and path actions in the Files tree"),
    MODULE_FILTER("module-filter", "Search Everywhere module selection popup"),
    USAGES_RESULTS("usages-results", "Find Usages with indexed results"),
    USAGES_SEARCH("usages-search", "Find Usages with matching text highlighted"),
    USAGES_MENU("usages-menu", "Find Usages with the keyboard context menu open"),
    SETTINGS("settings", "Settings controls"),
    SERVICE_STATUS("service-status", "Published Game and MCP states with the MCP detail popup"),
    INDEXING("indexing", "Runtime index activity in the status bar"),
    MOD_PAGE("mod-page", "Mod page overview with its logo, facts and dependencies"),
    MOD_CONFIGURATION("mod-configuration", "Configuration settings with a modified value"),
    MOD_RESOURCES("mod-resources", "Mod resources listed by category"),
    DEFINITION_PAGE("definition-page", "Block definition page with its files"),
    PACK_CONFIGURATION("pack-configuration", "Every modified setting of the pack under its mod and file"),
    KEY_BINDINGS("key-bindings", "The pack's key bindings with a changed key that collides"),
    CONTENT("content", "The pack's registered content with every kind listed together");

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
