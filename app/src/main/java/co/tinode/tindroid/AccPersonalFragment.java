package co.tinode.tindroid;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.flexbox.FlexboxLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tindroid.widgets.AttachmentPickerDialog;
import co.tinode.tindroid.widgets.PhoneEdit;
import co.tinode.tinodesdk.FndTopic;
import co.tinode.tinodesdk.MeTopic;
import co.tinode.tinodesdk.PromisedReply;
import co.tinode.tinodesdk.Tinode;
import co.tinode.tinodesdk.model.Credential;
import co.tinode.tinodesdk.model.MsgSetMeta;
import co.tinode.tinodesdk.model.ServerMessage;

/**
 * Fragment for editing current user details.
 */
public class AccPersonalFragment extends Fragment
        implements ChatsActivity.FormUpdatable, UiUtils.AliasChecker, UtilsMedia.MediaPreviewer, MenuProvider {

    private final ActivityResultLauncher<PickVisualMediaRequest> mRequestAvatarLauncher =
            UtilsMedia.pickMediaLauncher(this, this);

    private final ActivityResultLauncher<Void> mThumbPhotoLauncher =
            UtilsMedia.takePreviewPhotoLauncher(this, this);

    private final ActivityResultLauncher<String> mRequestPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    mThumbPhotoLauncher.launch(null);
                }
            });

    private UiUtils.ValidatorHandler mAliasChecker;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        final AppCompatActivity activity = (AppCompatActivity) requireActivity();

        mAliasChecker = new UiUtils.ValidatorHandler(this);

        // Inflate the fragment layout
        View fragment = inflater.inflate(R.layout.fragment_acc_personal, container, false);
        final ActionBar bar = activity.getSupportActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true);
        }

        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.general);
        toolbar.setNavigationOnClickListener(v -> activity.getSupportFragmentManager().popBackStack());

        return fragment;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().addMenuProvider(this,
                getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    @Override
    public void onResume() {
        final FragmentActivity activity = getActivity();
        final MeTopic<VxCard> me = Cache.getTinode().getMeTopic();

        if (me == null || activity == null) {
            return;
        }

        // Avatar kiválasztás
        activity.findViewById(R.id.uploadAvatar).setOnClickListener(v ->
                new AttachmentPickerDialog.Builder()
                        .setGalleryLauncher(mRequestAvatarLauncher)
                        .setCameraPreviewLauncher(mThumbPhotoLauncher, mRequestPermissionsLauncher)
                        .build()
                        .show(getChildFragmentManager()));

        final TextView alias = activity.findViewById(R.id.alias);
        alias.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                alias.setError(UiUtils.validateAlias(activity, mAliasChecker, s.toString()));
            }
        });

        // Kezdeti értékek
        updateFormValues(activity, me);

        super.onResume();
    }

    @Override
    public void updateFormValues(@NonNull final FragmentActivity activity, final MeTopic<VxCard> me) {
        String fn = null;
        String description = null;
        View fragmentView = getView();
        if (fragmentView == null) {
            return;
        }
        if (me != null) {
            Credential[] creds = me.getCreds();
            if (creds != null) {
                // We only support two emails and two phone numbers at a time.
                Credential email = null, email2 = null;
                Credential phone = null, phone2 = null;
                Bundle argsEmail = new Bundle(), argsPhone = new Bundle();
                argsEmail.putString("method", "email");
                argsPhone.putString("method", "tel");
                for (Credential cred : creds) {
                    if ("email".equals(cred.meth)) {
                        if (!cred.isDone() || email != null) {
                            email2 = cred;
                        } else {
                            email = cred;
                        }
                    } else if ("tel".equals(cred.meth)) {
                        if (!cred.isDone() || phone != null) {
                            phone2 = cred;
                        } else {
                            phone = cred;
                        }
                    }
                }

                if (email == null) {
                    fragmentView.findViewById(R.id.emailWrapper).setVisibility(View.GONE);
                } else {
                    fragmentView.findViewById(R.id.emailWrapper).setVisibility(View.VISIBLE);
                    TextView emailField = fragmentView.findViewById(R.id.email);
                    emailField.setText(email.val);
                    if (email2 != null && email2.isDone()) {
                        emailField.setBackground(null);
                        AppCompatImageButton delete = fragmentView.findViewById(R.id.emailDelete);
                        delete.setVisibility(View.VISIBLE);
                        delete.setTag(email);
                        delete.setOnClickListener(this::showDeleteCredential);
                    } else {
                        fragmentView.findViewById(R.id.emailDelete).setVisibility(View.INVISIBLE);
                        argsEmail.putString("oldValue", email.val);
                        if (email2 == null) {
                            emailField.setOnClickListener(this::showEditCredential);
                            emailField.setBackgroundResource(R.drawable.dotted_line);
                        } else {
                            emailField.setBackground(null);
                        }
                    }
                    emailField.setTag(argsEmail);
                }

                if (email2 == null) {
                    fragmentView.findViewById(R.id.emailNewWrapper).setVisibility(View.GONE);
                } else {
                    fragmentView.findViewById(R.id.emailNewWrapper).setVisibility(View.VISIBLE);
                    TextView emailField2 = fragmentView.findViewById(R.id.emailNew);
                    emailField2.setText(email2.val);
                    if (!email2.isDone()) {
                        argsEmail.putString("newValue", email2.val);
                        fragmentView.findViewById(R.id.unconfirmedEmail).setVisibility(View.VISIBLE);
                        emailField2.setOnClickListener(this::showEditCredential);
                        emailField2.setBackgroundResource(R.drawable.dotted_line);
                    } else {
                        fragmentView.findViewById(R.id.unconfirmedEmail).setVisibility(View.INVISIBLE);
                        emailField2.setBackground(null);
                    }
                    emailField2.setTag(argsEmail);

                    AppCompatImageButton delete = fragmentView.findViewById(R.id.emailNewDelete);
                    delete.setVisibility(View.VISIBLE);
                    delete.setTag(email2);
                    delete.setOnClickListener(this::showDeleteCredential);
                }

                if (phone == null) {
                    fragmentView.findViewById(R.id.phoneWrapper).setVisibility(View.GONE);
                } else {
                    activity.findViewById(R.id.phoneWrapper).setVisibility(View.VISIBLE);
                    TextView phoneField = fragmentView.findViewById(R.id.phone);
                    phoneField.setText(PhoneEdit.formatIntl(phone.val));
                    if (phone2 != null && phone2.isDone()) {
                        phoneField.setBackground(null);
                        AppCompatImageButton delete = fragmentView.findViewById(R.id.phoneDelete);
                        delete.setVisibility(View.VISIBLE);
                        delete.setTag(phone);
                        delete.setOnClickListener(this::showDeleteCredential);
                    } else {
                        fragmentView.findViewById(R.id.phoneDelete).setVisibility(View.INVISIBLE);
                        argsPhone.putString("oldValue", phone.val);
                        if (phone2 == null) {
                            phoneField.setOnClickListener(this::showEditCredential);
                            phoneField.setBackgroundResource(R.drawable.dotted_line);
                        } else {
                            phoneField.setBackground(null);
                        }
                    }
                    phoneField.setTag(argsPhone);
                }

                if (phone2 == null) {
                    fragmentView.findViewById(R.id.phoneNewWrapper).setVisibility(View.GONE);
                } else {
                    fragmentView.findViewById(R.id.phoneNewWrapper).setVisibility(View.VISIBLE);
                    TextView phoneField2 = fragmentView.findViewById(R.id.phoneNew);
                    phoneField2.setText(PhoneEdit.formatIntl(phone2.val));
                    if (!phone2.isDone()) {
                        argsPhone.putString("newValue", phone2.val);
                        fragmentView.findViewById(R.id.unconfirmedPhone).setVisibility(View.VISIBLE);
                        phoneField2.setOnClickListener(this::showEditCredential);
                        phoneField2.setBackgroundResource(R.drawable.dotted_line);
                    } else {
                        fragmentView.findViewById(R.id.unconfirmedPhone).setVisibility(View.INVISIBLE);
                        phoneField2.setBackground(null);
                    }
                    phoneField2.setTag(argsPhone);

                    AppCompatImageButton delete = fragmentView.findViewById(R.id.phoneNewDelete);
                    delete.setVisibility(View.VISIBLE);
                    delete.setTag(phone2);
                    delete.setOnClickListener(this::showDeleteCredential);
                }
            }

            VxCard pub = me.getPub();
            UiUtils.setAvatar(fragmentView.findViewById(R.id.imageAvatar), pub, Cache.getTinode().getMyId(), false);
            if (pub != null) {
                fn = pub.fn;
                description = pub.note;
            }

            ((TextView) activity.findViewById(R.id.alias)).setText(me.alias());
        }

        ((TextView) fragmentView.findViewById(R.id.topicTitle)).setText(fn);
        ((TextView) fragmentView.findViewById(R.id.topicDescription)).setText(description);
    }

    private void showEditCredential(View view) {
        final ChatsActivity activity = (ChatsActivity) requireActivity();
        activity.showFragment(ChatsActivity.FRAGMENT_ACC_CREDENTIALS, (Bundle) view.getTag());
    }

    private void showDeleteCredential(View view) {
        final Activity activity = requireActivity();
        Credential cred = (Credential) view.getTag();

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setNegativeButton(android.R.string.cancel, null)
                .setTitle(R.string.delete_credential_title)
                .setMessage(getString(R.string.delete_credential_confirmation, cred.meth, cred.val))
                .setCancelable(true)
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    final MeTopic me = Cache.getTinode().getMeTopic();
                    // noinspection unchecked
                    me.delCredential(cred.meth, cred.val)
                            .thenCatch(new UiUtils.ToastFailureListener(activity));
                })
                .show();
    }

    @Override
    public void handleMedia(final Bundle args) {
        final Activity activity = requireActivity();
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        // >>> FONTOS: jelöljük meg, hogy AVATAR és adjuk át a 'me' topikot
        args.putBoolean(AttachmentHandler.ARG_AVATAR, true);
        args.putString(Const.INTENT_EXTRA_TOPIC, Tinode.TOPIC_ME);
        ((ChatsActivity) activity).showFragment(ChatsActivity.FRAGMENT_AVATAR_PREVIEW, args);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.menu_save, menu);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save) {
            FragmentActivity activity = requireActivity();
            if (activity.isFinishing() || activity.isDestroyed()) {
                return false;
            }
            final MeTopic<VxCard> me = Cache.getTinode().getMeTopic();
            String title = ((TextView) activity.findViewById(R.id.topicTitle)).getText().toString().trim();
            String description = ((TextView) activity.findViewById(R.id.topicDescription)).getText().toString().trim();
            String alias = ((TextView) activity.findViewById(R.id.alias)).getText().toString().trim();

            UiUtils.updateTopicDesc(me, title, null, description, alias)
                    .thenApply(new PromisedReply.SuccessListener<>() {
                        @Override
                        public PromisedReply<ServerMessage> onSuccess(ServerMessage unused) {
                            if (!activity.isFinishing() && !activity.isDestroyed()) {
                                activity.runOnUiThread(() -> activity.getSupportFragmentManager().popBackStack());
                            }
                            return null;
                        }
                    })
                    .thenCatch(new UiUtils.ToastFailureListener(activity));
            return true;
        }
        return false;
    }

    private void setValidationError(final String error) {
        View fv = getView();
        if (fv != null && isVisible()) {
            Activity activity = requireActivity();
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            activity.runOnUiThread(() -> ((TextView) fv.findViewById(R.id.alias)).setError(error));
        }
    }

    public void checkUniqueness(String alias) {
        final MeTopic<VxCard> me = Cache.getTinode().getMeTopic();

        if (me == null || !isVisible()) {
            return;
        }

        final FndTopic<?> fnd = Cache.getTinode().getOrCreateFndTopic();
        fnd.checkTagUniqueness(alias, Cache.getTinode().getMyId())
                .thenApply(new PromisedReply.SuccessListener<>() {
                    @Override
                    public PromisedReply<Boolean> onSuccess(Boolean result) {
                        if (result) {
                            setValidationError(null);
                        } else {
                            setValidationError(getString(R.string.alias_already_taken));
                        }
                        return null;
                    }
                }).thenCatch(new PromisedReply.FailureListener<>() {
                    @Override
                    public <E extends Exception> PromisedReply<Boolean> onFailure(E err) {
                        setValidationError(err.toString());
                        return null;
                    }
                });
    }
}
