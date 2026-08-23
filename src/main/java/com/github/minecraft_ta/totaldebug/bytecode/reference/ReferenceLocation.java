package com.github.minecraft_ta.totaldebug.bytecode.reference;

import java.util.Objects;

/** A class or declaration containing one or more references to a query. */
public record ReferenceLocation(String className, Site site) implements Comparable<ReferenceLocation> {
    public ReferenceLocation {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(site, "site");
    }

    public static ReferenceLocation classDeclaration(String className) {
        return new ReferenceLocation(className, ClassDeclaration.INSTANCE);
    }

    public static ReferenceLocation field(String className, String name, String descriptor) {
        return new ReferenceLocation(className, new Field(name, descriptor));
    }

    public static ReferenceLocation method(String className, String name, String descriptor) {
        return new ReferenceLocation(className, new Method(name, descriptor));
    }

    public static ReferenceLocation recordComponent(String className, String name, String descriptor) {
        return new ReferenceLocation(className, new RecordComponent(name, descriptor));
    }

    @Override
    public int compareTo(ReferenceLocation other) {
        int classComparison = this.className.compareTo(other.className);
        if (classComparison != 0) {
            return classComparison;
        }
        return compareSites(this.site, other.site);
    }

    public sealed interface Site {
    }

    public enum ClassDeclaration implements Site {
        INSTANCE
    }

    public record Field(String name, String descriptor) implements Site {
        public Field {
            requireMember(name, descriptor);
        }
    }

    public record Method(String name, String descriptor) implements Site {
        public Method {
            requireMember(name, descriptor);
        }
    }

    public record RecordComponent(String name, String descriptor) implements Site {
        public RecordComponent {
            requireMember(name, descriptor);
        }
    }

    private static int compareSites(Site left, Site right) {
        int kindComparison = Integer.compare(siteOrder(left), siteOrder(right));
        if (kindComparison != 0) {
            return kindComparison;
        }
        if (left instanceof ClassDeclaration) {
            return 0;
        }
        MemberKey leftMember = memberKey(left);
        MemberKey rightMember = memberKey(right);
        int nameComparison = leftMember.name().compareTo(rightMember.name());
        return nameComparison != 0
                ? nameComparison
                : leftMember.descriptor().compareTo(rightMember.descriptor());
    }

    private static int siteOrder(Site site) {
        return switch (site) {
            case ClassDeclaration ignored -> 0;
            case Field ignored -> 1;
            case Method ignored -> 2;
            case RecordComponent ignored -> 3;
        };
    }

    private static MemberKey memberKey(Site site) {
        return switch (site) {
            case Field field -> new MemberKey(field.name(), field.descriptor());
            case Method method -> new MemberKey(method.name(), method.descriptor());
            case RecordComponent component -> new MemberKey(component.name(), component.descriptor());
            case ClassDeclaration ignored -> throw new IllegalArgumentException("Class declarations have no member key");
        };
    }

    private static void requireMember(String name, String descriptor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Member name must not be blank");
        }
        if (descriptor.isBlank()) {
            throw new IllegalArgumentException("Member descriptor must not be blank");
        }
    }

    private record MemberKey(String name, String descriptor) {
    }
}
