/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.server.search;

import android.app.ISearchManagerCallback;
import android.app.SearchDialog;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

/**
 * Runs an instance of {@link SearchDialog} on its own thread.
 */
class SearchDialogWrapper
implements DialogInterface.OnCancelListener, DialogInterface.OnDismissListener {

    private static final String TAG = "SearchManagerService";
    private static final boolean DBG = false;

    private static final String DISABLE_SEARCH_PROPERTY = "dev.disablesearchdialog";

    private static final String SEARCH_UI_THREAD_NAME = "SearchDialog";
    private static final int SEARCH_UI_THREAD_PRIORITY =
        android.os.Process.THREAD_PRIORITY_FOREGROUND;

    // Takes no arguments
    private static final int MSG_INIT = 0;
    // Takes these arguments:
    // arg1: selectInitialQuery, 0 = false, 1 = true
    // arg2: globalSearch, 0 = false, 1 = true
    // obj: searchManagerCallback
    // data[KEY_INITIAL_QUERY]: initial query
    // data[KEY_LAUNCH_ACTIVITY]: launch activity
    // data[KEY_APP_SEARCH_DATA]: app search data
    private static final int MSG_START_SEARCH = 1;
    // Takes no arguments
    private static final int MSG_STOP_SEARCH = 2;
    // Takes no arguments
    private static final int MSG_ON_CONFIGURATION_CHANGED = 3;

    private static final String KEY_INITIAL_QUERY = "q";
    private static final String KEY_LAUNCH_ACTIVITY = "a";
    private static final String KEY_APP_SEARCH_DATA = "d";

    // Context used for getting search UI resources
    private final Context mContext;

    // Handles messages on the search UI thread.
    private final SearchDialogHandler mSearchUiThread;

    // The search UI
    SearchDialog mSearchDialog;

    // If the search UI is visible, this is the callback for the client that showed it.
    ISearchManagerCallback mCallback = null;

    // Allows disabling of search dialog for stress testing runs
    private final boolean mDisabledOnBoot;

    /**
     * Creates a new search dialog wrapper and a search UI thread. The search dialog itself will
     * be created some asynchronously on the search UI thread.
     *
     * @param context Context used for getting search UI resources.
     */
    public SearchDialogWrapper(Context context) {
        mContext = context;

        mDisabledOnBoot = !TextUtils.isEmpty(SystemProperties.get(DISABLE_SEARCH_PROPERTY));

        // Create the search UI thread
        HandlerThread t = new HandlerThread(SEARCH_UI_THREAD_NAME, SEARCH_UI_THREAD_PRIORITY);
        t.start();
        mSearchUiThread = new SearchDialogHandler(t.getLooper());

        // Create search UI on the search UI thread
        mSearchUiThread.sendEmptyMessage(MSG_INIT);
    }

    /**
     * Initializes the search UI.
     * Must be called from the search UI thread.
     */
    private void init() {
        mSearchDialog = new SearchDialog(mContext);
        mSearchDialog.setOnCancelListener(this);
        mSearchDialog.setOnDismissListener(this);
    }

    private void registerBroadcastReceiver() {
        IntentFilter closeDialogsFilter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, closeDialogsFilter);
        IntentFilter configurationChangedFilter =
                new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        mContext.registerReceiver(mBroadcastReceiver, configurationChangedFilter);
    }

    private void unregisterBroadcastReceiver() {
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    /**
     * Closes the search dialog when requested by the system (e.g. when a phone call comes in).
     */
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                if (DBG) debug(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
                stopSearch();
            } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                if (DBG) debug(Intent.ACTION_CONFIGURATION_CHANGED);
                onConfigurationChanged();
            }
        }
    };

    //
    // External API
    //

    /**
     * Launches the search UI.
     * Can be called from any thread.
     *
     * @see SearchManager#startSearch(String, boolean, ComponentName, Bundle, boolean)
     */
    public void startSearch(final String initialQuery,
            final boolean selectInitialQuery,
            final ComponentName launchActivity,
            final Bundle appSearchData,
            final boolean globalSearch,
            final ISearchManagerCallback searchManagerCallback) {
        if (DBG) debug("startSearch()");
        Message msg = Message.obtain();
        msg.what = MSG_START_SEARCH;
        msg.arg1 = selectInitialQuery ? 1 : 0;
        msg.arg2 = globalSearch ? 1 : 0;
        msg.obj = searchManagerCallback;
        Bundle msgData = msg.getData();
        msgData.putString(KEY_INITIAL_QUERY, initialQuery);
        msgData.putParcelable(KEY_LAUNCH_ACTIVITY, launchActivity);
        msgData.putBundle(KEY_APP_SEARCH_DATA, appSearchData);
        mSearchUiThread.sendMessage(msg);
    }

    /**
     * Cancels the search dialog.
     * Can be called from any thread.
     */
    public void stopSearch() {
        if (DBG) debug("stopSearch()");
        mSearchUiThread.sendEmptyMessage(MSG_STOP_SEARCH);
    }

    /**
     * Updates the search UI in response to a configuration change.
     * Can be called from any thread.
     */
    void onConfigurationChanged() {
        if (DBG) debug("onConfigurationChanged()");
        mSearchUiThread.sendEmptyMessage(MSG_ON_CONFIGURATION_CHANGED);
    }

    //
    // Implementation methods that run on the search UI thread
    //

    private class SearchDialogHandler extends Handler {

        public SearchDialogHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_INIT:
                    init();
                    break;
                case MSG_START_SEARCH:
                    handleStartSearchMessage(msg);
                    break;
                case MSG_STOP_SEARCH:
                    performStopSearch();
                    break;
                case MSG_ON_CONFIGURATION_CHANGED:
                    performOnConfigurationChanged();
                    break;
            }
        }

        private void handleStartSearchMessage(Message msg) {
            Bundle msgData = msg.getData();
            String initialQuery = msgData.getString(KEY_INITIAL_QUERY);
            boolean selectInitialQuery = msg.arg1 != 0;
            ComponentName launchActivity =
                    (ComponentName) msgData.getParcelable(KEY_LAUNCH_ACTIVITY);
            Bundle appSearchData = msgData.getBundle(KEY_APP_SEARCH_DATA);
            boolean globalSearch = msg.arg2 != 0;
            ISearchManagerCallback searchManagerCallback = (ISearchManagerCallback) msg.obj;
            performStartSearch(initialQuery, selectInitialQuery, launchActivity,
                    appSearchData, globalSearch, searchManagerCallback);
        }

    }

    /**
     * Actually launches the search UI.
     * This must be called on the search UI thread.
     */
    void performStartSearch(String initialQuery,
            boolean selectInitialQuery,
            ComponentName launchActivity,
            Bundle appSearchData,
            boolean globalSearch,
            ISearchManagerCallback searchManagerCallback) {
        if (DBG) debug("performStartSearch()");

        if (mDisabledOnBoot) {
            Log.d(TAG, "ignoring start search request because " + DISABLE_SEARCH_PROPERTY
                    + " system property is set.");
            return;
        }

        registerBroadcastReceiver();
        mCallback = searchManagerCallback;
        mSearchDialog.show(initialQuery, selectInitialQuery, launchActivity, appSearchData,
                globalSearch);
    }

    /**
     * Actually cancels the search UI.
     * This must be called on the search UI thread.
     */
    void performStopSearch() {
        if (DBG) debug("performStopSearch()");
        mSearchDialog.cancel();
    }

    /**
     * Must be called from the search UI thread.
     */
    void performOnConfigurationChanged() {
        if (DBG) debug("performOnConfigurationChanged()");
        mSearchDialog.onConfigurationChanged();
    }

    /**
     * Called by {@link SearchDialog} when it goes away.
     */
    public void onDismiss(DialogInterface dialog) {
        if (DBG) debug("onDismiss()");
        if (mCallback != null) {
            try {
                // should be safe to do on the search UI thread, since it's a oneway interface
                mCallback.onDismiss();
            } catch (DeadObjectException ex) {
                // The process that hosted the callback has died, do nothing
            } catch (RemoteException ex) {
                Log.e(TAG, "onDismiss() failed: " + ex);
            }
            // we don't need the callback anymore, release it
            mCallback = null;
        }
        unregisterBroadcastReceiver();
    }

    /**
     * Called by {@link SearchDialog} when the user or activity cancels search.
     * Whenever this method is called, {@link #onDismiss} is always called afterwards.
     */
    public void onCancel(DialogInterface dialog) {
        if (DBG) debug("onCancel()");
        if (mCallback != null) {
            try {
                // should be safe to do on the search UI thread, since it's a oneway interface
                mCallback.onCancel();
            } catch (DeadObjectException ex) {
                // The process that hosted the callback has died, do nothing
            } catch (RemoteException ex) {
                Log.e(TAG, "onCancel() failed: " + ex);
            }
        }
    }

    private static void debug(String msg) {
        Thread thread = Thread.currentThread();
        Log.d(TAG, msg + " (" + thread.getName() + "-" + thread.getId() + ")");
    }
}