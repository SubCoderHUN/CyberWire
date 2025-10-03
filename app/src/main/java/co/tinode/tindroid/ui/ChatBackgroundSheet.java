package co.tinode.tindroid.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import co.tinode.tindroid.MessagesFragment;
import co.tinode.tindroid.R;
import co.tinode.tindroid.util.ThemeStorage;
import co.tinode.tindroid.util.UiThemes;

public class ChatBackgroundSheet extends BottomSheetDialogFragment {
    private static final String ARG_TOPIC = "topic";
    private String mTopicName;

    public static ChatBackgroundSheet newInstance(String topicName) {
        ChatBackgroundSheet f = new ChatBackgroundSheet();
        Bundle b = new Bundle();
        b.putString(ARG_TOPIC, topicName);
        f.setArguments(b);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTopicName = getArguments() != null ? getArguments().getString(ARG_TOPIC) : null;
        setCancelable(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_background_picker, container, false);
        GridLayout grid = v.findViewById(R.id.bg_grid);

        int size = (int) (80 * getResources().getDisplayMetrics().density);
        int margin = (int) (8 * getResources().getDisplayMetrics().density);

        for (UiThemes.BackgroundOption opt : UiThemes.list()) {
            ImageView iv = new ImageView(getContext());
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size; lp.height = size;
            lp.setMargins(margin, margin, margin, margin);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);

            if (opt.drawable != null) {
                iv.setImageResource(opt.drawable);
            } else if (opt.color != null) {
                iv.setImageDrawable(null);
                iv.setBackgroundColor(opt.color);
            }

            iv.setOnClickListener(vw -> {
                ThemeStorage.saveBackgroundForTopic(requireContext(), mTopicName, opt.id);
                if (getParentFragment() instanceof MessagesFragment) {
                    ((MessagesFragment) getParentFragment()).applyChatBackground(opt.id);
                }
                Toast.makeText(getContext(), R.string.chat_background_set, Toast.LENGTH_SHORT).show();
                dismissAllowingStateLoss();
            });

            grid.addView(iv);
        }

        return v;
    }
}
