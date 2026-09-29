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
            List<String> sharing = binding == null ? List.of() : bindings.clashes(binding).stream().map(clash -> clash.other().name()).toList();
            rows.add(new Row(change, binding == null ? name : binding.name(), mod, key(bindings, current),
                    key(bindings, KeyBindings.Assignment.decode(change.original())),
                    sharing.isEmpty() ? "" : "Shares its key with " + String.join(", ", sharing), new NavigationTarget.KeyBindings(name)));
        }
        rows.sort(Comparator.comparing(Row::name));
        return new Rows(rows, problems);
    }

    @Override
    public CompletableFuture<String> revert(List<ChangeRecord.Change> changes, CatalogIndex index) {
        // Each binding is reverted on its own, so one changed since does not keep the others from their keys.
        return ChangeLabels.each(changes, change -> {
            String name = ((ChangeRecord.KeyBinding) change.target()).name();
            return this.keys.set(List.of(new KeyBindingControl.Change(name, KeyBindings.Assignment.decode(change.current()),
                    KeyBindings.Assignment.decode(change.original()))));
        }, change -> ((ChangeRecord.KeyBinding) change.target()).name());
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
