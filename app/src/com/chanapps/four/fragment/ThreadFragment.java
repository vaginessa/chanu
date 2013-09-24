package com.chanapps.four.fragment;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.*;
import android.widget.*;
import com.chanapps.four.activity.*;
import com.chanapps.four.adapter.AbstractBoardCursorAdapter;
import com.chanapps.four.adapter.ThreadListCursorAdapter;
import com.chanapps.four.component.ActivityDispatcher;
//import com.chanapps.four.component.AdComponent;
import com.chanapps.four.component.ThemeSelector;
import com.chanapps.four.component.ThreadViewable;
import com.chanapps.four.data.*;
import com.chanapps.four.loader.ChanImageLoader;
import com.chanapps.four.loader.ThreadCursorLoader;
import com.chanapps.four.service.FetchChanDataService;
import com.chanapps.four.service.NetworkProfileManager;
import com.chanapps.four.service.ThreadImageDownloadService;
import com.chanapps.four.service.profile.NetworkProfile;
import com.chanapps.four.viewer.ThreadListener;
import com.chanapps.four.viewer.ThreadViewer;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.assist.PauseOnScrollListener;
import uk.co.senab.actionbarpulltorefresh.library.PullToRefreshAttacher;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: arley
 * Date: 11/27/12
 * Time: 12:26 PM
 * To change this template use File | Settings | File Templates.
 */
public class ThreadFragment extends Fragment implements ThreadViewable
{

    public static final String TAG = ThreadFragment.class.getSimpleName();
    public static final String BOARD_CODE = "boardCode";
    public static final String THREAD_NO = "threadNo";
    public static final String POST_NO = "postNo";

    protected static final int DRAWABLE_ALPHA_LIGHT = 0xc2;
    protected static final int DRAWABLE_ALPHA_DARK = 0xee;

    public static final boolean DEBUG = true;

    public static final String GOOGLE_TRANSLATE_ROOT = "http://translate.google.com/#auto";
    public static final int MAX_HTTP_GET_URL_LEN = 2000;
    protected static final int LOADER_ID = 0;
    protected static final String FIRST_VISIBLE_POSITION = "firstVisiblePosition";
    protected static final String FIRST_VISIBLE_POSITION_OFFSET = "firstVisiblePositionOffset";

    protected String boardCode;
    protected long threadNo;

