package co.tinode.tindroid.util;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;

public class ThemeStorage {
    private static final String PREFS = "chat_backgrounds";

    private static String k(String topicName) {
        return "bg:" + (TextUtils.isEmpty(topicName) ? "default" : topicName);
    }

    public static void saveBackgroundForTopic(Context ctx, String topicName, String bgId) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(k(topicName), bgId)
                .apply();
    }

    @Nullable
    public static String getBackgroundForTopic(Context ctx, String topicName) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(k(topicName), null);
    }
}
