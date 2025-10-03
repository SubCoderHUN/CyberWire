package co.tinode.tindroid.util;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.core.view.ViewCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.shape.MaterialShapeDrawable;

import co.tinode.tindroid.R;

public class ThemeApplier {

    /* HÍVÁS: ThemeApplier.applyChat(activity, fragment.getView(), palette) */
    public static void applyChat(@NonNull Activity a, @Nullable View root, @NonNull UiPalette p) {
        // 0) Status/Nav bar
        Window w = a.getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(p.primary);
            w.setNavigationBarColor(p.surface);
        }

        // 1) Toolbar (keressük több módon)
        View tbView = a.findViewById(R.id.toolbar);
        if (tbView == null && root != null) tbView = root.findViewById(R.id.toolbar);
        if (tbView == null) {
            // néha az include-olt wrapper ID látszik, a gyerek a 0. elem
            View wrap = a.findViewById(R.id.messages_toolbar);
            if (wrap == null && root != null) wrap = root.findViewById(R.id.messages_toolbar);
            if (wrap instanceof ViewGroup vg && vg.getChildCount() > 0) {
                View child = vg.getChildAt(0);
                if (child instanceof Toolbar || child instanceof MaterialToolbar) tbView = child;
            }
        }
        if (tbView != null) {
            tbView.setBackgroundColor(p.primary);
            if (tbView instanceof MaterialToolbar mt) {
                mt.setTitleTextColor(p.onPrimary);
                mt.setSubtitleTextColor(p.onPrimary);
                mt.setNavigationIconTint(p.onPrimary);
                Drawable ov = mt.getOverflowIcon();
                if (ov != null) ov.setTint(p.onPrimary);
            } else if (tbView instanceof Toolbar t) {
                t.setTitleTextColor(p.onPrimary);
                Drawable nav = t.getNavigationIcon();
                if (nav != null) try { nav.setTint(p.onPrimary); } catch (Throwable ignored) {}
                Drawable ov = t.getOverflowIcon();
                if (ov != null) try { ov.setTint(p.onPrimary); } catch (Throwable ignored) {}
            }
        }

        // 2) Fragment-gyökér (chat háttér már a MessagesFragment-ben áll, itt nem bántjuk)
        final View scope = (root != null) ? root : a.findViewById(android.R.id.content);

        // 3) “Új üzenet” sáv kártya
        CardView inputCard = scope.findViewById(R.id.sendMessageFragment);
        if (inputCard != null) inputCard.setCardBackgroundColor(p.surface);

        // 4) EditText + ikonok
        EditText et = scope.findViewById(R.id.editMessage);
        if (et != null) {
            et.setTextColor(p.onSurface);
            et.setHintTextColor(mix(p.onSurface, 0.6f));
            if (et.getBackground() instanceof MaterialShapeDrawable msd) {
                msd.setFillColor(android.content.res.ColorStateList.valueOf(p.surfaceVariant));
                msd.setStroke(1.0f, p.outline);
            } else {
                // fallback: háttér-tint
                ViewCompat.setBackgroundTintList(et,
                        android.content.res.ColorStateList.valueOf(p.surfaceVariant));
            }
        }
        tint(scope, R.id.attachImage, p.onSurface);
        tint(scope, R.id.attachFile,  p.onSurface);
        tint(scope, R.id.chatSendButton, p.accent);
        tint(scope, R.id.chatAudioButton, p.accent);
        tint(scope, R.id.chatEditDoneButton, p.accent);

        // 5) FAB (ugrás a legalsóra)
        FloatingActionButton fab = scope.findViewById(R.id.goToLatest);
        if (fab != null) {
            fab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(p.surfaceVariant));
            fab.setColorFilter(p.onSurface);
        }

        // 6) Progress indikátor – biztonságos típuskezelés
        View progressView = scope.findViewById(R.id.attachmentProgressBar);
        if (progressView instanceof CircularProgressIndicator cpi) {
            cpi.setIndicatorColor(p.accent);
            cpi.setTrackColor(mix(p.accent, 0.2f));
        } else if (progressView instanceof ProgressBar pb) {
            pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(p.accent));
            try {
                pb.setProgressBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(mix(p.accent, 0.2f)));
            } catch (Throwable ignored) {}
        }
    }

    private static void tint(@NonNull View scope, int id, int color) {
        View v = scope.findViewById(id);
        if (v instanceof AppCompatImageButton b) {
            b.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        } else if (v instanceof ImageView i) {
            i.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        } else if (v instanceof TextView t) {
            t.setTextColor(color);
        }
    }

    private static int mix(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