    protected AbstractBoardCursorAdapter adapter;
    protected View layout;
    protected AbsListView absListView;
    protected Handler handler;
    protected String query = "";
    protected long postNo; // for direct jumps from latest post / recent images
    protected String imageUrl;
    protected boolean shouldPlayThread = false;
    protected ShareActionProvider shareActionProvider = null;
    protected Map<String, Uri> checkedImageUris = new HashMap<String, Uri>(); // used for tracking what's in the media store
    protected ActionMode actionMode = null;
    protected int firstVisiblePosition = -1;
    protected int firstVisiblePositionOffset = -1;
    protected PullToRefreshAttacher mPullToRefreshAttacher;
    protected View boardTitleBar;
    protected View boardSearchResultsBar;
    protected ThreadListener threadListener;
    protected boolean progressVisible = false;
    protected Menu menu = null;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup viewGroup, Bundle bundle) {
        if (bundle == null)
            bundle = getArguments();
        boardCode = bundle.getString(BOARD_CODE);
        threadNo = bundle.getLong(THREAD_NO);
        postNo = bundle.getLong(POST_NO);
        query = bundle.getString(SearchManager.QUERY);
        if (DEBUG) Log.i(TAG, "onCreateView /" + boardCode + "/" + threadNo + "#p" + postNo + " q=" + query);
        int layoutId = query != null && !query.isEmpty() ? R.layout.thread_list_layout_search : R.layout.thread_list_layout;
        layout = inflater.inflate(layoutId, viewGroup, false);
        createAbsListView();

        if (threadNo > 0)
            getLoaderManager().initLoader(LOADER_ID, null, loaderCallbacks);
        else
            if (DEBUG) Log.i(TAG, "onCreateView /" + boardCode + "/" + threadNo + "#p" + postNo
                    + " no thread found, skipping loader");

        boardTitleBar = layout.findViewById(R.id.board_title_bar);
        boardSearchResultsBar = layout.findViewById(R.id.board_search_results_bar);
        setHasOptionsMenu(true);
        return layout;
    }

    protected PullToRefreshAttacher.OnRefreshListener pullToRefreshListener = new PullToRefreshAttacher.OnRefreshListener() {
        @Override
        public void onRefreshStarted(View view) {
            if (DEBUG) Log.i(TAG, "pullToRefreshListener.onRefreshStarted()");
            onRefresh();
        }
    };

    protected boolean onTablet() {
        return getActivity() != null && ((ThreadActivity)getActivity()).onTablet();
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putString(ChanBoard.BOARD_CODE, boardCode);
        savedInstanceState.putLong(ChanThread.THREAD_NO, threadNo);
        savedInstanceState.putString(SearchManager.QUERY, query);
        int pos = absListView == null ? -1 : absListView.getFirstVisiblePosition();
        View view = absListView == null ? null : absListView.getChildAt(0);
        int offset = view == null ? 0 : view.getTop();
        savedInstanceState.putInt(FIRST_VISIBLE_POSITION, pos);
        savedInstanceState.putInt(FIRST_VISIBLE_POSITION_OFFSET, offset);
        if (DEBUG) Log.i(TAG, "onSaveInstanceState /" + boardCode + "/" + threadNo + " pos=" + pos);
    }

    @Override
    public void onViewStateRestored(Bundle bundle) {
        super.onViewStateRestored(bundle);
        if (bundle == null)
            return;
        boardCode = bundle.getString(ChanBoard.BOARD_CODE);
        threadNo = bundle.getLong(ChanThread.THREAD_NO, 0);
        query = bundle.getString(SearchManager.QUERY);
        firstVisiblePosition = bundle.getInt(FIRST_VISIBLE_POSITION);
        firstVisiblePositionOffset = bundle.getInt(FIRST_VISIBLE_POSITION_OFFSET);
        if (DEBUG) Log.i(TAG, "onRestoreInstanceState /" + boardCode + "/" + threadNo + " pos=" + firstVisiblePosition);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (DEBUG) Log.i(TAG, "onStart /" + boardCode + "/" + threadNo);
        //if (getActivity() != null)
        //    (new AdComponent(getActivity().getApplicationContext(), layout.findViewById(R.id.board_grid_advert))).hideOrDisplayAds();
        if (handler == null)
            handler = new Handler();
        threadListener = new ThreadListener(this, ThemeSelector.instance(getActivity().getApplicationContext()).isDark());
        if (threadNo > 0 && (adapter == null || adapter.getCount() <= 1)) { // <= 0
            ThreadActivity activity = (ThreadActivity)getActivity();
            if (activity == null) {
                if (DEBUG) Log.i(TAG, "onStart /" + boardCode + "/" + threadNo + " activity null, skipping loader");
            }
            else if (activity.refreshing) {
                restartIfDeadAsync();
            }
            else if (!getLoaderManager().hasRunningLoaders()) {
                if (DEBUG) Log.i(TAG, "onStart /" + boardCode + "/" + threadNo + " no data and no running loaders, restarting loader");
                //getLoaderManager().restartLoader(LOADER_ID, null, loaderCallbacks);
            }
        }
        else {
            if (DEBUG) Log.i(TAG, "onStart /" + boardCode + "/" + threadNo + " no thread found, skipping loader");
        }
    }

    protected void restartIfDeadAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ChanThread thread = ChanFileStorage.loadThreadData(getActivityContext(), boardCode, threadNo);
                final boolean isDead = thread != null && thread.isDead;
                if (handler != null)
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isDead) {
                                if (DEBUG) Log.i(TAG, "onStart /" + boardCode + "/" + threadNo + " dead thread, restarting loader");
                                getLoaderManager().restartLoader(LOADER_ID, null, loaderCallbacks);
                            }
                            else {
                                if (DEBUG) Log.i(TAG, "onStart /" + boardCode + "/" + threadNo + " activity refreshing, skipping loader");
                            }
                        }
                    });
            }
        }).start();
    }

    @Override
    public AbsListView getAbsListView() {
        return absListView;
    }

    @Override
    public ResourceCursorAdapter getAdapter() {
        return adapter;
    }

    @Override
    public Handler getHandler() {
        return handler;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (DEBUG) Log.i(TAG, "onResume /" + boardCode + "/" + threadNo);
        if (handler == null)
            handler = new Handler();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (DEBUG) Log.i(TAG, "onPause /" + boardCode + "/" + threadNo);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (DEBUG) Log.i(TAG, "onStop /" + boardCode + "/" + threadNo);
        //if (absListView != null)
        //    getLoaderManager().destroyLoader(LOADER_ID);
        handler = null;
    }

    protected boolean warnedAboutNetworkDown() {
        ThreadActivity activity = (ThreadActivity)getActivity();
        if (activity == null)
            return false;
        else
            return activity.warnedAboutNetworkDown();
    }

    protected void warnedAboutNetworkDown(boolean set) {
        ThreadActivity activity = (ThreadActivity)getActivity();
        if (activity == null)
            return;
        else
            activity.warnedAboutNetworkDown(set);
    }

    public void fetchIfNeeded(final Handler activityHandler) {
        if (DEBUG) Log.i(TAG, "fetchIfNeeded() /" + boardCode + "/" + threadNo);
        new Thread(new Runnable() {
            @Override
            public void run() {
                ChanThread thread = ChanFileStorage.loadThreadData(getActivityContext(), boardCode, threadNo);
                if (thread == null) {
                    if (DEBUG) Log.i(TAG, "fetchIfNeeded() /" + boardCode + "/" + threadNo + " null thread");
                    return;
                }
                if (thread.isDead) {
                    if (DEBUG) Log.i(TAG, "fetchIfNeeded() /" + boardCode + "/" + threadNo + " dead thread");
                    return;
                }
                final int replies = thread.replies;
                if (DEBUG) Log.i(TAG, "fetchIfNeeded() /" + boardCode + "/" + threadNo + " checking thread replies=" + thread.replies);
                if (activityHandler != null)
                    activityHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (replies < 0 || replies > absListView.getCount() - 1) {
                                if (DEBUG) Log.i(TAG, "fetchIfNeeded() /" + boardCode + "/" + threadNo + " should fetch more, trying");
                                tryFetchThread();
                            }
                        }
                    });
            }
        }).start();
    }

    protected void tryFetchThread() {
        if (DEBUG) Log.i(TAG, "tryFetchThread /" + boardCode + "/" + threadNo);
        if (handler == null) {
            if (DEBUG) Log.i(TAG, "tryFetchThread not in foreground, exiting");
            setProgressAsync(false);
            return;
        }
        NetworkProfile.Health health = NetworkProfileManager.instance().getCurrentProfile().getConnectionHealth();
        if (health == NetworkProfile.Health.NO_CONNECTION || health == NetworkProfile.Health.BAD) {
            if (DEBUG) Log.i(TAG, "tryFetchThread bad health, exiting");
            final Context context = getActivityContext();
            if (handler != null && context != null && !warnedAboutNetworkDown()) {
                warnedAboutNetworkDown(true);
                final String msg = String.format(getString(R.string.mobile_profile_health_status),
                        health.toString().toLowerCase().replaceAll("_", " "));
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
            setProgressAsync(false);
            return;
        }
        else {
            warnedAboutNetworkDown(false);
        }
        ThreadActivity activity = (ThreadActivity)getActivity();
        ThreadFragment primary = activity == null ? null : activity.getPrimaryItem();
        if (primary == null || primary != this) {
            if (DEBUG) Log.i(TAG, "tryFetchThread exiting since non-primary item this=" + this + " is not primary=" + primary);
            setProgressAsync(false);
            return;
        }
        if (DEBUG) Log.i(TAG, "tryFetchThread clearing fetch chan data service queue");
        FetchChanDataService.clearServiceQueue(getActivityContext());
        if (DEBUG) Log.i(TAG, "tryFetchThread calling fetch chan data service for /" + boardCode + "/" + threadNo);
        boolean fetchScheduled = FetchChanDataService.scheduleThreadFetch(getActivityContext(), boardCode, threadNo, true, false);
        if (fetchScheduled) {
            if (DEBUG) Log.i(TAG, "tryFetchThread scheduled fetch");
            setProgressAsync(true);
        }
        else {
            if (DEBUG) Log.i(TAG, "tryFetchThread couldn't fetch");
            setProgressAsync(false);
            return;
        }
    }
    
    protected void setProgressAsync(final boolean on) {
        if (handler != null)
            handler.post(new Runnable() {
                @Override
                public void run() {
                    setProgress(on);
                }
            });
    }

    protected void onThreadLoadFinished(Cursor data) {
        adapter.swapCursor(data);
        // retry load if maybe data wasn't there yet
        selectCurrentThreadAsync();
    }

    protected void selectCurrentThreadAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ChanThread thread = ChanFileStorage.loadThreadData(getActivityContext(), boardCode, threadNo);
                selectCurrentThread(thread);
            }
        }).start();
    }

    protected static final int FROM_BOARD_THREAD_ADAPTER_COUNT = 5; // thread header + related title + 3 related boards

    protected void setProgressFromThreadState(final ChanThread thread) {
        if (DEBUG) Log.i(TAG, "setProgressFromThreadState /" + boardCode + "/" + threadNo + " listViewCount=" + (absListView == null ? 0 : absListView.getCount()));
        ThreadActivity activity = getActivity() instanceof ThreadActivity ? (ThreadActivity)getActivity() : null;
        if (activity == null) {
            if (DEBUG) Log.i(TAG, "setProgressFromThreadState /" + boardCode + "/" + threadNo + " not attached to activity, exiting");
            return;
        }
        else if (activity.getCurrentFragment() != this) {
            if (DEBUG) Log.i(TAG, "setProgressFromThreadState /" + boardCode + "/" + threadNo + " not the current fragment, exiting");
        }
        else if (!NetworkProfileManager.isConnected()) {
            if (DEBUG) Log.i(TAG, "setProgressFromThreadState /" + boardCode + "/" + threadNo + " no connection, setting load finished for thread=" + thread);
            setProgress(false);
        }
        else if (thread.isDead) {
            if (DEBUG) Log.i(TAG, "setProgressFromThreadState /" + boardCode + "/" + threadNo + " dead thread, setting load finished for thread=" + thread);
            setProgress(false);
        }
        else if (thread != null && thread.posts != null && thread.posts.length == 1 && thread.posts[0].replies > 0
                && absListView != null && absListView.getCount() <= FROM_BOARD_THREAD_ADAPTER_COUNT) {
            if (DEBUG) Log.i(TAG, "setProgressFromThreadState /" + boardCode + "/" + threadNo + " thread not fully loaded, awaiting load thread=" + thread);
        }
        else if (!thread.defData
                && thread.posts != null && thread.posts.length > 0
                && thread.posts[0] != null && !thread.posts[0].defData && thread.posts[0].replies >= 0) { // post is loaded
            if (DEBUG) Log.i(TAG, "setProgressFromThreadState /" + boardCode + "/" + threadNo + " thread loaded, setting load finished for thread=" + thread);
            setProgress(false);
        }
        else {
            if (DEBUG) Log.i(TAG, "setProgressFromThreadState /" + boardCode + "/" + threadNo + " thread not yet loaded, awaiting load thread=" + thread);
        }    
    }

    public void scrollToPostAsync(final long scrollToPostNo) {
        if (DEBUG) Log.i(TAG, "scrollToPostAsync() postNo=" + scrollToPostNo);
        scrollToPost(scrollToPostNo, null);
    }

    protected void scrollToPost(final long scrollToPostNo, final Runnable uiCallback) {
        if (DEBUG) Log.i(TAG, "scrollToPost() postNo=" + scrollToPostNo + " begin");
        if (adapter == null) {
            if (DEBUG) Log.i(TAG, "scrollToPost() postNo=" + scrollToPostNo + " null adapter, exiting");
            return;
        }
        Cursor cursor = adapter.getCursor();
        cursor.moveToPosition(-1);
        boolean found = false;
        int pos = 0;
        while (cursor.moveToNext()) {
            long postNoAtPos = cursor.getLong(cursor.getColumnIndex(ChanPost.POST_ID));
            if (postNoAtPos == scrollToPostNo) {
                found = true;
                break;
            }
            pos++;
        }
        final boolean hasPost = found;
        final int postPos = pos;
        if (hasPost) {
            if (DEBUG) Log.i(TAG, "scrollToPost() found postNo=" + scrollToPostNo + " at pos=" + pos);
        }
        else {
            if (DEBUG) Log.i(TAG, "scrollToPost() didn't find postNo=" + scrollToPostNo);
        }
        if (handler == null) {
            if (DEBUG) Log.i(TAG, "scrollToPost() postNo=" + scrollToPostNo + " null handler, exiting");
            return;
        }
        if (handler != null && (hasPost || uiCallback != null))
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (hasPost) {
                        if (absListView == null) {
                            if (DEBUG) Log.i(TAG, "scrollToPost() postNo=" + scrollToPostNo + " null list view, exiting");
                            return;
                        }
                        if (DEBUG) Log.i(TAG, "scrollToPost() postNo=" + scrollToPostNo + " scrolling to pos=" + postPos + " on UI thread");
                        absListView.smoothScrollToPosition(postPos);
                        //absListView.setSelection(postPos);
                        SparseBooleanArray booleanArray = absListView.getCheckedItemPositions();
                        for (int i = 0; i < absListView.getCount(); i++)
                            if (booleanArray.get(i, false) && i != postPos)
                                absListView.setItemChecked(i, false);
                        if (!booleanArray.get(postPos, false))
                            absListView.setItemChecked(postPos, true);
                        postNo = -1;
                    }
                    if (uiCallback != null)
                        uiCallback.run();
                }
            });
    }

    protected void selectCurrentThread(final ChanThread thread) {
        if (DEBUG) Log.i(TAG, "onThreadLoadFinished /" + boardCode + "/" + threadNo + " thread=" + thread);
        if (query != null && !query.isEmpty()) {
            if (handler != null)
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        displaySearchTitle();
                        setProgressFromThreadState(thread);
                    }
                });
        }
        //else if (thread.threadNeedsRefresh()) {
        //    if (DEBUG) Log.i(TAG, "onThreadLoadFinished /" + boardCode + "/" + threadNo + " trying thread refresh");
        //    tryFetchThread();
        //}
        else if (thread.isDead) {
            if (DEBUG) Log.i(TAG, "onThreadLoadFinished /" + boardCode + "/" + threadNo + " dead thread, redisplaying");
            if (handler != null)
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        //absListView.invalidateViews();
                        setProgressFromThreadState(thread);
                    }
                });
        }
        else if (postNo > 0) {
            if (DEBUG) Log.i(TAG, "onThreadLoadFinished /" + boardCode + "/" + threadNo + " scrolling to postNo=" + postNo);
            scrollToPost(postNo, new Runnable() {
                @Override
                public void run() {
                    setProgressFromThreadState(thread);
                }
            });
        }
        else if (firstVisiblePosition >= 0) {
            if (DEBUG) Log.i(TAG, "onThreadLoadFinished /" + boardCode + "/" + threadNo + " scrolling to saved pos=" + firstVisiblePosition);
            if (handler != null)
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (absListView instanceof ListView)
                            ((ListView)absListView).setSelectionFromTop(firstVisiblePosition, firstVisiblePositionOffset);
                        else
                            absListView.setSelection(firstVisiblePosition);
                        firstVisiblePosition = -1;
                        firstVisiblePositionOffset = -1;
                        setProgressFromThreadState(thread);
                    }
                });
        }
        else {
            if (DEBUG) Log.i(TAG, "onThreadLoadFinished /" + boardCode + "/" + threadNo + " setting spinner from thread state");
            if (handler != null)
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        setProgressFromThreadState(thread);
                    }
                });
        }
    }

    protected void setProgress(boolean on) {
        progressVisible = on;
        ThreadActivity activity = (ThreadActivity)getActivity();
        if (activity != null) {
        	activity.setProgressForFragment(boardCode, threadNo, on);
        }
    }
    
    protected void createAbsListView() {
        ImageLoader imageLoader = ChanImageLoader.getInstance(getActivityContext());
        absListView = (ListView) layout.findViewById(R.id.thread_list_view);
        adapter = new ThreadListCursorAdapter(getActivity(), viewBinder, true);
        absListView.setAdapter(adapter);
        absListView.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
        absListView.setOnCreateContextMenuListener(this);
        //absListView.setOnItemClickListener(threadItemListener);
        absListView.setOnScrollListener(new PauseOnScrollListener(imageLoader, true, true));
    }

    public void setPullToRefreshAttacher(PullToRefreshAttacher mPullToRefreshAttacher) {
        this.mPullToRefreshAttacher = mPullToRefreshAttacher;
        if (mPullToRefreshAttacher != null && absListView != null)
            mPullToRefreshAttacher.setRefreshableView(absListView, pullToRefreshListener);
    }

    /*
    protected AbsListView.OnItemClickListener threadItemListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
            Cursor cursor = (Cursor) adapterView.getItemAtPosition(position);
            postNo = cursor.getLong(cursor.getColumnIndex(ChanPost.POST_ID));
            int flags = cursor.getInt(cursor.getColumnIndex(ChanPost.POST_FLAGS));
            if (DEBUG) Log.i(TAG, "onItemClick pos=" + position + " postNo=" + postNo + " flags=" + flags + " view=" + view);
            // /updateSharedIntent();
            if ((flags & ChanPost.FLAG_IS_AD) > 0)
                itemAdListener.onClick(view);
            else if ((flags & ChanPost.FLAG_IS_TITLE) > 0)
                itemTitleListener.onClick(view);
            else if ((flags & ChanPost.FLAG_IS_BUTTON) > 0)
                itemButtonListener.onClick(view);
            else if ((flags & ChanPost.FLAG_IS_THREADLINK) > 0)
                itemThreadLinkListener.onClick(view);
            else if ((flags & ChanPost.FLAG_IS_BOARDLINK) > 0)
                itemBoardLinkListener.onClick(view);
        }
    };
    */

    private void postReply(long postNos[]) {
        String replyText = "";
        for (long postNo : postNos) {
            replyText += ">>" + postNo + "\n";
        }
        postReply(replyText);
    }

    private void postReply(String replyText) {
        PostReplyActivity.startActivity(getActivityContext(), boardCode, threadNo, 0, ChanPost.planifyText(replyText));
    }

    protected View.OnClickListener imagesOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            GalleryViewActivity.startAlbumViewActivity(getActivityContext(), boardCode, threadNo);
        }
    };

    /*
    protected View.OnClickListener postReplyListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            int pos = absListView.getPositionForView(v);
            if (pos < 0)
                return;
            Cursor cursor = adapter.getCursor();
            if (!cursor.moveToPosition(pos))
                return;
            long postNo = cursor.getLong(cursor.getColumnIndex(ChanPost.POST_ID));
            if (postNo == threadNo)
                postNo = 0;
            PostReplyActivity.startActivity(getActivityContext(), boardCode, threadNo, postNo, ChanPost.planifyText(""));
        }
    };
    */

    protected boolean isThreadPlayable() {
        return adapter != null
                && adapter.getCount() > 0
                && !getLoaderManager().hasRunningLoaders()
                && !progressVisible;
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        this.menu = menu;
        MenuItem playMenuItem = menu.findItem(R.id.play_thread_menu);
        if (playMenuItem != null)
            synchronized (this) {
                if (isThreadPlayable()) {
                    playMenuItem.setIcon(shouldPlayThread ? R.drawable.av_stop : R.drawable.av_play);
                    playMenuItem.setTitle(shouldPlayThread ? R.string.play_thread_stop_menu : R.string.play_thread_menu);
                    playMenuItem.setVisible(true);
                }
                else {
                    playMenuItem.setVisible(false);
                }
            }
        setDeadStatusAsync();
        setWatchMenuAsync();
        super.onPrepareOptionsMenu(menu);
    }

    protected void setDeadStatusAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean undead = undead();
                if (handler != null)
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (menu == null)
                                return;
                            MenuItem item;
                            if ((item = menu.findItem(R.id.refresh_menu)) != null)
                                item.setVisible(undead);
                            if ((item = menu.findItem(R.id.post_reply_all_menu)) != null)
                                item.setVisible(undead);
                            if ((item = menu.findItem(R.id.post_reply_all_quote_menu)) != null)
                                item.setVisible(undead);
                            if ((item = menu.findItem(R.id.web_menu)) != null)
                                item.setVisible(undead);
                        }
                    });
            }
        }).start();
    }

    protected void setWatchMenuAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ChanThread thread = ChanFileStorage.loadThreadData(getActivityContext(), boardCode, threadNo);
                final boolean watched = ChanFileStorage.isThreadWatched(getActivityContext(), thread);
                if (handler != null)
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (menu == null)
                                return;
                            MenuItem item;
                            if ((item = menu.findItem(R.id.watch_thread_menu)) != null)
                                item.setVisible(!watched);
                            if ((item = menu.findItem(R.id.watch_remove_thread_menu)) != null)
                                item.setVisible(watched);
                        }
                    });
            }
        }).start();
    }

    protected boolean undead() {
        ChanThread thread = ChanFileStorage.loadThreadData(getActivity(), boardCode, threadNo);
        return !(thread != null && thread.isDead);
    }

    protected void navigateUp() {
        Activity activity = getActivity();
        if (activity == null)
            return;
        if (activity instanceof ThreadActivity)
            ((ThreadActivity)activity).navigateUp();
        else if (activity instanceof BoardActivity)
            ((BoardActivity)activity).navigateUp();
        else
            activity.finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                navigateUp();
                return true;
            case R.id.refresh_menu:
                setProgress(true);
                NetworkProfileManager.instance().manualRefresh(getChanActivity());
                return true;
            case R.id.view_image_gallery_menu:
                GalleryViewActivity.startAlbumViewActivity(getActivityContext(), boardCode, threadNo);
                return true;
            case R.id.watch_thread_menu:
                addToWatchlist();
                return true;
            case R.id.watch_remove_thread_menu:
                removeFromWatchlist();
                return true;
            case R.id.scroll_to_bottom_menu:
                int n = adapter.getCount() - 1;
                if (DEBUG) Log.i(TAG, "jumping to item n=" + n);
                absListView.setSelection(n);
                return true;
            case R.id.post_reply_all_menu:
                long[] postNos = { threadNo };
                postReply(postNos);
                return true;
            case R.id.post_reply_all_quote_menu:
                String quotesText = selectQuoteText(0);
                postReply(quotesText);
                return true;
            /*
            case R.id.download_all_images_menu:
                ThreadImageDownloadService.startDownloadToBoardFolder(getActivityContext(), boardCode, threadNo);
                Toast.makeText(getActivityContext(), R.string.download_all_images_notice_prefetch, Toast.LENGTH_SHORT).show();
                return true;
            */
            case R.id.download_all_images_to_gallery_menu:
                ThreadImageDownloadService.startDownloadToGalleryFolder(getActivityContext(), boardCode, threadNo);
                Toast.makeText(getActivityContext(), R.string.download_all_images_notice, Toast.LENGTH_SHORT).show();
                return true;
            case R.id.play_thread_menu:
                return playThreadMenu();
            case R.id.web_menu:
                String url = ChanThread.threadUrl(boardCode, threadNo);
                ActivityDispatcher.launchUrlInBrowser(getActivityContext(), url);
            default:
                ThreadActivity activity = (ThreadActivity)getActivity();
                if (activity != null)
                    return activity.onOptionsItemSelected(item);
                else
                    return super.onOptionsItemSelected(item);
        }
    }

    protected void addToWatchlist() {
        addToWatchlist(getActivityContext(), handler, boardCode, threadNo);
        setWatchMenuAsync();
    }

    protected void removeFromWatchlist() {
        removeFromWatchlist(getActivityContext(), handler, boardCode, threadNo);
        setWatchMenuAsync();
    }

    public static void addToWatchlist(final Context context, final Handler handler,
                                      final String boardCode, final long threadNo) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int msgId;
                try {
                    final ChanThread thread = ChanFileStorage.loadThreadData(context, boardCode, threadNo);
                    if (thread == null) {
                        Log.e(TAG, "Couldn't add null thread /" + boardCode + "/" + threadNo + " to watchlist");
                        msgId = R.string.thread_not_added_to_watchlist;
                    }
                    else {
                        ChanFileStorage.addWatchedThread(context, thread);
                        BoardActivity.refreshWatchlist();
                        msgId = R.string.thread_added_to_watchlist;
                        if (DEBUG) Log.i(TAG, "Added /" + boardCode + "/" + threadNo + " to watchlist");
                    }
                }
                catch (IOException e) {
                    msgId = R.string.thread_not_added_to_watchlist;
                    Log.e(TAG, "Exception adding /" + boardCode + "/" + threadNo + " to watchlist", e);
                }
                final int stringId = msgId;
                if (handler != null)
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context, stringId, Toast.LENGTH_SHORT).show();
                        }
                    });
            }
        }).start();
    }

    public static void removeFromWatchlist(final Context context, final Handler handler,
                                      final String boardCode, final long threadNo) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int msgId;
                try {
                    final ChanThread thread = ChanFileStorage.loadThreadData(context, boardCode, threadNo);
                    if (thread == null) {
                        Log.e(TAG, "Couldn't remove thread /" + boardCode + "/" + threadNo + " from watchlist");
                        msgId = R.string.thread_watchlist_not_deleted_thread;
                    }
                    else {
                        ChanFileStorage.deleteWatchedThread(context, thread);
                        BoardActivity.refreshWatchlist();
                        msgId = R.string.thread_deleted_from_watchlist;
                        if (DEBUG) Log.i(TAG, "Deleted /" + boardCode + "/" + threadNo + " from watchlist");
                    }
                }
                catch (IOException e) {
                    msgId = R.string.thread_watchlist_not_deleted_thread;
                    Log.e(TAG, "Exception deleting /" + boardCode + "/" + threadNo + " from watchlist", e);
                }
                final int stringId = msgId;
                if (handler != null)
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context, stringId, Toast.LENGTH_SHORT).show();
                        }
                    });
            }
        }).start();
    }

    public ChanActivityId getChanActivityId() {
        return new ChanActivityId(LastActivity.THREAD_ACTIVITY, boardCode, threadNo, postNo, query);
    }

    protected String selectText(SparseBooleanArray postPos) {
        String text = "";
        for (int i = 0; i < absListView.getCount(); i++) {
            if (!postPos.get(i))
                continue;
            Cursor cursor = (Cursor) adapter.getItem(i);
            if (cursor == null)
                continue;
            String subject = cursor.getString(cursor.getColumnIndex(ChanPost.POST_SPOILER_SUBJECT));
            String message = cursor.getString(cursor.getColumnIndex(ChanPost.POST_SPOILER_TEXT));
            if (!subject.isEmpty() || !message.isEmpty()) {
                text = subject
                        + (!subject.isEmpty() && !message.isEmpty() ? "<br/>" : "")
                        + message;
                if (DEBUG) Log.i(TAG, "selectText() de-spoilered text=" + text);
            }
            else {
                subject = cursor.getString(cursor.getColumnIndex(ChanPost.POST_SUBJECT_TEXT));
                message = cursor.getString(cursor.getColumnIndex(ChanPost.POST_TEXT));
                text = subject
                        + (!subject.isEmpty() && !message.isEmpty() ? "<br/>" : "")
                        + message;
                if (DEBUG) Log.i(TAG, "selectText() raw text=" + text);
            }
            break;
//            if (!text.isEmpty())
//                text += "<br/><br/>";
        }
        text = text.replaceAll("(</?br/?>)+", "\n").replaceAll("<[^>]*>", "");
        if (DEBUG) Log.i(TAG, "selectText() returning filtered text=" + text);
        return text;
    }

    protected String selectQuoteText(SparseBooleanArray postPos) {
        for (int i = 0; i < absListView.getCount(); i++) {
            if (!postPos.get(i))
                continue;
            return selectQuoteText(i);
        }
        return "";
    }

    protected String selectQuoteText(int i) {
        Cursor cursor = adapter.getCursor();
        if (cursor == null)
            return "";
        cursor.moveToPosition(i);
        String postNo = cursor.getString(cursor.getColumnIndex(ChanPost.POST_ID));
        String t = cursor.getString(cursor.getColumnIndex(ChanPost.POST_SUBJECT_TEXT));
        String u = cursor.getString(cursor.getColumnIndex(ChanPost.POST_TEXT));
        String itemText = (t == null ? "" : t)
                + (t != null && u != null && !t.isEmpty() && !u.isEmpty() ? "<br/>" : "")
                + (u == null ? "" : u);
        if (itemText == null)
            itemText = "";
        String postPrefix = ">>" + postNo + "\n";
        String text = postPrefix + ChanPost.quoteText(itemText);
        if (DEBUG) Log.i(TAG, "Selected itemText=" + itemText + " resulting quoteText=" + text);
        return text;
    }

    protected void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getActivityContext().getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText(
                getActivityContext().getString(R.string.app_name),
                ChanPost.planifyText(text));
        clipboard.setPrimaryClip(clip);
        Toast.makeText(getActivityContext(), R.string.copy_text_complete, Toast.LENGTH_SHORT).show();
    }
    /*
    protected View.OnClickListener itemAdListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            int pos = absListView.getPositionForView(v);
            Cursor cursor = adapter.getCursor();
            cursor.moveToPosition(pos);
            String adUrl = cursor.getString(cursor.getColumnIndex(ChanPost.POST_TEXT));
            if (adUrl != null && !adUrl.isEmpty())
                ActivityDispatcher.launchUrlInBrowser(getActivity(), adUrl);
        }
    };

    protected View.OnClickListener itemTitleListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            int pos = absListView.getPositionForView(v);
            Cursor cursor = adapter.getCursor();
            cursor.moveToPosition(pos);
            int flags = cursor.getInt(cursor.getColumnIndex(ChanPost.POST_FLAGS));
            final String title = cursor.getString(cursor.getColumnIndex(ChanPost.POST_SUBJECT_TEXT));
            final String desc = cursor.getString(cursor.getColumnIndex(ChanPost.POST_TEXT));
            if ((flags & ChanPost.FLAG_IS_TITLE) > 0
                    && title != null && !title.isEmpty()
                    && desc != null && !desc.isEmpty()) {
                (new GenericDialogFragment(title.replaceAll("<[^>]*>", " "), desc))
                        .show(getFragmentManager(), ThreadFragment.TAG);
            }

        }
    };

    // we only support reply button currently
    protected View.OnClickListener itemButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            int pos = absListView.getPositionForView(v);
            Cursor cursor = adapter.getCursor();
            cursor.moveToPosition(pos);
            int flags = cursor.getInt(cursor.getColumnIndex(ChanPost.POST_FLAGS));
            if ((flags & ChanPost.FLAG_IS_BUTTON) > 0)
                PostReplyActivity.startActivity(getActivity(), boardCode, threadNo, 0, "");
        }
    };

    protected View.OnClickListener itemThreadLinkListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            int pos = absListView.getPositionForView(v);
            Cursor cursor = adapter.getCursor();
            cursor.moveToPosition(pos);
            String linkedBoardCode = cursor.getString(cursor.getColumnIndex(ChanPost.POST_BOARD_CODE));
            long linkedThreadNo = cursor.getLong(cursor.getColumnIndex(ChanPost.POST_ID));
            absListView.setItemChecked(pos, false); // gets checked for some reason
            if (linkedBoardCode == null || linkedBoardCode.isEmpty() || linkedThreadNo <= 0)
                return;
            if (boardCode.equals(linkedBoardCode)) {
                ((ThreadActivity)getActivity()).showThread(threadNo);
            } else {
                ThreadActivity.startActivity(getActivity(), linkedBoardCode, linkedThreadNo, "");
                getActivity().finish();
            }
        }
    };

    protected View.OnClickListener itemBoardLinkListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            int pos = absListView.getPositionForView(v);
            Cursor cursor = adapter.getCursor();
            cursor.moveToPosition(pos);
            String linkedBoardCode = cursor.getString(cursor.getColumnIndex(ChanPost.POST_BOARD_CODE));
            absListView.setItemChecked(pos, false); // gets checked for some reason
            if (linkedBoardCode != null && !linkedBoardCode.isEmpty()) {
                BoardActivity.startActivity(getActivity(), linkedBoardCode, "");
                getActivity().finish();
            }
        }
    };
    */

    protected View.OnLongClickListener startActionModeListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            int pos = absListView.getPositionForView(v);
            Cursor cursor = adapter.getCursor();
            if (cursor.moveToPosition(pos))
                postNo = cursor.getLong(cursor.getColumnIndex(ChanPost.POST_ID));
            if (DEBUG) Log.i(TAG, "on long click for pos=" + pos + " postNo=" + postNo);

            View itemView = null;
            for (int i = 0; i < absListView.getChildCount(); i++) {
                View child = absListView.getChildAt(i);
                if (absListView.getPositionForView(child) == pos) {
                    itemView = child;
                    break;
                }
            }
            if (DEBUG) Log.i(TAG, "found itemView=" + itemView);
            if (itemView == null)
                return false;

            absListView.setItemChecked(pos, true);

            if (actionMode == null) {
                if (DEBUG) Log.i(TAG, "starting action mode...");
                getActivity().startActionMode(actionModeCallback);
                if (DEBUG) Log.i(TAG, "started action mode");
            }
            else {
                if (DEBUG) Log.i(TAG, "action mode already started, updating share intent");
                updateSharedIntent();
            }
            return true;
        }
    };

    protected Map<ChanBlocklist.BlockType, List<String>> extractBlocklist(SparseBooleanArray postPos) {
        Map<ChanBlocklist.BlockType, List<String>> blocklist = new HashMap<ChanBlocklist.BlockType, List<String>>();
        List<String> tripcodes = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        List<String> emails = new ArrayList<String>();
        List<String> userIds = new ArrayList<String>();
        if (adapter == null)
            return blocklist;
        Cursor cursor = adapter.getCursor();
        if (cursor == null)
            return blocklist;

        for (int i = 0; i < adapter.getCount(); i++) {
            if (!postPos.get(i))
                continue;
            if (!cursor.moveToPosition(i))
                continue;
            String tripcode = cursor.getString(cursor.getColumnIndex(ChanPost.POST_TRIPCODE));
            if (tripcode != null && !tripcode.isEmpty())
                tripcodes.add(tripcode);
            String name = cursor.getString(cursor.getColumnIndex(ChanPost.POST_NAME));
            if (name != null && !name.isEmpty() && !name.equals("Anonymous"))
                names.add(name);
            String email = cursor.getString(cursor.getColumnIndex(ChanPost.POST_EMAIL));
            if (email != null && !email.isEmpty() && !email.equals("sage"))
                emails.add(email);
            String userId = cursor.getString(cursor.getColumnIndex(ChanPost.POST_USER_ID));
            if (userId != null && !userId.isEmpty() && !userId.equals("Heaven"))
                userIds.add(userId);
        }
        if (tripcodes.size() > 0)
            blocklist.put(ChanBlocklist.BlockType.TRIPCODE, tripcodes);
        if (names.size() > 0)
            blocklist.put(ChanBlocklist.BlockType.NAME, names);
        if (emails.size() > 0)
            blocklist.put(ChanBlocklist.BlockType.EMAIL, emails);
        if (userIds.size() > 0)
            blocklist.put(ChanBlocklist.BlockType.ID, userIds);

        return blocklist;
    }

    protected boolean translatePosts(SparseBooleanArray postPos) {
        final Locale locale = getResources().getConfiguration().locale;
        final String localeCode = locale.getLanguage();
        final String text = selectText(postPos);
        final String strippedText = text.replaceAll("<br/?>", "\n").replaceAll("<[^>]*>", "").trim();
        if (DEBUG) Log.i(TAG, "translatePosts() translating text=" + strippedText);
        String escaped;
        try {
            escaped = URLEncoder.encode(strippedText, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Unsupported encoding utf-8? You crazy!", e);
            escaped = strippedText;
        }
        if (escaped.isEmpty()) {
            Toast.makeText(getActivityContext(), R.string.translate_no_text, Toast.LENGTH_SHORT);
            return true;
        }
        String translateUrl = GOOGLE_TRANSLATE_ROOT + "/" + localeCode + "/" + escaped;
        if (translateUrl.length() > MAX_HTTP_GET_URL_LEN)
            translateUrl = translateUrl.substring(0, MAX_HTTP_GET_URL_LEN);
        if (DEBUG) Log.i(TAG, "translatePosts() launching url=" + translateUrl);
        ActivityDispatcher.launchUrlInBrowser(getActivityContext(), translateUrl);
        return true;
    }

    protected boolean playThreadMenu() {
        //NetworkProfileManager.instance().getUserStatistics().featureUsed(ChanFeature.PLAY_THREAD);
        synchronized (this) {
            shouldPlayThread = !shouldPlayThread; // user clicked, invert play status
            getActivity().invalidateOptionsMenu();
            if (!shouldPlayThread) {
                return false;
            }
            if (!canPlayThread()) {
                shouldPlayThread = false;
                Toast.makeText(getActivityContext(), R.string.thread_no_start_play, Toast.LENGTH_SHORT).show();
                return false;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (handler != null)
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                absListView.setFastScrollEnabled(false);
                            }
                        });
                    while (true) {
                        synchronized (this) {
                            if (!canPlayThread())
                                break;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (absListView == null || adapter == null)
                                        return;
                                    /*
                                    int first = absListView.getFirstVisiblePosition();
                                    int last = absListView.getLastVisiblePosition();
                                    for (int pos = first; pos <= last; pos++)
                                        expandVisibleItem(first, pos);
                                    */
                                    absListView.smoothScrollBy(2, 25);
                                }
                                /*
                                private void expandVisibleItem(int first, int pos) {
                                    View listItem = absListView.getChildAt(pos - first);
                                    View image = listItem == null ? null : listItem.findViewById(R.id.list_item_image);
                                    Cursor cursor = adapter.getCursor();
                                    //if (DEBUG) Log.i(TAG, "pos=" + pos + " listItem=" + listItem + " expandButton=" + expandButton);
                                    if (listItem != null
                                            && image != null
                                            && image.getVisibility() == View.VISIBLE
                                            && image.getHeight() > 0
                                            && cursor.moveToPosition(pos))
                                    {
                                        long id = cursor.getLong(cursor.getColumnIndex(ChanPost.POST_ID));
                                        absListView.performItemClick(image, pos, id);
                                    }
                                }
                                */
                            });
                        }
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    synchronized (this) {
                        shouldPlayThread = false;
                    }
                    if (handler != null)
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                absListView.setFastScrollEnabled(true);
                                getActivity().invalidateOptionsMenu();
                            }
                        });
                }
            }).start();
        }
        return true;
    }

    protected boolean canPlayThread() {
        if (shouldPlayThread == false)
            return false;
        if (absListView == null || adapter == null || adapter.getCount() <= 0)
            return false;
        //if (absListView.getLastVisiblePosition() == adapter.getCount() - 1)
        //    return false; // stop
        //It is scrolled all the way down here
        if (absListView.getLastVisiblePosition() >= absListView.getAdapter().getCount() - 1)
            return false;
        if (handler == null)
            return false;
        return true;
    }

    private void setShareIntent(final Intent intent) {
        if (ActivityDispatcher.onUIThread())
            synchronized (this) {
                if (shareActionProvider != null && intent != null)
                    shareActionProvider.setShareIntent(intent);
            }
        else if (handler != null)
            handler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (this) {
                        if (shareActionProvider != null && intent != null)
                            shareActionProvider.setShareIntent(intent);
                    }
                }
            });
    }

    public void updateSharedIntent() {
        SparseBooleanArray postPos = absListView.getCheckedItemPositions();
        if (postPos == null)
            return;
        if (DEBUG) Log.i(TAG, "updateSharedIntent() checked count=" + postPos.size());
        String linkUrl = (postNo > 0 && postNo != threadNo)
                ? ChanPost.postUrl(boardCode, threadNo, postNo)
                : ChanThread.threadUrl(boardCode, threadNo);
        String text = selectText(postPos);
        String extraText = linkUrl + (text.isEmpty() ? "" : "\n\n" + text);
        ArrayList<String> paths = new ArrayList<String>();
        Cursor cursor = adapter.getCursor();
        ImageLoader imageLoader = ChanImageLoader.getInstance(getActivityContext());
        for (int i = 0; i < absListView.getCount(); i++) {
            if (!postPos.get(i) || !cursor.moveToPosition(i))
                continue;
            File file = ThreadViewer.fullSizeImageFile(getActivityContext(), cursor); // try for full size first
            if (file == null) { // if can't find it, fall back to thumbnail
                String url = cursor.getString(cursor.getColumnIndex(ChanPost.POST_IMAGE_URL)); // thumbnail
                if (DEBUG) Log.i(TAG, "Couldn't find full image, falling back to thumbnail=" + url);
                file = (url == null || url.isEmpty()) ? null : imageLoader.getDiscCache().get(url);
            }
            if (file == null || !file.exists() || !file.canRead() || file.length() <= 0)
                continue;
            paths.add(file.getAbsolutePath());
        }
        Intent intent;
        if (paths.size() == 0) {
            intent = new Intent(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_TEXT, extraText);
            intent.setType("text/html");
            setShareIntent(intent);
        } else {
            ArrayList<Uri> uris = new ArrayList<Uri>();
            ArrayList<String> missingPaths = new ArrayList<String>();
            for (String path : paths) {
                if (checkedImageUris.containsKey(path)) {
                    Uri uri = checkedImageUris.get(path);
                    uris.add(uri);
                    if (DEBUG) Log.i(TAG, "Added uri=" + uri);
                } else {
                    uris.add(Uri.fromFile(new File(path)));
                    missingPaths.add(path);
                }
            }
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            intent.putExtra(Intent.EXTRA_TEXT, extraText);
            intent.setType("image/jpeg");
            setShareIntent(intent);
            if (missingPaths.size() > 0) {
                if (DEBUG) Log.i(TAG, "launching scanner for missing paths count=" + missingPaths.size());
                asyncUpdateSharedIntent(missingPaths);
            }
        }
    }

    protected void asyncUpdateSharedIntent(ArrayList<String> pathList) {
        String[] paths = new String[pathList.size()];
        String[] types = new String[pathList.size()];
        for (int i = 0; i < pathList.size(); i++) {
            paths[i] = pathList.get(i);
            types[i] = "image/jpeg";
        }
        MediaScannerConnection.scanFile(getActivityContext(), paths, types, mediaScannerListener);
    }

    public void onRefresh() {
        if (getActivity() != null)
            getActivity().invalidateOptionsMenu(); // in case spinner needs to be reset
        refreshThread();
        if (actionMode != null)
            actionMode.finish();
    }

    public void refreshThread() {
        refreshThread(null);
    }

    public void refreshThread(final String message) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ChanThread thread = ChanFileStorage.loadThreadData(getActivityContext(), boardCode, threadNo);
                if (thread != null && thread.isDead) {
                    if (DEBUG) Log.i(TAG, "refreshThread /" + boardCode + "/" + threadNo + " found dead thread");
                }
                if (handler != null && getActivity() != null && getActivity().getLoaderManager() != null)
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (DEBUG) Log.i(TAG, "refreshThread /" + boardCode + "/" + threadNo + " restarting loader");
                            if (getActivity() != null && getActivity().getLoaderManager() != null) {
                                getLoaderManager().restartLoader(LOADER_ID, null, loaderCallbacks);
                                if (message != null)
                                    Toast.makeText(getActivityContext(), message, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            }
        }).start();
    }

    private static final String IMAGE_SEARCH_ROOT = "http://tineye.com/search?url=";
    private static final String IMAGE_SEARCH_ROOT_ANIME = "http://iqdb.org/?url=";

    private void imageSearch(SparseBooleanArray postPos, String rootUrl) {
        String imageUrl = "";
        for (int i = 0; i < absListView.getCount(); i++) {
            if (!postPos.get(i))
                continue;
            Cursor cursor = (Cursor) adapter.getItem(i);
            if (cursor == null)
                continue;
            int flags = cursor.getInt(cursor.getColumnIndex(ChanPost.POST_FLAGS));
            if ((flags & ChanPost.FLAG_HAS_IMAGE) == 0)
                continue;
            String url = cursor.getString(cursor.getColumnIndex(ChanPost.POST_FULL_IMAGE_URL));
            if (url == null || url.isEmpty())
                continue;
            imageUrl = url;
            break;
        }
        if (imageUrl.isEmpty()) {
            Toast.makeText(getActivityContext(), R.string.full_screen_image_search_not_found, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        try {
            String encodedImageUrl = URLEncoder.encode(imageUrl, "UTF-8");
            String url =  rootUrl + encodedImageUrl;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        }
        catch (Exception e) {
            Log.e(TAG, "Couldn't do image search imageUrl=" + imageUrl, e);
            Toast.makeText(getActivityContext(), R.string.full_screen_image_search_error, Toast.LENGTH_SHORT).show();
        }
    }

    protected View.OnClickListener overflowListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            int pos = absListView.getPositionForView(v);
            if (pos >= 0) {
                absListView.setItemChecked(pos, true);
                postNo = absListView.getItemIdAtPosition(pos);
            }
            updateSharedIntent();
            PopupMenu popup = new PopupMenu(getActivityContext(), v);
            int menuId = undead() ? R.menu.thread_context_menu : R.menu.thread_dead_context_menu;
            popup.inflate(menuId);
            popup.setOnMenuItemClickListener(popupListener);
            popup.setOnDismissListener(popupDismissListener);
            MenuItem shareItem = popup.getMenu().findItem(R.id.thread_context_share_action_menu);
            shareActionProvider = shareItem == null ? null : (ShareActionProvider) shareItem.getActionProvider();
            popup.show();
        }
    };

    protected PopupMenu.OnMenuItemClickListener popupListener = new PopupMenu.OnMenuItemClickListener() {
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            long[] postNos = absListView.getCheckedItemIds();
            SparseBooleanArray postPos = absListView.getCheckedItemPositions();
            if (postNos.length == 0) {
                Toast.makeText(getActivityContext(), R.string.thread_no_posts_selected, Toast.LENGTH_SHORT).show();
                return false;
            }
            switch (item.getItemId()) {
                case R.id.post_reply_all_menu:
                    if (DEBUG) Log.i(TAG, "Post nos: " + Arrays.toString(postNos));
                    postReply(postNos);
                    return true;
                case R.id.post_reply_all_quote_menu:
                    String quotesText = selectQuoteText(postPos);
                    postReply(quotesText);
                    return true;
                case R.id.copy_text_menu:
                    String selectText = selectText(postPos);
                    copyToClipboard(selectText);
                    //(new SelectTextDialogFragment(text)).show(getFragmentManager(), SelectTextDialogFragment.TAG);
                    return true;
                case R.id.download_images_to_gallery_menu:
                    ThreadImageDownloadService.startDownloadToGalleryFolder(
                            getActivityContext(), boardCode, threadNo, postNos);
                    Toast.makeText(getActivityContext(), R.string.download_all_images_notice, Toast.LENGTH_SHORT).show();
                    return true;
                case R.id.image_search_menu:
                    imageSearch(postPos, IMAGE_SEARCH_ROOT);
                    return true;
                case R.id.anime_image_search_menu:
                    imageSearch(postPos, IMAGE_SEARCH_ROOT_ANIME);
                    return true;
                case R.id.translate_posts_menu:
                    return translatePosts(postPos);
                case R.id.delete_posts_menu:
                    (new DeletePostDialogFragment(boardCode, threadNo, postNos))
                            .show(getFragmentManager(), DeletePostDialogFragment.TAG);
                    return true;
                case R.id.report_posts_menu:
                    (new ReportPostDialogFragment(boardCode, threadNo, postNos))
                            .show(getFragmentManager(), ReportPostDialogFragment.TAG);
                    return true;
                case R.id.block_posts_menu:
                    Map<ChanBlocklist.BlockType, List<String>> blocklist = extractBlocklist(postPos);
                    (new BlocklistSelectToAddDialogFragment(blocklist)).show(
                            getActivity().getFragmentManager(), TAG);
                    return true;
                case R.id.web_menu:
                    String url = ChanPost.postUrl(boardCode, threadNo, postNos[0]);
                    ActivityDispatcher.launchUrlInBrowser(getActivityContext(), url);
                default:
                    return false;
            }
        }
    };

    protected PopupMenu.OnDismissListener popupDismissListener = new PopupMenu.OnDismissListener() {
        @Override
        public void onDismiss(PopupMenu menu) {
        }
    };

    protected LoaderManager.LoaderCallbacks<Cursor> loaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            if (DEBUG) Log.i(TAG, "onCreateLoader /" + boardCode + "/" + threadNo + " id=" + id);
            setProgress(true);
            boolean showRelatedBoards;
            if (onTablet())
                showRelatedBoards = false;
            else showRelatedBoards = true;
            return new ThreadCursorLoader(getActivityContext(), boardCode, threadNo, query, showRelatedBoards);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            if (DEBUG) Log.i(TAG, "onLoadFinished /" + boardCode + "/" + threadNo + " id=" + loader.getId()
                    + " count=" + (data == null ? 0 : data.getCount()) + " loader=" + loader);
            onThreadLoadFinished(data);
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            if (DEBUG) Log.i(TAG, "onLoaderReset /" + boardCode + "/" + threadNo + " id=" + loader.getId());
            adapter.swapCursor(null);
        }
    };

    protected Context getActivityContext() {
        return getActivity();
    }

    protected ChanIdentifiedActivity getChanActivity() {
        return (ChanIdentifiedActivity)getActivity();
    }
    
    protected ActionMode.Callback actionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            if (DEBUG) Log.i(TAG, "onCreateActionMode");
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.thread_context_menu, menu);
            MenuItem shareItem = menu.findItem(R.id.thread_context_share_action_menu);
            if (shareItem != null) {
                shareActionProvider = (ShareActionProvider) shareItem.getActionProvider();
            } else {
                shareActionProvider = null;
            }
            mode.setTitle(R.string.thread_context_select);
            actionMode = mode;
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            if (DEBUG) Log.i(TAG, "onPrepareActionMode");
            updateSharedIntent();
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            long[] postNos = absListView.getCheckedItemIds();
            SparseBooleanArray postPos = absListView.getCheckedItemPositions();
            if (postNos.length == 0) {
                Toast.makeText(getActivityContext(), R.string.thread_no_posts_selected, Toast.LENGTH_SHORT).show();
                return false;
            }

            switch (item.getItemId()) {
                case R.id.post_reply_all_menu:
                    if (DEBUG) Log.i(TAG, "Post nos: " + Arrays.toString(postNos));
                    postReply(postNos);
                    return true;
                case R.id.post_reply_all_quote_menu:
                    String quotesText = selectQuoteText(postPos);
                    postReply(quotesText);
                    return true;
                case R.id.copy_text_menu:
                    String selectText = selectText(postPos);
                    copyToClipboard(selectText);
                    //(new SelectTextDialogFragment(text)).show(getFragmentManager(), SelectTextDialogFragment.TAG);
                    return true;
                case R.id.download_images_to_gallery_menu:
                    ThreadImageDownloadService.startDownloadToGalleryFolder(
                            getActivityContext(), boardCode, threadNo, postNos);
                    Toast.makeText(getActivityContext(), R.string.download_all_images_notice, Toast.LENGTH_SHORT).show();
                    return true;
                case R.id.image_search_menu:
                    imageSearch(postPos, IMAGE_SEARCH_ROOT);
                    return true;
                case R.id.anime_image_search_menu:
                    imageSearch(postPos, IMAGE_SEARCH_ROOT_ANIME);
                    return true;
                case R.id.translate_posts_menu:
                    return translatePosts(postPos);
                case R.id.delete_posts_menu:
                    (new DeletePostDialogFragment(boardCode, threadNo, postNos))
                            .show(getFragmentManager(), DeletePostDialogFragment.TAG);
                    return true;
                case R.id.report_posts_menu:
                    (new ReportPostDialogFragment(boardCode, threadNo, postNos))
                            .show(getFragmentManager(), ReportPostDialogFragment.TAG);
                    return true;
                case R.id.block_posts_menu:
                    Map<ChanBlocklist.BlockType, List<String>> blocklist = extractBlocklist(postPos);
                    (new BlocklistSelectToAddDialogFragment(blocklist))
                            .show(getActivity().getFragmentManager(), TAG);
                    return true;
                case R.id.web_menu:
                    String url = ChanPost.postUrl(boardCode, threadNo, postNos[0]);
                    ActivityDispatcher.launchUrlInBrowser(getActivityContext(), url);
                default:
                    return false;
            }
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            SparseBooleanArray positions = absListView.getCheckedItemPositions();
            if (DEBUG) Log.i(TAG, "onDestroyActionMode checked size=" + positions.size());
            for (int i = 0; i < absListView.getCount(); i++) {
                if (positions.get(i)) {
                    absListView.setItemChecked(i, false);
                }
            }
            actionMode = null;
        }
    };

    protected MediaScannerConnection.OnScanCompletedListener mediaScannerListener
            = new MediaScannerConnection.MediaScannerConnectionClient()
    {
        @Override
        public void onMediaScannerConnected() {}
        @Override
        public void onScanCompleted(String path, Uri uri) {
            if (DEBUG) Log.i(TAG, "Scan completed for path=" + path + " result uri=" + uri);
            if (uri == null)
                uri = Uri.parse(path);
            checkedImageUris.put(path, uri);
            updateSharedIntent();
        }
    };

    @Override
    public void showDialog(String boardCode, long threadNo, long postNo, int pos, ThreadPopupDialogFragment.PopupType popupType) {
        if (DEBUG) Log.i(TAG, "showDialog /" + boardCode + "/" + threadNo + "#p" + postNo + " pos=" + pos);
        if (popupType == ThreadPopupDialogFragment.PopupType.SELF)
            (new ThreadSinglePopupDialogFragment(boardCode, threadNo, postNo, pos))
                    .show(getFragmentManager(), ThreadPopupDialogFragment.TAG);
        else
            (new ThreadPopupDialogFragment(boardCode, threadNo, postNo, pos, popupType))
                    .show(getFragmentManager(), ThreadPopupDialogFragment.TAG);
    }

    public String toString() {
        return "ThreadFragment[] " + getChanActivityId().toString();
    }

    protected AbstractBoardCursorAdapter.ViewBinder viewBinder = new AbstractBoardCursorAdapter.ViewBinder() {
        @Override
        public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
            return ThreadViewer.setViewValue(view, cursor, boardCode,
                    true,
                    0,
                    0,
                    threadListener.thumbOnClickListener,
                    threadListener.backlinkOnClickListener,
                    imagesOnClickListener,
                    threadListener.repliesOnClickListener,
                    threadListener.sameIdOnClickListener,
                    threadListener.exifOnClickListener,
                    //postReplyListener,
                    overflowListener,
                    threadListener.expandedImageListener,
                    threadListener.itemBoardLinkListener,
                    startActionModeListener
            );
        }
    };

    protected void displaySearchTitle() {
        displayTitleBar(getString(R.string.search_results_title), R.drawable.search, R.drawable.search_light);
        int resultsId = adapter != null && adapter.getCount() > 0
                ? R.string.board_search_results
                : R.string.board_search_no_results;
        String results = String.format(getString(resultsId), query);
        TextView searchResultsTextView = (TextView)boardSearchResultsBar.findViewById(R.id.board_search_results_text);
        searchResultsTextView.setText(results);
        boardSearchResultsBar.setVisibility(View.VISIBLE);
    }

    protected void displayTitleBar(String title, int lightIconId, int darkIconId) {
        if (boardTitleBar == null)
            return;
        TextView boardTitle = (TextView)boardTitleBar.findViewById(R.id.board_title_text);
        ImageView boardIcon = (ImageView)boardTitleBar.findViewById(R.id.board_title_icon);
        if (boardTitle == null || boardIcon == null)
            return;
        boardTitle.setText(title);
        boolean isDark = ThemeSelector.instance(getActivity().getApplicationContext()).isDark();
        int drawableId = isDark ? lightIconId : darkIconId;
        int alpha = isDark ? DRAWABLE_ALPHA_DARK : DRAWABLE_ALPHA_LIGHT;
        if (drawableId > 0) {
            boardIcon.setImageResource(drawableId);
            boardIcon.setAlpha(alpha);
        }
        boardTitleBar.setVisibility(View.VISIBLE);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.thread_menu, menu);
        ThreadActivity activity = (ThreadActivity)getActivity();
        if (activity != null)
            activity.createSearchView(menu);
        super.onCreateOptionsMenu(menu, menuInflater);
    }


}
