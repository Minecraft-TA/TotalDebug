package net.minecraft.test;

public final class GeneratedNamesFixture {
    private GeneratedNamesFixture() {
    }

    public static String format(String p_100_, int p_101_) {
        String var3 = p_100_;
        for (int var4 = 0; var4 < p_101_; var4++) {
            var3 += p_100_;
        }
        return var3;
    }

    public static java.util.function.IntUnaryOperator operator(int p_200_) {
        return p_201_ -> p_200_ + p_201_;
    }

    public static java.util.function.IntUnaryOperator nonCapturingOperator(int p_202_) {
        return p_203_ -> p_203_ + 1;
    }

    public static java.util.function.IntUnaryOperator operatorBesideLocal() {
        int i = Integer.parseInt("1");
        System.out.println(i);
        return p_204_ -> p_204_ + 1;
    }

    public static String collidingLocal(String p_300_) {
        String s = p_300_ + "!";
        return s;
    }
}
