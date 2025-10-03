package co.tinode.tindroid;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.database.sqlite.SQLiteBlobTooBigException;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.IconMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.app.ActivityCompat;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import co.tinode.tindroid.db.BaseDb;
import co.tinode.tindroid.db.MessageDb;
import co.tinode.tindroid.db.SqlStore;
import co.tinode.tindroid.db.StoredMessage;
import co.tinode.tindroid.format.CopyFormatter;
import co.tinode.tindroid.format.FullFormatter;
import co.tinode.tindroid.format.QuoteFormatter;
import co.tinode.tindroid.format.StableLinkMovementMethod;
import co.tinode.tindroid.format.ThumbnailTransformer;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tinodesdk.ComTopic;
import co.tinode.tinodesdk.MeTopic;
import co.tinode.tinodesdk.PromisedReply;
import co.tinode.tinodesdk.Storage;
import co.tinode.tinodesdk.Tinode;
import co.tinode.tinodesdk.Topic;
import co.tinode.tinodesdk.model.Drafty;
import co.tinode.tinodesdk.model.MsgGetMeta;
import co.tinode.tinodesdk.model.MsgRange;
import co.tinode.tinodesdk.model.ServerMessage;
import co.tinode.tinodesdk.model.Subscription;

import co.tinode.tindroid.util.PaletteManager;

import coil.ImageLoader;
import coil.request.ImageRequest;
import coil.request.SuccessResult;
import coil.request.ErrorResult;
import coil.size.Size;
import coil.size.Dimension;
import coil.decode.ImageDecoderDecoder; // API 28+
import coil.decode.GifDecoder;         // API <28
import android.graphics.drawable.Drawable;
import android.widget.TextView;



/**
 * Handle display of a conversation
 */
