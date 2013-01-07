/**
 * 
 */
package com.chanapps.four.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Date;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.chanapps.four.activity.R;
import com.chanapps.four.data.ChanBoard;
import com.chanapps.four.data.ChanFileStorage;
import com.chanapps.four.data.ChanHelper;
import com.chanapps.four.data.ChanThread;

/**
 * @author "Grzegorz Nittner" <grzegorz.nittner@gmail.com>
 *
 */
public class FetchChanDataService extends BaseChanService {
	private static final String TAG = FetchChanDataService.class.getSimpleName();

	protected static final long STORE_INTERVAL_MS = 2000;
    protected static final int MAX_THREAD_RETENTION_PER_BOARD = 100;
    
    protected static final long MIN_BOARD_FORCE_INTERVAL = 10000; // 10 sec
    protected static final long MIN_BOARD_FETCH_INTERVAL = 300000; // 5 min
    
    protected static final long MIN_THREAD_FORCE_INTERVAL = 10000; // 10 sec
    protected static final long MIN_THREAD_FETCH_INTERVAL = 300000; // 5 min
    
    private String boardCode;
    private int pageNo;
    private long threadNo;
    private boolean boardHandling = true;
    private boolean force;
    
    private ChanBoard board;
    private ChanThread thread;

    public static void startService(Context context, String boardCode) {
        startService(context, boardCode, 0);
    }
    
    public static void startServiceWithPriority(Context context, String boardCode) {
    	startServiceWithPriority(context, boardCode, 0);
    }

    private static void startService(Context context, String boardCode, int pageNo) {
        Log.i(TAG, "Start chan fetch service for " + boardCode + " page " + pageNo );
        Intent intent = new Intent(context, FetchChanDataService.class);
        intent.putExtra(ChanHelper.BOARD_CODE, boardCode);
        intent.putExtra(ChanHelper.PAGE, pageNo);
        context.startService(intent);
    }
    
    private static void startServiceWithPriority(Context context, String boardCode, int pageNo) {
        Log.i(TAG, "Start chan priorty fetch service for " + boardCode + " page " + pageNo );
        Intent intent = new Intent(context, FetchChanDataService.class);
        intent.putExtra(ChanHelper.BOARD_CODE, boardCode);
        intent.putExtra(ChanHelper.PAGE, pageNo);
        intent.putExtra(ChanHelper.PRIORITY_MESSAGE, 1);
        context.startService(intent);
    }
    
    public static void startService(Context context, String boardCode, long threadNo) {
        Log.i(TAG, "Start chan fetch service for " + boardCode + "/" + threadNo );
        Intent intent = new Intent(context, FetchChanDataService.class);
        intent.putExtra(ChanHelper.BOARD_CODE, boardCode);
        intent.putExtra(ChanHelper.THREAD_NO, threadNo);
        context.startService(intent);
    }

    public static void startServiceWithPriority(Context context, String boardCode, long threadNo) {
        Log.i(TAG, "Start chan priority fetch service for " + boardCode + "/" + threadNo );
        Intent intent = new Intent(context, FetchChanDataService.class);
        intent.putExtra(ChanHelper.BOARD_CODE, boardCode);
        intent.putExtra(ChanHelper.THREAD_NO, threadNo);
        intent.putExtra(ChanHelper.PRIORITY_MESSAGE, 1);
        context.startService(intent);
    }
    
    public static void clearServiceQueue(Context context) {
        Log.i(TAG, "Clearing chan fetch service queue");
        Intent intent = new Intent(context, FetchChanDataService.class);
        intent.putExtra(ChanHelper.CLEAR_FETCH_QUEUE, 1);
        context.startService(intent);
    }

    public FetchChanDataService() {
   		super("chan_fetch");
   	}

    protected FetchChanDataService(String name) {
   		super(name);
   	}
    
