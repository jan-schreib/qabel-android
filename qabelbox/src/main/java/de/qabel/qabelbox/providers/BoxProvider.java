package de.qabel.qabelbox.providers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.provider.MediaStore.Video.Media;
import android.support.annotation.NonNull;
import android.util.Log;

import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import de.qabel.core.config.Identities;
import de.qabel.core.config.Identity;
import de.qabel.desktop.repository.IdentityRepository;
import de.qabel.desktop.repository.exception.PersistenceException;
import de.qabel.qabelbox.BuildConfig;
import de.qabel.qabelbox.QblBroadcastConstants;
import de.qabel.qabelbox.R;
import de.qabel.qabelbox.config.AppPreference;
import de.qabel.qabelbox.dagger.components.BoxComponent;
import de.qabel.qabelbox.dagger.components.DaggerBoxComponent;
import de.qabel.qabelbox.dagger.modules.ContextModule;
import de.qabel.qabelbox.exceptions.QblStorageException;
import de.qabel.qabelbox.exceptions.QblStorageNotFound;
import de.qabel.qabelbox.storage.BoxManager;
import de.qabel.qabelbox.storage.BoxVolume;
import de.qabel.qabelbox.storage.model.BoxFile;
import de.qabel.qabelbox.storage.model.BoxFolder;
import de.qabel.qabelbox.storage.model.BoxObject;
import de.qabel.qabelbox.storage.navigation.BoxNavigation;
import de.qabel.qabelbox.storage.notifications.StorageNotificationManager;

public class BoxProvider extends DocumentsProvider {

    private static final String TAG = "BoxProvider";

