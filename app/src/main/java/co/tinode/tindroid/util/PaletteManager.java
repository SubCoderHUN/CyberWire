package co.tinode.tindroid.util;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

public class PaletteManager {

    public static class BubblePalette {
        @ColorInt public final int mine;
        @ColorInt public final int other;
        @ColorInt public final int mineFlash;
        @ColorInt public final int otherFlash;
        @ColorInt public final int mineFlashLight;
        @ColorInt public final int otherFlashLight;

        public BubblePalette(int mine, int other, int mineFlash, int otherFlash,
                             int mineFlashLight, int otherFlashLight) {
            this.mine = mine;
            this.other = other;
            this.mineFlash = mineFlash;
            this.otherFlash = otherFlash;
            this.mineFlashLight = mineFlashLight;
            this.otherFlashLight = otherFlashLight;
        }
    }

    private static BubblePalette CURRENT = defaults();

    public static BubblePalette get() { return CURRENT; }

    public static void set(@NonNull BubblePalette p) { CURRENT = p; }

    public static BubblePalette defaults() {
        return new BubblePalette(
                0xFFDCF8C6,  // mine
                0xFFFFFFFF,  // other
                0xFFE3FFD6,  // mineFlash
                0xFFF6F6F6,  // otherFlash
                0xFFEAF9DE,  // mineFlashLight
                0xFFFAFAFA   // otherFlashLight
        );
    }
}