	@Override
	protected void onHandleIntent(Intent intent) {
		boardCode = intent.getStringExtra(ChanHelper.BOARD_CODE);
		pageNo = intent.getIntExtra(ChanHelper.PAGE, 0);
		threadNo = intent.getLongExtra(ChanHelper.THREAD_NO, 0);
		boardHandling = threadNo == 0;

		force = intent.getBooleanExtra(ChanHelper.FORCE_REFRESH, false);
		if (boardHandling) {
			Log.i(TAG, "Handling board " + boardCode + " page=" + pageNo);
	        handleBoard();
		} else {
			Log.i(TAG, "Handling thread " + boardCode + "/" + threadNo);
			handleThread();
		}
	}

	private void handleBoard() {
        if (boardCode.equals(ChanBoard.WATCH_BOARD_CODE)) {
            Log.e(TAG, "Watching board must use ChanWatchlist instead of service");
            return;
        }

		long startTime = Calendar.getInstance().getTimeInMillis();
        BufferedReader in = null;
        HttpURLConnection tc = null;
		try {
			board = ChanFileStorage.loadBoardData(getBaseContext(), boardCode);
			URL chanApi = new URL("http://api.4chan.org/" + boardCode + "/" + pageNo + ".json");
			Log.i(TAG, "Fetching board " + boardCode + " page " + pageNo);
			
            long now = (new Date()).getTime();
            if (pageNo == 0) {
                Log.i(TAG, "page 0 request, therefore resetting board.lastPage to false");
                board.lastPage = false;
            }

            if (pageNo > 0 && board.lastPage) {
                Log.i(TAG, "Board request after last page, therefore service is terminating");
                return;
            }

            if (pageNo == 0) {
                Log.i(TAG, "page 0 request, therefore resetting board.lastPage to false");
                board.lastPage = false;
                long interval = now - board.lastFetched;
                if (force && interval < MIN_BOARD_FORCE_INTERVAL) {
                    Log.i(TAG, "board is forced but interval=" + interval + " is less than min=" + MIN_BOARD_FORCE_INTERVAL + " so exiting");
                    return;
                }
                if (!force && interval < MIN_BOARD_FETCH_INTERVAL) {
                    Log.i(TAG, "board interval=" + interval + " less than min=" + MIN_BOARD_FETCH_INTERVAL + " and not force thus exiting service");
                    return;
                }
            }

            tc = (HttpURLConnection) chanApi.openConnection();
            if (board.lastFetched > 0) {
                tc.setIfModifiedSince(board.lastFetched);
            }
            Log.i(TAG, "Calling API " + tc.getURL() + " response length=" + tc.getContentLength() + " code=" + tc.getResponseCode());
            board.lastFetched = now;
            if (pageNo > 0 && tc.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                Log.i(TAG, "Got 404 on next page, assuming last page at pageNo=" + pageNo);
                board.lastPage = true;
                // @TODO: We need to handle this case so further pages are not fetched
                
            } else {
                in = new BufferedReader(new InputStreamReader(tc.getInputStream()));
            	ChanFileStorage.storeBoardFile(getBaseContext(), boardCode, pageNo, in);
                
                Log.w(TAG, "Fetched and stored " + chanApi + " in " + (Calendar.getInstance().getTimeInMillis() - startTime) + "ms");
                startTime = Calendar.getInstance().getTimeInMillis();
            }

            if (force) {
            	BoardLoadService.startServiceWithPriority(getBaseContext(), boardCode, pageNo);
            } else {
            	BoardLoadService.startService(getBaseContext(), boardCode, pageNo);
            }
            if (!board.lastPage) {
                pageNo++;
                Log.i(TAG, "Starting serivce to load next page for " + boardCode + " page " + pageNo);
                if (force) {
                	if (priorityMessageCounter == 0) {
                		// we continue fetching priority fetches only when there is no other priority fetch scheduled
                		FetchChanDataService.startServiceWithPriority(getBaseContext(), boardCode, pageNo);
                	}
                } else {
                	FetchChanDataService.startService(getBaseContext(), boardCode, pageNo);
                }
            }            
        } catch (IOException e) {
            toastUI(R.string.board_service_couldnt_read);
            Log.e(TAG, "IO Error reading Chan board json", e);
		} catch (Exception e) {
            toastUI(R.string.board_service_couldnt_load);
			Log.e(TAG, "Error parsing Chan board json", e);
		} finally {
			closeBufferAndConnection(in, tc);
		}
	}

