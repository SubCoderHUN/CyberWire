package co.tinode.tindroid.util;

import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import co.tinode.tindroid.R;
import co.tinode.tindroid.util.PaletteManager.BubblePalette;

public class UiThemes {

    // ---- Háttér opciók listája ---------------------------------------------------------------

    public static class BackgroundOption {
        public final String id;
        public final Integer color;     // egyszínű háttér (ColorInt)
        public final Integer drawable;  // drawable háttér
        public BackgroundOption(String id, Integer color, Integer drawable) {
            this.id = id;
            this.color = color;
            this.drawable = drawable;
        }
    }

    public static List<BackgroundOption> list() {
        List<BackgroundOption> list = new ArrayList<>();

        // Meglévő default (a layout fallbackje)
        list.add(new BackgroundOption("default_drawable", null, R.drawable.message_view_bkg));

        // Egyszínűek
        list.add(new BackgroundOption("solid_light", 0xFFF5F5F5, null));
        list.add(new BackgroundOption("solid_dark",  0xFF1E1E1E, null));

        // Gradiensek (assetmentes)
        list.add(new BackgroundOption("grad_blue",     null, R.drawable.bg_gradient_blue));
        list.add(new BackgroundOption("grad_sunset",   null, R.drawable.bg_gradient_sunset));
        list.add(new BackgroundOption("grad_forest",   null, R.drawable.bg_gradient_forest));
        list.add(new BackgroundOption("grad_graphite", null, R.drawable.bg_gradient_graphite));
        list.add(new BackgroundOption("grad_purple",   null, R.drawable.bg_gradient_purple));

        // Mintás (ismétlődő tile-ok)
        list.add(new BackgroundOption("pat_dots",     null, R.drawable.bg_pattern_dots));
        list.add(new BackgroundOption("pat_grid",     null, R.drawable.bg_pattern_grid));
        list.add(new BackgroundOption("pat_diag",     null, R.drawable.bg_pattern_diagonal));

        return list;
    }

    @Nullable
    public static BackgroundOption findById(String id) {
        for (BackgroundOption o : list()) {
            if (o.id.equals(id)) return o;
        }
        return null;
    }

    // ---- Buborék színpaletta (MessagesAdapter használja) -------------------------------------

    public static BubblePalette paletteFor(@Nullable String id) {
        if (id == null) return PaletteManager.defaults();

        switch (normalize(id)) {
            case "solid_dark":
            case "grad_graphite":
            case "pat_dots":
            case "pat_grid":
            case "pat_diag":
            case "default_drawable":
                // Sötét alap → sötétebb más, világosabb saját
                return new BubblePalette(
                        0xFF263238, // mine
                        0xFF121212, // other
                        0xFF2E3C45, // mineFlash
                        0xFF1C1C1C, // otherFlash
                        0xFF31424B, // mineFlashLight
                        0xFF202020  // otherFlashLight
                );

            case "grad_blue":
            case "grad_forest":
            case "grad_purple":
                // Színes, de nem túl világos hátterek
                return new BubblePalette(
                        0xCCEBF5FF, // mine (kicsit áttetsző hatás)
                        0xCCFFFFFF, // other
                        0xD6F2F9FF, // mineFlash
                        0xD6FFFFFF, // otherFlash
                        0xE0F6FBFF, // mineFlashLight
                        0xE0FFFFFF  // otherFlashLight
                );

            case "grad_sunset":
                return new BubblePalette(
                        0xCCFFF3E0, // mine
                        0xCCFFFFFF, // other
                        0xD6FFF7EB,
                        0xD6FFFFFF,
                        0xE0FFFAF2,
                        0xE0FFFFFF
                );

            case "cyberpunk":
                return new BubblePalette(
                        0xAA00FFFF, 0xCC0A0A0A,
                        0xCC00FFFF, 0xCC202020,
                        0xDD00FFFF, 0xDD2A2A2A
                );

            case "solid_light":
            default:
                return PaletteManager.defaults();
        }
    }

    // ---- Teljes UI paletta (Toolbar, input sáv, ikonok stb.) ----------------------------------

    public static UiPalette uiPaletteFor(@Nullable String id) {
        if (id == null) return UiPalette.lightDefault();

        switch (normalize(id)) {
            case "solid_light":
                return new UiPalette(
                        0xFFFFFFFF, 0xFF202124,   // primary / onPrimary
                        0xFFFFFFFF, 0xFF202124,   // surface / onSurface
                        0xFFF1F3F4, 0x1F000000,   // surfaceVariant / outline
                        0xFF2962FF                  // accent
                );

            case "solid_dark":
            case "grad_graphite":
            case "pat_dots":
            case "pat_grid":
            case "pat_diag":
            case "default_drawable":
                return new UiPalette(
                        0xFF121212, 0xFFFFFFFF,   // primary
                        0xFF1E1E1E, 0xFFECECEC,   // surface
                        0xFF2A2A2A, 0x66FFFFFF,   // variant / outline
                        0xFFFF4081                  // accent
                );

            case "grad_blue":
                return new UiPalette(
                        0xFF0D47A1, 0xFFFFFFFF,
                        0xFF121212, 0xFFECECEC,
                        0xFF1C1C1C, 0x66FFFFFF,
                        0xFF42A5F5
                );

            case "grad_purple":
                return new UiPalette(
                        0xFF311B92, 0xFFFFFFFF,
                        0xFF121212, 0xFFECECEC,
                        0xFF1C1C1C, 0x66FFFFFF,
                        0xFF7E57C2
                );

            case "grad_forest":
                return new UiPalette(
                        0xFF1B5E20, 0xFFFFFFFF,
                        0xFF121212, 0xFFECECEC,
                        0xFF1C1C1C, 0x66FFFFFF,
                        0xFF4CAF50
                );

            case "grad_sunset":
                return new UiPalette(
                        0xFFFF7043, 0xFFFFFFFF,
                        0xFFFFFFFF, 0xFF202124,
                        0xFFF6EFEA, 0x332B2B2B,
                        0xFFFFA726
                );

            case "cyberpunk":
                return new UiPalette(
                        0xFF111318, 0xFFE6F7FF,   // sötét felső sáv + világos ikon
                        0xFF121418, 0xFFE6F7FF,   // felületek sötétek, szöveg világos
                        0xFF1A1E24, 0x6655FFFF,   // variáns + outline
                        0xFFFFD600                 // accent = neon sárga
                );

            default:
                return UiPalette.lightDefault();
        }
    }

    /**
     * Paletta meghatározása az AKTUÁLISAN mentett háttér alapján.
     * Ezt hívd MessagesFragment-ben:
     * UiPalette p = UiThemes.paletteForCurrentBackground(requireContext(), mTopicName);
     */
// UiThemes.java

    public static UiPalette paletteForCurrentBackground(@NonNull Context ctx,
                                                        @Nullable String topicName) {
        String bgId = ThemeStorage.getBackgroundForTopic(ctx, topicName);

        PaletteManager.set(paletteFor(bgId));

        if (bgId == null) {
            String defaultThemeId = "default_drawable"; // vagy pl. "grad_graphite", ha azt akarod
            PaletteManager.set(paletteFor(defaultThemeId));
            return uiPaletteFor(defaultThemeId);
        }

        return uiPaletteFor(bgId);
    }


    // ---- Helper -----------------------------------------------------------------------------

    private static String normalize(@NonNull String id) {
        // régi/eltérő aliasok összefűzése, ha valahol máshol más név szerepelt
        if ("light_solid".equals(id)) return "solid_light";
        if ("dark_solid".equals(id))  return "solid_dark";
        return id;
    }
}
