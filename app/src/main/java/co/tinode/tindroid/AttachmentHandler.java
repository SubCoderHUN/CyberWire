package co.tinode.tindroid;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.exifinterface.media.ExifInterface;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import co.tinode.tindroid.db.BaseDb;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tinodesdk.LargeFileHelper;
import co.tinode.tinodesdk.PromisedReply;
import co.tinode.tinodesdk.Storage;
import co.tinode.tinodesdk.Tinode;
import co.tinode.tinodesdk.Topic;
import co.tinode.tinodesdk.model.Drafty;
import co.tinode.tinodesdk.model.ServerMessage;
import co.tinode.tinodesdk.model.TheCard;

public class AttachmentHandler extends Worker {
    final static String ARG_OPERATION = "operation";
    final static String ARG_OPERATION_IMAGE = "image";
    final static String ARG_OPERATION_FILE = "file";
    final static String ARG_OPERATION_AUDIO = "audio";
    final static String ARG_OPERATION_VIDEO = "video";

    // Bundle arg names.
    final static String ARG_TOPIC_NAME = Const.INTENT_EXTRA_TOPIC;
    final static String ARG_LOCAL_URI = "local_uri";
    final static String ARG_REMOTE_URI = "remote_uri";
    final static String ARG_SRC_BYTES = "bytes";
    final static String ARG_SRC_BITMAP = "bitmap";
    final static String ARG_PREVIEW = "preview";
    final static String ARG_MIME_TYPE = "mime";
    public static final String ARG_IS_GIF = "arg_is_gif";
    public static final String ARG_KEEP_ORIGINAL = "arg_keep_original";

    final static String ARG_PRE_MIME_TYPE = "pre_mime";
    final static String ARG_PRE_URI = "pre_rem_uri";
    final static String ARG_IMAGE_WIDTH = "width";
    final static String ARG_IMAGE_HEIGHT = "height";
    final static String ARG_DURATION = "duration";
    final static String ARG_FILE_SIZE = "fileSize";

    final static String ARG_FILE_PATH = "filePath";
    final static String ARG_FILE_NAME = "fileName";
    final static String ARG_MSG_ID = "msgId";
    final static String ARG_IMAGE_CAPTION = "caption";
    final static String ARG_PROGRESS = "progress";
    final static String ARG_ERROR = "error";
    final static String ARG_FATAL = "fatal";
    final static String ARG_AVATAR = "square_img";

    final static String TAG_UPLOAD_WORK = "AttachmentUploader";

    private static final String TAG = "AttachmentHandler";

    private LargeFileHelper mUploader = null;