	private void handleThread() {
		long startTime = Calendar.getInstance().getTimeInMillis();
        BufferedReader in = null;
        HttpURLConnection tc = null;
        if (threadNo == 0) {
            Log.e(TAG, "Thread loading must be done via the BoardLoadService");
        }
        if (boardCode.equals(ChanBoard.WATCH_BOARD_CODE)) {
            Log.e(TAG, "Watching board must use ChanWatchlist instead of service");
            return;
        }

		try {
            thread = ChanFileStorage.loadThreadData(this, boardCode, threadNo);
            long now = (new Date()).getTime();
            if (thread == null) {
                thread = new ChanThread();
                thread.board = boardCode;
                thread.no = threadNo;
                thread.isDead = false;
            } else {
                if (thread.isDead) {
                    Log.i(TAG, "Dead thread retrieved from storage, therefore service is terminating");
                    return;
                }
                long interval = now - thread.lastFetched;
                if (force && interval < MIN_THREAD_FORCE_INTERVAL) {
                    Log.i(TAG, "thread is forced but interval=" + interval + " is less than min=" + MIN_THREAD_FORCE_INTERVAL + " so exiting");
                    return;
                }
                if (!force && interval < MIN_THREAD_FETCH_INTERVAL) {
                    Log.i(TAG, "thread interval=" + interval + " less than min=" + MIN_THREAD_FETCH_INTERVAL + " and not force thus exiting service");
                    return;
                }
            }

            URL chanApi = new URL("http://api.4chan.org/" + boardCode + "/res/" + threadNo + ".json");
            tc = (HttpURLConnection) chanApi.openConnection();
            if (thread.lastFetched > 0) {
                tc.setIfModifiedSince(thread.lastFetched);
            }
            Log.i(TAG, "Calling API " + tc.getURL() + " response length=" + tc.getContentLength() + " code=" + tc.getResponseCode());

            thread.lastFetched = now;
            if (tc.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                Log.i(TAG, "Got 404 on thread, thread no longer exists");
                markDead();
            } else {
                Log.i(TAG, "Received api response, storing results ...");
                in = new BufferedReader(new InputStreamReader(tc.getInputStream()));
                ChanFileStorage.storeThreadFile(getBaseContext(), boardCode, threadNo, in);
            }
            
            if (force) {
            	ThreadLoadService.startServiceWithPriority(getBaseContext(), boardCode, threadNo);
            } else {
            	ThreadLoadService.startService(getBaseContext(), boardCode, threadNo);
            }

        } catch (IOException e) {
            toastUI(R.string.board_service_couldnt_read);
            Log.e(TAG, "IO Error reading Chan thread json. " + e.getMessage(), e);
		} catch (Exception e) {
            toastUI(R.string.board_service_couldnt_load);
			Log.e(TAG, "Error parsing Chan thread json. " + e.getMessage(), e);
		} finally {
			closeBufferAndConnection(in, tc);
		}
	}
	
	private void markDead() {
        thread.isDead = true;
        Log.i(TAG, "marked thread as dead, will not be loaded again");
    }

	protected void closeBufferAndConnection(BufferedReader in, HttpURLConnection tc) {
		try {
			if (in != null) {
				in.close();
			}
		    if (tc != null) {
		        tc.disconnect();
		    }
		} catch (Exception e) {
			Log.e(TAG, "Error closing reader", e);
		}
	}

}