public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.ViewHolder> {
    private static final String TAG = "MessagesAdapter";

    private static final int MESSAGES_TO_LOAD = 20;

    private static final int MESSAGES_QUERY_ID = 200;

    private static final String HARD_RESET = "hard_reset";
    private static final int REFRESH_NONE = 0;
    private static final int REFRESH_SOFT = 1;
    private static final int REFRESH_HARD = 2;

    // Bits defining message bubble variations
    // _TIP == "single", i.e. has a bubble tip.
    // _DATE == the date bubble is visible.
    private static final int VIEWTYPE_SIDE_LEFT   = 0b000010;
    private static final int VIEWTYPE_SIDE_RIGHT  = 0b000100;
    private static final int VIEWTYPE_TIP         = 0b001000;
    private static final int VIEWTYPE_AVATAR      = 0b010000;
    private static final int VIEWTYPE_DATE        = 0b100000;
    private static final int VIEWTYPE_INVALID     = 0b000000;

    // Duration of a message bubble animation in ms.
    private static final int MESSAGE_BUBBLE_ANIMATION_SHORT = 150;
    private static final int MESSAGE_BUBBLE_ANIMATION_LONG = 600;

    // Storage Permissions
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static final String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    private static final float[] EMOJI_SCALING = new float[]{1.26f, 1.55f, 1.93f, 2.40f, 3.00f};

    private final MessageActivity mActivity;
    private final ActionMode.Callback mSelectionModeCallback;
    private final SwipeRefreshLayout mRefresher;
    private final MessageLoaderCallbacks mMessageLoaderCallback;
    private ActionMode mSelectionMode;
    private RecyclerView mRecyclerView;
    private Cursor mCursor;
    private String mTopicName = null;
    private SparseBooleanArray mSelectedItems = null;
    private int mPagesToLoad;

    private final MediaControl mMediaControl;
    private final MessagesFragment mHost;

    private static volatile coil.ImageLoader sGifImageLoader;

    private static final boolean DEBUG_GIF = BuildConfig.DEBUG;

    private static void gifLog(@NonNull String msg) {
        if (DEBUG_GIF) Log.d(TAG, msg);
    }


    MessagesAdapter(@NonNull MessageActivity activity,
                    @NonNull SwipeRefreshLayout refresher,
                    @NonNull MessagesFragment host)
    {
        super();

        mActivity = activity;
        setHasStableIds(true);

        mRefresher = refresher;
        mPagesToLoad = 1;

        mMessageLoaderCallback = new MessageLoaderCallbacks();

        mMediaControl = new MediaControl(activity);

        this.mHost = host;

        mSelectionModeCallback = new ActionMode.Callback() {
            @Override
            public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
                if (mSelectedItems == null) {
                    mSelectedItems = new SparseBooleanArray();
                }
                int selected = mSelectedItems.size();
                menu.findItem(R.id.action_reply).setVisible(selected <= 1);
                menu.findItem(R.id.action_forward).setVisible(selected <= 1);
                return true;
            }

            @Override
            @SuppressLint("NotifyDataSetChanged")
            public void onDestroyActionMode(ActionMode actionMode) {
                // ❗️Ne maradjon bent a kijelölés – ez okozta, hogy a toolbar később nem jött vissza
                clearSelection();
            }

            @Override
            public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
                mActivity.getMenuInflater().inflate(R.menu.menu_message_selected, menu);
                menu.findItem(R.id.action_delete).setVisible(!ComTopic.isChannel(mTopicName));
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
                // Don't convert to switch: Android does not like it.
                int id = menuItem.getItemId();
                int[] selected = getSelectedArray();
                if (id == R.id.action_edit) {
                    if (selected != null) {
                        showMessageQuote(UiUtils.MsgAction.EDIT, selected[0], Const.EDIT_PREVIEW_LENGTH);
                    }
                    return true;
                } else if (id == R.id.action_delete) {
                    if (selected != null) {
                        final Topic topic = Cache.getTinode().getTopic(mTopicName);
                        if (topic != null) {
                            showDeleteMessageConfirmationDialog(mActivity, selected, topic.isDeleter());
                        }
                    }
                    return true;
                } else if (id == R.id.action_copy) {
                    copyMessageText(selected);
                    return true;
                } else if (id == R.id.action_send_now) {
                    // Just try to resend all messages, regardless of selection.
                    mActivity.syncAllMessages(true);
                    return true;
                } else if (id == R.id.action_reply) {
                    if (selected != null) {
                        showMessageQuote(UiUtils.MsgAction.REPLY, selected[0], Const.QUOTED_REPLY_LENGTH);
                    }
                    return true;
                } else if (id == R.id.action_forward) {
                    if (selected != null) {
                        showMessageForwardSelector(selected[0]);
                    }
                    return true;
                } else if (id == R.id.action_pin || id == R.id.action_unpin) {
                    if (selected != null) {
                        sendPinMessage(selected[0], id == R.id.action_pin);
                    }
                    return true;
                }

                return false;
            }
        };

        verifyStoragePermissions();
    }

    // Utils – dp -> px
    private static int dp(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // GIF célméret számítás: min 240dp, max a képernyő 66%-a, arány tartás
    private static Point calcGifTargetSize(@NonNull Context ctx, int origW, int origH) {
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int maxW = (int) (screenW * 0.66f);
        int minW = dp(ctx, 240);

        // ha nincs érvényes origW, legyen egy életszerű alap
        if (origW <= 0 || origH <= 0) {
            origW = dp(ctx, 220);
            origH = dp(ctx, 165);
        }

        // akár 2×-es nagyításig is engedünk, de plafon a maxW
        int targetW = Math.min(maxW, Math.max(minW, origW * 2));
        int targetH = Math.round((float) targetW * origH / origW);

        return new Point(targetW, targetH);
    }

    private static String sniffMime(@Nullable byte[] data, @Nullable String declared) {
        if (data != null && data.length >= 12) {
            if (data[0]=='G' && data[1]=='I' && data[2]=='F' && data[3]=='8') return "image/gif";
            if ((data[0] & 0xFF) == 0x89 && data[1]=='P' && data[2]=='N' && data[3]=='G') return "image/png";
            if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return "image/jpeg";
            if (data[0]=='R' && data[1]=='I' && data[2]=='F' && data[3]=='F'
                    && data[8]=='W' && data[9]=='E' && data[10]=='B' && data[11]=='P') {
                return "image/webp";
            }
        }
        return declared;
    }
    private static coil.ImageLoader getGifImageLoader(@NonNull Context ctx) {
        if (sGifImageLoader == null) {
            synchronized (MessagesAdapter.class) {
                if (sGifImageLoader == null) {
                    coil.ComponentRegistry.Builder crb = new coil.ComponentRegistry.Builder();
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        crb.add(new ImageDecoderDecoder.Factory()); // GIF + animated WebP
                    } else {
                        crb.add(new GifDecoder.Factory());          // GIF only
                    }
                    sGifImageLoader = new coil.ImageLoader
                            .Builder(ctx.getApplicationContext())
                            .components(crb.build())
                            .crossfade(true)
                            .build();
                }
            }
        }
        return sGifImageLoader;
    }
    private static void setTopDrawable(@NonNull TextView tv, @Nullable Drawable d) {
        tv.setCompoundDrawablesWithIntrinsicBounds(null, d, null, null);
    }

    private static void startIfAnimatable(@Nullable Drawable d) {
        if (d instanceof android.graphics.drawable.Animatable) {
            try { ((android.graphics.drawable.Animatable) d).start(); } catch (Throwable ignore) {}
        }
    }

    private coil.request.Disposable bindImageOrGifIntoTextView(
            @NonNull TextView tv,
            @Nullable Uri uri,
            @Nullable byte[] bytes,
            @NonNull String declaredMime,
            int reqW, int reqH
    ) {
        final Context ctx = tv.getContext();
        final coil.ImageLoader loader = getGifImageLoader(ctx);

        final String realMime = (bytes != null) ? sniffMime(bytes, declaredMime) : declaredMime;

        // <<< ÚJ: célméret számítás (az eredeti reqW/reqH csak “kiindulási tipp”)
        Point target = calcGifTargetSize(ctx, reqW, reqH);
        int targetW = target.x;
        int targetH = target.y;

        coil.request.ImageRequest.Builder rb = new coil.request.ImageRequest.Builder(ctx)
                .target(new coil.target.Target() {
                    @Override public void onStart(@Nullable Drawable placeholder) {
                        setTopDrawable(tv, placeholder);
                    }
                    @Override public void onSuccess(@NonNull Drawable result) {
                        setTopDrawable(tv, result);
                        startIfAnimatable(result);
                    }
                    @Override public void onError(@Nullable Drawable error) {
                        setTopDrawable(tv, error);
                    }
                })
                // <<< LÉNYEG: az általunk számolt target méretet kérjük és tartsuk is meg pontosan
                .size(new coil.size.Size(
                        new coil.size.Dimension.Pixels(targetW),
                        new coil.size.Dimension.Pixels(targetH)))
                .precision(coil.size.Precision.EXACT)
                .crossfade(true)
                .listener(new coil.request.ImageRequest.Listener() {
                    @Override public void onStart(@NonNull ImageRequest request) {
                        gifLog("Coil START data=" + request.getData());
                    }
                    @Override public void onSuccess(@NonNull ImageRequest request,
                                                    @NonNull SuccessResult result) {
                        gifLog("Coil SUCCESS " + result.getDrawable().getClass().getName()
                                + " src=" + result.getDataSource() + " mime=" + realMime);
                    }
                    @Override public void onError(@NonNull ImageRequest request,
                                                  @NonNull ErrorResult result) {
                        Log.e(TAG, "Coil ERROR", result.getThrowable());
                    }
                });

        if (bytes != null) {
            rb.data(bytes);
            gifLog("Coil: data=byte[], realMime=" + realMime + " len=" + bytes.length);
        } else if (uri != null) {
            rb.data(uri);
            gifLog("Coil: data=uri=" + uri + ", declared/realMime=" + realMime);
        } else {
            Log.e(TAG, "bindImageOrGifIntoTextView: NO DATA");
            return null;
        }

        return loader.enqueue(rb.build());
    }





    // Generates formatted content:
    //  - "( ! ) invalid content"
    //  - "( <) processing ..."
    //  - "( ! ) failed"
    private static Spanned serviceContentSpanned(Context ctx, int iconId, int messageId) {
        SpannableString span = new SpannableString(ctx.getString(messageId));
        span.setSpan(new StyleSpan(Typeface.ITALIC), 0, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ForegroundColorSpan(Color.rgb(0x75, 0x75, 0x75)),
                0, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Drawable icon = AppCompatResources.getDrawable(ctx, iconId);
        span.setSpan(new IconMarginSpan(UiUtils.bitmapFromDrawable(icon), 24),
                0, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    private static int findInCursor(@Nullable Cursor cur, int seq) {
        if (seq <= 0 || cur == null || cur.isClosed()) {
            return -1;
        }

        int low = 0;
        int high = cur.getCount() - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            StoredMessage m = getMessage(cur, mid, 0); // previewLength == 0 means no content is needed.
            if (m == null) {
                return -mid;
            }

            // Messages are sorted in descending order by seq.
            int cmp = -m.seq + seq;
            if (cmp < 0)
                low = mid + 1;
            else if (cmp > 0)
                high = mid - 1;
            else
                return mid; // key found
        }
        return -(low + 1);  // key not found
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);

        mRecyclerView = recyclerView;
    }

    // Confirmation dialog "Do you really want to delete message(s)"
    private void showDeleteMessageConfirmationDialog(final Activity activity, final int[] positions,
                                                     boolean forAll) {
        final AlertDialog.Builder confirmBuilder = new AlertDialog.Builder(activity);
        boolean multiple = positions.length > 1;
        confirmBuilder.setTitle(multiple ?
                R.string.delete_message_title_plural :
                R.string.delete_message_title_single);
        confirmBuilder.setNegativeButton(android.R.string.cancel, null);

        if (forAll && !Topic.isSlfType(mTopicName)) {
            // Set the custom layout with the checkbox.
            LayoutInflater inflater = activity.getLayoutInflater();
            View layout = inflater.inflate(R.layout.dialog_confirm_delete, null);
            ((TextView) layout.findViewById(R.id.confirmDelete)).setText(multiple ?
                    R.string.confirm_delete_message_plural :
                    R.string.confirm_delete_message_single);
            CheckBox forAllCheckbox = layout.findViewById(R.id.deleteForAll);
            if (Topic.isP2PType(mTopicName)) {
                final ComTopic topic = Cache.getTinode().getComTopic(mTopicName);
                VxCard card = topic != null ? (VxCard) topic.getPub() : null;
                String peer = card != null ? card.fn : mTopicName;
                forAllCheckbox.setText(activity.getString(R.string.delete_for_peer, peer));
            } else {
                forAllCheckbox.setText(R.string.delete_for_everybody);
            }

            confirmBuilder.setView(layout);
        } else {
            confirmBuilder.setMessage(multiple ?
                    R.string.confirm_delete_message_plural :
                    R.string.confirm_delete_message_single);
        }

        confirmBuilder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            CheckBox forAllCheckbox = ((AlertDialog) dialog).findViewById(R.id.deleteForAll);
            boolean hard = forAllCheckbox != null && forAllCheckbox.isChecked();
            sendDeleteMessages(positions, hard);
        });
        confirmBuilder.show();
    }

    private int[] getSelectedArray() {
        if (mSelectedItems == null || mSelectedItems.size() == 0) {
            return null;
        }

        int[] items = new int[mSelectedItems.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = mSelectedItems.keyAt(i);
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private void copyMessageText(int[] positions) {
        if (positions == null || positions.length == 0) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        final Topic topic = Cache.getTinode().getTopic(mTopicName);
        if (topic == null) {
            return;
        }

        // The list is inverted, so iterating messages in inverse order as well.
        for (int i = positions.length - 1; i >= 0; i--) {
            int pos = positions[i];
            StoredMessage msg = getMessage(pos);
            if (msg != null) {
                if (msg.from != null) {
                    Subscription<VxCard, ?> sub = (Subscription<VxCard, ?>) topic.getSubscription(msg.from);
                    sb.append("\n[");
                    sb.append((sub != null && sub.pub != null) ? sub.pub.fn : msg.from);
                    sb.append("]: ");
                }
                if (msg.content != null) {
                    sb.append(msg.content.format(new CopyFormatter(mActivity)));
                }
                sb.append("; ").append(UtilsString.shortDate(msg.ts));
            }
            toggleSelectionAt(pos);
            notifyItemChanged(pos);
        }

        updateSelectionMode();

        if (sb.length() > 1) {
            // Delete unnecessary CR in the beginning.
            sb.deleteCharAt(0);
            String text = sb.toString();

            ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("message text", text));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void sendDeleteMessages(final int[] positions, final boolean hard) {
        if (positions == null || positions.length == 0) {
            return;
        }

        final Topic topic = Cache.getTinode().getTopic(mTopicName);
        if (topic == null) {
            return;
        }

        final Storage store = BaseDb.getInstance().getStore();
        ArrayList<Integer> toDelete = new ArrayList<>();
        int i = 0;
        int discarded = 0;
        while (i < positions.length) {
            int pos = positions[i++];
            StoredMessage msg = getMessage(pos);
            if (msg == null) {
                continue;
            }

            int replSeq = msg.getReplacementSeqId();
            if (replSeq > 0) {
                // Deleting all version of an edited message.
                int[] ids = store.getAllMsgVersions(topic, replSeq, -1);
                for (int id : ids) {
                    if (BaseDb.isUnsentSeq(id)) {
                        store.msgDiscardSeq(topic, id);
                        discarded++;
                    } else {
                        toDelete.add(id);
                    }
                }
            }

            if (msg.status == BaseDb.Status.SYNCED) {
                toDelete.add(msg.seq);
            } else {
                store.msgDiscard(topic, msg.getDbId());
                discarded++;
            }
        }

        if (!toDelete.isEmpty()) {
            topic.delMessages(toDelete, hard)
                    .thenApply(new PromisedReply.SuccessListener<ServerMessage>() {
                        @Override
                        public PromisedReply<ServerMessage> onSuccess(ServerMessage result) {
                            runLoader(false);
                            mActivity.runOnUiThread(() -> updateSelectionMode());
                            return null;
                        }
                    }, new UiUtils.ToastFailureListener(mActivity));
        } else if (discarded > 0) {
            runLoader(false);
            updateSelectionMode();
        }
    }

    private void sendPinMessage(int pos, boolean pin) {
        StoredMessage msg = getMessage(mCursor, pos, 0);
        if (msg == null) {
            return;
        }
        mActivity.sendPinMessage(msg.seq, pin);
    }

    void updateSelectedOnPinnedChange(int seq) {
        int pos = findInCursor(mCursor, seq);
        if (pos < 0) {
            return;
        }

        notifyItemChanged(pos);
        if (mSelectedItems != null) {
            toggleSelectionAt(pos);
            updateSelectionMode();
        }
    }

    private String messageFrom(StoredMessage msg) {
        Tinode tinode =  Cache.getTinode();
        String uname = null;
        if (tinode.isMe(msg.from)) {
            MeTopic<VxCard> me = tinode.getMeTopic();
            if (me != null) {
                VxCard pub = me.getPub();
                uname = pub != null ? pub.fn : null;
            }
        } else {
            @SuppressWarnings("unchecked") final ComTopic<VxCard> topic = (ComTopic<VxCard>) tinode.getTopic(mTopicName);
            if (topic != null) {
                if (topic.isChannel() || topic.isP2PType()) {
                    VxCard pub = topic.getPub();
                    uname = pub != null ? pub.fn : null;
                } else {
                    final Subscription<VxCard, ?> sub = topic.getSubscription(msg.from);
                    uname = (sub != null && sub.pub != null) ? sub.pub.fn : null;
                }
            }
        }
        if (TextUtils.isEmpty(uname)) {
            uname = mActivity.getString(R.string.unknown);
        }
        return uname;
    }

    private void showMessageQuote(UiUtils.MsgAction action, int pos, int quoteLength) {
        toggleSelectionAt(pos);
        notifyItemChanged(pos);
        updateSelectionMode();

        StoredMessage msg = getMessage(pos);
        if (msg == null) {
            return;
        }

        ThumbnailTransformer tr = new ThumbnailTransformer();
        final Drafty content = msg.content.replyContent(quoteLength, 1).transform(tr);
        tr.completionPromise().thenApply(new PromisedReply.SuccessListener<>() {
            @Override
            public PromisedReply<Void[]> onSuccess(Void[] result) {
                mActivity.runOnUiThread(() -> {
                    if (action == UiUtils.MsgAction.REPLY) {
                        Drafty reply = Drafty.quote(messageFrom(msg), msg.from, content);
                        mActivity.showReply(reply, msg.seq);
                    } else {
                        // If the message being edited is a replacement message, use the original seqID.
                        int seq = msg.getReplacementSeqId();
                        if (seq <= 0) {
                            seq = msg.seq;
                        }
                        String markdown = msg.content.toMarkdown(false);
                        mActivity.startEditing(markdown, content.wrapInto("QQ"), seq);
                    }
                });
                return null;
            }
        }).thenCatch(new PromisedReply.FailureListener<>() {
            @Override
            public <E extends Exception> PromisedReply<Void[]> onFailure(E err) {
                Log.w(TAG, "Unable to create message preview", err);
                return null;
            }
        });
    }

    private void showMessageForwardSelector(int pos) {
        StoredMessage msg = getMessage(pos);
        if (msg != null) { // No need to check message status, OK to forward failed message.
            toggleSelectionAt(pos);
            notifyItemChanged(pos);
            updateSelectionMode();

            Bundle args = new Bundle();
            String uname = "➦ " + messageFrom(msg);
            String from = msg.from != null ? msg.from : mTopicName;
            args.putSerializable(ForwardToFragment.CONTENT_TO_FORWARD, msg.content.forwardedContent());
            args.putSerializable(ForwardToFragment.FORWARDING_FROM_USER, Drafty.mention(uname, from));
            args.putString(ForwardToFragment.FORWARDING_FROM_TOPIC, mTopicName);
            ForwardToFragment fragment = new ForwardToFragment();
            fragment.setArguments(args);
            fragment.show(mActivity.getSupportFragmentManager(), MessageActivity.FRAGMENT_FORWARD_TO);
        }
    }

    private static int packViewType(int side, boolean tip, boolean avatar, boolean date) {
        int type = side;
        if (tip) {
            type |= VIEWTYPE_TIP;
        }
        if (avatar) {
            type |= VIEWTYPE_AVATAR;
        }
        if (date) {
            type |= VIEWTYPE_DATE;
        }
        return type;
    }

    @Override
    public int getItemViewType(int position) {
        int itemType = VIEWTYPE_INVALID;
        StoredMessage m = getMessage(position);

        if (m != null) {
            long nextFrom = -2;
            Date nextDate = null;
            if (position > 0) {
                StoredMessage m2 = getMessage(position - 1);
                if (m2 != null) {
                    nextFrom = m2.userId;
                    nextDate = m2.ts;
                }
            }
            Date prevDate = null;
            if (position < getItemCount() - 1) {
                StoredMessage m2 = getMessage(position + 1);
                if (m2 != null) {
                    prevDate = m2.ts;
                }
            }
            itemType = packViewType(m.isMine() ? VIEWTYPE_SIDE_RIGHT : VIEWTYPE_SIDE_LEFT,
                    m.userId != nextFrom || !UiUtils.isSameDate(nextDate, m.ts),
                    Topic.isGrpType(mTopicName) && !ComTopic.isChannel(mTopicName),
                    !UiUtils.isSameDate(prevDate, m.ts));
        }

        return itemType;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Create a new message bubble view.

        int layoutId = -1;
        if ((viewType & VIEWTYPE_SIDE_LEFT) != 0) {
            if ((viewType & VIEWTYPE_AVATAR) != 0 && (viewType & VIEWTYPE_TIP) != 0) {
                layoutId = R.layout.message_left_single_avatar;
            } else if ((viewType & VIEWTYPE_TIP) != 0) {
                layoutId = R.layout.message_left_single;
            } else if ((viewType & VIEWTYPE_AVATAR) != 0) {
                layoutId = R.layout.message_left_avatar;
            } else {
                layoutId = R.layout.message_left;
            }
        } else if ((viewType & VIEWTYPE_SIDE_RIGHT) != 0) {
            if ((viewType & VIEWTYPE_TIP) != 0) {
                layoutId = R.layout.message_right_single;
            } else {
                layoutId = R.layout.message_right;
            }
        }

        View v = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        if (v != null) {
            View dateBubble = v.findViewById(R.id.dateDivider);
            if (dateBubble != null) {
                dateBubble.setVisibility((viewType & VIEWTYPE_DATE) != 0 ? View.VISIBLE : View.GONE);
            }
        }

        return new ViewHolder(v, viewType);
    }

// ---------- GIF helpers: IM entity -> meta ----------

    private static class GifMeta {
        final @Nullable Uri uri;
        final @Nullable byte[] bytes;
        final @NonNull String mime;
        final @Nullable String name;
        final int w, h;

        GifMeta(@Nullable Uri uri,
                @Nullable byte[] bytes,
                @NonNull String mime,
                @Nullable String name,
                int w,
                int h) {
            this.uri = uri;
            this.bytes = bytes;
            this.mime = mime;
            this.name = name;
            this.w = w;
            this.h = h;
        }
    }

    /** Csak http/https/content/file sémákat engedünk; minden más: null. */
    @Nullable
    private static Uri safeParseHttpUri(@Nullable Object rawRef) {
        if (rawRef instanceof CharSequence) {
            try {
                Uri u = Uri.parse(rawRef.toString());
                String s = u.getScheme();
                if ("http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s)
                        || "content".equalsIgnoreCase(s) || "file".equalsIgnoreCase(s)) {
                    return u;
                }
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /** Kikeresi az első GIF-nek jelölt IM entitást és visszaadja a betöltéshez kellő metát. */
    @Nullable
    private GifMeta firstGifEntity(@Nullable Drafty content) {
        if (content == null) return null;
        Drafty.Entity[] ents = content.getEntities();
        if (ents == null) return null;

        for (Drafty.Entity e : ents) {
            if (!"IM".equals(e.tp) || e.data == null) continue;

            String mime = UiUtils.getStringVal("mime", e.data, null);
            if (!"image/gif".equalsIgnoreCase(mime)) continue;

            // inline bájtmező több néven is érkezhet
            byte[] bytes = UiUtils.getByteArray("val", e.data);
            if (bytes == null || bytes.length == 0) bytes = UiUtils.getByteArray("data", e.data);
            if (bytes == null || bytes.length == 0) bytes = UiUtils.getByteArray("bits", e.data);

            // ref -> Uri (ha van)
            Uri uri = safeParseHttpUri(e.data.get("ref"));
            if (uri == null) {
                Uri u2 = UiUtils.getUriVal("uri", e.data);
                if (u2 != null) uri = u2;
            }

            String name = UiUtils.getStringVal("name", e.data, null);
            int w = UiUtils.getIntVal("width", e.data);
            int h = UiUtils.getIntVal("height", e.data);

            gifLog("GIF entity -> uri=" + uri + ", inlineBytes=" + (bytes==null?0:bytes.length)
                    + ", w=" + w + ", h=" + h + ", name=" + name);

            return new GifMeta(uri, bytes, mime, name, w, h);
        }
        return null;
    }
    private static boolean hasQuote(@Nullable Drafty c) {
        if (c == null) return false;
        Drafty.Style[] fmts = c.getStyles();
        if (fmts != null) {
            for (Drafty.Style fmt : fmts) {
                if ("QQ".equals(fmt.tp)) return true; // QQ = idézet
            }
        }
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
        final ComTopic<VxCard> topic = (ComTopic<VxCard>) Cache.getTinode().getTopic(mTopicName);
        final StoredMessage m = getMessage(position);
        if (topic == null || m == null) return;

        holder.seqId = m.seq;
        if (mCursor == null) return;

        if (holder.gifLoad != null) {
            holder.gifLoad.dispose();
            holder.gifLoad = null;
        }
        setTopDrawable(holder.mText, null); // nagyon fontos: ne maradjon ott régi drawable

        final long msgId = m.getDbId();

        boolean isEdited = m.isReplacement() && m.getHeader("webrtc") == null;
        boolean hasAttachment = m.content != null && m.content.getEntReferences() != null;
        boolean uploadingAttachment = hasAttachment && m.isPending();
        boolean uploadFailed = hasAttachment && (m.status == BaseDb.Status.FAILED);

        // Tartalom kirakása
        if (m.content != null) {
            FullFormatter formatter = new FullFormatter(holder.mText, uploadingAttachment ? null : new SpanClicker(m.seq));
            formatter.setQuoteFormatter(new QuoteFormatter(holder.mText, holder.mText.getTextSize()));
            Spanned text = m.content.format(formatter);
            if (TextUtils.isEmpty(text)) {
                if (m.status == BaseDb.Status.DRAFT || m.status == BaseDb.Status.QUEUED || m.status == BaseDb.Status.SENDING) {
                    text = serviceContentSpanned(mActivity, R.drawable.ic_schedule_gray, R.string.processing);
                } else if (m.status == BaseDb.Status.FAILED) {
                    text = serviceContentSpanned(mActivity, R.drawable.ic_error_gray, R.string.failed);
                } else {
                    text = serviceContentSpanned(mActivity, R.drawable.ic_warning_gray, R.string.invalid_content);
                }
            } else if (m.content.isPlain()) {
                int count = UtilsString.countEmoji(text, EMOJI_SCALING.length + 1);
                if (count > 0 && count <= EMOJI_SCALING.length) {
                    CharacterStyle style = new RelativeSizeSpan(EMOJI_SCALING[EMOJI_SCALING.length - count]);
                    text = new SpannableStringBuilder(text);
                    ((SpannableStringBuilder) text).setSpan(style, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            holder.mText.setText(text);

            // ----- 2) GIF detektálás & megjelenítés -----
            final GifMeta gif = hasQuote(m.content) ? null : firstGifEntity(m.content);
            final boolean hasGif = gif != null;

            if (hasGif) {
                holder.mText.setText(""); // ne duplikálódjon a Drafty-ból jövő statikus tartalom
                holder.mText.setMovementMethod(null);
                holder.mText.setOnTouchListener(null);
                holder.mText.setLinksClickable(false);

                // FONTOS: a Disposable-t eltároljuk, hogy újrakötéskor/cikluskor lehessen cancel
                holder.gifLoad = bindImageOrGifIntoTextView(
                        holder.mText, gif.uri, gif.bytes, gif.mime, gif.w, gif.h
                );
            } else {
                // ha nincs GIF, biztosan ne maradjon rajta a régi
                setTopDrawable(holder.mText, null);
            }

        }


        if (m.content != null && m.content.hasEntities(Arrays.asList("AU","BN","EX","HT","IM","LN","MN","QQ","VD"))) {
            holder.mText.setOnTouchListener((v, ev) -> {
                holder.mGestureDetector.onTouchEvent(ev);
                return false;
            });
            holder.mText.setMovementMethod(StableLinkMovementMethod.getInstance());
            holder.mText.setLinksClickable(true);
            holder.mText.setFocusable(true);
            holder.mText.setClickable(true);
        } else {
            holder.mText.setOnTouchListener(null);
            holder.mText.setMovementMethod(null);
            holder.mText.setLinksClickable(false);
            holder.mText.setFocusable(false);
            holder.mText.setClickable(false);
            holder.mText.setAutoLinkMask(0);
        }

        // Feltöltési státusz
        if (hasAttachment && holder.mProgressContainer != null) {
            if (uploadingAttachment) {
                holder.mProgressResult.setVisibility(View.GONE);
                holder.mProgress.setVisibility(View.VISIBLE);
                holder.mProgressContainer.setVisibility(View.VISIBLE);
                holder.mCancelProgress.setOnClickListener(v -> {
                    cancelUpload(msgId);
                    holder.mProgress.setVisibility(View.GONE);
                    holder.mProgressResult.setText(R.string.canceled);
                    holder.mProgressResult.setVisibility(View.VISIBLE);
                });
            } else if (uploadFailed) {
                holder.mProgressResult.setText(R.string.failed);
                holder.mProgressResult.setVisibility(View.VISIBLE);
                holder.mProgress.setVisibility(View.GONE);
                holder.mProgressContainer.setVisibility(View.VISIBLE);
                holder.mCancelProgress.setOnClickListener(null);
            } else {
                holder.mProgressContainer.setVisibility(View.GONE);
                holder.mCancelProgress.setOnClickListener(null);
            }
        }

        // Kijelölés vizuál
        if (holder.mSelected != null) {
            holder.mSelected.setVisibility(mSelectedItems != null && mSelectedItems.get(position) ? View.VISIBLE : View.GONE);
        }

        // Avatar / név
        if (holder.mAvatar != null || holder.mUserName != null) {
            Subscription<VxCard, ?> sub = topic.getSubscription(m.from);
            if (sub != null) {
                if (holder.mAvatar != null) UiUtils.setAvatar(holder.mAvatar, sub.pub, sub.user, false);
                if (holder.mUserName != null && sub.pub != null) holder.mUserName.setText(sub.pub.fn);
            } else {
                if (holder.mAvatar != null) holder.mAvatar.setImageResource(R.drawable.ic_person_circle);
                if (holder.mUserName != null) {
                    Spannable span = new SpannableString(mActivity.getString(R.string.user_not_found));
                    span.setSpan(new StyleSpan(Typeface.ITALIC), 0, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    holder.mUserName.setText(span);
                }
            }
        }

        // Dátum/szerkesztve/meta
        if (m.ts != null) {
            Context context = holder.itemView.getContext();
            if (holder.mDateDivider.getVisibility() == View.VISIBLE) {
                long now = System.currentTimeMillis();
                CharSequence date = DateUtils.getRelativeTimeSpanString(
                        m.ts.getTime(), now, DateUtils.DAY_IN_MILLIS,
                        DateUtils.FORMAT_NUMERIC_DATE | DateUtils.FORMAT_SHOW_YEAR
                ).toString().toUpperCase();
                holder.mDateDivider.setText(date);
            }
            if (holder.mEdited != null) holder.mEdited.setVisibility(isEdited ? View.VISIBLE : View.GONE);
            if (holder.mMeta != null) holder.mMeta.setText(UtilsString.timeOnly(context, m.ts));
        }

        // Állapot ikon (csak saját üzenetnél)
        if (holder.mDeliveredIcon != null && (holder.mViewType & VIEWTYPE_SIDE_RIGHT) != 0) {
            UiUtils.setMessageStatusIcon(holder.mDeliveredIcon, m.status.value,
                    topic.msgReadCount(m.seq), topic.msgRecvCount(m.seq));
        }

        // ===== Kattintás/nyomás viselkedés =====
        final View tapTarget = holder.mRippleOverlay != null
                ? holder.mRippleOverlay
                : (holder.mMessageBubble != null ? holder.mMessageBubble : holder.itemView);

        tapTarget.setClickable(true);
        tapTarget.setLongClickable(true);
        tapTarget.setFocusable(true);
        holder.itemView.setLongClickable(true);

        // Közös long-press handler (reakciók megnyitása, ha nincs kijelölés)
        View.OnLongClickListener openReactions = v -> {
            if (mSelectedItems == null && mHost != null) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                try {
                    mHost.onMessageDoubleTapForReactions(m.seq);
                    return true; // kezeltük
                } catch (Exception ignored) {
                }
            }
            return false;
        };

        // HOSSZÚ NYOMÁS = reakciók (több helyen is, hogy biztosan elkapjuk)
        tapTarget.setOnLongClickListener(openReactions);
        holder.itemView.setOnLongClickListener(openReactions);
        if (holder.mText != null) holder.mText.setOnLongClickListener(openReactions);

        // Egyszeri kattintás: ha kijelölés van → toggle; különben buborék villanás + ha reply hivatkozás van, oda ugrik
        View.OnClickListener singleClick = v -> {
            if (mSelectedItems != null) {
                int pos = holder.getBindingAdapterPosition();
                toggleSelectionAt(pos);
                notifyItemChanged(pos);
                updateSelectionMode();
            } else {
                animateMessageBubble(holder, m.isMine(), true);
                int replySeq = UiUtils.parseSeqReference(m.getStringHeader("reply"));
                if (replySeq > 0) scrollToAndAnimate(replySeq);
            }
        };
        tapTarget.setOnClickListener(singleClick);
        holder.itemView.setOnClickListener(singleClick);

        // A TextView gesztusai (linkek, képek stb.) továbbra is működjenek
        tapTarget.setOnTouchListener((v, ev) -> false);

        // === Buborék alapszín beállítása paletta alapján ===
        if (holder.mMessageBubble != null) {
            PaletteManager.BubblePalette palette = PaletteManager.get();
            int bubbleColor = m.isMine() ? palette.mine : palette.other;
            holder.mMessageBubble.setBackgroundTintList(ColorStateList.valueOf(bubbleColor));

            // Opcionális: szövegszín automatikus kontraszt szerint
            if (holder.mText != null) {
                int yiq = ((bubbleColor >> 16) & 0xFF) * 299 +
                        ((bubbleColor >> 8) & 0xFF) * 587 +
                        (bubbleColor & 0xFF) * 114;
                holder.mText.setTextColor(yiq >= 128000 ? Color.BLACK : Color.WHITE);
            }
        }
    }





    // Publikus API: kijelölés indítása egy pozíción (swipe használja).
    public void startSelectionForPosition(int pos) {
        if (pos == RecyclerView.NO_POSITION) return;

        if (mSelectedItems == null) {
            mSelectedItems = new SparseBooleanArray();
        }
        if (!mSelectedItems.get(pos)) {
            mSelectedItems.put(pos, true);
        }
        notifyItemChanged(pos);

        // Az ActionMode indítását UI-ciklus végére tesszük, hogy ne akadjon az ItemTouchHelper-rel
        if (mRecyclerView != null) {
            mRecyclerView.post(() -> {
                if (mSelectionMode == null) {
                    mSelectionMode = mActivity.startSupportActionMode(mSelectionModeCallback);
                }
                updateSelectionMode();
            });
        } else {
            if (mSelectionMode == null) {
                mSelectionMode = mActivity.startSupportActionMode(mSelectionModeCallback);
            }
            updateSelectionMode();
        }
    }
    public void clearSelection() {
        if (mSelectedItems != null) {
            mSelectedItems.clear();
            mSelectedItems = null;
        }
        if (mSelectionMode != null) {
            mSelectionMode.finish();
            mSelectionMode = null;
        }
        notifyDataSetChanged();
    }
    public boolean hasSelection() {
        return mSelectedItems != null && mSelectedItems.size() > 0;
    }
    // Scroll to and animate message bubble.
    void scrollToAndAnimate(int seq) {
        final int pos = findInCursor(mCursor, seq);
        if (pos < 0) {
            return;
        }

        StoredMessage mm = getMessage(pos);
        if (mm != null) {
            LinearLayoutManager lm = (LinearLayoutManager) mRecyclerView.getLayoutManager();
            if (lm != null &&
                    pos >= lm.findFirstCompletelyVisibleItemPosition() &&
                    pos <= lm.findLastCompletelyVisibleItemPosition()) {
                // Completely visible, animate now.
                animateMessageBubble(
                        (ViewHolder) mRecyclerView.findViewHolderForAdapterPosition(pos),
                        mm.isMine(), false);
            } else {
                // Scroll then animate.
                mRecyclerView.clearOnScrollListeners();
                mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                        super.onScrollStateChanged(recyclerView, newState);
                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                            recyclerView.removeOnScrollListener(this);
                            animateMessageBubble(
                                    (ViewHolder) mRecyclerView.findViewHolderForAdapterPosition(pos),
                                    mm.isMine(), false);
                        }
                    }
                });
                mRecyclerView.smoothScrollToPosition(pos);
            }
        }
    }

    @Override
    public void onViewRecycled(final @NonNull ViewHolder vh) {
        super.onViewRecycled(vh);

        if (vh.gifLoad != null) {
            vh.gifLoad.dispose();
            vh.gifLoad = null;
        }
        setTopDrawable(vh.mText, null);

        // audio stop, ahogy nálad eddig is volt:
        if (vh.seqId > 0) {
            mMediaControl.releasePlayer(vh.seqId);
            vh.seqId = -1;
        }
    }


    private void animateMessageBubble(final ViewHolder vh, boolean isMine, boolean light) {
        if (vh == null || vh.mMessageBubble == null) return;

        PaletteManager.BubblePalette p = PaletteManager.get();

        int from = isMine ? p.mine : p.other;
        int to = isMine
                ? (light ? p.mineFlashLight : p.mineFlash)
                : (light ? p.otherFlashLight : p.otherFlash);

        ValueAnimator colorAnimation = ValueAnimator.ofArgb(from, to, from);
        colorAnimation.setDuration(light ? MESSAGE_BUBBLE_ANIMATION_SHORT : MESSAGE_BUBBLE_ANIMATION_LONG);
        colorAnimation.addUpdateListener(animator ->
                vh.mMessageBubble.setBackgroundTintList(ColorStateList.valueOf((int) animator.getAnimatedValue()))
        );
        colorAnimation.start();
    }


    // Must match position-to-item of getItemId.
    private StoredMessage getMessage(int position) {
        return getMessage(mCursor, position, -1);
    }

    private static StoredMessage getMessage(@Nullable Cursor cur, int position, int previewLength) {
        if (cur != null && !cur.isClosed() && cur.moveToPosition(position)) {
            return StoredMessage.readMessage(cur, previewLength);
        }
        return null;
    }

    @Override
    // Must match position-to-item of getMessage.
    public long getItemId(int position) {
        try {
            if (mCursor != null && !mCursor.isClosed() && mCursor.moveToPosition(position)) {
                return MessageDb.getLocalId(mCursor);
            }
        } catch (SQLiteBlobTooBigException ex) {
            Log.w(TAG, "Failed to read message (misconfigured server):", ex);
        }
        return View.NO_ID;
    }

    int findItemPositionById(long itemId, int first, int last) {
        if (mCursor == null || mCursor.isClosed()) {
            return -1;
        }

        try {
            for (int i = first; i <= last; i++) {
                if (mCursor.moveToPosition(i)) {
                    if (MessageDb.getLocalId(mCursor) == itemId) {
                        return i;
                    }
                }
            }
        } catch (SQLiteBlobTooBigException ex) {
            Log.w(TAG, "Failed to read message (misconfigured server):", ex);
        }
        return -1;
    }

    @Override
    public int getItemCount() {
        try {
            return mCursor != null && !mCursor.isClosed() ? mCursor.getCount() : 0;
        } catch (SQLiteBlobTooBigException ex) {
            Log.w(TAG, "Failed to read message count (misconfigured server):", ex);
            return 0;
        }
    }

    private void toggleSelectionAt(int pos) {
        if (mSelectedItems.get(pos)) {
            mSelectedItems.delete(pos);
        } else {
            mSelectedItems.put(pos, true);
        }
    }

    private void updateSelectionMode() {
        if (mSelectionMode != null) {
            int selected = mSelectedItems != null ? mSelectedItems.size() : 0;
            if (selected == 0) {
                mSelectionMode.finish();
                mSelectionMode = null;
            } else {
                mSelectionMode.setTitle(String.valueOf(selected));
                Menu menu = mSelectionMode.getMenu();
                boolean mutable = false;
                boolean repliable = false;
                Boolean pinned = null;
                if (selected == 1) {
                    StoredMessage msg = getMessage(mSelectedItems.keyAt(0));
                    if (msg != null && msg.status == BaseDb.Status.SYNCED) {
                        repliable = true;
                        final ComTopic topic = (ComTopic) Cache.getTinode().getTopic(mTopicName);
                        if (topic != null && topic.isManager()) {
                            pinned = topic.isPinned(msg.seq);
                        }
                        if (msg.content != null && msg.isMine()) {
                            mutable = true;
                            String[] types = new String[]{"AU", "EX", "FM", "IM", "VC", "VD"};
                            Drafty.Entity[] ents = msg.content.getEntities();
                            if (ents != null) {
                                for (Drafty.Entity ent : ents) {
                                    if (Arrays.binarySearch(types, ent.tp) >= 0) {
                                        mutable = false;
                                        break;
                                    }
                                }
                            }
                            if (mutable) {
                                Drafty.Style[] fmts = msg.content.getStyles();
                                if (fmts != null) {
                                    for (Drafty.Style fmt : fmts) {
                                        if ("QQ".equals(fmt.tp)) {
                                            mutable = false;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                menu.findItem(R.id.action_edit).setVisible(mutable);
                menu.findItem(R.id.action_reply).setVisible(repliable);
                menu.findItem(R.id.action_forward).setVisible(repliable);
                if (pinned != null) {
                    menu.findItem(R.id.action_pin).setVisible(!pinned);
                    menu.findItem(R.id.action_unpin).setVisible(pinned);
                } else {
                    menu.findItem(R.id.action_pin).setVisible(false);
                    menu.findItem(R.id.action_unpin).setVisible(false);
                }
            }
        }
    }

    void resetContent(@Nullable final String topicName) {
        if (topicName == null) {
            boolean hard = mTopicName != null;
            mTopicName = null;
            swapCursor(null, hard ? REFRESH_HARD : REFRESH_NONE);
        } else {
            boolean hard = !topicName.equals(mTopicName);
            mTopicName = topicName;
            runLoader(hard);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void swapCursor(final Cursor cursor, final int refresh) {
        if (mCursor != null && mCursor == cursor) {
            return;
        }

        // Clear selection
        if (mSelectionMode != null) {
            mSelectionMode.finish();
            mSelectionMode = null;
        }

        Cursor oldCursor = mCursor;
        mCursor = cursor;
        if (oldCursor != null) {
            oldCursor.close();
        }

        if (refresh != REFRESH_NONE) {
            mActivity.runOnUiThread(() -> {
                int position = -1;
                if (cursor != null) {
                    LinearLayoutManager lm = (LinearLayoutManager) mRecyclerView.getLayoutManager();
                    if (lm != null) {
                        position = lm.findFirstVisibleItemPosition();
                    }
                }
                mRefresher.setRefreshing(false);
                if (refresh == REFRESH_HARD) {
                    mRecyclerView.setAdapter(MessagesAdapter.this);
                } else {
                    notifyDataSetChanged();
                }
                if (cursor != null) {
                    if (position == 0) {
                        mRecyclerView.scrollToPosition(0);
                    }
                }
            });
        }
    }

    /**
     * Checks if the app has permission to write to device storage
     * <p>
     * If the app does not has permission then the user will be prompted to grant permission.
     */
    private void verifyStoragePermissions() {
        // Check if we have write permission
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                !UiUtils.isPermissionGranted(mActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            // We don't have permission so prompt the user
            Log.d(TAG, "No permission to write to storage");
            ActivityCompat.requestPermissions(mActivity, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE);
        }
    }

    // Run loader on UI thread
    private void runLoader(final boolean hard) {
        mActivity.runOnUiThread(() -> {
            final LoaderManager lm = LoaderManager.getInstance(mActivity);
            final Loader<Cursor> loader = lm.getLoader(MESSAGES_QUERY_ID);
            Bundle args = new Bundle();
            args.putBoolean(HARD_RESET, hard);
            if (loader != null && !loader.isReset()) {
                lm.restartLoader(MESSAGES_QUERY_ID, args, mMessageLoaderCallback);
            } else {
                lm.initLoader(MESSAGES_QUERY_ID, args, mMessageLoaderCallback);
            }
        });
    }

    // Increase the number of loaded messages by one page.
    // Returns null if request was satisfied by loading the full page from disk,
    // MetaGetBuilder if more messages have to be fetched from the server.
    MsgGetMeta loadPreviousPage() {
        final Topic topic = Cache.getTinode().getTopic(mTopicName);
        int count = getItemCount();
        if (count == mPagesToLoad * MESSAGES_TO_LOAD) {
            // Check if there are gaps in the next page.
            final StoredMessage msg = getMessage(mCursor,count - 1, 0);
            final SqlStore store = BaseDb.getInstance().getStore();
            MsgRange[] missing = store.getMissingRanges(topic, msg.seq, MESSAGES_TO_LOAD, false);
            if (missing == null) {
                mPagesToLoad++;
                runLoader(false);
                return null;
            } else {
                return topic.getMetaGetBuilder().withData(missing, MESSAGES_TO_LOAD).build();
            }
        }
        return topic.getMetaGetBuilder().withEarlierData(MESSAGES_TO_LOAD).build();
    }

    private void cancelUpload(long msgId) {
        Storage store = BaseDb.getInstance().getStore();
        final Topic topic = Cache.getTinode().getTopic(mTopicName);
        if (store != null && topic != null) {
            store.msgFailed(topic, msgId);
            // Invalidate cached data.
            runLoader(false);
        }

        final String uniqueID = Long.toString(msgId);

        WorkManager wm = WorkManager.getInstance(mActivity);
        WorkInfo.State state = null;
        try {
            List<WorkInfo> lwi = wm.getWorkInfosForUniqueWork(uniqueID).get();
            if (!lwi.isEmpty()) {
                WorkInfo wi = lwi.get(0);
                state = wi.getState();
            }
        } catch (CancellationException | ExecutionException | InterruptedException ignored) {
        }

        if (state == null || !state.isFinished()) {
            wm.cancelUniqueWork(uniqueID);
        }
    }

    void releaseAudio() {
        mMediaControl.releasePlayer(0);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final int mViewType;
        final ImageView mAvatar;
        final View mMessageBubble;
        final ImageView mDeliveredIcon;
        final TextView mDateDivider;
        final TextView mText;
        final TextView mEdited;
        final TextView mMeta;
        final TextView mUserName;
        final View mSelected;
        final View mRippleOverlay;
        final View mProgressContainer;
        final ProgressBar mProgressBar;
        final AppCompatImageButton mCancelProgress;
        final View mProgress;
        final TextView mProgressResult;
        final GestureDetector mGestureDetector;
        coil.request.Disposable gifLoad; // <--- ÚJ
        int seqId = 0;
        ViewHolder(View itemView, int viewType) {
            super(itemView);

            mViewType = viewType;
            mAvatar = itemView.findViewById(R.id.avatar);
            mMessageBubble = itemView.findViewById(R.id.messageBubble);
            mDeliveredIcon = itemView.findViewById(R.id.messageViewedIcon);
            mDateDivider = itemView.findViewById(R.id.dateDivider);
            mText = itemView.findViewById(R.id.messageText);
            mMeta = itemView.findViewById(R.id.messageMeta);
            mEdited = itemView.findViewById(R.id.messageEdited);
            mUserName = itemView.findViewById(R.id.userName);
            mSelected = itemView.findViewById(R.id.selected);
            mRippleOverlay = itemView.findViewById(R.id.overlay);
            mProgressContainer = itemView.findViewById(R.id.progressContainer);
            mProgress = itemView.findViewById(R.id.progressPanel);
            mProgressBar = itemView.findViewById(R.id.attachmentProgressBar);
            mCancelProgress = itemView.findViewById(R.id.attachmentProgressCancel);
            mProgressResult = itemView.findViewById(R.id.progressResult);

            mGestureDetector = new GestureDetector(itemView.getContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public void onLongPress(@NonNull MotionEvent ev) {
                            // NOP – a hosszú nyomás ne indítson semmit
                        }

                        @Override
                        public boolean onSingleTapConfirmed(@NonNull MotionEvent ev) {
                            itemView.performClick();
                            return true;
                        }

                        @Override
                        public void onShowPress(@NonNull MotionEvent ev) {
                            if (mRippleOverlay != null) {
                                mRippleOverlay.setPressed(true);
                                mRippleOverlay.postDelayed(() -> mRippleOverlay.setPressed(false), 250);
                            }
                        }

                        @Override
                        public boolean onDown(@NonNull MotionEvent ev) {
                            int x = (int) ev.getX();
                            int y = (int) ev.getY();
                            mText.setTag(R.id.click_coordinates, new Point(x, y));
                            return true;
                        }
                    });
        }
    }

    private class MessageLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        private boolean mHardReset;

        @NonNull
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            if (id == MESSAGES_QUERY_ID) {
                if (args != null) {
                    mHardReset = args.getBoolean(HARD_RESET, false);
                }
                return new MessageDb.Loader(mActivity, mTopicName, mPagesToLoad, MESSAGES_TO_LOAD);
            }

            throw new IllegalArgumentException("Unknown loader id " + id);
        }

        @Override
        public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
            if (loader.getId() == MESSAGES_QUERY_ID) {
                swapCursor(cursor, mHardReset ? REFRESH_HARD : REFRESH_SOFT);
            }
        }

        @Override
        public void onLoaderReset(@NonNull Loader<Cursor> loader) {
            if (loader.getId() == MESSAGES_QUERY_ID) {
                swapCursor(null, mHardReset ? REFRESH_HARD : REFRESH_SOFT);
            }
        }
    }

    class SpanClicker implements FullFormatter.ClickListener {
        private final int mSeqId;

        SpanClicker(int seq) {
            mSeqId = seq;
        }

        @Override
        public boolean onClick(String type, Map<String, Object> data, Object params) {
            if (mSelectedItems != null) {
                return false;
            }

            return switch (type) {
                case "AU" ->
                    // Pause/resume audio.
                        clickAudio(data, params);
                case "LN" ->
                    // Click on an URL
                        clickLink(data);
                case "IM" ->
                    // Image
                        clickImage(data);
                case "EX" ->
                    // Attachment
                        clickAttachment(data);
                case "BN" ->
                    // Button
                        clickButton(data);
                case "VD" ->
                    // Pay video.
                        clickVideo(data);
                default -> false;
            };
        }
        // --- GIF/IMAGE betöltés Coil-lal: animált GIF-ek támogatása ---
        private void bindImageOrGifInto(@NonNull final ImageView target,
                                        @Nullable final Uri localUri,
                                        @Nullable final Uri remoteUri,
                                        @Nullable final byte[] inlineBytes,
                                        @Nullable final String mimeOrNull) {
            final Context ctx = target.getContext();
            final boolean isGif = "image/gif".equalsIgnoreCase(mimeOrNull);

            // Válaszd ki, miből töltsünk: helyi URI > távoli URI > inline bájtok.
            final Object data =
                    localUri != null ? localUri :
                            (remoteUri != null ? remoteUri :
                                    (inlineBytes != null ? inlineBytes : null));

            if (data == null) {
                target.setImageResource(R.drawable.ic_broken_image);
                return;
            }

            // FIGYELEM: animált GIF-hez a HW decode tilos (különben nem indul az animáció).
            coil.request.ImageRequest request = new coil.request.ImageRequest.Builder(ctx)
                    .data(data)
                    .allowHardware(!isGif ? true : false)
                    .crossfade(true)
                    .placeholder(R.drawable.ic_image)
                    .error(R.drawable.ic_broken_image)
                    .target(new coil.target.Target() {
                        @Override
                        public void onSuccess(@NonNull Drawable result) {
                            target.setImageDrawable(result);
                            // Biztonság kedvéért indítsuk el kézzel is az animációt,
                            // ha a drawable Animatable (GIF)!
                            if (result instanceof android.graphics.drawable.Animatable) {
                                ((android.graphics.drawable.Animatable) result).start();
                            }
                        }

                        @Override
                        public void onError(@Nullable Drawable error) {
                            target.setImageDrawable(error);
                        }

                        @Override
                        public void onStart(@Nullable Drawable placeholder) {
                            target.setImageDrawable(placeholder);
                        }
                    })
                    .build();

            coil.Coil.imageLoader(ctx).enqueue(request);
        }

        private boolean clickAttachment(Map<String, Object> data) {
            if (data == null) {
                return false;
            }

            String fname = UiUtils.getStringVal("name", data, null);
            String mimeType = UiUtils.getStringVal("mime", data, null);

            // Try to extract file name from reference.
            if (TextUtils.isEmpty(fname)) {
                String ref = UiUtils.getStringVal("ref", data, "");
                try {
                    URL url = new URL(ref);
                    fname = url.getFile();
                } catch (MalformedURLException ignored) {
                }
            }

            if (fname != null) {
                fname = fname.trim();
            }
            if (TextUtils.isEmpty(fname)) {
                fname = mActivity.getString(R.string.default_attachment_name);
                fname += Long.toString(System.currentTimeMillis() % 10000);
            }

            AttachmentHandler.enqueueDownloadAttachment(mActivity,
                    UiUtils.getStringVal("ref", data, null),
                    UiUtils.getByteArray("val", data), fname, mimeType);

            return true;
        }

        // Audio play/pause.
        private boolean clickAudio(Map<String, Object> data, Object params) {
            if (data == null) {
                return false;
            }

            try {
                FullFormatter.AudioClickAction aca = (FullFormatter.AudioClickAction) params;
                if (aca.action == FullFormatter.AudioClickAction.Action.PLAY) {
                    if (mMediaControl.ensurePlayerReady(mSeqId, data, aca.control)) {
                        mMediaControl.playWhenReady();
                    }
                } else if (aca.action == FullFormatter.AudioClickAction.Action.PAUSE) {
                    mMediaControl.pause();
                } else if (aca.seekTo != null) {
                    if (mMediaControl.ensurePlayerReady(mSeqId, data, aca.control)) {
                        mMediaControl.seekToWhenReady(aca.seekTo);
                    }
                }
            } catch (ClassCastException ignored) {
                Toast.makeText(mActivity, R.string.unable_to_play_audio, Toast.LENGTH_SHORT).show();
                return false;
            }

            return true;
        }

        // Button click in Drafty form.
        private boolean clickButton(Map<String, Object> data) {
            if (data == null) {
                return false;
            }

            try {
                String actionType = UiUtils.getStringVal("act", data, null);
                String actionValue = UiUtils.getStringVal("val", data, null);
                String name = UiUtils.getStringVal("name", data, null);
                if ("pub".equals(actionType)) {
                    Drafty newMsg = new Drafty(UiUtils.getStringVal("title", data, null));
                    Map<String, Object> json = new HashMap<>();
                    // {"seq":6,"resp":{"yes":1}}
                    if (!TextUtils.isEmpty(name)) {
                        Map<String, Object> resp = new HashMap<>();
                        resp.put(name, TextUtils.isEmpty(actionValue) ? 1 : actionValue);
                        json.put("resp", resp);
                    }

                    json.put("seq", Integer.toString(mSeqId));
                    newMsg.attachJSON(json);
                    mActivity.sendMessage(newMsg, -1, false);

                } else if ("url".equals(actionType)) {
                    URL url = new URL(Cache.getTinode().getBaseUrl(),
                            UiUtils.getStringVal("ref", data, ""));
                    String scheme = url.getProtocol();
                    // As a security measure refuse to follow URLs with non-http(s) protocols.
                    if ("http".equals(scheme) || "https".equals(scheme)) {
                        Uri uri = Uri.parse(url.toString());
                        Uri.Builder builder = uri.buildUpon();
                        if (!TextUtils.isEmpty(name)) {
                            builder = builder.appendQueryParameter(name,
                                    TextUtils.isEmpty(actionValue) ? "1" : actionValue);
                        }
                        builder = builder
                                .appendQueryParameter("seq", Integer.toString(mSeqId))
                                .appendQueryParameter("uid", Cache.getTinode().getMyId());
                        Intent viewIntent = new Intent(Intent.ACTION_VIEW, builder.build());
                        try {
                            mActivity.startActivity(viewIntent);
                        } catch (ActivityNotFoundException ignored) {
                            Log.w(TAG, "No application can open the URL");
                            Toast.makeText(mActivity, R.string.action_failed, Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            } catch (MalformedURLException ignored) {
                return false;
            }

            return true;
        }

        private boolean clickLink(Map<String, Object> data) {
            if (data == null) {
                return false;
            }

            try {
                URL url = new URL(Cache.getTinode().getBaseUrl(), UiUtils.getStringVal("url", data, ""));
                String scheme = url.getProtocol();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // As a security measure refuse to follow URLs with non-http(s) protocols.
                    Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()));
                    try {
                        mActivity.startActivity(viewIntent);
                    } catch (ActivityNotFoundException ignored) {
                        Log.w(TAG, "No application can open the url " + url);
                        Toast.makeText(mActivity, R.string.action_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (MalformedURLException ignored) {
                return false;
            }
            return true;
        }

        // Code common to image & video click.
        private Bundle mediaClick(Map<String, Object> data) {
            if (data == null) {
                return null;
            }

            Bundle args = null;
            Uri ref =  UiUtils.getUriVal("ref", data);
            if (ref != null) {
                args = new Bundle();
                args.putParcelable(AttachmentHandler.ARG_REMOTE_URI, ref);
            }

            byte[] bytes = UiUtils.getByteArray("val", data);
            if (bytes != null) {
                args = args == null ? new Bundle() : args;
                args.putByteArray(AttachmentHandler.ARG_SRC_BYTES, bytes);
            }

            if (args == null) {
                return null;
            }

            args.putString(AttachmentHandler.ARG_MIME_TYPE, UiUtils.getStringVal("mime", data, null));
            args.putString(AttachmentHandler.ARG_FILE_NAME, UiUtils.getStringVal("name", data, null));
            args.putInt(AttachmentHandler.ARG_IMAGE_WIDTH, UiUtils.getIntVal("width", data));
            args.putInt(AttachmentHandler.ARG_IMAGE_HEIGHT, UiUtils.getIntVal("height", data));

            return args;
        }
        private boolean clickImage(Map<String, Object> data) {
            Bundle args = mediaClick(data);

            if (args == null) {
                Toast.makeText(mActivity, R.string.broken_image, Toast.LENGTH_SHORT).show();
                return false;
            }

            mActivity.showFragment(MessageActivity.FRAGMENT_VIEW_IMAGE, args, true);
            return true;
        }

        private boolean clickVideo(Map<String, Object> data) {
            Bundle args = mediaClick(data);

            if (args == null) {
                Toast.makeText(mActivity, R.string.broken_video, Toast.LENGTH_SHORT).show();
                return false;
            }

            Uri preref = UiUtils.getUriVal("preref", data);
            if (preref != null) {
                args.putParcelable(AttachmentHandler.ARG_PRE_URI, preref);
            }
            byte[] bytes = UiUtils.getByteArray("preview", data);
            if (bytes != null) {
                args.putByteArray(AttachmentHandler.ARG_PREVIEW, bytes);
            }
            args.putString(AttachmentHandler.ARG_PRE_MIME_TYPE, UiUtils.getStringVal("premime", data, null));

            mActivity.showFragment(MessageActivity.FRAGMENT_VIEW_VIDEO, args, true);

            return true;
        }
    }
}