    public AttachmentHandler(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    private enum UploadType {
        UNKNOWN, AUDIO, FILE, IMAGE, VIDEO;

        static UploadType parse(String type) {
            if (ARG_OPERATION_AUDIO.equals(type)) {
                return AUDIO;
            } else if (ARG_OPERATION_FILE.equals(type)) {
                return FILE;
            } else if (ARG_OPERATION_IMAGE.equals(type)) {
                return IMAGE;
            } else if (ARG_OPERATION_VIDEO.equals(type)) {
                return VIDEO;
            }
            return UNKNOWN;
        }
    }

    // === Első frame-ből preview PNG + méret (GIF-hez praktikus) ===
    @Nullable
    private static byte[] extractFirstFramePng(@NonNull Context ctx,
                                               @NonNull Uri contentUri,
                                               @NonNull int[] outWH /* len=2 */) {
        outWH[0] = outWH[1] = 0;
        Bitmap first = null;
        try (InputStream in = ctx.getContentResolver().openInputStream(contentUri)) {
            if (in != null) first = BitmapFactory.decodeStream(in);
            if (first == null) return null;
            outWH[0] = first.getWidth();
            outWH[1] = first.getHeight();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            first.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return bos.toByteArray();
        } catch (Throwable ignore) {
            return null;
        } finally {
            if (first != null) first.recycle();
        }
    }

    // (Kényelmi) GIF küldése content URI-ból
    public static Operation sendGifFromContentUri(@NonNull AppCompatActivity activity,
                                                  @NonNull String topic,
                                                  @NonNull Uri contentUri,
                                                  @Nullable String displayName) {
        Bundle args = new Bundle();
        args.putString(ARG_TOPIC_NAME, topic);
        args.putParcelable(ARG_LOCAL_URI, contentUri);
        args.putString(ARG_MIME_TYPE, "image/gif");
        if (!TextUtils.isEmpty(displayName)) {
            args.putString(ARG_FILE_NAME, displayName);
        }
        return enqueueMsgAttachmentUploadRequest(activity, ARG_OPERATION_IMAGE, args);
    }

    @NonNull
    static UploadDetails getFileDetails(@NonNull final Context context, @NonNull Uri uri, @Nullable String filePath) {
        final ContentResolver resolver = context.getContentResolver();
        String fname = null;
        long fsize = 0L;
        int orientation = -1;

        UploadDetails result = new UploadDetails();
        result.width = 0;
        result.height = 0;

        String mimeType = resolver.getType(uri);
        if (mimeType == null) {
            mimeType = UiUtils.getMimeType(uri);
        }
        if ("application/json".equals(mimeType)) {
            // Elkerüljük a Drafty JSON ütközést.
            mimeType = "application/octet-stream";
        }
        result.mimeType = mimeType;

        String[] projection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, MediaStore.MediaColumns.ORIENTATION};
        } else {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }

        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) fname = cursor.getString(idx);
                idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) fsize = cursor.getLong(idx);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    idx = cursor.getColumnIndex(MediaStore.MediaColumns.ORIENTATION);
                    if (idx >= 0) orientation = cursor.getInt(idx);
                }
            }
        } catch (Exception ignored) {}

        result.imageOrientation = orientation;

        // Ha még mindig nincs méret, próbáljuk elérni közvetlenül
        if (fsize <= 0) {
            String path = filePath != null ? filePath : UiUtils.getContentPath(context, uri);
            if (path != null) {
                result.filePath = path;
                File file = new File(path);
                if (fname == null) fname = file.getName();
                fsize = file.length();
            } else {
                try {
                    DocumentFile df = DocumentFile.fromSingleUri(context, uri);
                    if (df != null) {
                        fname = df.getName();
                        fsize = df.length();
                    }
                } catch (SecurityException ignored) {}
            }
        }

        result.fileName = fname;
        result.fileSize = fsize;

        return result;
    }

    static Operation enqueueMsgAttachmentUploadRequest(AppCompatActivity activity, String operation, Bundle args) {
        String topicName = args.getString(AttachmentHandler.ARG_TOPIC_NAME);
        Drafty content = new Drafty();
        HashMap<String, Object> head = new HashMap<>();
        head.put("mime", Drafty.MIME_TYPE);
        Storage.Message msg = BaseDb.getInstance().getStore()
                .msgDraft(Cache.getTinode().getTopic(topicName), content, head);
        if (msg == null) {
            Log.w(TAG, "Failed to create draft message");
            return null;
        }

        UploadType type = UploadType.parse(operation);
        Uri uri = args.getParcelable(AttachmentHandler.ARG_LOCAL_URI);
        if (uri == null) {
            Log.w(TAG, "Missing local attachment URI");
            return null;
        }

        Data.Builder data = new Data.Builder()
                .putString(ARG_OPERATION, operation)
                .putString(ARG_LOCAL_URI, uri.toString())
                .putLong(ARG_MSG_ID, msg.getDbId())
                .putString(ARG_TOPIC_NAME, topicName)
                .putString(ARG_FILE_NAME, args.getString(ARG_FILE_NAME))
                .putLong(ARG_FILE_SIZE, args.getLong(ARG_FILE_SIZE))
                .putString(ARG_MIME_TYPE, args.getString(ARG_MIME_TYPE))
                .putString(ARG_IMAGE_CAPTION, args.getString(ARG_IMAGE_CAPTION))
                .putString(ARG_FILE_PATH, args.getString(ARG_FILE_PATH))
                .putInt(ARG_IMAGE_WIDTH, args.getInt(ARG_IMAGE_WIDTH))
                .putInt(ARG_IMAGE_HEIGHT, args.getInt(ARG_IMAGE_HEIGHT));

        if (type == UploadType.AUDIO || type == UploadType.VIDEO) {
            byte[] preview = args.getByteArray(ARG_PREVIEW);
            if (preview != null) data.putByteArray(ARG_PREVIEW, preview);
            data.putInt(ARG_DURATION, args.getInt(ARG_DURATION));
            Uri preUri = args.getParcelable(AttachmentHandler.ARG_PRE_URI);
            if (preUri != null) data.putString(ARG_PRE_URI, preUri.toString());
            if (type == UploadType.VIDEO && (preview != null || preUri != null)) {
                data.putString(ARG_PRE_MIME_TYPE, args.getString(ARG_PRE_MIME_TYPE));
            }
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest upload = new OneTimeWorkRequest.Builder(AttachmentHandler.class)
                .setInputData(data.build())
                .setConstraints(constraints)
                .addTag(TAG_UPLOAD_WORK)
                .build();

        return WorkManager.getInstance(activity).enqueueUniqueWork(Long.toString(msg.getDbId()),
                ExistingWorkPolicy.REPLACE, upload);
    }

    @SuppressWarnings("UnusedReturnValue")
    static long enqueueDownloadAttachment(AppCompatActivity activity, String ref, byte[] bits,
                                          String fname, String mimeType) {
        long downloadId = -1;
        if (ref != null) {
            try {
                URL url = new URL(Cache.getTinode().getBaseUrl(), ref);
                String scheme = url.getProtocol();
                if (scheme.equals("http") || scheme.equals("https")) {
                    LargeFileHelper lfh = Cache.getTinode().getLargeFileHelper();
                    downloadId = remoteDownload(activity, Uri.parse(url.toString()), fname, mimeType, lfh.headers());
                } else {
                    Log.w(TAG, "Unsupported transport protocol '" + scheme + "'");
                    Toast.makeText(activity, R.string.failed_to_download, Toast.LENGTH_SHORT).show();
                }
            } catch (MalformedURLException ex) {
                Log.w(TAG, "Server address is not yet configured", ex);
                Toast.makeText(activity, R.string.failed_to_download, Toast.LENGTH_SHORT).show();
            }
        } else if (bits != null) {
            File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            path.mkdirs();

            File file = new File(path, fname);

            if (TextUtils.isEmpty(mimeType)) {
                mimeType = UiUtils.getMimeType(Uri.fromFile(file));
                if (mimeType == null) mimeType = "*/*";
            }

            Uri result;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(bits);
                    result = FileProvider.getUriForFile(activity, "co.tinode.tindroid.provider", file);
                } catch (IOException ex) {
                    Log.w(TAG, "Failed to save attachment to storage", ex);
                    Toast.makeText(activity, R.string.failed_to_save_download, Toast.LENGTH_SHORT).show();
                    return downloadId;
                }
            } else {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fname);
                cv.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                ContentResolver resolver = activity.getContentResolver();
                Uri dst = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                result = resolver.insert(dst, cv);
                if (result != null) {
                    try {
                        new ParcelFileDescriptor.
                                AutoCloseOutputStream(resolver.openFileDescriptor(result, "w")).write(bits);
                    } catch (IOException ex) {
                        Log.w(TAG, "Failed to save attachment to media storage", ex);
                        Toast.makeText(activity, R.string.failed_to_save_download, Toast.LENGTH_SHORT).show();
                        return downloadId;
                    }
                    cv.clear();
                    cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(result, cv, null, null);
                }
            }

            MediaScannerConnection.scanFile(activity, new String[]{file.toString()}, null, null);

            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(result, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                activity.startActivity(intent);
            } catch (ActivityNotFoundException ex) {
                Log.w(TAG, "No application can handle downloaded file", ex);
                Toast.makeText(activity, R.string.failed_to_open_file, Toast.LENGTH_SHORT).show();
                activity.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
            }
        } else {
            Log.w(TAG, "Invalid or missing attachment");
            Toast.makeText(activity, R.string.failed_to_download, Toast.LENGTH_SHORT).show();
        }

        return downloadId;
    }

    private static long remoteDownload(AppCompatActivity activity, final Uri uri, final String fname, final String mime,
                                       final Map<String, String> headers) {

        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return -1;

        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).mkdirs();

        DownloadManager.Request req = new DownloadManager.Request(uri);
        req.addRequestHeader("Origin", Cache.getTinode().getHttpOrigin());
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                req.addRequestHeader(entry.getKey(), entry.getValue());
            }
        }

        return dm.enqueue(
                req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI |
                                DownloadManager.Request.NETWORK_MOBILE)
                        .setMimeType(mime)
                        .setAllowedOverRoaming(false)
                        .setTitle(fname)
                        .setDescription(activity.getString(R.string.download_title))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setVisibleInDownloadsUi(true)
                        .setDestinationUri(Uri.fromFile(new File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fname)))
        );
    }

    private static @Nullable URI wrapRefUrl(@Nullable String refUrl) {
        URI ref = null;
        if (refUrl != null) {
            try {
                ref = new URI(refUrl);
                if (ref.isAbsolute()) {
                    ref = new URI(Cache.getTinode().getBaseUrl().toString()).relativize(ref);
                }
            } catch (URISyntaxException | MalformedURLException ignored) {}
        }
        return ref;
    }

    // Audio
    private static Drafty draftyAudio(String mimeType, byte[] preview, byte[] bits, String refUrl,
                                      int duration, String fname, long size) {
        return new Drafty().insertAudio(0, mimeType, bits, preview, duration, fname, wrapRefUrl(refUrl), size);
    }

    // Kép (általános, GIF-hez is ezt használjuk mime="image/gif"-fel)
    private static Drafty draftyImage(String caption, String mimeType, byte[] bits, String refUrl,
                                      int width, int height, String fname, long size) {
        Drafty content = new Drafty();
        content.insertImage(0, mimeType, bits, width, height, fname, wrapRefUrl(refUrl), size);
        if (!TextUtils.isEmpty(caption)) {
            content.appendLineBreak().append(Drafty.fromPlainText(caption));
        }
        return content;
    }

    // Fájl in-band
    private static Drafty draftyFile(String mimeType, String fname, byte[] bits) {
        Drafty content = new Drafty();
        content.attachFile(mimeType, bits, fname);
        return content;
    }

    // Fájl linkkel
    private static Drafty draftyAttachment(String mimeType, String fname, String refUrl, long size) {
        Drafty content = new Drafty();
        content.attachFile(mimeType, fname, refUrl, size);
        return content;
    }

    // Videó
    private static Drafty draftyVideo(String caption, String mimeType, byte[] bits, String refUrl,
                                      int width, int height,
                                      int duration, byte[] preview, String preref, String premime,
                                      String fname, long size) {
        Drafty content = new Drafty();
        content.insertVideo(0, mimeType, bits, width, height, preref == null ? preview : null,
                wrapRefUrl(preref), premime, duration, fname, wrapRefUrl(refUrl), size);
        if (!TextUtils.isEmpty(caption)) {
            content.appendLineBreak().append(Drafty.fromPlainText(caption));
        }
        return content;
    }

    @NonNull
    @Override
    public ListenableWorker.Result doWork() {
        return uploadMessageAttachment(getApplicationContext(), getInputData());
    }

    @Override
    public void onStopped() {
        if (mUploader != null) mUploader.cancel();
        super.onStopped();
    }
    // GIF küldés: ugyanaz, mint kép, csak image/gif MIME-mal.