    public static final String[] DEFAULT_ROOT_PROJECTION = new
            String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID,};

    public static final String[] DEFAULT_DOCUMENT_PROJECTION = new
            String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Media.DATA};

    public static final String AUTHORITY = ".providers.documents";
    public static final String PATH_SEP = "/";
    public static final String DOCID_SEPARATOR = "::::";

    private static final int KEEP_ALIVE_TIME = 1;
    private static final TimeUnit KEEP_ALIVE_TIME_UNIT = TimeUnit.SECONDS;

    @Inject
    StorageNotificationManager storageNotificationManager;
    @Inject
    DocumentIdParser mDocumentIdParser;

    @Inject
    IdentityRepository identityRepository;

    @Inject
    AppPreference appPrefereces;

    @Inject
    BoxManager boxManager;

    private ThreadPoolExecutor mThreadPoolExecutor;

    private Map<String, BoxCursor> folderContentCache;
    private String currentFolder;

    BoxComponent boxComponent;

    private BroadcastReceiver volumesChangedBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            notifyRootsUpdated();
        }
    };

    @Override
    public boolean onCreate() {

        final Context context = getContext();

        mThreadPoolExecutor = new ThreadPoolExecutor(
                2,
                2,
                KEEP_ALIVE_TIME,
                KEEP_ALIVE_TIME_UNIT,
                new LinkedBlockingDeque<>());

        folderContentCache = new HashMap<>();

        boxComponent = DaggerBoxComponent.builder().contextModule(new ContextModule(context)).build();
        boxComponent.inject(this);

        context.registerReceiver(volumesChangedBroadcastReceiver,
                new IntentFilter(QblBroadcastConstants.Storage.BOX_VOLUMES_CHANGES));

        return true;
    }

    /**
     * Notify the system that the roots have changed
     * This happens if identities or prefixes changed.
     */
    public void notifyRootsUpdated() {
        getContext().getContentResolver()
                .notifyChange(DocumentsContract.buildRootsUri(
                        BuildConfig.APPLICATION_ID + AUTHORITY), null);
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {

        String[] netProjection = reduceProjection(projection, DEFAULT_ROOT_PROJECTION);

        MatrixCursor result = new MatrixCursor(netProjection);
        try {
            Identities identities = identityRepository.findAll();
            for (Identity identity : identities.getIdentities()) {
                final MatrixCursor.RowBuilder row = result.newRow();
                String pub_key = identity.getEcPublicKey().getReadableKeyIdentifier();
                String prefix;
                try {
                    prefix = identity.getPrefixes().get(0);
                } catch (IndexOutOfBoundsException e) {
                    Log.e(TAG, "Could not find a prefix in identity " + pub_key);
                    continue;
                }
                row.add(Root.COLUMN_ROOT_ID,
                        mDocumentIdParser.buildId(pub_key, prefix, null));
                row.add(Root.COLUMN_DOCUMENT_ID,
                        mDocumentIdParser.buildId(pub_key, prefix, "/"));
                row.add(Root.COLUMN_ICON, R.drawable.qabel_logo);
                row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE);
                row.add(Root.COLUMN_TITLE, "Qabel Box");
                row.add(Root.COLUMN_SUMMARY, identity.getAlias());
            }
        } catch (PersistenceException e) {
            throw new FileNotFoundException("Error loading identities");
        }

        return result;
    }

    private String[] reduceProjection(String[] projection, String[] supportedProjection) {

        if (projection == null) {
            return supportedProjection;
        }
        HashSet<String> supported = new HashSet<>(Arrays.asList(supportedProjection));
        ArrayList<String> result = new ArrayList<>();
        for (String column : projection) {
            if (supported.contains(column)) {
                result.add(column);
            } else {
                Log.w(TAG, "Requested cursor field don't supported '" + column + "'");
            }
        }
        if (result.size() == 0) {
            Log.e(TAG, "Cursors contain no fields after reduceProjection. Add fallback field");
            //add fallback if no field supported. this avoid crashes on different third party apps
            result.add(Document.COLUMN_DOCUMENT_ID);
        }

        return result.toArray(projection);
    }

    public BoxVolume getVolumeForRoot(String identity, String prefix) throws FileNotFoundException {

        try {
            return boxManager.createBoxVolume(identity, prefix);
        } catch (QblStorageException e) {
            e.printStackTrace();
            throw new FileNotFoundException("Cannot create BoxVolume");
        }
    }

    @Override
    public Cursor queryDocument(String documentIdString, String[] projection)
            throws FileNotFoundException {

        MatrixCursor cursor = createCursor(projection, false);
        try {
            DocumentId documentId = mDocumentIdParser.parse(documentIdString);
            String logInfos = shrinkDocumentId(documentIdString);
            if (projection != null) {
                logInfos += " projSize=" + projection.length;
            } else {
                logInfos += " projection=null. All fields used";
            }
            Log.v(TAG, "QueryDocument " + logInfos);
            String filePath = documentId.getFilePath();

            BoxVolume volume = getVolumeForRoot(documentId.getIdentityKey(),
                    documentId.getPrefix());

            if (filePath.equals(PATH_SEP)) {
                // root id
                insertRootDoc(cursor, documentIdString);
                return cursor;
            }
            BoxNavigation navigation = volume.navigate();
            navigation.navigate(documentId.getPathString());
            if (navigation.getFile(documentId.getFileName(), false) == null) {
                return null;
            }
            Log.d(TAG, "Inserting basename " + documentId.getFileName());
            insertFileByName(cursor, navigation, documentIdString, documentId.getFileName());
        } catch (QblStorageException e) {
            Log.i(TAG, "Could not find document " + documentIdString, e);
            throw new FileNotFoundException("Failed navigating the volume");
        }

        Log.v(TAG, "query roots result, cursorCount=" + cursor.getCount() + " cursorColumn=" + cursor.getColumnCount());
        return cursor;
    }

    private String shrinkDocumentId(String documentId) {

        if (documentId == null) {
            return null;
        }
        String[] elements = documentId.split("/");
        return elements[elements.length - 1];
    }

    private BoxVolume getVolumeForId(String documentId) throws FileNotFoundException {

        return getVolumeForRoot(
                mDocumentIdParser.getIdentity(documentId),
                mDocumentIdParser.getPrefix(documentId));
    }

    void insertFileByName(MatrixCursor cursor, BoxNavigation navigation,
                          String documentId, String basename) throws QblStorageException {

        for (BoxFolder folder : navigation.listFolders()) {
            Log.d(TAG, "Checking folder:" + folder.name);
            if (basename.equals(folder.name)) {
                insertFolder(cursor, documentId, folder);
                return;
            }
        }
        for (BoxFile file : navigation.listFiles()) {
            Log.d(TAG, "Checking file:" + file.name);
            if (basename.equals(file.name)) {
                insertFile(cursor, documentId, file);
                return;
            }
        }
        BoxObject external = navigation.getExternal(basename);
        if (external != null) {
            insertFile(cursor, documentId, external);
            return;
        }
        throw new QblStorageNotFound("File not found");
    }

    void insertRootDoc(MatrixCursor cursor, String documentId) {

        final MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, documentId);
        row.add(Document.COLUMN_DISPLAY_NAME, "Root");
        row.add(Document.COLUMN_SUMMARY, null);
        row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE);
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {

        Log.d(TAG, "Query Child Documents: " + parentDocumentId);
        BoxCursor cursor = folderContentCache.get(parentDocumentId);
        boolean cacheHit = (cursor != null);
        if (parentDocumentId.equals(currentFolder) && cacheHit) {
            // best case: we are still in the same folder and we got a cache hit
            Log.d(TAG, "Up to date cached data found");
            cursor.setExtraLoading(false);
            return cursor;
        }
        if (cacheHit) {
            // we found it in the cache, but since we changed the folder, we refresh anyway
            cursor.setExtraLoading(true);
        } else {
            Log.d(TAG, "Serving empty listing and refreshing");
            cursor = createCursor(projection, true);
        }
        currentFolder = parentDocumentId;
        asyncChildDocuments(parentDocumentId, projection, cursor);
        return cursor;
    }

    /**
     * Create and fill a new MatrixCursor
     * <p>
     * The cursor can be modified to show a loading and/or an error message.
     *
     * @param parentDocumentId
     * @param projection
     * @return Fully initialized cursor with the directory listing as rows
     * @throws FileNotFoundException
     */
    private BoxCursor createBoxCursor(String parentDocumentId, String[] projection) throws FileNotFoundException {

        Log.v(TAG, "createBoxCursor");
        BoxCursor cursor = createCursor(projection, false);
        try {
            DocumentId parentId = mDocumentIdParser.parse(parentDocumentId);
            BoxVolume volume = getVolumeForRoot(parentId.getIdentityKey(), parentId.getPrefix());

            BoxNavigation navigation = volume.navigate();
            navigation.navigate(parentId.getPathString());
            insertFolderListing(cursor, navigation, parentDocumentId);
        } catch (QblStorageException e) {
            Log.e(TAG, "Could not navigate", e);
            throw new FileNotFoundException("Failed navigating the volume");
        }
        folderContentCache.put(parentDocumentId, cursor);
        return cursor;
    }

    /**
     * Query the directory listing, store the cursor in the folderContentCache and
     * notify the original cursor of the update.
     *
     * @param parentDocumentId
     * @param projection
     * @param result           Original cursor
     */
    private void asyncChildDocuments(final String parentDocumentId, final String[] projection,
                                     BoxCursor result) {

        Log.v(TAG, "asyncChildDocuments");
        final Uri uri = DocumentsContract.buildChildDocumentsUri(
                BuildConfig.APPLICATION_ID + AUTHORITY, parentDocumentId);
        // tell the original cursor how he gets notified
        result.setNotificationUri(getContext().getContentResolver(), uri);

        // create a new cursor and store it
        mThreadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {

                try {
                    createBoxCursor(parentDocumentId, projection);
                } catch (FileNotFoundException e) {
                    BoxCursor cursor = createCursor(projection, false);
                    cursor.setError(getContext().getString(R.string.folderListingUpdateError));
                    folderContentCache.put(parentDocumentId, cursor);
                }
                getContext().getContentResolver().notifyChange(uri, null);
            }
        });
    }

    @NonNull
    private BoxCursor createCursor(String[] projection, final boolean extraLoading) {

        String[] reduced = reduceProjection(projection, DEFAULT_DOCUMENT_PROJECTION);
        BoxCursor cursor = new BoxCursor(reduced);
        cursor.setExtraLoading(extraLoading);
        return cursor;
    }

    private void insertFolderListing(MatrixCursor cursor, BoxNavigation navigation, String parentDocumentId) throws QblStorageException {

        for (BoxFolder folder : navigation.listFolders()) {
            insertFolder(cursor, parentDocumentId + folder.name + PATH_SEP, folder);
        }
        for (BoxFile file : navigation.listFiles()) {
            insertFile(cursor, parentDocumentId + file.name, file);
        }
        for (BoxObject file : navigation.listExternalNames()) {
            insertFile(cursor, parentDocumentId + file.name, file);
        }
    }

    private void insertFile(MatrixCursor cursor, String documentId, BoxObject file) {

        final MatrixCursor.RowBuilder row = cursor.newRow();
        String mimeType = URLConnection.guessContentTypeFromName(file.name);
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }
        row.add(Document.COLUMN_DOCUMENT_ID, documentId);
        row.add(Document.COLUMN_DISPLAY_NAME, file.name);
        row.add(Document.COLUMN_SUMMARY, null);
        row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE);
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Media.DATA, documentId);
    }

    private void insertFolder(MatrixCursor cursor, String documentId, BoxFolder folder) {

        final MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, documentId);
        row.add(Document.COLUMN_DISPLAY_NAME, folder.name);
        row.add(Document.COLUMN_SUMMARY, null);
        row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE);
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId,
                                             final String mode, final CancellationSignal signal)
            throws FileNotFoundException {

        Log.d(TAG, "Open document: " + documentId);
        final boolean isWrite = (mode.indexOf('w') != -1);
        final boolean isRead = (mode.indexOf('r') != -1);

        if (isWrite) {
            // Attach a close listener if the document is opened in write mode.
            try {
                Handler handler = new Handler(getContext().getMainLooper());
                final File tmp;
                if (isRead) {
                    tmp = downloadFile(documentId, mode, signal);
                } else {
                    tmp = File.createTempFile("uploadAndDeleteLocalfile", "", getContext().getExternalCacheDir());
                }
                ParcelFileDescriptor.OnCloseListener onCloseListener = e -> {
                    // Update the file with the cloud server.  The client is done writing.
                    Log.i(TAG, "A file with id " + documentId + " has been closed!  Time to " +
                            "update the server.");
                    if (e != null) {
                        Log.e(TAG, "IOException in onClose", e);
                        return;
                    }
                    // in another thread!
                    new AsyncTask<Void, Void, String>() {
                        @Override
                        protected String doInBackground(Void... params) {
                            try {
                                DocumentId documentId1 = mDocumentIdParser.parse(documentId);
                                String path = documentId1.getPathString();
                                BoxVolume volume = getVolumeForRoot(documentId1.getIdentityKey(),
                                        documentId1.getPrefix());
                                BoxNavigation boxNavigation = volume.navigate();
                                boxNavigation.navigate(path);
                                boxNavigation.upload(documentId1.getFileName(),
                                        new FileInputStream(tmp));
                                boxNavigation.commit();
                            } catch (FileNotFoundException | QblStorageException e1) {
                                Log.e(TAG, "Cannot upload file!", e);
                            }
                            Log.d(TAG, "UPLOAD DONE");
                            return documentId;
                        }
                    }.execute();
                };
                return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.parseMode(mode), handler,
                        onCloseListener);
            } catch (IOException e) {
                throw new FileNotFoundException();
            }
        } else {
            File tmp = downloadFile(documentId, mode, signal);
            final int accessMode = ParcelFileDescriptor.parseMode(mode);
            return ParcelFileDescriptor.open(tmp, accessMode);
        }
    }

    private File downloadFile(final String documentId, final String mode, final CancellationSignal signal) throws FileNotFoundException {

        final Future<File> future = mThreadPoolExecutor.submit(
                () -> getFile(signal, documentId));
        if (signal != null) {
            signal.setOnCancelListener(() -> {
                Log.d(TAG, "openDocument cancelling");
                future.cancel(true);
            });
        }

        try {
            return future.get();
        } catch (InterruptedException e) {
            Log.d(TAG, "openDocument cancelled download");
            throw new FileNotFoundException();
        } catch (ExecutionException e) {
            Log.d(TAG, "Execution error", e);
            throw new FileNotFoundException();
        }
    }

    private File getFile(CancellationSignal signal, final String documentId)
            throws IOException, QblStorageException {
        return boxManager.downloadFileDecrypted(documentId);
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {

        Log.d(TAG, "createDocument: " + parentDocumentId + "; " + mimeType + "; " + displayName);

        try {

            DocumentId parentId = mDocumentIdParser.parse(parentDocumentId);
            String parentPath = parentId.getFilePath();
            BoxVolume volume = getVolumeForRoot(parentId.getIdentityKey(), parentId.getPrefix());


            BoxNavigation navigation = volume.navigate();
            navigation.navigate(parentPath);

            if (mimeType.equals(Document.MIME_TYPE_DIR)) {
                navigation.createFolder(displayName);
            } else {
                navigation.upload(displayName, new ByteArrayInputStream(new byte[0]));
            }
            navigation.commit();

            return parentDocumentId + displayName;
        } catch (QblStorageException e) {
            Log.e(TAG, "could not create file", e);
            throw new FileNotFoundException();
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {

        Log.d(TAG, "deleteDocument: " + documentId);

        try {

            DocumentId document = mDocumentIdParser.parse(documentId);
            BoxVolume volume = getVolumeForRoot(document.getIdentityKey(), document.getPrefix());
            BoxNavigation navigation = volume.navigate();
            navigation.navigate(document.getPathString());

            String basename = document.getFileName();
            for (BoxFile file : navigation.listFiles()) {
                if (file.name.equals(basename)) {
                    navigation.delete(file);
                    navigation.commit();
                    return;
                }
            }
            for (BoxFolder folder : navigation.listFolders()) {
                if (folder.name.equals(basename)) {
                    navigation.delete(folder);
                    navigation.commit();
                    return;
                }
            }
        } catch (QblStorageException e) {
            Log.e(TAG, "could not create file", e);
            throw new FileNotFoundException();
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {

        Log.d(TAG, "renameDocument: " + documentId + " to " + displayName);

        try {

            DocumentId document = mDocumentIdParser.parse(documentId);
            BoxVolume volume = getVolumeForId(documentId);

            String[] splitPath = document.getPath();
            String basename = document.getFileName();
            BoxNavigation navigation = volume.navigate();

            String[] newPath = Arrays.copyOf(splitPath, splitPath.length + 1);
            newPath[newPath.length - 1] = displayName;

            String renamedId = mDocumentIdParser.buildId(
                    mDocumentIdParser.getIdentity(documentId),
                    mDocumentIdParser.getPrefix(documentId),
                    StringUtils.join(newPath, PATH_SEP));

            for (BoxFile file : navigation.listFiles()) {
                if (file.name.equals(basename)) {
                    navigation.rename(file, displayName);
                    navigation.commit();
                    return renamedId;
                }
            }
            for (BoxFolder folder : navigation.listFolders()) {
                if (folder.name.equals(basename)) {
                    navigation.rename(folder, displayName);
                    navigation.commit();
                    return renamedId;
                }
            }
            throw new FileNotFoundException();
        } catch (QblStorageException e) {
            Log.e(TAG, "could not create file", e);
            throw new FileNotFoundException();
        }
    }

    class BoxCursor extends MatrixCursor {

        private boolean extraLoading;
        private String error;

        public BoxCursor(String[] columnNames) {

            super(columnNames);
        }

        public void setExtraLoading(boolean loading) {

            this.extraLoading = loading;
        }

        public Bundle getExtras() {

            Bundle bundle = new Bundle();
            bundle.putBoolean(DocumentsContract.EXTRA_LOADING, extraLoading);
            if (error != null) {
                bundle.putString(DocumentsContract.EXTRA_ERROR, error);
            }
            return bundle;
        }

        public void setError(String error) {

            this.error = error;
        }
    }
}
