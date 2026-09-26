package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.util.Arrays;

/** Immutable affine transform using Minecraft's column-vector composition order. */
final class ModelTransform {

    static final ModelTransform IDENTITY = new ModelTransform(new double[]{
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
    });

    private final double[] values;

    private ModelTransform(double[] values) {
        this.values = values;
    }

    static ModelTransform matrix(double[][] rows) {
        double[] values = new double[16];
        for (int row = 0; row < 4; row++) {
            System.arraycopy(rows[row], 0, values, row * 4, 4);
        }
        return new ModelTransform(values);
    }

    static ModelTransform translation(double x, double y, double z) {
        return matrix(new double[][]{
                {1, 0, 0, x},
                {0, 1, 0, y},
                {0, 0, 1, z},
                {0, 0, 0, 1}
        });
    }

    static ModelTransform scale(double x, double y, double z) {
        return matrix(new double[][]{
                {x, 0, 0, 0},
                {0, y, 0, 0},
                {0, 0, z, 0},
                {0, 0, 0, 1}
        });
    }

    static ModelTransform eulerDegrees(double x, double y, double z) {
        return rotationX(Math.toRadians(x))
                .compose(rotationY(Math.toRadians(y)))
                .compose(rotationZ(Math.toRadians(z)));
    }

    static ModelTransform quaternion(double x, double y, double z, double w) {
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (length == 0) {
            return IDENTITY;
        }
        x /= length;
        y /= length;
        z /= length;
        w /= length;
        return matrix(new double[][]{
                {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), 0},
                {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), 0},
                {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), 0},
                {0, 0, 0, 1}
        });
    }

    static ModelTransform rotation(ItemModelRepository.Axis axis, double degrees) {
        double radians = Math.toRadians(degrees);
        return switch (axis) {
            case X -> rotationX(radians);
            case Y -> rotationY(radians);
            case Z -> rotationZ(radians);
        };
    }

    private static ModelTransform rotationX(double radians) {
        double sine = Math.sin(radians);
        double cosine = Math.cos(radians);
        return matrix(new double[][]{
                {1, 0, 0, 0},
                {0, cosine, -sine, 0},
                {0, sine, cosine, 0},
                {0, 0, 0, 1}
        });
    }

    private static ModelTransform rotationY(double radians) {
        double sine = Math.sin(radians);
        double cosine = Math.cos(radians);
        return matrix(new double[][]{
                {cosine, 0, sine, 0},
                {0, 1, 0, 0},
                {-sine, 0, cosine, 0},
                {0, 0, 0, 1}
        });
    }

    private static ModelTransform rotationZ(double radians) {
        double sine = Math.sin(radians);
        double cosine = Math.cos(radians);
        return matrix(new double[][]{
                {cosine, -sine, 0, 0},
                {sine, cosine, 0, 0},
                {0, 0, 1, 0},
                {0, 0, 0, 1}
        });
    }

    /** Returns a transform which applies {@code other} first and this transform second. */
    ModelTransform compose(ModelTransform other) {
        if (this.isIdentity()) {
            return other;
        }
        if (other.isIdentity()) {
            return this;
        }
        double[] result = new double[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                for (int inner = 0; inner < 4; inner++) {
                    result[row * 4 + column] += this.values[row * 4 + inner]
                            * other.values[inner * 4 + column];
                }
            }
        }
        return new ModelTransform(result);
    }

    ModelTransform around(ItemModelRepository.Vec3 origin) {
        return translation(origin.x(), origin.y(), origin.z())
                .compose(this)
                .compose(translation(-origin.x(), -origin.y(), -origin.z()));
    }

    ItemModelRepository.Vec3 apply(ItemModelRepository.Vec3 point) {
        double x = this.values[0] * point.x() + this.values[1] * point.y()
                + this.values[2] * point.z() + this.values[3];
        double y = this.values[4] * point.x() + this.values[5] * point.y()
                + this.values[6] * point.z() + this.values[7];
        double z = this.values[8] * point.x() + this.values[9] * point.y()
                + this.values[10] * point.z() + this.values[11];
        double w = this.values[12] * point.x() + this.values[13] * point.y()
                + this.values[14] * point.z() + this.values[15];
        return w == 0 || w == 1
                ? new ItemModelRepository.Vec3(x, y, z)
                : new ItemModelRepository.Vec3(x / w, y / w, z / w);
    }

    boolean isIdentity() {
        return this == IDENTITY || Arrays.equals(this.values, IDENTITY.values);
    }
}
