package de.qabel.qabelbox.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.inject.Inject;

import de.qabel.qabelbox.QblBroadcastConstants;
import de.qabel.qabelbox.R;
import de.qabel.qabelbox.adapter.FilesAdapter;
import de.qabel.qabelbox.dagger.components.MainActivityComponent;
import de.qabel.qabelbox.exceptions.QblStorageException;
import de.qabel.qabelbox.exceptions.QblStorageNotFound;
import de.qabel.qabelbox.helper.UIHelper;
import de.qabel.qabelbox.services.StorageBroadcastConstants;
import de.qabel.qabelbox.storage.BoxManager;
import de.qabel.qabelbox.storage.model.BoxExternalFile;
import de.qabel.qabelbox.storage.model.BoxFile;
import de.qabel.qabelbox.storage.model.BoxFolder;
import de.qabel.qabelbox.storage.navigation.BoxNavigation;
import de.qabel.qabelbox.storage.model.BoxObject;
import de.qabel.qabelbox.storage.model.BoxUploadingFile;
import de.qabel.qabelbox.storage.BoxVolume;
import de.qabel.qabelbox.storage.StorageSearch;

public class FilesFragment extends FilesFragmentBase {

    private static final String TAG = "FilesFragment";

    protected BoxNavigation boxNavigation;

    private AsyncTask<Void, Void, Void> browseToTask;

    private MenuItem mSearchAction;
    private boolean isSearchOpened = false;
    private EditText searchText;
    private AsyncTask<String, Void, StorageSearch> searchTask;
    private StorageSearch mCachedStorageSearch;

    private String targetPath;

    @Inject
    BoxManager boxManager;

    @Inject
    BoxVolume mBoxVolume;

    public FilesFragment() {
        setAdapter(new FilesAdapter(new ArrayList<>(20)));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        activity.registerReceiver(mMessageReceiver,
                new IntentFilter(QblBroadcastConstants.Storage.BOX_UPLOAD_CHANGED));
    }

