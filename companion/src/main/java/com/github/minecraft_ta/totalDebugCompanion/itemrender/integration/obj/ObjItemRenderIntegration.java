package com.github.minecraft_ta.totalDebugCompanion.itemrender.integration.obj;

import java.util.Arrays;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Archive-only implementation of NeoForge's generic Wavefront OBJ model loader. */
public final class ObjItemRenderIntegration {

    public static final String MODEL_LOADER = "neoforge:obj";
    private static final int MAXIMUM_OBJ_BYTES = 32 * 1024 * 1024;
    private static final int MAXIMUM_MATERIAL_BYTES = 4 * 1024 * 1024;

    private ObjItemRenderIntegration() {
    }

    public static ParsedModel load(
            String modelLocation,
            String materialOverride,
            boolean flipV,
            boolean shadeQuads,
            boolean emissiveAmbient,
            ResourceReader resources
    ) throws IOException {
        ResourceId model = ResourceId.parse(modelLocation);
        String source = new String(resources.read(model.toString(), MAXIMUM_OBJ_BYTES), StandardCharsets.UTF_8);
        Map<String, Material> materials = new HashMap<>();
        if (materialOverride != null) {
            ResourceId material = resolve(model, materialOverride);
            materials.putAll(readMaterials(material, resources));
        }

        List<Position> positions = new ArrayList<>();
        List<TexCoord> textureCoordinates = new ArrayList<>();
        List<PendingFace> pendingFaces = new ArrayList<>();
        Material currentMaterial = null;
        String rootPart = "";
        String nestedPart = "";
        boolean objectAboveGroup = false;

        for (String rawLine : source.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] values = line.split("\\s+");
            switch (values[0]) {
                case "mtllib" -> {
                    if (materialOverride == null && values.length > 1) {
                        ResourceId material = resolve(model, join(values, 1));
                        materials.putAll(readMaterials(material, resources));
                    }
                }
                case "usemtl" -> currentMaterial = materials.get(join(values, 1));
                case "v" -> positions.add(parsePosition(values));
                case "vt" -> textureCoordinates.add(parseTexCoord(values, flipV));
                case "o" -> {
                    String name = requiredName(values, "object");
                    if (objectAboveGroup || rootPart.isEmpty()) {
                        objectAboveGroup = true;
                        rootPart = name;
                        nestedPart = "";
                    } else {
                        nestedPart = name;
                    }
                }
                case "g" -> {
                    String name = requiredName(values, "group");
                    if (objectAboveGroup) {
                        nestedPart = name;
                    } else {
                        rootPart = name;
                        nestedPart = "";
                    }
                }
                case "f" -> {
                    if (currentMaterial == null) {
                        continue;
                    }
                    if (values.length < 4) {
                        throw new IOException("OBJ face must have at least three vertices in " + model);
                    }
                    pendingFaces.add(new PendingFace(
                            List.of(Arrays.copyOfRange(values, 1, values.length)),
                            currentMaterial,
                            rootPart,
                            nestedPart,
                            positions.size(),
                            textureCoordinates.size()
                    ));
                }
                default -> {
                    // Normals, smoothing groups, and the remaining Wavefront statements do not
                    // alter the isolated textured quads needed for an item render.
                }
            }
        }
        List<Face> faces = new ArrayList<>(pendingFaces.size());
        for (PendingFace pending : pendingFaces) {
            List<Vertex> vertices = new ArrayList<>(4);
            for (String value : pending.vertices()) {
                if (vertices.size() == 4) {
                    break;
                }
                String[] indices = value.split("/", -1);
                Position position = positions.get(resolveIndex(
                        indices[0], positions.size(), pending.positionCount(), "position", model
                ));
                TexCoord textureCoordinate = indices.length > 1 && !indices[1].isEmpty()
                        ? textureCoordinates.get(resolveIndex(
                                indices[1], textureCoordinates.size(), pending.textureCount(), "texture", model
                        ))
                        : defaultTextureCoordinate(vertices.size(), flipV);
                vertices.add(new Vertex(
                        position.x(), position.y(), position.z(), textureCoordinate.u(), textureCoordinate.v()
                ));
            }
            while (vertices.size() < 4) {
                vertices.add(vertices.getLast());
            }
            Texture texture = normalizeTexture(pending.material().diffuseTexture());
            faces.add(new Face(
                    List.copyOf(vertices),
                    texture.value(),
                    texture.reference(),
                    pending.material().tintIndex(),
                    pending.material().argb(),
                    shadeQuads,
                    emissiveAmbient && pending.material().emissive(),
                    pending.rootPart(),
                    pending.nestedPart()
            ));
        }
        return new ParsedModel(List.copyOf(faces));
    }

    private static Map<String, Material> readMaterials(ResourceId location, ResourceReader resources) throws IOException {
        String source = new String(resources.read(location.toString(), MAXIMUM_MATERIAL_BYTES), StandardCharsets.UTF_8);
        Map<String, Material> materials = new HashMap<>();
        Material current = null;
        for (String rawLine : source.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] values = line.split("\\s+");
            switch (values[0]) {
                case "newmtl" -> {
                    current = new Material(join(values, 1));
                    materials.put(current.name(), current);
                }
                case "Kd" -> requireMaterial(current, location).setDiffuse(values);
                case "Ka" -> requireMaterial(current, location).setAmbient(values);
                case "map_Kd" -> requireMaterial(current, location).diffuseTexture = values[values.length - 1];
                case "d" -> requireMaterial(current, location).dissolve = Double.parseDouble(values[1]);
                case "Tr" -> requireMaterial(current, location).transparency = Double.parseDouble(values[1]);
                case "forge_TintIndex", "neoforge_TintIndex" ->
                        requireMaterial(current, location).tintIndex = Integer.parseInt(values[1]);
                default -> {
                }
            }
        }
        return materials;
    }

    private static Material requireMaterial(Material material, ResourceId location) throws IOException {
        if (material == null) {
            throw new IOException("MTL statement before newmtl in " + location);
        }
        return material;
    }

    private static Position parsePosition(String[] values) throws IOException {
        if (values.length < 4) {
            throw new IOException("OBJ vertex must contain x, y, and z");
        }
        return new Position(
                Double.parseDouble(values[1]),
                Double.parseDouble(values[2]),
                Double.parseDouble(values[3])
        );
    }

    private static TexCoord parseTexCoord(String[] values, boolean flipV) throws IOException {
        if (values.length < 3) {
            throw new IOException("OBJ texture coordinate must contain u and v");
        }
        double v = Double.parseDouble(values[2]);
        return new TexCoord(Double.parseDouble(values[1]), flipV ? 1.0 - v : v);
    }

    private static TexCoord defaultTextureCoordinate(int vertex, boolean flipV) {
        TexCoord coordinate = switch (vertex) {
            case 0 -> new TexCoord(0, 0);
            case 1 -> new TexCoord(0, 1);
            case 2 -> new TexCoord(1, 1);
            default -> new TexCoord(1, 0);
        };
        return flipV ? new TexCoord(coordinate.u(), 1.0 - coordinate.v()) : coordinate;
    }

    private static int resolveIndex(
            String value,
            int finalSize,
            int sizeAtFace,
            String kind,
            ResourceId model
    ) throws IOException {
        int parsed = Integer.parseInt(value);
        int index = parsed < 0 ? sizeAtFace + parsed : parsed - 1;
        if (index < 0 || index >= finalSize) {
            throw new IOException("OBJ " + kind + " index " + parsed + " is out of range in " + model);
        }
        return index;
    }

    private static String requiredName(String[] values, String kind) throws IOException {
        if (values.length < 2) {
            throw new IOException("OBJ " + kind + " has no name");
        }
        return values[1];
    }

    private static Texture normalizeTexture(String texture) {
        if (texture == null) {
            return new Texture(null, false);
        }
        if (texture.startsWith("#")) {
            return new Texture(texture, true);
        }
        String normalized = texture.replace('\\', '/');
        int assets = normalized.indexOf("/assets/");
        if (assets >= 0) {
            normalized = normalized.substring(assets + "/assets/".length());
            int slash = normalized.indexOf('/');
            if (slash > 0) {
                normalized = normalized.substring(0, slash) + ':' + normalized.substring(slash + 1);
            }
        }
        int textures = normalized.indexOf("textures/");
        if (textures >= 0) {
            normalized = normalized.substring(0, textures) + normalized.substring(textures + "textures/".length());
        }
        if (normalized.endsWith(".png")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return new Texture(normalized, false);
    }

    private static ResourceId resolve(ResourceId base, String reference) throws IOException {
        if (reference.indexOf(':') >= 0) {
            return ResourceId.parse(reference);
        }
        int slash = base.path().lastIndexOf('/');
        String directory = slash < 0 ? "" : base.path().substring(0, slash + 1);
        String combined = directory + reference.replace('\\', '/');
        if (combined.contains("../") || combined.startsWith("/")) {
            throw new IOException("Invalid relative OBJ resource " + reference);
        }
        return new ResourceId(base.namespace(), combined);
    }

    private static String join(String[] values, int start) {
        return String.join(" ", Arrays.copyOfRange(values, start, values.length));
    }

    @FunctionalInterface
    public interface ResourceReader {
        byte[] read(String resourceLocation, int maximumBytes) throws IOException;
    }

    public record ParsedModel(List<Face> faces) {
    }

    public record Face(
            List<Vertex> vertices,
            String texture,
            boolean textureReference,
            int tintIndex,
            int color,
            boolean shade,
            boolean emissive,
            String rootPart,
            String nestedPart
    ) {
    }

    public record Vertex(double x, double y, double z, double u, double v) {
    }

    private record Position(double x, double y, double z) {
    }

    private record TexCoord(double u, double v) {
    }

    private record Texture(String value, boolean reference) {
    }

    private record PendingFace(
            List<String> vertices,
            Material material,
            String rootPart,
            String nestedPart,
            int positionCount,
            int textureCount
    ) {
    }

    private record ResourceId(String namespace, String path) {
        private static ResourceId parse(String value) {
            int separator = value.indexOf(':');
            return separator < 0
                    ? new ResourceId("minecraft", value)
                    : new ResourceId(value.substring(0, separator), value.substring(separator + 1));
        }

        @Override
        public String toString() {
            return this.namespace + ':' + this.path;
        }
    }

    private static final class Material {
        private final String name;
        private double red = 1;
        private double green = 1;
        private double blue = 1;
        private double alpha = 1;
        private double ambientRed;
        private double ambientGreen;
        private double ambientBlue;
        private double dissolve = 1;
        private double transparency;
        private String diffuseTexture;
        private int tintIndex = -1;

        private Material(String name) {
            this.name = name;
        }

        private String name() {
            return this.name;
        }

        private void setDiffuse(String[] values) {
            this.red = Double.parseDouble(values[1]);
            this.green = Double.parseDouble(values[2]);
            this.blue = Double.parseDouble(values[3]);
            if (values.length > 4) {
                this.alpha = Double.parseDouble(values[4]);
            }
        }

        private void setAmbient(String[] values) {
            this.ambientRed = Double.parseDouble(values[1]);
            this.ambientGreen = Double.parseDouble(values[2]);
            this.ambientBlue = Double.parseDouble(values[3]);
        }

        private String diffuseTexture() {
            return this.diffuseTexture;
        }

        private int tintIndex() {
            return this.tintIndex;
        }

        private boolean emissive() {
            return this.ambientRed + this.ambientGreen + this.ambientBlue > 0;
        }

        private int argb() {
            double resultingAlpha = Math.clamp(this.alpha * this.dissolve * (1.0 - this.transparency), 0, 1);
            return (int) Math.round(resultingAlpha * 255) << 24
                    | (int) Math.round(Math.clamp(this.red, 0, 1) * 255) << 16
                    | (int) Math.round(Math.clamp(this.green, 0, 1) * 255) << 8
                    | (int) Math.round(Math.clamp(this.blue, 0, 1) * 255);
        }
    }
}
