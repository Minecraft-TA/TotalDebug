package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.util.Arrays;

import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelRepository.Element;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelRepository.ElementRotation;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelRepository.ExtraFaceData;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelRepository.Face;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelRepository.GuiLight;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelRepository.MeshFace;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelRepository.MeshVertex;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelRepository.ResolvedModel;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelRepository.Transform;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.ItemModelRepository.Vec3;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class SoftwareItemRenderer {

    private static final List<String> GENERATED_LAYERS = List.of("layer0", "layer1", "layer2", "layer3", "layer4");
    private static final Vec3 LIGHT_ZERO = new Vec3(-0.2, 1.0, 0.7).normalize();
    private static final Vec3 LIGHT_ONE = new Vec3(0.2, 1.0, -0.7).normalize();
    private static final TextureRegion WHITE_TEXTURE;

    static {
        BufferedImage white = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        white.setRGB(0, 0, 0xFFFFFFFF);
        WHITE_TEXTURE = TextureRegion.full(white);
    }

    private final ItemModelRepository models;

    SoftwareItemRenderer(ItemModelRepository models) {
        this.models = models;
    }

    BufferedImage render(ItemRenderRequest request) throws IOException {
        ResolvedModel model = this.models.resolve(request.modelId());
        BufferedImage result = new BufferedImage(request.size(), request.size(), BufferedImage.TYPE_INT_ARGB);
        // The flat path draws at full brightness, which only front light gives.
        if (model.kind() == ItemModelRepository.ModelKind.GENERATED && model.guiLight() == GuiLight.FRONT
                && model.guiTransform().isIdentity() && model.rootTransform().isIdentity()) {
            renderFlatGenerated(model, request, result);
            return result;
        }
        List<Triangle> triangles = new ArrayList<>();
        double[] opaqueDepth = new double[request.size() * request.size()];
        Arrays.fill(opaqueDepth, Double.NEGATIVE_INFINITY);
        collectModel(
                model,
                request,
                triangles,
                model.guiTransform(),
                model.guiLight(),
                ModelTransform.IDENTITY
        );
        renderTriangles(result, opaqueDepth, triangles);
        return result;
    }

    private void collectModel(
            ResolvedModel model,
            ItemRenderRequest request,
            List<Triangle> triangles,
            Transform transform,
            GuiLight guiLight,
            ModelTransform inheritedRootTransform
    ) throws IOException {
        ModelTransform rootTransform = inheritedRootTransform.compose(model.rootTransform());
        switch (model.kind()) {
            case GENERATED -> collectGenerated(model, request, triangles, transform, rootTransform, guiLight);
            case ELEMENTS -> collectElements(model, request, triangles, transform, rootTransform, guiLight);
            case MESH -> collectMesh(model, request, triangles, transform, rootTransform, guiLight);
            case FLUID_CONTAINER -> collectFluidContainer(model, request, triangles, transform, rootTransform);
            case COMPOSITE -> {
                for (ResolvedModel part : model.parts()) {
                    collectModel(part, request, triangles, transform, guiLight, rootTransform);
                }
            }
            case NONE -> throw noGeometry(model, "no renderable geometry");
        }
    }

    private void renderFlatGenerated(ResolvedModel model, ItemRenderRequest request, BufferedImage target) throws IOException {
        if (!model.hasTexture("layer0")) {
            throw noGeometry(model, "generated model has no layer0 texture");
        }
        for (int layerIndex = 0; layerIndex < GENERATED_LAYERS.size(); layerIndex++) {
            String layer = GENERATED_LAYERS.get(layerIndex);
            if (!model.hasTexture(layer)) {
                break;
            }
            TextureRegion texture = this.models.texture(model.resolveTexture('#' + layer));
            ExtraFaceData faceData = model.layerFaceData().getOrDefault(layerIndex, ExtraFaceData.DEFAULT);
            int tint = multiplyArgb(request.tintColor(layerIndex), faceData.color());
            drawScaledLayer(target, texture, tint);
        }
    }

    private void collectGenerated(
            ResolvedModel model,
            ItemRenderRequest request,
            List<Triangle> triangles,
            Transform transform,
            ModelTransform rootTransform,
            GuiLight guiLight
    ) throws IOException {
        boolean foundLayer = false;
        for (int layerIndex = 0; layerIndex < GENERATED_LAYERS.size(); layerIndex++) {
            String layer = GENERATED_LAYERS.get(layerIndex);
            if (!model.hasTexture(layer)) {
                break;
            }
            foundLayer = true;
            TextureRegion texture = this.models.texture(model.resolveTexture('#' + layer));
            ExtraFaceData faceData = model.layerFaceData().getOrDefault(layerIndex, ExtraFaceData.DEFAULT);
            int tint = multiplyArgb(request.tintColor(layerIndex), faceData.color());
            addGeneratedLayer(
                    triangles,
                    texture,
                    null,
                    tint,
                    request.size(),
                    transform,
                    rootTransform,
                    guiLight,
                    faceData
            );
        }
        if (!foundLayer) {
            throw noGeometry(model, "generated model has no layer0 texture");
        }
    }

    private void collectFluidContainer(
            ResolvedModel model, ItemRenderRequest request, List<Triangle> triangles,
            Transform transform, ModelTransform rootTransform
    ) throws IOException {
        var container = model.fluidContainer();
        ItemModelId fluidId = request.fluidId() == null ? container.fluidId() : request.fluidId();
        FluidAppearance fluid = fluidId.equals(ItemModelId.parse("minecraft:empty"))
                ? null : this.models.fluidAppearance(fluidId);
        if (fluid != null && container.flipGas() && fluid.lighterThanAir()) {
            rootTransform = rootTransform.compose(ModelTransform.scale(-1, -1, 1).around(new Vec3(0.5, 0.5, 0.5)));
        }
        int before = triangles.size();
        TextureRegion base = model.hasTexture("base") ? this.models.texture(model.resolveTexture("base")) : null;
        if (base != null) {
            addGeneratedLayer(triangles, base, null, request.tintColor(0), request.size(), transform,
                    rootTransform, GuiLight.FRONT, ExtraFaceData.DEFAULT);
        }
        if (fluid != null && model.hasTexture("fluid")) {
            TextureRegion mask = this.models.fluidMask(model.resolveTexture("fluid"));
            TextureRegion texture = this.models.texture(fluid.stillTexture());
            int tint = request.tintColors().getOrDefault(1, fluid.tint());
            ExtraFaceData light = container.applyLuminosity() && fluid.lightLevel() > 0
                    ? new ExtraFaceData(0xFFFFFFFF, 15, 15, false) : ExtraFaceData.DEFAULT;
            addGeneratedLayer(triangles, texture, mask, tint, request.size(), transform,
                    rootTransform.compose(ModelTransform.scale(1, 1, 1.002).around(new Vec3(0.5, 0.5, 0.5))),
                    GuiLight.FRONT, light);
        }
        if (model.hasTexture("cover") && (!container.coverIsMask() || base != null)) {
            // A cover that is not a mask is drawn as it is, animated or not.
            TextureRegion cover = container.coverIsMask() ? this.models.fluidMask(model.resolveTexture("cover"))
                    : this.models.texture(model.resolveTexture("cover"));
            addGeneratedLayer(triangles, container.coverIsMask() ? base : cover, cover, request.tintColor(2),
                    request.size(), transform,
                    rootTransform.compose(ModelTransform.scale(1, 1, 1.004).around(new Vec3(0.5, 0.5, 0.5))),
                    GuiLight.FRONT, ExtraFaceData.DEFAULT);
        }
        if (triangles.size() == before) {
            throw noGeometry(model, "fluid container has no visible layers");
        }
    }

    private void collectElements(
            ResolvedModel model,
            ItemRenderRequest request,
            List<Triangle> triangles,
            Transform transform,
            ModelTransform rootTransform,
            GuiLight guiLight
    ) throws IOException {
        for (Element element : model.elements()) {
            for (Map.Entry<ItemModelRepository.Direction, Face> faceEntry : element.faces().entrySet()) {
                Face face = faceEntry.getValue();
                TextureRegion texture = this.models.texture(model.resolveTexture(face.texture()));
                Vec3[] modelVertices = faceEntry.getKey().vertices(element.from(), element.to());
                Vertex[] vertices = new Vertex[4];
                Vec3[] transformed = new Vec3[4];
                for (int vertexIndex = 0; vertexIndex < vertices.length; vertexIndex++) {
                    Vec3 elementVertex = applyElementRotation(modelVertices[vertexIndex], element.rotation());
                    Vec3 transformedVertex = applyGuiTransform(rootTransform.apply(elementVertex), transform);
                    transformed[vertexIndex] = transformedVertex;
                    vertices[vertexIndex] = project(
                            transformedVertex,
                            face.u(vertexIndex) / 16.0,
                            face.v(vertexIndex) / 16.0,
                            request.size()
                    );
                }

                Vec3 normal = transformed[1].subtract(transformed[0])
                        .cross(transformed[2].subtract(transformed[0]))
                        .normalize();
                Vec3 localNormal = modelVertices[1].subtract(modelVertices[0])
                        .cross(modelVertices[2].subtract(modelVertices[0])).normalize();
                Vec3 outward = applyGuiTransform(rootTransform.apply(applyElementRotation(
                        modelVertices[0].add(localNormal), element.rotation())), transform).subtract(transformed[0]);
                if (normal.dot(outward) < 0) {
                    normal = normal.scale(-1);
                }
                // Items render with back faces culled; a rear face would otherwise blend through a translucent front.
                if (normal.z() <= 0) {
                    continue;
                }
                double brightness = brightness(guiLight, element.shade(), normal, face.faceData());
                int tint = multiplyArgb(request.tintColor(face.tintIndex()), face.faceData().color());
                triangles.add(new Triangle(vertices[0], vertices[1], vertices[2], texture, tint, brightness));
                triangles.add(new Triangle(vertices[0], vertices[2], vertices[3], texture, tint, brightness));
            }
        }
    }

    private void collectMesh(
            ResolvedModel model,
            ItemRenderRequest request,
            List<Triangle> triangles,
            Transform transform,
            ModelTransform rootTransform,
            GuiLight guiLight
    ) throws IOException {
        boolean anyFace = false;
        ModelTransform objTransform = rootTransform.isIdentity()
                ? rootTransform
                : rootTransform.around(new Vec3(0.5, 0.5, 0.5));
        for (MeshFace face : model.meshFaces()) {
            if (!face.isVisible(model.visibility())) {
                continue;
            }
            if (face.vertices().size() < 3) {
                continue;
            }
            anyFace = true;
            TextureRegion texture = face.texture() == null
                    ? WHITE_TEXTURE
                    : this.models.texture(model.resolveMeshTexture(face));
            Vertex[] vertices = new Vertex[face.vertices().size()];
            Vec3[] transformed = new Vec3[face.vertices().size()];
            for (int index = 0; index < face.vertices().size(); index++) {
                MeshVertex vertex = face.vertices().get(index);
                Vec3 position = applyGuiTransform(objTransform.apply(vertex.position()), transform);
                transformed[index] = position;
                vertices[index] = project(position, vertex.u(), vertex.v(), request.size());
            }
            Vec3 normal = transformed[1].subtract(transformed[0])
                    .cross(transformed[2].subtract(transformed[0]))
                    .normalize();
            Vec3 local = face.vertices().get(0).position();
            Vec3 localNormal = face.vertices().get(1).position().subtract(local)
                    .cross(face.vertices().get(2).position().subtract(local)).normalize();
            Vec3 outward = applyGuiTransform(objTransform.apply(local.add(localNormal)), transform).subtract(transformed[0]);
            if (normal.dot(outward) < 0) {
                normal = normal.scale(-1);
            }
            // Items render with back faces culled, like element faces.
            if (normal.z() <= 0) {
                continue;
            }
            ExtraFaceData light = face.emissive()
                    ? new ExtraFaceData(0xFFFFFFFF, 15, 15, false)
                    : ExtraFaceData.DEFAULT;
            double brightness = brightness(guiLight, face.shade(), normal, light);
            int tint = multiplyArgb(request.tintColor(face.tintIndex()), face.color());
            triangles.add(new Triangle(vertices[0], vertices[1], vertices[2], texture, tint, brightness));
            if (vertices.length >= 4) {
                triangles.add(new Triangle(vertices[0], vertices[2], vertices[3], texture, tint, brightness));
            }
        }
        // Faces culled because they face away still draw nothing, as in the game: an empty image, not an error.
        if (!anyFace) {
            throw noGeometry(model, "OBJ model has no visible faces");
        }
    }

    private static void addGeneratedLayer(
            List<Triangle> triangles,
            TextureRegion texture,
            TextureRegion mask,
            int tint,
            int renderSize,
            Transform guiTransform,
            ModelTransform rootTransform,
            GuiLight guiLight,
            ExtraFaceData faceData
    ) {
        double front = 8.5 / 16.0;
        double back = 7.5 / 16.0;
        if (mask == null) {
            addTexturedQuad(
                    triangles,
                    new Vec3[]{
                            new Vec3(0, 1, front), new Vec3(0, 0, front),
                            new Vec3(1, 0, front), new Vec3(1, 1, front)
                    },
                    new double[][]{{0, 0}, {0, 1}, {1, 1}, {1, 0}},
                    texture,
                    tint,
                    renderSize,
                    guiTransform,
                    rootTransform,
                    guiLight,
                    true,
                    faceData
            );
            addTexturedQuad(
                    triangles,
                    new Vec3[]{
                            new Vec3(1, 1, back), new Vec3(1, 0, back),
                            new Vec3(0, 0, back), new Vec3(0, 1, back)
                    },
                    new double[][]{{1, 0}, {1, 1}, {0, 1}, {0, 0}},
                    texture,
                    tint,
                    renderSize,
                    guiTransform,
                    rootTransform,
                    guiLight,
                    true,
                    faceData
            );
        }

        TextureRegion shape = mask == null ? texture : mask;
        int width = shape.columns();
        int height = shape.rows();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((shape.pixel(x, y) >>> 24) == 0) {
                    continue;
                }
                double x0 = shape.u0(x);
                double x1 = shape.u1(x);
                double y0 = 1.0 - shape.v0(y);
                double y1 = 1.0 - shape.v1(y);
                double u0 = shape.u0(x);
                double u1 = shape.u1(x);
                double v0 = shape.v0(y);
                double v1 = shape.v1(y);
                if (mask != null) {
                    addTexturedQuad(triangles,
                            new Vec3[]{new Vec3(x0, y0, front), new Vec3(x0, y1, front), new Vec3(x1, y1, front), new Vec3(x1, y0, front)},
                            new double[][]{{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}},
                            texture, tint, renderSize, guiTransform, rootTransform, guiLight, true, faceData);
                    addTexturedQuad(triangles,
                            new Vec3[]{new Vec3(x1, y0, back), new Vec3(x1, y1, back), new Vec3(x0, y1, back), new Vec3(x0, y0, back)},
                            new double[][]{{u1, v0}, {u1, v1}, {u0, v1}, {u0, v0}},
                            texture, tint, renderSize, guiTransform, rootTransform, guiLight, true, faceData);
                }
                double edgeU = (u0 + u1) / 2;
                double edgeV = (v0 + v1) / 2;
                if (x == 0 || (shape.pixel(x - 1, y) >>> 24) == 0) {
                    addTexturedQuad(triangles,
                            new Vec3[]{new Vec3(x0, y0, back), new Vec3(x0, y1, back), new Vec3(x0, y1, front), new Vec3(x0, y0, front)},
                            new double[][]{{edgeU, v0}, {edgeU, v1}, {edgeU, v1}, {edgeU, v0}},
                            texture, tint, renderSize, guiTransform, rootTransform, guiLight, true, faceData);
                }
                if (x == width - 1 || (shape.pixel(x + 1, y) >>> 24) == 0) {
                    addTexturedQuad(triangles,
                            new Vec3[]{new Vec3(x1, y0, front), new Vec3(x1, y1, front), new Vec3(x1, y1, back), new Vec3(x1, y0, back)},
                            new double[][]{{edgeU, v0}, {edgeU, v1}, {edgeU, v1}, {edgeU, v0}},
                            texture, tint, renderSize, guiTransform, rootTransform, guiLight, true, faceData);
                }
                if (y == 0 || (shape.pixel(x, y - 1) >>> 24) == 0) {
                    addTexturedQuad(triangles,
                            new Vec3[]{new Vec3(x0, y0, back), new Vec3(x0, y0, front), new Vec3(x1, y0, front), new Vec3(x1, y0, back)},
                            new double[][]{{u0, edgeV}, {u0, edgeV}, {u1, edgeV}, {u1, edgeV}},
                            texture, tint, renderSize, guiTransform, rootTransform, guiLight, true, faceData);
                }
                if (y == height - 1 || (shape.pixel(x, y + 1) >>> 24) == 0) {
                    addTexturedQuad(triangles,
                            new Vec3[]{new Vec3(x0, y1, front), new Vec3(x0, y1, back), new Vec3(x1, y1, back), new Vec3(x1, y1, front)},
                            new double[][]{{u0, edgeV}, {u0, edgeV}, {u1, edgeV}, {u1, edgeV}},
                            texture, tint, renderSize, guiTransform, rootTransform, guiLight, true, faceData);
                }
            }
        }
    }

    private static void addTexturedQuad(
            List<Triangle> triangles,
            Vec3[] modelVertices,
            double[][] textureCoordinates,
            TextureRegion texture,
            int tint,
            int renderSize,
            Transform guiTransform,
            ModelTransform rootTransform,
            GuiLight guiLight,
            boolean shade,
            ExtraFaceData faceData
    ) {
        Vertex[] vertices = new Vertex[4];
        Vec3[] transformed = new Vec3[4];
        for (int index = 0; index < 4; index++) {
            transformed[index] = applyGuiTransform(rootTransform.apply(modelVertices[index]), guiTransform);
            vertices[index] = project(
                    transformed[index],
                    textureCoordinates[index][0],
                    textureCoordinates[index][1],
                    renderSize
            );
        }
        Vec3 normal = transformed[1].subtract(transformed[0])
                .cross(transformed[2].subtract(transformed[0]))
                .normalize();
        Vec3 localNormal = modelVertices[1].subtract(modelVertices[0])
                .cross(modelVertices[2].subtract(modelVertices[0])).normalize();
        Vec3 outward = applyGuiTransform(rootTransform.apply(modelVertices[0].add(localNormal)), guiTransform)
                .subtract(transformed[0]);
        if (normal.dot(outward) < 0) {
            normal = normal.scale(-1);
        }
        // Generated sprites are closed extrusions. Their hidden opposite faces must not blend again.
        if (normal.z() <= 0) {
            return;
        }
        double brightness = brightness(guiLight, shade, normal, faceData);
        triangles.add(new Triangle(vertices[0], vertices[1], vertices[2], texture, tint, brightness));
        triangles.add(new Triangle(vertices[0], vertices[2], vertices[3], texture, tint, brightness));
    }

    private static void renderTriangles(BufferedImage target, double[] opaqueDepth, List<Triangle> triangles) {
        triangles.sort(Comparator.comparingDouble(Triangle::depth));
        for (Triangle triangle : triangles) {
            rasterize(target, opaqueDepth, triangle);
        }
    }

    private static Vec3 applyElementRotation(Vec3 vertex, ElementRotation rotation) {
        if (rotation == null || rotation.angle() == 0.0) {
            return vertex;
        }
        Vec3 relative = vertex.subtract(rotation.origin());
        Vec3 rotated = rotateAroundAxis(relative, rotation.axis(), Math.toRadians(rotation.angle()));
        if (rotation.rescale()) {
            double perpendicularScale = 1.0 / Math.cos(Math.toRadians(Math.abs(rotation.angle())));
            Vec3 scale = switch (rotation.axis()) {
                case X -> new Vec3(1.0, perpendicularScale, perpendicularScale);
                case Y -> new Vec3(perpendicularScale, 1.0, perpendicularScale);
                case Z -> new Vec3(perpendicularScale, perpendicularScale, 1.0);
            };
            rotated = rotated.multiply(scale);
        }
        return rotated.add(rotation.origin());
    }

    private static Vec3 applyGuiTransform(Vec3 vertex, Transform transform) {
        Vec3 relative = vertex.subtract(new Vec3(0.5, 0.5, 0.5));
        relative = rotateXyz(relative, transform.rightRotation());
        relative = relative.multiply(transform.scale());
        relative = rotateXyz(relative, transform.rotation());
        return relative.add(transform.translation());
    }

    private static Vec3 rotateXyz(Vec3 vector, Vec3 degrees) {
        Vec3 rotated = rotateAroundAxis(vector, ItemModelRepository.Axis.Z, Math.toRadians(degrees.z()));
        rotated = rotateAroundAxis(rotated, ItemModelRepository.Axis.Y, Math.toRadians(degrees.y()));
        return rotateAroundAxis(rotated, ItemModelRepository.Axis.X, Math.toRadians(degrees.x()));
    }

    private static Vec3 rotateAroundAxis(Vec3 vector, ItemModelRepository.Axis axis, double radians) {
        if (radians == 0.0) {
            return vector;
        }
        double sine = Math.sin(radians);
        double cosine = Math.cos(radians);
        return switch (axis) {
            case X -> new Vec3(
                    vector.x(),
                    vector.y() * cosine - vector.z() * sine,
                    vector.y() * sine + vector.z() * cosine
            );
            case Y -> new Vec3(
                    vector.x() * cosine + vector.z() * sine,
                    vector.y(),
                    -vector.x() * sine + vector.z() * cosine
            );
            case Z -> new Vec3(
                    vector.x() * cosine - vector.y() * sine,
                    vector.x() * sine + vector.y() * cosine,
                    vector.z()
            );
        };
    }

    private static Vertex project(Vec3 vertex, double u, double v, int size) {
        return new Vertex(
                size * 0.5 + vertex.x() * size,
                size * 0.5 - vertex.y() * size,
                vertex.z(),
                u,
                v
        );
    }

    private static double brightness(GuiLight guiLight, boolean shade, Vec3 normal, ExtraFaceData faceData) {
        if (!shade || guiLight == GuiLight.FRONT) {
            return 1.0;
        }
        double primary = Math.max(0.0, normal.dot(LIGHT_ZERO));
        double secondary = Math.max(0.0, normal.dot(LIGHT_ONE));
        double directional = Math.clamp(0.42 + primary * 0.38 + secondary * 0.20, 0.35, 1.0);
        double emitted = Math.max(faceData.blockLight(), faceData.skyLight()) / 15.0;
        return Math.max(directional, emitted);
    }

    private static void drawScaledLayer(BufferedImage target, TextureRegion texture, int tint) {
        for (int y = 0; y < target.getHeight(); y++) {
            for (int x = 0; x < target.getWidth(); x++) {
                int source = colorize(texture.sampleScaled(x, y, target.getWidth(), target.getHeight()), tint, 1.0);
                if ((source >>> 24) != 0) {
                    target.setRGB(x, y, blend(target.getRGB(x, y), source));
                }
            }
        }
    }

    private static void rasterize(BufferedImage target, double[] opaqueDepth, Triangle triangle) {
        double area = edge(triangle.first(), triangle.second(), triangle.third().x(), triangle.third().y());
        if (Math.abs(area) < 1.0e-9) {
            return;
        }

        int minimumX = Math.max(0, (int) Math.floor(Math.min(
                triangle.first().x(), Math.min(triangle.second().x(), triangle.third().x())
        )));
        int maximumX = Math.min(target.getWidth() - 1, (int) Math.ceil(Math.max(
                triangle.first().x(), Math.max(triangle.second().x(), triangle.third().x())
        )));
        int minimumY = Math.max(0, (int) Math.floor(Math.min(
                triangle.first().y(), Math.min(triangle.second().y(), triangle.third().y())
        )));
        int maximumY = Math.min(target.getHeight() - 1, (int) Math.ceil(Math.max(
                triangle.first().y(), Math.max(triangle.second().y(), triangle.third().y())
        )));

        for (int y = minimumY; y <= maximumY; y++) {
            for (int x = minimumX; x <= maximumX; x++) {
                double sampleX = x + 0.5;
                double sampleY = y + 0.5;
                double firstWeight = edge(triangle.second(), triangle.third(), sampleX, sampleY) / area;
                double secondWeight = edge(triangle.third(), triangle.first(), sampleX, sampleY) / area;
                double thirdWeight = edge(triangle.first(), triangle.second(), sampleX, sampleY) / area;
                if (!coversSample(firstWeight, triangle.second(), triangle.third(), area)
                        || !coversSample(secondWeight, triangle.third(), triangle.first(), area)
                        || !coversSample(thirdWeight, triangle.first(), triangle.second(), area)) {
                    continue;
                }

                double u = firstWeight * triangle.first().u()
                        + secondWeight * triangle.second().u()
                        + thirdWeight * triangle.third().u();
                double v = firstWeight * triangle.first().v()
                        + secondWeight * triangle.second().v()
                        + thirdWeight * triangle.third().v();
                double depth = firstWeight * triangle.first().z()
                        + secondWeight * triangle.second().z()
                        + thirdWeight * triangle.third().z();
                int source = colorize(triangle.texture().sample(u, v), triangle.tint(), triangle.brightness());
                int sourceAlpha = source >>> 24;
                if (sourceAlpha == 0) {
                    continue;
                }

                int pixelIndex = y * target.getWidth() + x;
                if (depth + 1.0e-9 < opaqueDepth[pixelIndex]) {
                    continue;
                }
                if (sourceAlpha == 255) {
                    target.setRGB(x, y, source);
                    opaqueDepth[pixelIndex] = depth;
                } else {
                    target.setRGB(x, y, blend(target.getRGB(x, y), source));
                }
            }
        }
    }

    private static double edge(Vertex start, Vertex end, double x, double y) {
        return (x - start.x()) * (end.y() - start.y()) - (y - start.y()) * (end.x() - start.x());
    }

    private static boolean coversSample(double weight, Vertex start, Vertex end, double area) {
        if (weight > 1.0e-7) {
            return true;
        }
        if (weight < -1.0e-7) {
            return false;
        }
        // Include only top and left edges so adjacent triangles never blend a shared sample twice.
        double winding = Math.signum(area);
        double dy = (end.y() - start.y()) * winding;
        double dx = (end.x() - start.x()) * winding;
        return dy > 0 || (dy == 0 && dx < 0);
    }

    private static int colorize(int source, int tint, double brightness) {
        int alpha = (source >>> 24) * (tint >>> 24) / 255;
        int red = (int) Math.round(((source >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF) / 255.0 * brightness);
        int green = (int) Math.round(((source >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF) / 255.0 * brightness);
        int blue = (int) Math.round((source & 0xFF) * (tint & 0xFF) / 255.0 * brightness);
        return alpha << 24 | Math.min(red, 255) << 16 | Math.min(green, 255) << 8 | Math.min(blue, 255);
    }

    private static int multiplyArgb(int first, int second) {
        int alpha = (first >>> 24) * (second >>> 24) / 255;
        int red = (first >>> 16 & 0xFF) * (second >>> 16 & 0xFF) / 255;
        int green = (first >>> 8 & 0xFF) * (second >>> 8 & 0xFF) / 255;
        int blue = (first & 0xFF) * (second & 0xFF) / 255;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int blend(int destination, int source) {
        int sourceAlpha = source >>> 24;
        if (sourceAlpha == 255) {
            return source;
        }
        int destinationAlpha = destination >>> 24;
        int inverseSourceAlpha = 255 - sourceAlpha;
        int outputAlpha = sourceAlpha + destinationAlpha * inverseSourceAlpha / 255;
        if (outputAlpha == 0) {
            return 0;
        }

        int red = blendedChannel(destination >>> 16 & 0xFF, source >>> 16 & 0xFF, destinationAlpha, sourceAlpha, inverseSourceAlpha, outputAlpha);
        int green = blendedChannel(destination >>> 8 & 0xFF, source >>> 8 & 0xFF, destinationAlpha, sourceAlpha, inverseSourceAlpha, outputAlpha);
        int blue = blendedChannel(destination & 0xFF, source & 0xFF, destinationAlpha, sourceAlpha, inverseSourceAlpha, outputAlpha);
        return outputAlpha << 24 | red << 16 | green << 8 | blue;
    }

    private static ItemRenderException noGeometry(ResolvedModel model, String detail) {
        return new ItemRenderException(
                ItemRenderException.Kind.NO_GEOMETRY,
                detail,
                "Item model " + model.id() + " has no renderable geometry: " + detail
        );
    }

    private static int blendedChannel(
            int destination,
            int source,
            int destinationAlpha,
            int sourceAlpha,
            int inverseSourceAlpha,
            int outputAlpha
    ) {
        int premultiplied = source * sourceAlpha + destination * destinationAlpha * inverseSourceAlpha / 255;
        return Math.min(255, premultiplied / outputAlpha);
    }

    private record Vertex(double x, double y, double z, double u, double v) {
    }

    private record Triangle(
            Vertex first,
            Vertex second,
            Vertex third,
            TextureRegion texture,
            int tint,
            double brightness
    ) {

        double depth() {
            return (this.first.z() + this.second.z() + this.third.z()) / 3.0;
        }
    }
}