    @Override
    public void onDetach() {
        getActivity().unregisterReceiver(mMessageReceiver);
        super.onDetach();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        getComponent(MainActivityComponent.class).inject(this);
        initializeNavigation();
    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (filesAdapter == null) {
                return;
            }
            String documentId = intent.getStringExtra(StorageBroadcastConstants.EXTRA_UPLOAD_DOCUMENT_ID);
            int uploadStatus = intent.getIntExtra(StorageBroadcastConstants.EXTRA_UPLOAD_STATUS, -1);

            switch (uploadStatus) {
                case StorageBroadcastConstants.UPLOAD_STATUS_NEW:
                    Log.d(TAG, "Received new uploadAndDeleteLocalfile: " + documentId);
                    fillAdapter(filesAdapter);
                    notifyFilesAdapterChanged();
                    break;
                case StorageBroadcastConstants.UPLOAD_STATUS_FINISHED:
                    Log.d(TAG, "Received uploadEncrypted finished: " + documentId);
                    fillAdapter(filesAdapter);
                    notifyFilesAdapterChanged();
                    break;
                case StorageBroadcastConstants.UPLOAD_STATUS_FAILED:
                    Log.d(TAG, "Received uploadAndDeleteLocalfile failed: " + documentId);
                    refresh();
                    break;
            }
        }
    };

    private void initializeNavigation() {
        setAdapter(filesAdapter);
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                setIsLoading(true);
            }

            @Override
            protected Void doInBackground(Void... params) {
                try {
                    setBoxNavigation(mBoxVolume.navigate());
                } catch (QblStorageNotFound e) {
                    Log.w(TAG, "Cannot navigate to root. maybe first initialization", e);
                    try {
                        mBoxVolume.createIndex();
                        setBoxNavigation(mBoxVolume.navigate());
                    } catch (QblStorageException e1) {
                        Log.e(TAG, "Creating a volume failed", e1);
                        cancel(true);
                        return null;
                    }
                } catch (QblStorageException e) {
                    getActivity().runOnUiThread(() ->
                            Toast.makeText(getActivity(),
                                    R.string.error_reason_io, Toast.LENGTH_LONG)
                    );
                    cancel(true);
                    return null;
                }
                if (boxNavigation != null && targetPath != null) {
                    try {
                        boxNavigation.navigate(targetPath);
                    } catch (QblStorageException e) {
                        Log.e(TAG, "Cannot navigate to " + targetPath);
                    }
                    targetPath = null;
                }
                fillAdapter(filesAdapter);
                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                super.onPostExecute(aVoid);
                setIsLoading(false);
                notifyFilesAdapterChanged();
            }

            @Override
            protected void onCancelled() {
                setIsLoading(false);
                super.onCancelled();
            }
        }.executeOnExecutor(serialExecutor);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void navigateBackToRoot() {
        if (!areTasksPending()) {
            if (boxNavigation == null || !boxNavigation.hasParent()) {
                return;
            }

            browseToTask = new AsyncTask<Void, Void, Void>() {
                @Override
                protected void onPreExecute() {
                    super.onPreExecute();
                    preBrowseTo();
                }

                @Override
                protected Void doInBackground(Void... voids) {

                    waitForBoxNavigation();
                    try {
                        boxNavigation.navigateToRoot();
                        fillAdapter(filesAdapter);
                    } catch (QblStorageException e) {
                        Log.d(TAG, "browseTo failed", e);
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {
                    super.onPostExecute(aVoid);
                    setIsLoading(false);
                    updateSubtitle();
                    notifyFilesAdapterChanged();
                    browseToTask = null;
                }
            };
            browseToTask.executeOnExecutor(serialExecutor);
        } else {
            Log.w(TAG, "Other tasks are still pending. Will ignore this one");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.ab_files, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        mSearchAction = menu.findItem(R.id.action_search);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // handle item selection
        switch (item.getItemId()) {
            case R.id.action_search:
                if (!isSearchRunning()) {
                    handleMenuSearch();
                } else if (isSearchOpened) {
                    removeSearchInActionbar(actionBar);
                }
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private boolean isSearchRunning() {
        if (isSearchOpened) {
            return true;
        }
        return searchTask != null && ((!searchTask.isCancelled() && searchTask.getStatus() != AsyncTask.Status.FINISHED));
    }

    /**
     * handle click on search icon
     */
    private void handleMenuSearch() {
        if (isSearchOpened) {
            removeSearchInActionbar(actionBar);
        } else {
            openSearchInActionBar(actionBar);
        }
    }

    /**
     * setup the actionbar to show a input dialog for search keyword
     *
     * @param action
     */
    private void openSearchInActionBar(final ActionBar action) {

        action.setDisplayShowCustomEnabled(true);
        action.setCustomView(R.layout.ab_search_field);
        action.setDisplayShowTitleEnabled(false);

        searchText = (EditText) action.getCustomView().findViewById(R.id.edtSearch);

        //add editor action listener
        searchText.setOnEditorActionListener((v, actionId, event) -> {

            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String text = searchText.getText().toString();
                removeSearchInActionbar(action);
                startSearch(text);
                return true;
            }
            return false;
        });

        searchText.requestFocus();

        //open keyboard
        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(searchText, InputMethodManager.SHOW_IMPLICIT);
        mActivity.fab.hide();
        mSearchAction.setIcon(R.drawable.close_white);
        isSearchOpened = true;
    }

    /**
     * restore the actionbar
     *
     * @param action
     */
    private void removeSearchInActionbar(ActionBar action) {

        action.setDisplayShowCustomEnabled(false);
        action.setDisplayShowTitleEnabled(true);

        //hides the keyboard
        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchText.getWindowToken(), 0);

        imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, InputMethodManager.RESULT_HIDDEN);
        mSearchAction.setIcon(R.drawable.magnify_white);
        action.setTitle(getTitle());
        isSearchOpened = false;
        mActivity.fab.show();
    }

    @Override
    public void onPause() {
        if (isSearchOpened) {
            removeSearchInActionbar(actionBar);
        }
        super.onPause();
    }

    /**
     * start search
     *
     * @param searchText
     */
    private void startSearch(final String searchText) {
        if (browseToTask != null) {
            Log.d(TAG, "BrowseTask is running. Will cancel it and start searching instead");
            cancelBrowseToTask();
            browseToTask = null;
        }
        if (!areTasksPending()) {
            searchTask = new AsyncTask<String, Void, StorageSearch>() {

                @Override
                protected void onPreExecute() {
                    super.onPreExecute();
                    setIsLoading(true);
                }

                @Override
                protected void onCancelled(StorageSearch storageSearch) {
                    setIsLoading(false);
                    super.onCancelled(storageSearch);
                    searchTask = null;
                }

                @Override
                protected void onPostExecute(StorageSearch storageSearch) {

                    setIsLoading(false);

                    //check if files found
                    if (storageSearch == null || storageSearch.filterOnlyFiles().getResults().size() == 0) {
                        Toast.makeText(getActivity(), R.string.no_entrys_found, Toast.LENGTH_SHORT).show();
                        searchTask = null;
                        return;
                    }
                    if (!mActivity.isFinishing() && !searchTask.isCancelled()) {
                        try {
                            mCachedStorageSearch = storageSearch.clone();
                        } catch (CloneNotSupportedException e) {
                            e.printStackTrace();
                        }
                        FilesSearchResultFragment fragment = FilesSearchResultFragment.newInstance(mCachedStorageSearch, searchText);
                        mActivity.toggle.setDrawerIndicatorEnabled(false);
                        getFragmentManager().beginTransaction().replace(R.id.fragment_container, fragment, FilesSearchResultFragment.TAG).addToBackStack(null).commit();
                    }
                    searchTask = null;
                }

                @Override
                protected StorageSearch doInBackground(String... params) {
                    try {
                        if (mCachedStorageSearch != null) {
                            mCachedStorageSearch.refreshRange(boxNavigation, true);
                            return mCachedStorageSearch;
                        }

                        return new StorageSearch(boxNavigation);
                    } catch (QblStorageException e) {
                        e.printStackTrace();
                    }
                    return null;
                }
            };
            searchTask.executeOnExecutor(serialExecutor);
        }
    }

    private void cancelSearchTask() {
        if (searchTask != null) {
            searchTask.cancel(true);
            searchTask = null;
        }
    }

    public void setAdapter(FilesAdapter adapter) {
        filesAdapter = adapter;
        filesAdapter.setEmptyView(mEmptyView, mLoadingView);
    }

    @Override
    public boolean isFabNeeded() {
        return true;
    }

    protected void setBoxNavigation(BoxNavigation boxNavigation) {
        this.boxNavigation = boxNavigation;
    }

    public BoxNavigation getBoxNavigation() {
        return boxNavigation;
    }

    @Override
    public String getTitle() {
        return getString(R.string.headline_files);
    }

    /**
     * handle back pressed
     *
     * @return true if back handled
     */
    public boolean handleBackPressed() {
        if (isSearchOpened) {
            removeSearchInActionbar(actionBar);
            return true;
        }
        if (searchTask != null) {
            cancelSearchTask();
            return true;
        }

        return false;
    }

    public BoxVolume getBoxVolume() {
        return mBoxVolume;
    }

    public void setCachedSearchResult(StorageSearch searchResult) {
        mCachedStorageSearch = searchResult;
    }

    public void unshare(final BoxFile boxObject) {
        new AsyncTask<Void, Void, Boolean>() {

            public AlertDialog wait;

            @Override
            protected void onPreExecute() {
                wait = UIHelper.showWaitMessage(mActivity, R.string.dialog_headline_info, R.string.message_revoke_share, false);
            }

            @Override
            protected Boolean doInBackground(Void... params) {
                boolean ret = getBoxNavigation().removeFileMetadata(boxObject);
                try {
                    getBoxNavigation().commit();
                } catch (QblStorageException e) {
                    e.printStackTrace();
                    return false;
                }
                return ret;
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (success) {
                    refresh();
                    Toast.makeText(mActivity, R.string.message_unshare_successfull, Toast.LENGTH_SHORT).show();
                } else {
                    UIHelper.showDialogMessage(mActivity, R.string.dialog_headline_warning, R.string.message_unshare_not_successfull, Toast.LENGTH_SHORT);
                }
                wait.dismiss();

            }
        }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);

    }

    public void delete(final BoxObject boxObject) {
        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(String.format(
                        getResources().getString(R.string.confirm_delete_message), boxObject.name))
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                        new AsyncTask<Void, Void, Void>() {
                            @Override
                            protected void onCancelled() {
                                setIsLoading(false);
                            }

                            @Override
                            protected void onPreExecute() {
                                setIsLoading(true);
                            }

                            @Override
                            protected Void doInBackground(Void... params) {
                                try {
                                    if (boxObject instanceof BoxExternalFile) {
                                        getBoxNavigation().detachExternal(boxObject.name);
                                    } else {
                                        getBoxNavigation().delete(boxObject);
                                    }
                                    getBoxNavigation().commit();
                                } catch (QblStorageException e) {
                                    Log.e(TAG, "Cannot delete " + boxObject.name);
                                }
                                return null;
                            }

                            @Override
                            protected void onPostExecute(Void aVoid) {
                                refresh();
                            }
                        }.execute();
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    showAbortMessage();
                }).create().show();
    }

    @Override
    public void refresh() {
        if (boxNavigation == null) {
            return;
        }
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    boxNavigation.reload();
                    boxManager.clearCachedUploads(boxNavigation.getPath());
                    loadBoxObjectsToAdapter(boxNavigation, filesAdapter);
                } catch (QblStorageException e) {
                    Log.e(TAG, "refresh failed", e);
                }
                return null;
            }

            @Override
            protected void onCancelled() {
                setIsLoading(true);
                showAbortMessage();
            }

            @Override
            protected void onPreExecute() {
                setIsLoading(true);
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                super.onPostExecute(aVoid);

                filesAdapter.sort();
                notifyFilesAdapterChanged();
                setIsLoading(false);
            }
        };
        asyncTask.execute();
    }

    private void showAbortMessage() {
        Toast.makeText(mActivity, R.string.aborted, Toast.LENGTH_SHORT).show();
    }

    public boolean browseToParent() {

        if (!areTasksPending()) {

            if (boxNavigation == null || !boxNavigation.hasParent()) {
                return false;
            }

            browseToTask = new AsyncTask<Void, Void, Void>() {
                @Override
                protected void onPreExecute() {

                    super.onPreExecute();
                    preBrowseTo();
                }

                @Override
                protected Void doInBackground(Void... voids) {

                    waitForBoxNavigation();
                    try {
                        boxNavigation.navigateToParent();
                        fillAdapter(filesAdapter);
                    } catch (QblStorageException e) {
                        Log.d(TAG, "browseTo failed", e);
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {

                    super.onPostExecute(aVoid);
                    setIsLoading(false);
                    updateSubtitle();
                    notifyFilesAdapterChanged();
                    browseToTask = null;
                }
            };
            browseToTask.executeOnExecutor(serialExecutor);
            return true;
        } else {
            Log.w(TAG, "Other tasks are still pending. Will ignore this one");
            return false;
        }
    }

    @Override
    public void updateSubtitle() {

        String path = boxNavigation != null ? boxNavigation.getPath() : "";
        if (path.equals("/")) {
            path = null;
        }
        if (path != null && path.contains(BoxFolder.RECEIVED_SHARE_NAME)) {
            path = path.replace(BoxFolder.RECEIVED_SHARE_NAME, getString(R.string.shared_with_you));
        }
        if (actionBar != null) {
            actionBar.setSubtitle(path);
            if (isSearchOpened) {
                removeSearchInActionbar(actionBar);
            }
        }
    }

    private void preBrowseTo() {
        setIsLoading(true);
    }

    protected void fillAdapter(FilesAdapter filesAdapter) {

        if (filesAdapter == null || boxNavigation == null) {
            return;
        }
        try {
            loadBoxObjectsToAdapter(boxNavigation, filesAdapter);
            insertCachedFinishedUploads(filesAdapter);
            insertPendingUploads(filesAdapter);
            filesAdapter.sort();
        } catch (QblStorageException e) {
            Log.e(TAG, "fillAdapter failed", e);
        } catch (Exception e) {
            //catch all other, if something going wrong that app would crashed on started and user have no way to change this
            Log.e(TAG, "fillAdapter failed", e);
        }
    }

    private void insertPendingUploads(FilesAdapter filesAdapter) {
        List<BoxUploadingFile> uploadsInPath = boxManager.getPendingUploads(boxNavigation.getPath());
        for (BoxUploadingFile boxUploadingFile : uploadsInPath) {
            filesAdapter.remove(boxUploadingFile.name);
            filesAdapter.add(boxUploadingFile);
        }
    }

    private void insertCachedFinishedUploads(FilesAdapter filesAdapter) {
        Collection<BoxFile> cachedFiles = boxManager.
                getCachedFinishedUploads(boxNavigation.getPath());

        if (cachedFiles != null) {
            for (BoxFile boxFile : cachedFiles) {
                filesAdapter.remove(boxFile.name);
                filesAdapter.add(boxFile);
            }
        }
    }

    private void loadBoxObjectsToAdapter(BoxNavigation boxNavigation, FilesAdapter filesAdapter) throws QblStorageException, ArrayIndexOutOfBoundsException {

        filesAdapter.clear();
        for (BoxFolder boxFolder : boxNavigation.listFolders()) {
            Log.d(TAG, "Adding folder: " + boxFolder.name);
            filesAdapter.add(boxFolder);
        }
        for (BoxObject boxExternal : boxNavigation.listExternals()) {
            Log.d(TAG, "Adding external: " + boxExternal.name);
            filesAdapter.add(boxExternal);
        }
        for (BoxFile boxFile : boxNavigation.listFiles()) {
            Log.d(TAG, "Adding file: " + boxFile.name);
            filesAdapter.add(boxFile);
        }
    }

    private void waitForBoxNavigation() {

        while (boxNavigation == null) {
            Log.d(TAG, "waiting for BoxNavigation");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public boolean areTasksPending() {
        return browseToTask != null || searchTask != null;
    }

    public void browseTo(final BoxFolder navigateTo) {

        Log.d(TAG, "Browsing to " + navigateTo.name);
        if (!areTasksPending()) {
            browseToTask = new AsyncTask<Void, Void, Void>() {
                @Override
                protected void onPreExecute() {

                    super.onPreExecute();
                    preBrowseTo();
                }

                @Override
                protected Void doInBackground(Void... voids) {

                    waitForBoxNavigation();
                    try {
                        boxNavigation.navigate(navigateTo);
                        fillAdapter(filesAdapter);
                    } catch (QblStorageException e) {
                        Log.e(TAG, "browseTo failed", e);
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {

                    super.onPostExecute(aVoid);
                    setIsLoading(false);
                    updateSubtitle();
                    notifyFilesAdapterChanged();
                    browseToTask = null;
                }

                @Override
                protected void onCancelled() {

                    super.onCancelled();
                    setIsLoading(false);
                    browseToTask = null;
                    Toast.makeText(getActivity(), R.string.aborted,
                            Toast.LENGTH_SHORT).show();
                }
            };
            browseToTask.executeOnExecutor(serialExecutor);
        } else {
            Log.w(TAG, "Other Task is still in progress. Will ignore this");
        }
    }

    private void cancelBrowseToTask() {

        if (browseToTask != null) {
            Log.d(TAG, "Found a running browseToTask");
            browseToTask.cancel(true);
            browseToTask = null;
            Log.d(TAG, "Canceled browserToTask");
        }
    }

    public void navigateTo(String path) {
        Log.d(TAG, "Browsing to " + path);
        if (!areTasksPending() && boxNavigation != null) {
            browseToTask = new AsyncTask<Void, Void, Void>() {
                @Override
                protected void onPreExecute() {
                    super.onPreExecute();
                    preBrowseTo();
                }

                @Override
                protected Void doInBackground(Void... voids) {
                    waitForBoxNavigation();
                    try {
                        boxNavigation.navigate(path);
                        fillAdapter(filesAdapter);
                    } catch (QblStorageException e) {
                        Log.e(TAG, "browseTo failed", e);
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {

                    super.onPostExecute(aVoid);
                    setIsLoading(false);
                    updateSubtitle();
                    notifyFilesAdapterChanged();
                    browseToTask = null;
                }

                @Override
                protected void onCancelled() {

                    super.onCancelled();
                    setIsLoading(false);
                    browseToTask = null;
                    Toast.makeText(getActivity(), R.string.aborted,
                            Toast.LENGTH_SHORT).show();
                }
            };
            browseToTask.executeOnExecutor(serialExecutor);
        } else {
            targetPath = path;
            Log.w(TAG, "Other Task is still in progress. Will ignore this");
        }
    }
}
