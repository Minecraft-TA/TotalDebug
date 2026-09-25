package com.github.minecraft_ta.totalDebugCompanion.navigation;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsage;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/** A semantic Companion destination. No target retains a Swing component. */
public sealed interface NavigationTarget permits
        NavigationTarget.RuntimeClass,
        NavigationTarget.RuntimeDeclaration,
        NavigationTarget.RuntimeLine,
        NavigationTarget.LocalFile,
        NavigationTarget.LocalDirectory,
        NavigationTarget.ArchiveEntry,
        NavigationTarget.ArchiveDirectory,
        NavigationTarget.UsageSite,
        NavigationTarget.SymbolUsages,
        NavigationTarget.LiteralUsages,
        NavigationTarget.RuntimePackage,
        NavigationTarget.ModuleSearch,
        NavigationTarget.Inspection,
        NavigationTarget.ModPage,
        NavigationTarget.PackConfiguration,
        NavigationTarget.Changes,
        NavigationTarget.Definition,
        NavigationTarget.RuntimeModuleNode {

    /**
     * An installed mod's page, or a namespace's or runtime module's page when no captured mod has that id.
     * {@code resourceCategory} selects a resource category on the Resources tab and is empty otherwise.
     */
    record ModPage(String modId, ModTab tab, String resourceCategory) implements NavigationTarget {
        public ModPage {
            modId = requireText(modId, "modId");
            Objects.requireNonNull(tab, "tab");
            resourceCategory = Objects.requireNonNullElse(resourceCategory, "");
        }

        public ModPage(String modId) {
            this(modId, ModTab.OVERVIEW, "");
        }
    }

    /** What Companion changed in the pack. */
    record Changes() implements NavigationTarget {
    }

    /** The settings of every mod in the pack. */
    record PackConfiguration() implements NavigationTarget {
    }

    /** A registered block, item or entity type, described by the captured pack catalog. */
    record Definition(SubjectRef.Definition subject) implements NavigationTarget {
        public Definition {
            Objects.requireNonNull(subject, "subject");
        }
    }

    /** A runtime module's node in the Runtime tree. */
    record RuntimeModuleNode(String moduleId) implements NavigationTarget {
        public RuntimeModuleNode {
            moduleId = requireText(moduleId, "moduleId");
        }
    }

    /** A block or entity selected in the game, inspected in the session where it was selected. */
    record Inspection(InspectSubjectPayload subject) implements NavigationTarget {
        public Inspection {
            Objects.requireNonNull(subject, "subject");
        }
    }

    record RuntimeClass(String binaryName) implements NavigationTarget {
        public RuntimeClass {
            binaryName = requireText(binaryName, "binaryName");
        }
    }

    record RuntimeDeclaration(RuntimeMember member) implements NavigationTarget {
        public RuntimeDeclaration {
            Objects.requireNonNull(member, "member");
        }
    }

    record RuntimeLine(String binaryName, int displayedLine) implements NavigationTarget {
        public RuntimeLine {
            binaryName = requireText(binaryName, "binaryName");
            if (displayedLine < 1) {
                throw new IllegalArgumentException("displayedLine must be positive");
            }
        }
    }

    record LocalFile(Path path, int offset) implements NavigationTarget {
        public LocalFile {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
        }

        public LocalFile(Path path) {
            this(path, 0);
        }
    }

    record LocalDirectory(Path path) implements NavigationTarget {
        public LocalDirectory {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        }
    }

    record ArchiveEntry(Path archive, String entryName) implements NavigationTarget {
        public ArchiveEntry {
            archive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
            entryName = requireText(entryName, "entryName").replace('\\', '/');
        }
    }

    record ArchiveDirectory(Path archive, String entryName) implements NavigationTarget {
        public ArchiveDirectory {
            archive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
            entryName = Objects.requireNonNullElse(entryName, "").replace('\\', '/');
            while (entryName.startsWith("/")) {
                entryName = entryName.substring(1);
            }
            while (entryName.endsWith("/")) {
                entryName = entryName.substring(0, entryName.length() - 1);
            }
        }
    }

    record UsageSite(ReferenceUsage usage, ReferenceQuery query) implements NavigationTarget {
        public UsageSite {
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(query, "query");
        }
    }

    record SymbolUsages(CodeSymbol symbol) implements NavigationTarget {
        public SymbolUsages {
            Objects.requireNonNull(symbol, "symbol");
        }
    }

    record LiteralUsages(String literal) implements NavigationTarget {
        public LiteralUsages {
            Objects.requireNonNull(literal, "literal");
        }
    }

    record RuntimePackage(String packageName, String ownerClassName) implements NavigationTarget {
        public RuntimePackage {
            packageName = requireText(packageName, "packageName");
            ownerClassName = requireText(ownerClassName, "ownerClassName");
        }
    }

    record ModuleSearch(Set<String> moduleIds, String query) implements NavigationTarget {
        public ModuleSearch {
            moduleIds = Set.copyOf(Objects.requireNonNull(moduleIds, "moduleIds"));
            if (moduleIds.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("moduleIds must not contain blank IDs");
            }
            query = Objects.requireNonNullElse(query, "");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
