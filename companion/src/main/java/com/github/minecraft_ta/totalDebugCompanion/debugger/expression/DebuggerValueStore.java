package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.VMDisconnectedException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** History and inspectors share pins; aliases disable collection only once per target object. */
final class DebuggerValueStore {
    private final Map<ObjectReference, Integer> pins = new HashMap<>();
    private final Map<Integer, Entry> references = new HashMap<>();
    private final Map<String, Set<Integer>> history = new HashMap<>();
    private final IntConsumer removeReference;
    private long generation;

    DebuggerValueStore(IntConsumer removeReference) { this.removeReference = removeReference; }

    synchronized long generation() { return this.generation; }

    synchronized int register(long generation, String operation, ObjectReference object, IntSupplier addReference) {
        if (generation != this.generation) throw new CancellationException("Evaluation result expired");
        boolean newlyPinned = !this.pins.containsKey(object);
        if (newlyPinned) object.disableCollection();
        int reference;
        try { reference = addReference.getAsInt(); }
        catch (RuntimeException | Error failure) {
            if (newlyPinned) enableCollection(object);
            throw failure;
        }
        Entry entry = this.references.get(reference);
        if (entry == null) {
            entry = new Entry(reference, object);
            this.references.put(reference, entry);
            this.pins.merge(object, 1, Integer::sum);
        }
        if (this.history.computeIfAbsent(operation, ignored -> new HashSet<>()).add(reference)) entry.owners++;
        return reference;
    }

    synchronized DebuggerValueLease retain(int reference) {
        if (reference <= 0) return DebuggerValueLease.NONE;
        Entry entry = this.references.get(reference);
        if (entry == null) throw new IllegalArgumentException("Evaluation value reference expired: " + reference);
        entry.owners++;
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) release(entry);
        };
    }

    synchronized void releaseHistory(String operation) {
        Set<Integer> references = this.history.remove(operation);
        if (references == null) return;
        for (int reference : references) {
            Entry entry = this.references.get(reference);
            if (entry != null) release(entry);
        }
    }

    private synchronized void release(Entry entry) {
        if (this.references.get(entry.reference) != entry || --entry.owners != 0) return;
        this.references.remove(entry.reference);
        this.removeReference.accept(entry.reference);
        int aliases = this.pins.get(entry.object) - 1;
        if (aliases == 0) {
            this.pins.remove(entry.object);
            enableCollection(entry.object);
        } else this.pins.put(entry.object, aliases);
    }

    synchronized void clear() {
        this.generation++;
        this.references.keySet().forEach(this.removeReference::accept);
        this.references.clear();
        this.history.clear();
        this.pins.keySet().forEach(DebuggerValueStore::enableCollection);
        this.pins.clear();
    }

    private static void enableCollection(ObjectReference object) {
        try { object.enableCollection(); }
        catch (VMDisconnectedException | ObjectCollectedException ignored) { }
    }

    private static final class Entry {
        private final int reference;
        private final ObjectReference object;
        private int owners;
        private Entry(int reference, ObjectReference object) { this.reference = reference; this.object = object; }
    }
}
