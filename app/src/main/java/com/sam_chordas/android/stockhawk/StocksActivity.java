package com.sam_chordas.android.stockhawk;

import android.app.LoaderManager;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;

import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.sam_chordas.android.stockhawk.data.QuoteColumns;
import com.sam_chordas.android.stockhawk.data.QuoteProvider;
import com.sam_chordas.android.stockhawk.rest.QuoteCursorAdapter;
import com.sam_chordas.android.stockhawk.rest.RecyclerViewItemClickListener;
import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.PeriodicTask;
import com.google.android.gms.gcm.Task;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class StocksActivity extends AppCompatActivity implements
        LoaderManager.LoaderCallbacks<Cursor>, RecyclerViewItemClickListener.OnItemClickListener {

    private static final int CURSOR_LOADER_ID = 0;
    private final String EXTRA_CHANGE_UNITS = "EXTRA_CHANGE_UNITS";
    public static final int CHANGE_UNITS_DOLLARS = 0;
    public static final int CHANGE_UNITS_PERCENTAGES = 1;

    private int mChangeUnits = CHANGE_UNITS_DOLLARS;
    private QuoteCursorAdapter mAdapter;

    @Bind(R.id.recycler_view)
    RecyclerView mRecyclerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stocks);
        ButterKnife.bind(this);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }

        if (savedInstanceState == null) {
            // The intent service is for executing immediate pulls from the Yahoo API
            // GCMTaskService can only schedule tasks, they cannot execute immediately
            Intent stackServiceIntent = new Intent(this, StockIntentService.class);
            // Run the initialize task service so that some stocks appear upon an empty database
            stackServiceIntent.putExtra(StockIntentService.EXTRA_TAG, StockIntentService.ACTION_INIT);
            if (isNetworkAvailable()) {
                startService(stackServiceIntent);
            } else {
                networkToast();
            }
        } else {
            mChangeUnits = savedInstanceState.getInt(EXTRA_CHANGE_UNITS);
        }

        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.addOnItemTouchListener(new RecyclerViewItemClickListener(this, this));

        mAdapter = new QuoteCursorAdapter(this, null, mChangeUnits);
        mRecyclerView.setAdapter(mAdapter);
        getLoaderManager().initLoader(CURSOR_LOADER_ID, null, this);

        ItemTouchHelper.Callback callback = new ItemTouchHelperCallback(mAdapter);
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(callback);
        itemTouchHelper.attachToRecyclerView(mRecyclerView);

        if (isNetworkAvailable()) {
            // Create a periodic task to pull stocks once every hour after the app has been opened.
            // This is so Widget data stays up to date.
            PeriodicTask periodicTask = new PeriodicTask.Builder()
                    .setService(StockTaskService.class)
                    .setPeriod(/* 1h */ 60 * 60)
                    .setFlex(/* 10s */ 10)
                    .setTag(StockTaskService.TAG_PERIODIC)
                    .setRequiredNetwork(Task.NETWORK_STATE_CONNECTED)
                    .setRequiresCharging(false)
                    .build();
            // Schedule task with tag "periodic." This ensure that only the stocks present in the DB
            // are updated.
            GcmNetworkManager.getInstance(this).schedule(periodicTask);
        }
    }


    @Override
    public void onResume() {
        super.onResume();
        getLoaderManager().restartLoader(CURSOR_LOADER_ID, null, this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.stocks_activity, menu);
        return true;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_CHANGE_UNITS, mChangeUnits);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_change_units) {
            if (mChangeUnits == CHANGE_UNITS_DOLLARS) {
                mChangeUnits = CHANGE_UNITS_PERCENTAGES;
            } else {
                mChangeUnits = CHANGE_UNITS_DOLLARS;
            }
            mAdapter.setChangeUnits(mChangeUnits);
            mAdapter.notifyDataSetChanged();
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        // This narrows the return to only the stocks that are most current.
        return new CursorLoader(this, QuoteProvider.Quotes.CONTENT_URI,
                new String[]{QuoteColumns._ID, QuoteColumns.SYMBOL, QuoteColumns.BIDPRICE,
                        QuoteColumns.PERCENT_CHANGE, QuoteColumns.CHANGE, QuoteColumns.ISUP},
                QuoteColumns.ISCURRENT + " = ?",
                new String[]{"1"},
                null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mAdapter.swapCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }

    @OnClick(R.id.fab)
    public void showDialogForAddingStock() {
        if (isNetworkAvailable()) {
            new MaterialDialog.Builder(StocksActivity.this).title(R.string.symbol_search)
                    .content(R.string.content_test)
                    .inputType(InputType.TYPE_CLASS_TEXT)
                    .input(R.string.input_hint, R.string.input_pre_fill,
                            new MaterialDialog.InputCallback() {
                                @Override
                                public void onInput(@NonNull MaterialDialog dialog, CharSequence input) {
                                    addStockQuote(input.toString());
                                }
                            })
                    .show();
        } else {
            networkToast();
        }
    }

    @Override
    public void onItemClick(View v, int position) {

    }

    private void addStockQuote(String stockQuote) {
        // On FAB click, receive user input. Make sure the stock doesn't already exist
        // in the DB and proceed accordingly.
        Cursor cursor = getContentResolver().query(QuoteProvider.Quotes.CONTENT_URI,
                new String[]{QuoteColumns.SYMBOL},
                QuoteColumns.SYMBOL + "= ?",
                new String[]{stockQuote},
                null);

        if (cursor != null && cursor.getCount() != 0) {
            Toast toast = Toast.makeText(StocksActivity.this, R.string.stock_already_saved,
                    Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, Gravity.CENTER, 0);
            toast.show();
        } else {
            Intent stockIntentService = new Intent(this,
                    StockIntentService.class);
            stockIntentService.putExtra(StockIntentService.EXTRA_TAG, StockIntentService.ACTION_ADD);
            stockIntentService.putExtra(StockIntentService.EXTRA_SYMBOL, stockQuote);
            startService(stockIntentService);
        }

        if (cursor != null) {
            cursor.close();
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        return activeNetwork != null &&
                activeNetwork.isConnectedOrConnecting();
    }

    private void networkToast() {
        Toast.makeText(this, getString(R.string.network_toast),
                Toast.LENGTH_SHORT).show();
    }
}
