package co.tinode.tindroid.util;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

public class UiPalette {
    @ColorInt public final int primary;        // felső sáv, kiemelések
    @ColorInt public final int onPrimary;      // primary-n a szöveg/ikon
    @ColorInt public final int surface;        // kártyák, input sáv
    @ColorInt public final int onSurface;      // normál szöveg ikon
    @ColorInt public final int surfaceVariant; // gomb-háttér, elválasztók
    @ColorInt public final int outline;        // halvány vonalak
    @ColorInt public final int accent;         // FAB, aktív ikonok

    public UiPalette(int primary, int onPrimary, int surface, int onSurface,
                     int surfaceVariant, int outline, int accent) {
        this.primary = primary;
        this.onPrimary = onPrimary;
        this.surface = surface;
        this.onSurface = onSurface;
        this.surfaceVariant = surfaceVariant;
        this.outline = outline;
        this.accent = accent;
    }

    public static UiPalette lightDefault() {
        return new UiPalette(
                0xFF121212, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFF1A1A1A,
                0xFFF2F2F2, 0x33000000,
                0xFF2E7D32
        );
    }
}
