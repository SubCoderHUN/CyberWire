package co.tinode.tindroid.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import co.tinode.tindroid.MessagesFragment;
import co.tinode.tindroid.R;

public class ReactionBottomSheetDialog extends BottomSheetDialogFragment {

    private static final String ARG_SEQ = "arg_seq";
    private int mSeq;

    public static ReactionBottomSheetDialog newInstance(int seq) {
        ReactionBottomSheetDialog f = new ReactionBottomSheetDialog();
        Bundle b = new Bundle();
        b.putInt(ARG_SEQ, seq);
        f.setArguments(b);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mSeq = args != null ? args.getInt(ARG_SEQ, 0) : 0;
        setCancelable(true);
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        // Egyszerű, MaterialCardView nélküli layout, hogy ne legyen theme-dependency
        View v = inflater.inflate(R.layout.bottomsheet_reactions, container, false);

        View.OnClickListener click = btn -> {
            String emoji;
            int id = btn.getId();
            if (id == R.id.reac_like) emoji = "👍";
            else if (id == R.id.reac_love) emoji = "❤️";
            else if (id == R.id.reac_laugh) emoji = "😂";
            else if (id == R.id.reac_surprise) emoji = "😮";
            else if (id == R.id.reac_sad) emoji = "😢";
            else if (id == R.id.reac_angry) emoji = "🔥";
            else emoji = "👍";

            // callback a parent fragmentnek → reply az adott seq-re
            if (getParentFragment() instanceof MessagesFragment) {
                ((MessagesFragment) getParentFragment()).onReactionPicked(mSeq, emoji);
            }
            dismissAllowingStateLoss();
        };

        v.findViewById(R.id.reac_like).setOnClickListener(click);
        v.findViewById(R.id.reac_love).setOnClickListener(click);
        v.findViewById(R.id.reac_laugh).setOnClickListener(click);
        v.findViewById(R.id.reac_surprise).setOnClickListener(click);
        v.findViewById(R.id.reac_sad).setOnClickListener(click);
        v.findViewById(R.id.reac_angry).setOnClickListener(click);

        return v;
    }
}
