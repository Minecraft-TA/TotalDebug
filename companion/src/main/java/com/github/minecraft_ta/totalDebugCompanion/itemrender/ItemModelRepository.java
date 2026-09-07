package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import com.github.minecraft_ta.totalDebugCompanion.itemrender.integration.fusion.FusionItemRenderIntegration;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.integration.obj.ObjItemRenderIntegration;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ItemModelRepository {

    private static final int MAXIMUM_MODEL_BYTES = 4 * 1024 * 1024;
    private static final int MAXIMUM_TEXTURE_BYTES = 64 * 1024 * 1024;
    private static final long MAXIMUM_TEXTURE_PIXELS = 16L * 1024 * 1024;
    private static final ItemModelId BUILTIN_GENERATED = ItemModelId.parse("builtin/generated");
    private static final ItemModelId BUILTIN_ENTITY = ItemModelId.parse("builtin/entity");

    private final ResourcePackStack resources;
    private final AtlasSpriteResolver atlasSprites;
    private final Map<ItemModelId, ResolvedModel> modelCache = new HashMap<>();
    private final Map<ItemModelId, TextureRegion> textureCache = new HashMap<>();

    ItemModelRepository(ResourcePackStack resources) {
        this.resources = resources;
        this.atlasSprites = new AtlasSpriteResolver(resources);
    }

    ResolvedModel resolve(ItemModelId modelId) throws IOException {
        ResolvedModel cached = this.modelCache.get(modelId);
        if (cached != null) {
            return cached;
        }
        return resolve(modelId, new LinkedHashSet<>());
    }

    TextureRegion texture(ItemModelId textureId) throws IOException {
        TextureRegion cached = this.textureCache.get(textureId);
        if (cached != null) {
            return cached;
        }

        AtlasSpriteResolver.Sprite sprite = this.atlasSprites.resolve(textureId);
        BufferedImage decoded = readTextureImage(sprite.resource());
        TextureRegion frame = sprite.generated()
                ? TextureRegion.full(this.atlasSprites.applyPalette(sprite, decoded, this::readTextureImage))
                : textureFrame(sprite.resource(), decoded);
        this.textureCache.put(textureId, frame);
        return frame;
    }

    private BufferedImage readTextureImage(ItemModelId textureId) throws IOException {
        byte[] png = this.resources.readRequired(textureId.textureResourcePath(), MAXIMUM_TEXTURE_BYTES);
        BufferedImage decoded;
        try (ByteArrayInputStream input = new ByteArrayInputStream(png)) {
            decoded = ImageIO.read(input);
        }
        if (decoded == null) {
            throw new ItemRenderException(
                    ItemRenderException.Kind.RESOURCE_ERROR,
                    "unreadable PNG",
                    "Texture " + textureId + " is not a readable PNG"
            );
        }
        if ((long) decoded.getWidth() * decoded.getHeight() > MAXIMUM_TEXTURE_PIXELS) {
            throw new ItemRenderException(
                    ItemRenderException.Kind.RESOURCE_ERROR,
                    "texture pixel limit",
                    "Texture " + textureId + " exceeds the " + MAXIMUM_TEXTURE_PIXELS + " pixel limit"
            );
        }

        return decoded;
    }

    void clearCaches() {
        this.modelCache.clear();
        this.textureCache.clear();
        this.atlasSprites.clearCache();
    }

    private ResolvedModel resolve(ItemModelId modelId, LinkedHashSet<ItemModelId> resolving) throws IOException {
        ResolvedModel cached = this.modelCache.get(modelId);
        if (cached != null) {
            return cached;
        }
        if (modelId.equals(BUILTIN_GENERATED)) {
            return ResolvedModel.generatedRoot();
        }
        if (modelId.equals(BUILTIN_ENTITY)) {
            throw new UnsupportedItemModelException(modelId, "builtin/entity custom rendering");
        }
        if (!resolving.add(modelId)) {
            throw new ItemRenderException(
                    ItemRenderException.Kind.INVALID_MODEL,
                    "parent cycle",
                    "Item model parent cycle: " + formatCycle(resolving, modelId)
            );
        }

        try {
            RawModel raw = readRawModel(modelId);
            ResolvedModel resolved = resolveRawModel(modelId, raw, resolving);
            this.modelCache.put(modelId, resolved);
            return resolved;
        } finally {
            resolving.remove(modelId);
        }
    }

    private ResolvedModel resolveRawModel(
            ItemModelId modelId,
            RawModel raw,
            LinkedHashSet<ItemModelId> resolving
    ) throws IOException {
        ResolvedModel parent = null;
        if (raw.parent() != null) {
            if (raw.parent().equals(BUILTIN_ENTITY)) {
                throw new UnsupportedItemModelException(modelId, "builtin/entity custom rendering");
            }
            try {
                parent = resolve(raw.parent(), resolving);
            } catch (ItemRenderException exception) {
                if (exception.kind() != ItemRenderException.Kind.MISSING_RESOURCE
                        || !exception.detail().equals(raw.parent().modelResourcePath())) {
                    throw exception;
                }
                // Minecraft substitutes its missing model here. Local child geometry and textures
                // still win, so an absent parent must not prevent them from rendering.
            }
        }

        Map<String, String> textures = new HashMap<>();
        if (parent != null) {
            textures.putAll(parent.textures());
        }
        textures.putAll(raw.textures());

        List<Element> elements = raw.elements();
        if (elements.isEmpty() && parent != null) {
            elements = parent.elements();
        }

        Transform guiTransform = raw.guiTransform() != null
                ? raw.guiTransform()
                : parent == null ? Transform.IDENTITY : parent.guiTransform();
        GuiLight guiLight = raw.guiLight() != null
                ? raw.guiLight()
                : parent == null ? GuiLight.SIDE : parent.guiLight();
        ModelTransform rootTransform = raw.rootTransform() != null
                ? raw.rootTransform()
                : parent == null ? ModelTransform.IDENTITY : parent.rootTransform();
        Map<String, Boolean> visibility = new HashMap<>();
        if (parent != null) {
            visibility.putAll(parent.visibility());
        }
        visibility.putAll(raw.visibility());

        ModelKind kind = parent == null ? ModelKind.NONE : parent.kind();
        List<ResolvedModel> parts = parent == null ? List.of() : parent.parts();
        Map<Integer, ExtraFaceData> layerFaceData = parent == null ? Map.of() : parent.layerFaceData();
        List<MeshFace> meshFaces = parent == null ? List.of() : parent.meshFaces();
        switch (raw.loaderKind()) {
            case NORMAL -> {
                if (kind == ModelKind.NONE && !elements.isEmpty()) {
                    kind = ModelKind.ELEMENTS;
                }
            }
            case ITEM_LAYERS -> {
                kind = ModelKind.GENERATED;
                Map<Integer, ExtraFaceData> combinedLayerData = new HashMap<>(layerFaceData);
                combinedLayerData.putAll(raw.layerFaceData());
                layerFaceData = Map.copyOf(combinedLayerData);
            }
            case COMPOSITE -> {
                kind = ModelKind.COMPOSITE;
                List<ResolvedModel> resolvedParts = new ArrayList<>(raw.compositeParts().size());
                for (NamedRawModel part : raw.compositeParts()) {
                    if (visibility.getOrDefault(part.name(), true)) {
                        resolvedParts.add(resolveRawModel(modelId, part.model(), resolving));
                    }
                }
                parts = List.copyOf(resolvedParts);
                elements = List.of();
            }
            case SEPARATE_TRANSFORMS -> {
                ResolvedModel selected = resolveRawModel(modelId, raw.separateGuiModel(), resolving);
                return new ResolvedModel(
                        modelId,
                        selected.kind(),
                        selected.textures(),
                        selected.elements(),
                        selected.guiTransform(),
                        guiLight,
                        selected.parts(),
                        selected.layerFaceData(),
                        rootTransform.compose(selected.rootTransform()),
                        Map.copyOf(visibility),
                        selected.meshFaces()
                );
            }
            case OBJ -> {
                kind = ModelKind.MESH;
                meshFaces = loadObj(modelId, raw.objSpec());
                elements = List.of();
                parts = List.of();
            }
        }

        return new ResolvedModel(
                modelId,
                kind,
                Map.copyOf(textures),
                List.copyOf(elements),
                guiTransform,
                guiLight,
                parts,
                layerFaceData,
                rootTransform,
                Map.copyOf(visibility),
                meshFaces
        );
    }

    private RawModel readRawModel(ItemModelId modelId) throws IOException {
        byte[] bytes = this.resources.readRequired(modelId.modelResourcePath(), MAXIMUM_MODEL_BYTES);
        try {
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                throw new JsonParseException("root must be an object");
            }
            return parseRawModel(modelId, root.getAsJsonObject());
        } catch (UnsupportedItemModelException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ItemRenderException(
                    ItemRenderException.Kind.INVALID_MODEL,
                    "invalid model JSON",
                    "Invalid item model " + modelId + ": " + exception.getMessage(),
                    exception
            );
        }
    }

    private RawModel parseRawModel(ItemModelId modelId, JsonObject json) throws UnsupportedItemModelException {
        LoaderKind loaderKind = parseLoaderKind(modelId, json);

        ItemModelId parent = json.has("parent") ? ItemModelId.parse(requiredString(json, "parent")) : null;
        Map<String, String> textures = parseTextures(json);
        List<Element> elements = parseElements(json);
        Transform guiTransform = parseGuiTransform(json);
        GuiLight guiLight = parseGuiLight(json);
        List<NamedRawModel> compositeParts = loaderKind == LoaderKind.COMPOSITE
                ? parseCompositeParts(modelId, json)
                : List.of();
        Map<Integer, ExtraFaceData> layerFaceData = loaderKind == LoaderKind.ITEM_LAYERS
                ? parseLayerFaceData(json)
                : Map.of();
        if (loaderKind == LoaderKind.ITEM_LAYERS) {
            validateItemLayerRenderTypes(json);
        }
        RawModel separateGuiModel = loaderKind == LoaderKind.SEPARATE_TRANSFORMS
                ? parseSeparateGuiModel(modelId, json)
                : null;
        ObjSpec objSpec = loaderKind == LoaderKind.OBJ ? parseObjSpec(json) : null;
        return new RawModel(
                parent,
                textures,
                elements,
                guiTransform,
                guiLight,
                loaderKind,
                compositeParts,
                layerFaceData,
                json.has("transform") ? parseRootTransform(json.get("transform")) : null,
                parseVisibility(json),
                separateGuiModel,
                objSpec
        );
    }

    private static LoaderKind parseLoaderKind(ItemModelId modelId, JsonObject json) throws UnsupportedItemModelException {
        if (!json.has("loader")) {
            return LoaderKind.NORMAL;
        }
        String loader = requiredString(json, "loader");
        return switch (loader) {
            case "neoforge:composite" -> LoaderKind.COMPOSITE;
            case "neoforge:item_layers" -> LoaderKind.ITEM_LAYERS;
            case "neoforge:separate_transforms" -> LoaderKind.SEPARATE_TRANSFORMS;
            case ObjItemRenderIntegration.MODEL_LOADER -> LoaderKind.OBJ;
            case FusionItemRenderIntegration.MODEL_LOADER -> {
                FusionItemRenderIntegration.validateIsolatedModel(modelId, json);
                yield LoaderKind.NORMAL;
            }
            default -> throw new UnsupportedItemModelException(modelId, "custom model loader " + loader);
        };
    }

    private List<NamedRawModel> parseCompositeParts(ItemModelId modelId, JsonObject json) throws UnsupportedItemModelException {
        JsonObject childrenJson = requiredObject(json, "children");
        if (childrenJson.isEmpty()) {
            throw new JsonParseException("composite model must contain at least one child");
        }

        Map<String, RawModel> children = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> child : childrenJson.entrySet()) {
            children.put(child.getKey(), parseRawModel(
                    modelId,
                    requiredObject(child.getValue(), "composite child " + child.getKey())
            ));
        }
        if (!json.has("item_render_order")) {
            return children.entrySet().stream()
                    .map(entry -> new NamedRawModel(entry.getKey(), entry.getValue()))
                    .toList();
        }

        JsonArray orderJson = requiredArray(json, "item_render_order");
        List<NamedRawModel> ordered = new ArrayList<>(orderJson.size());
        for (JsonElement element : orderJson) {
            String name = element.getAsString();
            RawModel child = children.get(name);
            if (child == null) {
                throw new JsonParseException("item_render_order names unknown composite child " + name);
            }
            ordered.add(new NamedRawModel(name, child));
        }
        return List.copyOf(ordered);
    }

    private RawModel parseSeparateGuiModel(ItemModelId modelId, JsonObject json) throws UnsupportedItemModelException {
        JsonObject perspectives = requiredObject(json, "perspectives");
        JsonObject selected = perspectives.has("gui")
                ? requiredObject(perspectives, "gui")
                : requiredObject(json, "base");
        return parseRawModel(modelId, selected);
    }

    private static ObjSpec parseObjSpec(JsonObject json) {
        ItemModelId model = ItemModelId.parse(requiredString(json, "model"));
        String materialOverride = json.has("mtl_override")
                ? requiredString(json, "mtl_override")
                : null;
        return new ObjSpec(
                model,
                materialOverride,
                optionalBoolean(json, "flip_v", false),
                optionalBoolean(json, "shade_quads", true),
                optionalBoolean(json, "emissive_ambient", true)
        );
    }

    private List<MeshFace> loadObj(
            ItemModelId modelId,
            ObjSpec spec
    ) throws IOException {
        ObjItemRenderIntegration.ParsedModel parsed;
        try {
            parsed = ObjItemRenderIntegration.load(
                    spec.model().toString(),
                    spec.materialOverride(),
                    spec.flipV(),
                    spec.shadeQuads(),
                    spec.emissiveAmbient(),
                    (resourceLocation, maximumBytes) -> this.resources.readRequired(
                            resourceLocationPath(resourceLocation),
                            maximumBytes
                    )
            );
        } catch (ItemRenderException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ItemRenderException(
                    ItemRenderException.Kind.INVALID_MODEL,
                    "invalid OBJ",
                    "Invalid OBJ model " + spec.model() + " used by item model " + modelId
                            + ": " + exception.getMessage(),
                    exception
            );
        }
        List<MeshFace> faces = new ArrayList<>(parsed.faces().size());
        for (ObjItemRenderIntegration.Face face : parsed.faces()) {
            List<MeshVertex> vertices = face.vertices().stream()
                    .map(vertex -> new MeshVertex(
                            new Vec3(vertex.x(), vertex.y(), vertex.z()),
                            vertex.u(),
                            vertex.v()
                    ))
                    .toList();
            faces.add(new MeshFace(
                    vertices,
                    face.texture(),
                    face.textureReference(),
                    face.tintIndex(),
                    face.color(),
                    face.shade(),
                    face.emissive(),
                    face.rootPart(),
                    face.nestedPart()
            ));
        }
        return List.copyOf(faces);
    }

    private static String resourceLocationPath(String resourceLocation) {
        ItemModelId id = ItemModelId.parse(resourceLocation);
        return "assets/" + id.namespace() + "/" + id.path();
    }

    private static Map<String, Boolean> parseVisibility(JsonObject json) {
        if (!json.has("visibility")) {
            return Map.of();
        }
        JsonObject visibility = requiredObject(json, "visibility");
        Map<String, Boolean> values = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : visibility.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isBoolean()) {
                throw new JsonParseException("visibility " + entry.getKey() + " must be a boolean");
            }
            values.put(entry.getKey(), entry.getValue().getAsBoolean());
        }
        return Map.copyOf(values);
    }

    private static ModelTransform parseRootTransform(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            if (element.getAsString().equals("identity")) {
                return ModelTransform.IDENTITY;
            }
            throw new JsonParseException("unknown transform string " + element.getAsString());
        }
        if (element.isJsonArray()) {
            return parseTransformMatrix(element);
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException("transform must be identity, a matrix, or an object");
        }

        JsonObject json = element.getAsJsonObject();
        if (json.has("matrix")) {
            if (json.size() != 1) {
                throw new JsonParseException("transform matrix cannot be combined with other keys");
            }
            return parseTransformMatrix(json.get("matrix"));
        }

        Set<String> unknown = new LinkedHashSet<>(json.keySet());
        Vec3 translation = json.has("translation")
                ? parseVectorElement(json.get("translation"), "transform translation", 3)
                : Vec3.ZERO;
        unknown.remove("translation");
        ModelTransform leftRotation = ModelTransform.IDENTITY;
        if (json.has("rotation")) {
            leftRotation = parseTransformRotation(json.get("rotation"));
            unknown.remove("rotation");
        } else if (json.has("left_rotation")) {
            leftRotation = parseTransformRotation(json.get("left_rotation"));
            unknown.remove("left_rotation");
        }
        Vec3 scale = Vec3.ONE;
        if (json.has("scale")) {
            JsonElement scaleElement = json.get("scale");
            if (scaleElement.isJsonArray()) {
                scale = parseVectorElement(scaleElement, "transform scale", 3);
            } else {
                double scalar = scaleElement.getAsDouble();
                scale = new Vec3(scalar, scalar, scalar);
            }
            unknown.remove("scale");
        }
        ModelTransform rightRotation = ModelTransform.IDENTITY;
        if (json.has("right_rotation")) {
            rightRotation = parseTransformRotation(json.get("right_rotation"));
            unknown.remove("right_rotation");
        } else if (json.has("post-rotation")) {
            rightRotation = parseTransformRotation(json.get("post-rotation"));
            unknown.remove("post-rotation");
        }
        Vec3 origin = new Vec3(1, 1, 1);
        if (json.has("origin")) {
            origin = parseTransformOrigin(json.get("origin"));
            unknown.remove("origin");
        }
        if (!unknown.isEmpty()) {
            throw new JsonParseException("unknown transform keys: " + String.join(", ", unknown));
        }

        return ModelTransform.translation(translation.x(), translation.y(), translation.z())
                .compose(leftRotation)
                .compose(ModelTransform.scale(scale.x(), scale.y(), scale.z()))
                .compose(rightRotation)
                .around(origin);
    }

    private static ModelTransform parseTransformMatrix(JsonElement element) {
        if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new JsonParseException("transform matrix must contain three rows");
        }
        double[][] rows = new double[][]{
                {0, 0, 0, 0},
                {0, 0, 0, 0},
                {0, 0, 0, 0},
                {0, 0, 0, 1}
        };
        for (int row = 0; row < 3; row++) {
            JsonArray values = element.getAsJsonArray().get(row).getAsJsonArray();
            if (values.size() != 4) {
                throw new JsonParseException("transform matrix row must contain four numbers");
            }
            for (int column = 0; column < 4; column++) {
                rows[row][column] = values.get(column).getAsDouble();
            }
        }
        return ModelTransform.matrix(rows);
    }

    private static ModelTransform parseTransformRotation(JsonElement element) {
        if (element.isJsonArray()) {
            JsonArray values = element.getAsJsonArray();
            if (!values.isEmpty() && values.get(0).isJsonObject()) {
                ModelTransform result = ModelTransform.IDENTITY;
                for (JsonElement value : values) {
                    result = result.compose(parseAxisRotation(value));
                }
                return result;
            }
            if (values.size() == 3) {
                Vec3 euler = parseVectorElement(element, "transform rotation", 3);
                return ModelTransform.eulerDegrees(euler.x(), euler.y(), euler.z());
            }
            if (values.size() == 4) {
                return ModelTransform.quaternion(
                        values.get(0).getAsDouble(),
                        values.get(1).getAsDouble(),
                        values.get(2).getAsDouble(),
                        values.get(3).getAsDouble()
                );
            }
            throw new JsonParseException("transform rotation must contain three Euler angles or four quaternion values");
        }
        return parseAxisRotation(element);
    }

    private static ModelTransform parseAxisRotation(JsonElement element) {
        if (!element.isJsonObject() || element.getAsJsonObject().size() != 1) {
            throw new JsonParseException("axis rotation must contain exactly one x, y, or z value");
        }
        Map.Entry<String, JsonElement> entry = element.getAsJsonObject().entrySet().iterator().next();
        Axis axis = switch (entry.getKey()) {
            case "x" -> Axis.X;
            case "y" -> Axis.Y;
            case "z" -> Axis.Z;
            default -> throw new JsonParseException("unknown rotation axis " + entry.getKey());
        };
        return ModelTransform.rotation(axis, entry.getValue().getAsDouble());
    }

    private static Vec3 parseTransformOrigin(JsonElement element) {
        if (element.isJsonArray()) {
            return parseVectorElement(element, "transform origin", 3);
        }
        return switch (element.getAsString()) {
            case "center" -> new Vec3(0.5, 0.5, 0.5);
            case "corner" -> Vec3.ZERO;
            case "opposing-corner" -> Vec3.ONE;
            default -> throw new JsonParseException("transform origin must be center, corner, or opposing-corner");
        };
    }

    private static Vec3 parseVectorElement(JsonElement element, String name, int length) {
        if (!element.isJsonArray() || element.getAsJsonArray().size() != length) {
            throw new JsonParseException(name + " must contain " + length + " numbers");
        }
        JsonArray values = element.getAsJsonArray();
        return new Vec3(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
    }

    private static Map<Integer, ExtraFaceData> parseLayerFaceData(JsonObject json) {
        if (json.has("forge_data")) {
            throw new JsonParseException("forge_data must be replaced by neoforge_data");
        }
        if (!json.has("neoforge_data")) {
            return Map.of();
        }
        JsonObject neoforgeData = requiredObject(json, "neoforge_data");
        if (!neoforgeData.has("layers")) {
            return Map.of();
        }

        JsonObject layers = requiredObject(neoforgeData, "layers");
        Map<Integer, ExtraFaceData> layerData = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : layers.entrySet()) {
            int layer;
            try {
                layer = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException exception) {
                throw new JsonParseException("item layer index must be an integer: " + entry.getKey(), exception);
            }
            if (layer < 0 || layer > 4) {
                throw new JsonParseException("item layer index must be between 0 and 4: " + layer);
            }
            layerData.put(layer, parseExtraFaceData(entry.getValue(), ExtraFaceData.DEFAULT));
        }
        return Map.copyOf(layerData);
    }

    private static void validateItemLayerRenderTypes(JsonObject json) {
        if (!json.has("render_types")) {
            return;
        }
        JsonObject renderTypes = requiredObject(json, "render_types");
        Set<Integer> assignedLayers = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : renderTypes.entrySet()) {
            ItemModelId.parse(entry.getKey());
            if (!entry.getValue().isJsonArray()) {
                throw new JsonParseException("render type layers must be an array for " + entry.getKey());
            }
            for (JsonElement layerElement : entry.getValue().getAsJsonArray()) {
                int layer = layerElement.getAsInt();
                if (layer < 0 || layer > 4) {
                    throw new JsonParseException("render type layer must be between 0 and 4: " + layer);
                }
                if (!assignedLayers.add(layer)) {
                    throw new JsonParseException("render type assigned more than once for layer " + layer);
                }
            }
        }
    }

    private static Map<String, String> parseTextures(JsonObject json) {
        if (!json.has("textures")) {
            return Map.of();
        }
        JsonObject textureJson = requiredObject(json, "textures");
        Map<String, String> textures = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : textureJson.entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new JsonParseException("texture " + entry.getKey() + " must be a string");
            }
            textures.put(entry.getKey(), entry.getValue().getAsString());
        }
        return textures;
    }

    private static List<Element> parseElements(JsonObject json) {
        if (!json.has("elements")) {
            return List.of();
        }
        JsonArray elementJson = requiredArray(json, "elements");
        List<Element> elements = new ArrayList<>(elementJson.size());
        for (int index = 0; index < elementJson.size(); index++) {
            JsonObject value = requiredObject(elementJson.get(index), "elements[" + index + "]");
            if (value.has("forge_data")) {
                throw new JsonParseException("forge_data must be replaced by neoforge_data");
            }
            Vec3 from = parseVector(value, "from", null);
            Vec3 to = parseVector(value, "to", null);
            validateExtent(from, "from");
            validateExtent(to, "to");
            ElementRotation rotation = parseElementRotation(value);
            boolean shade = optionalBoolean(value, "shade", true);
            ExtraFaceData faceData = value.has("neoforge_data")
                    ? parseExtraFaceData(value.get("neoforge_data"), ExtraFaceData.DEFAULT)
                    : ExtraFaceData.DEFAULT;
            Map<Direction, Face> faces = parseFaces(value, from, to, faceData);
            elements.add(new Element(from, to, faces, rotation, shade));
        }
        return elements;
    }

    private static Map<Direction, Face> parseFaces(
            JsonObject element,
            Vec3 from,
            Vec3 to,
            ExtraFaceData elementFaceData
    ) {
        JsonObject faceJson = requiredObject(element, "faces");
        if (faceJson.isEmpty()) {
            throw new JsonParseException("element must contain at least one face");
        }
        Map<Direction, Face> faces = new EnumMap<>(Direction.class);
        for (Map.Entry<String, JsonElement> entry : faceJson.entrySet()) {
            Direction direction = Direction.parse(entry.getKey());
            JsonObject value = requiredObject(entry.getValue(), "face " + entry.getKey());
            if (value.has("forge_data")) {
                throw new JsonParseException("forge_data must be replaced by neoforge_data");
            }
            String texture = requiredString(value, "texture");
            int tintIndex = optionalInt(value, "tintindex", -1);
            int rotation = optionalInt(value, "rotation", 0);
            if (rotation < 0 || rotation > 270 || rotation % 90 != 0) {
                throw new JsonParseException("face rotation must be 0, 90, 180, or 270");
            }
            if (value.has("cullface")) {
                Direction.parse(requiredString(value, "cullface"));
            }
            double[] uv = value.has("uv") ? parseArray(value, "uv", 4) : direction.defaultUv(from, to);
            ExtraFaceData faceData = value.has("neoforge_data")
                    ? parseExtraFaceData(value.get("neoforge_data"), elementFaceData)
                    : elementFaceData;
            faces.put(direction, new Face(texture, tintIndex, uv, rotation, faceData));
        }
        return faces;
    }

    private static ExtraFaceData parseExtraFaceData(JsonElement element, ExtraFaceData fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException("neoforge_data must be an object");
        }
        JsonObject json = element.getAsJsonObject();
        int color = json.has("color") ? parseArgbColor(json.get("color")) : fallback.color();
        int blockLight = json.has("block_light") ? json.get("block_light").getAsInt() : fallback.blockLight();
        int skyLight = json.has("sky_light") ? json.get("sky_light").getAsInt() : fallback.skyLight();
        boolean ambientOcclusion = json.has("ambient_occlusion")
                ? json.get("ambient_occlusion").getAsBoolean()
                : fallback.ambientOcclusion();
        if (blockLight < 0 || blockLight > 15) {
            throw new JsonParseException("block_light must be between 0 and 15");
        }
        if (skyLight < 0 || skyLight > 15) {
            throw new JsonParseException("sky_light must be between 0 and 15");
        }
        return new ExtraFaceData(color, blockLight, skyLight, ambientOcclusion);
    }

    private static int parseArgbColor(JsonElement element) {
        if (!element.isJsonPrimitive()) {
            throw new JsonParseException("face color must be an integer or hexadecimal string");
        }
        if (element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        if (element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            try {
                return (int) Long.parseUnsignedLong(value, 16);
            } catch (NumberFormatException exception) {
                throw new JsonParseException("face color must be a hexadecimal ARGB value: " + value, exception);
            }
        }
        throw new JsonParseException("face color must be an integer or hexadecimal string");
    }

    private static ElementRotation parseElementRotation(JsonObject element) {
        if (!element.has("rotation")) {
            return null;
        }
        JsonObject json = requiredObject(element, "rotation");
        Vec3 origin = parseVector(json, "origin", null).scale(1.0 / 16.0);
        Axis axis;
        try {
            axis = Axis.valueOf(requiredString(json, "axis").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException("element rotation axis must be x, y, or z", exception);
        }
        double angle = requiredDouble(json, "angle");
        if (angle != 0.0 && Math.abs(angle) != 22.5 && Math.abs(angle) != 45.0) {
            throw new JsonParseException("element rotation angle must be -45, -22.5, 0, 22.5, or 45");
        }
        return new ElementRotation(origin, axis, angle, optionalBoolean(json, "rescale", false));
    }

    private static Transform parseGuiTransform(JsonObject model) {
        if (!model.has("display")) {
            return null;
        }
        JsonObject display = requiredObject(model, "display");
        if (!display.has("gui")) {
            return null;
        }
        JsonObject gui = requiredObject(display, "gui");
        Vec3 rotation = parseVector(gui, "rotation", Vec3.ZERO);
        Vec3 translation = parseVector(gui, "translation", Vec3.ZERO)
                .scale(1.0 / 16.0)
                .clamp(-5.0, 5.0);
        Vec3 scale = parseVector(gui, "scale", Vec3.ONE).clamp(-4.0, 4.0);
        Vec3 rightRotation = parseVector(gui, "right_rotation", Vec3.ZERO);
        return new Transform(rotation, translation, scale, rightRotation);
    }

    private static GuiLight parseGuiLight(JsonObject json) {
        if (!json.has("gui_light")) {
            return null;
        }
        return switch (requiredString(json, "gui_light")) {
            case "front" -> GuiLight.FRONT;
            case "side" -> GuiLight.SIDE;
            default -> throw new JsonParseException("gui_light must be front or side");
        };
    }

    private TextureRegion textureFrame(ItemModelId textureId, BufferedImage image) throws IOException {
        String metadataPath = textureId.textureResourcePath() + ".mcmeta";
        Optional<byte[]> metadataBytes = this.resources.read(metadataPath, MAXIMUM_MODEL_BYTES);
        if (metadataBytes.isEmpty()) {
            return TextureRegion.full(toArgb(image));
        }

        try {
            JsonObject root = JsonParser.parseString(new String(metadataBytes.get(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("fusion")) {
                return FusionItemRenderIntegration.isolatedTexture(textureId, image, root);
            }
            if (!root.has("animation")) {
                return TextureRegion.full(toArgb(image));
            }
            JsonObject animation = requiredObject(root, "animation");
            int frameWidth = optionalInt(animation, "width", -1);
            int frameHeight = optionalInt(animation, "height", -1);
            if (frameWidth < 1 && frameHeight < 1) {
                frameWidth = Math.min(image.getWidth(), image.getHeight());
                frameHeight = frameWidth;
            } else if (frameWidth < 1) {
                frameWidth = image.getWidth();
            } else if (frameHeight < 1) {
                frameHeight = image.getHeight();
            }
            if (frameWidth < 1 || frameHeight < 1 || image.getWidth() % frameWidth != 0 || image.getHeight() % frameHeight != 0) {
                throw new JsonParseException("animation frame size does not divide the texture");
            }

            int frameIndex = firstFrameIndex(animation);
            int columns = image.getWidth() / frameWidth;
            int rows = image.getHeight() / frameHeight;
            if (frameIndex < 0 || frameIndex >= columns * rows) {
                throw new JsonParseException("animation frame index is outside the texture");
            }
            return TextureRegion.full(toArgb(image.getSubimage(
                    frameIndex % columns * frameWidth,
                    frameIndex / columns * frameHeight,
                    frameWidth,
                    frameHeight
            )));
        } catch (RuntimeException exception) {
            throw new ItemRenderException(
                    ItemRenderException.Kind.RESOURCE_ERROR,
                    "invalid animation metadata",
                    "Invalid animation metadata for texture " + textureId + ": " + exception.getMessage(),
                    exception
            );
        }
    }

    private static int firstFrameIndex(JsonObject animation) {
        if (!animation.has("frames")) {
            return 0;
        }
        JsonArray frames = requiredArray(animation, "frames");
        if (frames.isEmpty()) {
            return 0;
        }
        JsonElement first = frames.get(0);
        return first.isJsonObject() ? requiredInt(first.getAsJsonObject(), "index") : first.getAsInt();
    }

    private static BufferedImage toArgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB && source.getMinX() == 0 && source.getMinY() == 0) {
            return source;
        }
        BufferedImage converted = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = converted.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return converted;
    }

    private static Vec3 parseVector(JsonObject json, String name, Vec3 fallback) {
        if (!json.has(name)) {
            if (fallback == null) {
                throw new JsonParseException("missing " + name);
            }
            return fallback;
        }
        double[] values = parseArray(json, name, 3);
        return new Vec3(values[0], values[1], values[2]);
    }

    private static double[] parseArray(JsonObject json, String name, int length) {
        JsonArray array = requiredArray(json, name);
        if (array.size() != length) {
            throw new JsonParseException(name + " must contain " + length + " numbers");
        }
        double[] values = new double[length];
        for (int index = 0; index < length; index++) {
            values[index] = array.get(index).getAsDouble();
        }
        return values;
    }

    private static void validateExtent(Vec3 vector, String name) {
        if (vector.x() < -16 || vector.x() > 32
                || vector.y() < -16 || vector.y() > 32
                || vector.z() < -16 || vector.z() > 32) {
            throw new JsonParseException(name + " exceeds the allowed -16 to 32 range");
        }
    }

    private static JsonObject requiredObject(JsonObject json, String name) {
        if (!json.has(name)) {
            throw new JsonParseException("missing " + name);
        }
        return requiredObject(json.get(name), name);
    }

    private static JsonObject requiredObject(JsonElement json, String name) {
        if (!json.isJsonObject()) {
            throw new JsonParseException(name + " must be an object");
        }
        return json.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name).isJsonArray()) {
            throw new JsonParseException(name + " must be an array");
        }
        return json.getAsJsonArray(name);
    }

    private static String requiredString(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name).isJsonPrimitive() || !json.get(name).getAsJsonPrimitive().isString()) {
            throw new JsonParseException(name + " must be a string");
        }
        return json.get(name).getAsString();
    }

    private static int requiredInt(JsonObject json, String name) {
        if (!json.has(name)) {
            throw new JsonParseException("missing " + name);
        }
        return json.get(name).getAsInt();
    }

    private static int optionalInt(JsonObject json, String name, int fallback) {
        return json.has(name) ? json.get(name).getAsInt() : fallback;
    }

    private static double requiredDouble(JsonObject json, String name) {
        if (!json.has(name)) {
            throw new JsonParseException("missing " + name);
        }
        return json.get(name).getAsDouble();
    }

    private static boolean optionalBoolean(JsonObject json, String name, boolean fallback) {
        return json.has(name) ? json.get(name).getAsBoolean() : fallback;
    }

    private static String formatCycle(Set<ItemModelId> resolving, ItemModelId repeated) {
        StringBuilder result = new StringBuilder();
        for (ItemModelId modelId : resolving) {
            if (!result.isEmpty()) {
                result.append(" -> ");
            }
            result.append(modelId);
        }
        return result.append(" -> ").append(repeated).toString();
    }

    record ResolvedModel(
            ItemModelId id,
            ModelKind kind,
            Map<String, String> textures,
            List<Element> elements,
            Transform guiTransform,
            GuiLight guiLight,
            List<ResolvedModel> parts,
            Map<Integer, ExtraFaceData> layerFaceData,
            ModelTransform rootTransform,
            Map<String, Boolean> visibility,
            List<MeshFace> meshFaces
    ) {

        static ResolvedModel generatedRoot() {
            return new ResolvedModel(
                    BUILTIN_GENERATED,
                    ModelKind.GENERATED,
                    Map.of(),
                    List.of(),
                    Transform.IDENTITY,
                    GuiLight.FRONT,
                    List.of(),
                    Map.of(),
                    ModelTransform.IDENTITY,
                    Map.of(),
                    List.of()
            );
        }

        boolean hasTexture(String name) {
            return this.textures.containsKey(name);
        }

        ItemModelId resolveTexture(String reference) throws ItemRenderException {
            String name = reference.startsWith("#") ? reference.substring(1) : reference;
            Set<String> visited = new LinkedHashSet<>();
            while (true) {
                if (!visited.add(name)) {
                    throw new ItemRenderException("Texture reference cycle in item model " + this.id + ": " + visited);
                }
                String value = this.textures.get(name);
                if (value == null) {
                    throw new ItemRenderException("Item model " + this.id + " has no texture named " + name);
                }
                if (value.startsWith("#")) {
                    name = value.substring(1);
                    continue;
                }
                try {
                    return ItemModelId.parse(value);
                } catch (IllegalArgumentException exception) {
                    throw new ItemRenderException("Invalid texture reference " + value + " in item model " + this.id, exception);
                }
            }
        }

        ItemModelId resolveMeshTexture(MeshFace face) throws ItemRenderException {
            if (face.texture() == null) {
                return null;
            }
            if (face.textureReference()) {
                return resolveTexture(face.texture());
            }
            try {
                return ItemModelId.parse(face.texture());
            } catch (IllegalArgumentException exception) {
                throw new ItemRenderException("Invalid OBJ texture " + face.texture() + " in item model " + this.id, exception);
            }
        }
    }

    private record RawModel(
            ItemModelId parent,
            Map<String, String> textures,
            List<Element> elements,
            Transform guiTransform,
            GuiLight guiLight,
            LoaderKind loaderKind,
            List<NamedRawModel> compositeParts,
            Map<Integer, ExtraFaceData> layerFaceData,
            ModelTransform rootTransform,
            Map<String, Boolean> visibility,
            RawModel separateGuiModel,
            ObjSpec objSpec
    ) {
    }

    private record NamedRawModel(String name, RawModel model) {
    }

    private record ObjSpec(
            ItemModelId model,
            String materialOverride,
            boolean flipV,
            boolean shadeQuads,
            boolean emissiveAmbient
    ) {
    }

    private enum LoaderKind {
        NORMAL,
        COMPOSITE,
        ITEM_LAYERS,
        SEPARATE_TRANSFORMS,
        OBJ
    }

    enum ModelKind {
        NONE,
        GENERATED,
        ELEMENTS,
        COMPOSITE,
        MESH
    }

    enum GuiLight {
        FRONT,
        SIDE
    }

    enum Axis {
        X,
        Y,
        Z
    }

    enum Direction {
        DOWN,
        UP,
        NORTH,
        SOUTH,
        WEST,
        EAST;

        static Direction parse(String value) {
            try {
                return valueOf(value.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("unknown direction " + value, exception);
            }
        }

        Vec3[] vertices(Vec3 from, Vec3 to) {
            double minX = from.x() / 16.0;
            double minY = from.y() / 16.0;
            double minZ = from.z() / 16.0;
            double maxX = to.x() / 16.0;
            double maxY = to.y() / 16.0;
            double maxZ = to.z() / 16.0;
            return switch (this) {
                case DOWN -> new Vec3[]{
                        new Vec3(minX, minY, maxZ), new Vec3(minX, minY, minZ),
                        new Vec3(maxX, minY, minZ), new Vec3(maxX, minY, maxZ)
                };
                case UP -> new Vec3[]{
                        new Vec3(minX, maxY, minZ), new Vec3(minX, maxY, maxZ),
                        new Vec3(maxX, maxY, maxZ), new Vec3(maxX, maxY, minZ)
                };
                case NORTH -> new Vec3[]{
                        new Vec3(maxX, maxY, minZ), new Vec3(maxX, minY, minZ),
                        new Vec3(minX, minY, minZ), new Vec3(minX, maxY, minZ)
                };
                case SOUTH -> new Vec3[]{
                        new Vec3(minX, maxY, maxZ), new Vec3(minX, minY, maxZ),
                        new Vec3(maxX, minY, maxZ), new Vec3(maxX, maxY, maxZ)
                };
                case WEST -> new Vec3[]{
                        new Vec3(minX, maxY, minZ), new Vec3(minX, minY, minZ),
                        new Vec3(minX, minY, maxZ), new Vec3(minX, maxY, maxZ)
                };
                case EAST -> new Vec3[]{
                        new Vec3(maxX, maxY, maxZ), new Vec3(maxX, minY, maxZ),
                        new Vec3(maxX, minY, minZ), new Vec3(maxX, maxY, minZ)
                };
            };
        }

        double[] defaultUv(Vec3 from, Vec3 to) {
            return switch (this) {
                case DOWN -> new double[]{from.x(), 16.0 - to.z(), to.x(), 16.0 - from.z()};
                case UP -> new double[]{from.x(), from.z(), to.x(), to.z()};
                case NORTH -> new double[]{16.0 - to.x(), 16.0 - to.y(), 16.0 - from.x(), 16.0 - from.y()};
                case SOUTH -> new double[]{from.x(), 16.0 - to.y(), to.x(), 16.0 - from.y()};
                case WEST -> new double[]{from.z(), 16.0 - to.y(), to.z(), 16.0 - from.y()};
                case EAST -> new double[]{16.0 - to.z(), 16.0 - to.y(), 16.0 - from.z(), 16.0 - from.y()};
            };
        }
    }

    record Element(Vec3 from, Vec3 to, Map<Direction, Face> faces, ElementRotation rotation, boolean shade) {
    }

    record Face(String texture, int tintIndex, double[] uv, int rotation, ExtraFaceData faceData) {

        double u(int vertexIndex) {
            int shifted = (vertexIndex + this.rotation / 90) % 4;
            return this.uv[shifted == 0 || shifted == 1 ? 0 : 2];
        }

        double v(int vertexIndex) {
            int shifted = (vertexIndex + this.rotation / 90) % 4;
            return this.uv[shifted == 0 || shifted == 3 ? 1 : 3];
        }
    }

    record ElementRotation(Vec3 origin, Axis axis, double angle, boolean rescale) {
    }

    record MeshVertex(Vec3 position, double u, double v) {
    }

    record MeshFace(
            List<MeshVertex> vertices,
            String texture,
            boolean textureReference,
            int tintIndex,
            int color,
            boolean shade,
            boolean emissive,
            String rootPart,
            String nestedPart
    ) {

        boolean isVisible(Map<String, Boolean> visibility) {
            return visibility.getOrDefault(this.rootPart, true)
                    && visibility.getOrDefault(this.nestedPart, true);
        }
    }

    record ExtraFaceData(int color, int blockLight, int skyLight, boolean ambientOcclusion) {

        static final ExtraFaceData DEFAULT = new ExtraFaceData(0xFFFFFFFF, 0, 0, true);
    }

    record Transform(Vec3 rotation, Vec3 translation, Vec3 scale, Vec3 rightRotation) {

        static final Transform IDENTITY = new Transform(Vec3.ZERO, Vec3.ZERO, Vec3.ONE, Vec3.ZERO);

        boolean isIdentity() {
            return this.equals(IDENTITY);
        }
    }

    record Vec3(double x, double y, double z) {

        static final Vec3 ZERO = new Vec3(0, 0, 0);
        static final Vec3 ONE = new Vec3(1, 1, 1);

        Vec3 add(Vec3 other) {
            return new Vec3(this.x + other.x, this.y + other.y, this.z + other.z);
        }

        Vec3 subtract(Vec3 other) {
            return new Vec3(this.x - other.x, this.y - other.y, this.z - other.z);
        }

        Vec3 multiply(Vec3 other) {
            return new Vec3(this.x * other.x, this.y * other.y, this.z * other.z);
        }

        Vec3 scale(double factor) {
            return new Vec3(this.x * factor, this.y * factor, this.z * factor);
        }

        Vec3 clamp(double minimum, double maximum) {
            return new Vec3(
                    Math.clamp(this.x, minimum, maximum),
                    Math.clamp(this.y, minimum, maximum),
                    Math.clamp(this.z, minimum, maximum)
            );
        }

        double dot(Vec3 other) {
            return this.x * other.x + this.y * other.y + this.z * other.z;
        }

        Vec3 cross(Vec3 other) {
            return new Vec3(
                    this.y * other.z - this.z * other.y,
                    this.z * other.x - this.x * other.z,
                    this.x * other.y - this.y * other.x
            );
        }

        Vec3 normalize() {
            double length = Math.sqrt(dot(this));
            return length == 0 ? ZERO : scale(1.0 / length);
        }
    }
}