// Ha out-of-band (refUrl != null), akkor a bits-hez a preview-t adjuk (ha van),
// ha in-band, akkor az eredeti GIF bájtokat.
    private static Drafty draftyGif(@Nullable String fname,
                                    @Nullable byte[] gifBitsOrNull,
                                    @Nullable String refUrl,
                                    int width,
                                    int height,
                                    @Nullable byte[] previewPngOrNull) {

        final String mime = "image/gif";

        // bits: in-band esetben az eredeti GIF; out-of-bandnál inkább a preview
        byte[] bits = (refUrl != null && previewPngOrNull != null)
                ? previewPngOrNull
                : gifBitsOrNull;

        long size = (gifBitsOrNull != null) ? gifBitsOrNull.length : 0L;

        Drafty content = new Drafty();
        content.insertImage(
                0,              // offset
                mime,           // MIME
                bits,           // val (lehet null is out-of-bandnál)
                width, height,  // dimenziók
                fname,          // fájlnév
                wrapRefUrl(refUrl), // ref (null, ha in-band)
                size            // fájlméret (tájékoztató)
        );
        return content;
    }

    // Create placeholder draft message.
    private static Drafty prepareDraft(UploadType operation, UploadDetails uploadDetails, String caption) {
        Drafty msgDraft = null;

        switch (operation) {
            case AUDIO:
                if (TextUtils.isEmpty(uploadDetails.mimeType)) {
                    uploadDetails.mimeType = "audio/aac";
                }
                msgDraft = draftyAudio(uploadDetails.mimeType, uploadDetails.previewBits,
                        uploadDetails.valueBits, uploadDetails.valueRef, uploadDetails.duration,
                        uploadDetails.fileName,
                        // ha in-band, akkor valueBits != null; ezt a hossz számításhoz használjuk
                        uploadDetails.valueBits != null ? uploadDetails.valueBits.length : uploadDetails.fileSize);
                break;

            case FILE:
                if (!TextUtils.isEmpty(uploadDetails.valueRef)) {
                    msgDraft = draftyAttachment(uploadDetails.mimeType, uploadDetails.fileName,
                            uploadDetails.valueRef, uploadDetails.fileSize);
                } else {
                    msgDraft = draftyFile(uploadDetails.mimeType, uploadDetails.fileName, uploadDetails.valueBits);
                }
                break;

            case IMAGE: {
                // Itt kezeljük a GIF-et is: ugyanúgy draftyImage, csak a MIME "image/gif".
                if (TextUtils.isEmpty(uploadDetails.mimeType)) {
                    uploadDetails.mimeType = "image/jpeg";
                }

                // Ha még nincs width/height, próbáljuk a preview-ból kiolvasni (ha van).
                if ((uploadDetails.width == 0 || uploadDetails.height == 0) && uploadDetails.previewBits != null) {
                    BitmapFactory.Options opts = boundsFromBitmapBits(uploadDetails.previewBits);
                    uploadDetails.width = opts.outWidth;
                    uploadDetails.height = opts.outHeight;
                }

                // Ha out-of-band (valueRef != null), akkor a „bits” legyen null (csak link megy a szerverre).
                byte[] bitsForDraft = (uploadDetails.valueRef != null) ? null : uploadDetails.valueBits;

                msgDraft = draftyImage(
                        caption,
                        uploadDetails.mimeType,         // lehet "image/gif" is
                        bitsForDraft,                   // in-band esetben a teljes tartalom
                        uploadDetails.valueRef,         // out-of-band esetben "mid:uploading-..." ref
                        uploadDetails.width,
                        uploadDetails.height,
                        uploadDetails.fileName,
                        uploadDetails.fileSize
                );
                break;
            }

            case VIDEO:
                if (TextUtils.isEmpty(uploadDetails.mimeType)) {
                    uploadDetails.mimeType = "video/mpeg";
                }
                msgDraft = draftyVideo(caption, uploadDetails.mimeType,
                        uploadDetails.valueBits, uploadDetails.valueRef, uploadDetails.width, uploadDetails.height,
                        uploadDetails.duration, uploadDetails.previewBits, uploadDetails.previewRef,
                        uploadDetails.previewMime, uploadDetails.fileName, uploadDetails.fileSize);
                break;
        }

        return msgDraft;
    }


    // Hosszú, blokkoló művelet – ne UI threaden hívd.
    private ListenableWorker.Result uploadMessageAttachment(final Context context, final Data args) {
        Storage store = BaseDb.getInstance().getStore();

        UploadType operation = UploadType.parse(args.getString(ARG_OPERATION));

        final String topicName = args.getString(ARG_TOPIC_NAME);
        final Uri uri = Uri.parse(args.getString(ARG_LOCAL_URI));
        final String filePath = args.getString(ARG_FILE_PATH);
        final long msgId = args.getLong(ARG_MSG_ID, 0);

        final Data.Builder result = new Data.Builder()
                .putString(ARG_TOPIC_NAME, topicName)
                .putLong(ARG_MSG_ID, msgId);

        final Topic topic = Cache.getTinode().getTopic(topicName);

        final long maxInbandAttachmentSize = Cache.getTinode().getServerLimit(Tinode.MAX_MESSAGE_SIZE,
                (1L << 18)) * 3 / 4 - 1024;
        final long maxFileUploadSize = Cache.getTinode().getServerLimit(Tinode.MAX_FILE_UPLOAD_SIZE, 1L << 23);

        Drafty content = null;
        boolean success = false;
        InputStream is = null;
        Bitmap bmp = null;

        try {
            final ContentResolver resolver = context.getContentResolver();
            final UploadDetails uploadDetails = getFileDetails(context, uri, filePath);

            if (uploadDetails.fileSize == 0) {
                Log.w(TAG, "File size is zero; uri=" + uri + "; file=" + filePath);
                return ListenableWorker.Result.failure(
                        result.putBoolean(ARG_FATAL, true)
                                .putString(ARG_ERROR, context.getString(R.string.unable_to_attach_file)).build());
            }

            if (TextUtils.isEmpty(uploadDetails.fileName)) {
                uploadDetails.fileName = context.getString(R.string.default_attachment_name);
            }

            if (TextUtils.isEmpty(uploadDetails.mimeType)) {
                uploadDetails.mimeType = args.getString(ARG_MIME_TYPE);
            }

            uploadDetails.valueRef = null;
            uploadDetails.previewRef = null;
            uploadDetails.previewSize = 0;

            if (operation == UploadType.IMAGE) {
                final boolean isGif = "image/gif".equalsIgnoreCase(uploadDetails.mimeType)
                        || args.getBoolean(ARG_IS_GIF, false);

                if (isGif) {
                    // GIF: ne konvertáljunk, a nyers stream kell.
                    int[] wh = new int[2];
                    byte[] prev = extractFirstFramePng(context, uri, wh);
                    uploadDetails.previewBits = prev;
                    uploadDetails.previewMime = (prev != null) ? "image/png" : null;
                    uploadDetails.previewSize = (prev != null) ? prev.length : 0;
                    uploadDetails.width = wh[0];
                    uploadDetails.height = wh[1];

                    is = resolver.openInputStream(uri);
                    if (is == null) throw new IOException("Failed to open GIF at " + uri);

                } else {
                    // Nem GIF: a meglévő képkezelés (rotate/scale)
                    bmp = prepareImage(resolver, uri, uploadDetails);
                    is = UiUtils.bitmapToStream(bmp, uploadDetails.mimeType);
                    uploadDetails.fileSize = is.available();

                    if (bmp.getWidth() > Const.IMAGE_PREVIEW_DIM || bmp.getHeight() > Const.IMAGE_PREVIEW_DIM) {
                        uploadDetails.previewBits = UiUtils.bitmapToBytes(
                                UiUtils.scaleBitmap(bmp, Const.IMAGE_PREVIEW_DIM, Const.IMAGE_PREVIEW_DIM, false),
                                "image/jpeg");
                        uploadDetails.previewMime = "image/jpeg";
                        uploadDetails.previewSize = uploadDetails.previewBits.length;
                    }
                }
            } else {
                // Audio/Video
                uploadDetails.duration = args.getInt(ARG_DURATION, 0);
                uploadDetails.previewBits = args.getByteArray(ARG_PREVIEW);
                if (uploadDetails.previewBits == null) {
                    String preUriStr = args.getString(ARG_PRE_URI);
                    if (preUriStr != null) {
                        InputStream posterIs = resolver.openInputStream(Uri.parse(preUriStr));
                        if (posterIs != null) {
                            uploadDetails.previewBits = readAll(posterIs);
                            posterIs.close();
                        }
                    }
                }
                if (operation == UploadType.VIDEO) {
                    uploadDetails.width = args.getInt(ARG_IMAGE_WIDTH, 0);
                    uploadDetails.height = args.getInt(ARG_IMAGE_HEIGHT, 0);
                    uploadDetails.previewMime = args.getString(ARG_PRE_MIME_TYPE);
                    uploadDetails.previewSize = uploadDetails.previewBits != null ? uploadDetails.previewBits.length : 0;
                    if (uploadDetails.previewSize > uploadDetails.fileSize) {
                        Log.w(TAG, "Video poster size " + uploadDetails.previewSize +
                                " is greater than video " + uploadDetails.fileSize);
                        return ListenableWorker.Result.failure(
                                result.putBoolean(ARG_FATAL, true)
                                        .putString(ARG_ERROR, context.getString(R.string.unable_to_attach_file)).build());
                    }
                }
            }

            if (uploadDetails.fileSize > maxFileUploadSize) {
                if (is != null) is.close();
                Log.w(TAG, "Unable to process attachment: too big, size=" + uploadDetails.fileSize);
                return ListenableWorker.Result.failure(
                        result.putString(ARG_ERROR,
                                        context.getString(
                                                R.string.attachment_too_large,
                                                UtilsString.bytesToHumanSize(uploadDetails.fileSize),
                                                UtilsString.bytesToHumanSize(maxFileUploadSize)))
                                .putBoolean(ARG_FATAL, true)
                                .build());
            } else {
                if (is == null) {
                    is = resolver.openInputStream(uri);
                }
                if (is == null) throw new IOException("Failed to open file at " + uri);

                // In-band vs out-of-band
                if (uploadDetails.fileSize + uploadDetails.previewSize > maxInbandAttachmentSize) {
                    // out-of-band: feltöltés a LargeFileHelper-rel
                    uploadDetails.valueRef = "mid:uploading-" + msgId;
                    if (uploadDetails.previewSize > maxInbandAttachmentSize / 4) {
                        uploadDetails.previewRef = "mid:uploading-" + msgId + "/1";
                    }
                } else {
                    // in-band: a teljes tartalom mehet bits-ként
                    uploadDetails.valueBits = readAll(is);
                }

                // Helyi draft frissítése (előnézet + placeholder ref)
                Drafty msgDraft = prepareDraft(operation, uploadDetails, args.getString(ARG_IMAGE_CAPTION));
                if (msgDraft != null) {
                    store.msgDraftUpdate(topic, msgId, msgDraft);
                } else {
                    store.msgDiscard(topic, msgId);
                    throw new IllegalArgumentException("Unknown operation " + operation);
                }

                if (uploadDetails.valueRef != null) {
                    setProgressAsync(new Data.Builder()
                            .putAll(result.build())
                            .putLong(ARG_PROGRESS, 0)
                            .putLong(ARG_FILE_SIZE, uploadDetails.fileSize).build());

                    // 0: fő média; 1: opcionális poster
                    @SuppressWarnings("unchecked")
                    PromisedReply<ServerMessage>[] uploadPromises =
                            (PromisedReply<ServerMessage>[]) new PromisedReply[2];

                    mUploader = Cache.getTinode().getLargeFileHelper();
                    uploadPromises[0] = mUploader.uploadAsync(is, uploadDetails.fileName,
                            uploadDetails.mimeType, uploadDetails.fileSize,
                            topicName, (progress, size) -> setProgressAsync(new Data.Builder()
                                    .putAll(result.build())
                                    .putLong(ARG_PROGRESS, progress)
                                    .putLong(ARG_FILE_SIZE, size)
                                    .build()));

                    if (uploadDetails.previewRef != null) {
                        uploadPromises[1] = mUploader.uploadAsync(new ByteArrayInputStream(uploadDetails.previewBits),
                                "poster", uploadDetails.previewMime, uploadDetails.previewSize,
                                topicName, null);
                    } else {
                        uploadPromises[1] = null;
                    }

                    ServerMessage[] msgs = new ServerMessage[2];
                    try {
                        Object[] objs = PromisedReply.allOf(uploadPromises).getResult();
                        msgs[0] = (ServerMessage) objs[0];
                        msgs[1] = (ServerMessage) objs[1];
                    } catch (Exception ex) {
                        store.msgFailed(topic, msgId);
                        throw ex;
                    }

                    mUploader = null;

                    success = msgs[0] != null && msgs[0].ctrl != null && msgs[0].ctrl.code == 200;

                    if (success) {
                        String url = msgs[0].ctrl.getStringParam("url", null);
                        result.putString(ARG_REMOTE_URI, url);

                        switch (operation) {
                            case AUDIO:
                                content = draftyAudio(uploadDetails.mimeType, uploadDetails.previewBits,
                                        null, url, uploadDetails.duration, uploadDetails.fileName,
                                        uploadDetails.fileSize);
                                break;

                            case FILE:
                                content = draftyAttachment(uploadDetails.mimeType, uploadDetails.fileName,
                                        url, uploadDetails.fileSize);
                                break;

                            case IMAGE:
                                if ("image/gif".equalsIgnoreCase(uploadDetails.mimeType)) {
                                    // GIF: out-of-band -> NINCS bits, CSAK url (ref)
                                    content = draftyImage(args.getString(ARG_IMAGE_CAPTION), uploadDetails.mimeType,
                                            null /* no bits for out-of-band GIF */,
                                            url,
                                            uploadDetails.width, uploadDetails.height,
                                            uploadDetails.fileName, uploadDetails.fileSize);
                                } else {
                                    // Normál kép: a jelenlegi működés marad
                                    content = draftyImage(args.getString(ARG_IMAGE_CAPTION), uploadDetails.mimeType,
                                            uploadDetails.previewBits, url, uploadDetails.width, uploadDetails.height,
                                            uploadDetails.fileName, uploadDetails.fileSize);
                                }
                                break;


                            case VIDEO:
                                String posterUrl = null;
                                if (msgs[1] != null && msgs[1].ctrl != null && msgs[1].ctrl.code == 200) {
                                    posterUrl = msgs[1].ctrl.getStringParam("url", null);
                                }
                                content = draftyVideo(args.getString(ARG_IMAGE_CAPTION), uploadDetails.mimeType,
                                        null, url, uploadDetails.width, uploadDetails.height,
                                        uploadDetails.duration, uploadDetails.previewBits,
                                        posterUrl, uploadDetails.previewMime,
                                        uploadDetails.fileName, uploadDetails.fileSize);
                                break;
                        }
                    } else {
                        result.putBoolean(ARG_FATAL, true)
                                .putString(ARG_ERROR, "Server returned error");
                    }
                } else {
                    // In-band siker: bits már az üzenetben lesz
                    success = true;
                    setProgressAsync(new Data.Builder()
                            .putAll(result.build())
                            .putLong(ARG_PROGRESS, 0)
                            .putLong(ARG_FILE_SIZE, uploadDetails.fileSize)
                            .build());
                }
            }
        } catch (CancellationException ignored) {
            result.putString(ARG_ERROR, context.getString(R.string.canceled));
            Log.d(TAG, "Upload cancelled");
        } catch (Exception ex) {
            result.putString(ARG_ERROR, ex.getMessage());
            Log.w(TAG, "Failed to upload file", ex);
        } finally {
            if (bmp != null) bmp.recycle();
            if (operation == UploadType.AUDIO && filePath != null) {
                new File(filePath).delete();
            }
            if (is != null) {
                try { is.close(); } catch (IOException ignored) {}
            }
        }

        if (success) {
            store.msgReady(topic, msgId, content);
            return ListenableWorker.Result.success(result.build());
        } else {
            return ListenableWorker.Result.failure(result.build());
        }
    }

    // Képeket normalizálunk (NEM GIF!)
    private static Bitmap prepareImage(ContentResolver r, Uri src, UploadDetails uploadDetails) throws IOException {
        InputStream is = r.openInputStream(src);
        if (is == null) throw new IOException("Decoding bitmap: source not available");
        Bitmap bmp = BitmapFactory.decodeStream(is, null, null);
        is.close();

        if (bmp == null) throw new IOException("Failed to decode bitmap");

        if (bmp.getWidth() > Const.MAX_BITMAP_SIZE || bmp.getHeight() > Const.MAX_BITMAP_SIZE) {
            bmp = UiUtils.scaleBitmap(bmp, Const.MAX_BITMAP_SIZE, Const.MAX_BITMAP_SIZE, false);
            byte[] bits = UiUtils.bitmapToBytes(bmp, uploadDetails.mimeType);
            uploadDetails.fileSize = bits.length;
        }

        int orientation = ExifInterface.ORIENTATION_UNDEFINED;
        try {
            if (uploadDetails.imageOrientation == -1) {
                is = r.openInputStream(src);
                if (is != null) {
                    ExifInterface exif = new ExifInterface(is);
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_UNDEFINED);
                    is.close();
                }
            } else {
                switch (uploadDetails.imageOrientation) {
                    case 0:   orientation = ExifInterface.ORIENTATION_NORMAL; break;
                    case 90:  orientation = ExifInterface.ORIENTATION_ROTATE_90; break;
                    case 180: orientation = ExifInterface.ORIENTATION_ROTATE_180; break;
                    case 270: orientation = ExifInterface.ORIENTATION_ROTATE_270; break;
                    default:
                }
            }

            switch (orientation) {
                case ExifInterface.ORIENTATION_NORMAL:
                    break;
                case ExifInterface.ORIENTATION_UNDEFINED:
                    Log.d(TAG, "Unable to obtain image orientation");
                default:
                    bmp = UiUtils.rotateBitmap(bmp, orientation);
                    break;
            }
        } catch (IOException ex) {
            Log.w(TAG, "Failed to obtain image orientation", ex);
        }

        uploadDetails.width = bmp.getWidth();
        uploadDetails.height = bmp.getHeight();

        return bmp;
    }

    private static BitmapFactory.Options boundsFromBitmapBits(byte[] bits) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        InputStream bais = new ByteArrayInputStream(bits);
        BitmapFactory.decodeStream(bais, null, options);
        try { bais.close(); } catch (IOException ignored) {}
        return options;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int len;
        while ((len = is.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
        return out.toByteArray();
    }

    static class UploadDetails {
        String mimeType;
        String previewMime;

        String filePath;
        String fileName;
        long fileSize;

        int imageOrientation;
        int width;
        int height;
        int duration;

        String valueRef;
        byte[] valueBits;

        // Poster/preview
        String previewFileName;
        int previewSize;
        String previewRef;
        byte[] previewBits;
    }

    /**
     * Avatar feltöltés – változatlan.
     */
    static PromisedReply<ServerMessage> uploadAvatar(@NonNull final VxCard pub, @Nullable Bitmap bmp,
                                                     @Nullable String topicName) {
        if (bmp == null) {
            return new PromisedReply<>((ServerMessage) null);
        }

        final String mimeType= "image/png";

        int width = bmp.getWidth();
        int height = bmp.getHeight();
        if (width < Const.MIN_AVATAR_SIZE || height < Const.MIN_AVATAR_SIZE) {
            return new PromisedReply<>(new Exception("Image is too small"));
        }

        if (width != height || width > Const.MAX_AVATAR_SIZE) {
            bmp = UiUtils.scaleSquareBitmap(bmp, Const.MAX_AVATAR_SIZE);
            width = bmp.getWidth();
            height = bmp.getHeight();
        }

        if (pub.photo == null) {
            pub.photo = new TheCard.Photo();
        }
        pub.photo.width = width;
        pub.photo.height = height;

        PromisedReply<ServerMessage> result;
        try (InputStream is = UiUtils.bitmapToStream(bmp, mimeType)) {
            long fileSize = is.available();
            if (fileSize > Const.MAX_INBAND_AVATAR_SIZE) {
                pub.photo.data = UiUtils.bitmapToBytes(UiUtils.scaleSquareBitmap(bmp, Const.AVATAR_THUMBNAIL_DIM), mimeType);
                LargeFileHelper uploader = Cache.getTinode().getLargeFileHelper();
                result = uploader.uploadAsync(is, System.currentTimeMillis() + ".png", mimeType, fileSize,
                        topicName, null).thenApply(new PromisedReply.SuccessListener<>() {
                    @Override
                    public PromisedReply<ServerMessage> onSuccess(ServerMessage msg) {
                        if (msg != null && msg.ctrl != null && msg.ctrl.code == 200) {
                            pub.photo.ref = msg.ctrl.getStringParam("url", null);
                        }
                        return null;
                    }
                });
            } else {
                pub.photo.data = UiUtils.bitmapToBytes(UiUtils.scaleSquareBitmap(bmp, Const.AVATAR_THUMBNAIL_DIM), mimeType);
                result = new PromisedReply<>((ServerMessage) null);
            }
        } catch (IOException | IllegalArgumentException ex) {
            Log.w(TAG, "Failed to upload avatar", ex);
            result = new PromisedReply<>(ex);
        }

        return result;
    }
}
