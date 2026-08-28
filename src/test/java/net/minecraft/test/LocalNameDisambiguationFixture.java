package net.minecraft.test;

public final class LocalNameDisambiguationFixture {
    private LocalNameDisambiguationFixture() {
    }

    public static void main(String[] args) {
        System.out.println(inspect("minecraft", new String[]{"other", "minecraft"}));
    }

    public static int inspect(String blockstate, String[] values) {
        int matches = 0;
        for (int i = 0; i < values.length; i++) {
            for (int j = 0; j < i; j++) {
                matches += j;
            }

            String blockstate1 = values[i];
            if (blockstate1.equals(blockstate)) {
                matches++;
            }
        }
        return matches;
    }
}
