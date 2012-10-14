package com.chanapps.four.test;

import java.util.Date;
import java.util.List;

import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.SimpleCursorAdapter;

import com.chanapps.four.data.ChanBoard;
import com.chanapps.four.data.ChanThread;
import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;

public class ThreadListActivity extends ListActivity {
	public static final String TAG = ThreadListActivity.class.getSimpleName();
	
	public static class MyCursorAdapter extends SimpleCursorAdapter {
		ImageLoader imageLoader = null;
	    DisplayImageOptions options = null;
	    
		public MyCursorAdapter(Context context, int layout, Cursor c, String[] from, int[] to,
				ImageLoader imageLoader, DisplayImageOptions options) {
			super(context, layout, c, from, to);
			this.imageLoader = imageLoader;
			this.options = options;
		}
		
		@Override
		public void setViewImage(ImageView v, String value) {
			try {
				this.imageLoader.displayImage(value, v, options);
	        } catch (NumberFormatException nfe) {
	            v.setImageURI(Uri.parse(value));
	        }
		}
	}
	
	ImageLoader imageLoader = null;
    DisplayImageOptions options = null;
    MyCursorAdapter adapter = null;
    MatrixCursor cursor = new MatrixCursor(new String[] {"_id", "image_url", "text"});
    long lastUpdate = 0;
    String boardCode = null;
    int threadId = 0;

    final Handler handler = new Handler() {
        public void handleMessage(Message msg) {
        	Log.i(TAG, "Notifying adapter change for " + msg.arg1);
    		adapter.notifyDataSetChanged();
    		if (new Date().getTime() - lastUpdate > 500) {
    			Log.i(TAG, "######## Updating list view for " + msg.arg1);
    			getListView().requestLayout();
    		}
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        
        options = new DisplayImageOptions.Builder()
//			.showImageForEmptyUri(R.drawable.stub_image)
			.cacheOnDisc()
			.imageScaleType(ImageScaleType.EXACT)
			.build();

        imageLoader = ImageLoader.getInstance();
        imageLoader.init(ImageLoaderConfiguration.createDefault(getApplicationContext()));

        adapter = new MyCursorAdapter(getApplicationContext(),
                R.layout.thread_activity_list_item,
                cursor,
                new String[] {"image_url", "text"},
                new int[] {R.id.list_item_image, R.id.list_item_text},
        		imageLoader, options);
        
        setListAdapter(adapter);
        startManagingCursor(cursor);

        Intent intent = getIntent();
        Uri data = intent.getData();
        if (data == null) {
            data = Uri.parse("android://api.chanapps.com/board/sp/thread/26837084");
        }
        List<String> pathSegments = data.getPathSegments();
        if (pathSegments.size() == 4) {
            setBoardCode(pathSegments.get(1));
            threadId = Integer.parseInt(pathSegments.get(3));
        }
        else {
            return;
        }

        lastUpdate = new Date().getTime();
        
        Thread thread = new Thread()
        {
            @Override
            public void run() {
            	ChanThread chanThread = new ChanThread();
            	chanThread.cursor = cursor;
                chanThread.loadChanThread(handler, boardCode, threadId);
                refresh();
            }
            
            private void refresh() {
                runOnUiThread(new Runnable() {
                	@Override
                    public void run() {
                        // Bind to our new adapter.
                		Log.i(TAG, "Data loaded ...");
                		adapter.notifyDataSetChanged();
                		getListView().requestLayout();
                	}
                });
            }
        };
        thread.start();
        
        // We'll define a custom screen layout here (the one shown above), but
        // typically, you could just use the standard ListActivity layout.
        setContentView(R.layout.thread_activity_list_layout);

        // Query for all people contacts using the Contacts.People convenience class.
        // Put a managed wrapper around the retrieved cursor so we don't have to worry about
        // requerying or closing it as the activity changes state.
        startManagingCursor(cursor);
        

        // Now create a new list adapter bound to the cursor.
        // SimpleListAdapter is designed for binding to a Cursor.
        adapter = new MyCursorAdapter(this,
                R.layout.thread_activity_list_item,
                cursor,
                new String[] {"image_url", "text"},
                new int[] {R.id.list_item_image, R.id.list_item_text},
                imageLoader, options);

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                int pageNo = 0;
                Uri uri = Uri.parse("android://api.chanapps.com/board/" + boardCode + "/page/" + pageNo);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PackageManager packageManager = getPackageManager();
                List<ResolveInfo> activities = packageManager.queryIntentActivities(intent, 0);
                boolean isIntentSafe = activities.size() > 0;
                if (isIntentSafe) {
                    Log.i(TAG, "Received click, calling intent " + uri + " ...");
                    startActivity(intent);
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.i(TAG, "onCreateOptionsMenu called");
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.thread_list_menu, menu);
        return true;
    }

    private void setBoardCode(String code) {
        boardCode = code;
        if (getActionBar() != null) {
            getActionBar().setTitle("/" + boardCode + " thread");
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }


}
