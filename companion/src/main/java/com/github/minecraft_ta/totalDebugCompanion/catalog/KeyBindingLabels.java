package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangeLabels;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Key bindings on the Changes page: each binding by its action, the mod it belongs to, and its key now and before; a
 * binding that shares its key with another says so.
 */
public final class KeyBindingLabels implements ChangeLabels {
    private final KeyBindingControl keys;

    public KeyBindingLabels(KeyBindingControl keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Override
    public String tab() {
        return "Key bindings";
    }

    @Override
    public boolean covers(ChangeRecord.Target target) {
        return target instanceof ChangeRecord.KeyBinding;
    }

    @Override
    public Rows rows(List<ChangeRecord.Change> changes, CatalogIndex index) {
        List<String> problems = new ArrayList<>();
        Map<String, KeyBindings.Assignment> options;
        try {
            options = KeyBindings.readOptions(this.keys.options());
        } catch (IOException unreadable) {
            problems.add("Could not read options.txt: " + unreadable.getMessage());
            options = Map.of();
        }
        KeyBindings bindings = bindings(index, options);
        List<Row> rows = new ArrayList<>();
        for (ChangeRecord.Change change : changes) {
            String name = ((ChangeRecord.KeyBinding) change.target()).name();
            KeyBindings.Binding binding = bindings.bindings().stream()
                    .filter(candidate -> candidate.spec().name().equals(name)).findFirst().orElse(null);
            // A binding the catalog does not describe still has its line in options.txt.
            KeyBindings.Assignment current = binding != null ? binding.current()
                    : options.getOrDefault(name, KeyBindings.Assignment.decode(change.current()));
            this.keys.record().observed(change.target(), current.encode(), String::equals);
            if (current.encode().equals(change.original())) continue;
            String mod = binding == null || index == null ? "" : index.mod(index.keyBindingOwner(binding.spec())).map(PackCatalog.Mod::name)
                    .orElse(index.keyBindingOwner(binding.spec()));
            // Bindings whose contexts never conflict share a key harmlessly.
            List<String> sharing = binding == null ? List.of() : bindings.clashes(binding).stream()
                    .filter(clash -> clash.overlap() != KeyBindings.Overlap.SEPARATE_CONTEXTS).map(clash -> clash.other().name()).toList();
            String before = key(bindings, KeyBindings.Assignment.decode(change.original()));
            rows.add(new Row(change, binding == null ? name : binding.name(), mod, key(bindings, current), before,
                    sharing.isEmpty() ? "" : "Shares its key with " + names(sharing), new NavigationTarget.KeyBindings(name),
                    new Actions("Show in Key Bindings", "Revert to " + before, "", name)));
        }
        rows.sort(Comparator.comparing(Row::name));
        return new Rows(rows, problems);
    }

    @Override
    public CompletableFuture<String> revert(List<ChangeRecord.Change> changes, CatalogIndex index) {
        // Each binding is reverted on its own, so one changed since does not keep the others from their keys.
        KeyBindings bindings = bindings(index, Map.of());
        return ChangeLabels.each(changes, change -> {
            String name = ((ChangeRecord.KeyBinding) change.target()).name();
            return this.keys.set(List.of(new KeyBindingControl.Change(name, KeyBindings.Assignment.decode(change.current()),
                    KeyBindings.Assignment.decode(change.original()))));
        }, change -> action(bindings, ((ChangeRecord.KeyBinding) change.target()).name()));
    }

    /** The action a binding is named by on the Key bindings page, or its name where the catalog does not describe it. */
    private static String action(KeyBindings bindings, String name) {
        return bindings.bindings().stream().filter(binding -> binding.spec().name().equals(name)).map(KeyBindings.Binding::name)
                .findFirst().orElse(name);
    }

    /** Up to three names, then how many more. */
    private static String names(List<String> names) {
        if (names.size() <= 3) return String.join(", ", names);
        return String.join(", ", names.subList(0, 3)) + " and " + (names.size() - 3) + " more";
    }

    private static String key(KeyBindings bindings, KeyBindings.Assignment assignment) {
        return assignment.unbound() ? "Not bound" : bindings.display(assignment);
    }

    private static KeyBindings bindings(CatalogIndex index, Map<String, KeyBindings.Assignment> current) {
        PackCatalog captured = index == null ? null : index.catalog();
        return captured == null ? new KeyBindings(List.of(), List.of(), current, Map.of())
                : new KeyBindings(captured.keyBindings(), captured.keyContexts(), current, captured.keyNames());
    }
}
