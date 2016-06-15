package info.blockchain.wallet.app_rate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.format.DateUtils;

import info.blockchain.wallet.ui.helpers.ToastCustom;

import piuk.blockchain.android.R;

public class AppRate implements android.content.DialogInterface.OnClickListener, OnCancelListener {

    private static final String TAG = "AppRater";

    public static final String SHARED_PREFS_NAME = "apprate_prefs";
    public static final String PREF_APP_HAS_CRASHED = "pref_app_has_crashed";
    public static final String PREF_DATE_REMIND_START = "date_remind_start";
    public static final String PREF_TRANSACTION_COUNT = "transaction_count";
    public static final String PREF_DONT_SHOW_AGAIN = "dont_show_again";

    private Activity hostActivity;
    private OnClickListener clickListener;
    private SharedPreferences preferences;

    private long minTransactionsUntilPrompt = 0;
    private long minDaysUntilPrompt = 0;

    public AppRate(Activity hostActivity) {
        this.hostActivity = hostActivity;
        preferences = hostActivity.getSharedPreferences(AppRate.SHARED_PREFS_NAME, 0);
    }

    public AppRate setMinTransactionsUntilPrompt(long minTransactionsUntilPrompt) {
        this.minTransactionsUntilPrompt = minTransactionsUntilPrompt;
        return this;
    }

    public AppRate setMinDaysUntilPrompt(long minDaysUntilPrompt) {
        this.minDaysUntilPrompt = minDaysUntilPrompt;
        return this;
    }

    /**
     * Reset all the data collected about number of transactions and days until first launch.
     * @param context A context.
     */
    public static void reset(Context context) {
        context.getSharedPreferences(AppRate.SHARED_PREFS_NAME, 0).edit().clear().commit();
    }

    /**
     * Display the rate dialog if needed.
     */
    public void init() {

        if (preferences.getBoolean(AppRate.PREF_DONT_SHOW_AGAIN, false) || (
                preferences.getBoolean(AppRate.PREF_APP_HAS_CRASHED, false))) {
            return;
        }

        SharedPreferences.Editor editor = preferences.edit();
        long transactionCount = preferences.getLong(AppRate.PREF_TRANSACTION_COUNT, 0);

        // Get date of 'remind me later'.
        Long dateRemindStart = preferences.getLong(AppRate.PREF_DATE_REMIND_START, 0);
        if (dateRemindStart == 0) {
            dateRemindStart = System.currentTimeMillis();
            editor.putLong(AppRate.PREF_DATE_REMIND_START, dateRemindStart);
        }

        // Show the rate dialog if needed.
        if (transactionCount >= minTransactionsUntilPrompt) {
            if (System.currentTimeMillis() >= dateRemindStart + (minDaysUntilPrompt * DateUtils.DAY_IN_MILLIS)) {
                showDefaultDialog();
            }
        }

        editor.commit();
    }

    public AppRate incrementTransactionCount(){

        SharedPreferences.Editor editor = preferences.edit();
        long transactionCount = preferences.getLong(AppRate.PREF_TRANSACTION_COUNT, 0) + 1;
        editor.putLong(AppRate.PREF_TRANSACTION_COUNT, transactionCount);
        return this;
    }

    /**
     * Shows the default rate dialog.
     * @return
     */
    private void showDefaultDialog() {

        String title = hostActivity.getString(R.string.rate_title);
        String message = hostActivity.getString(R.string.rate_message);
        String rate = hostActivity.getString(R.string.rate_yes);
        String remindLater = hostActivity.getString(R.string.rate_later);
        String dismiss = hostActivity.getString(R.string.rate_no);

        new AlertDialog.Builder(hostActivity)
                .setIcon(R.drawable.ic_launcher)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(rate, this)
                .setNegativeButton(dismiss, this)
                .setNeutralButton(remindLater, this)
                .setOnCancelListener(this)
                .create().show();
    }

    @Override
    public void onCancel(DialogInterface dialog) {

        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(AppRate.PREF_DATE_REMIND_START, System.currentTimeMillis());
        editor.putLong(AppRate.PREF_TRANSACTION_COUNT, 0);
        editor.commit();
    }

    /**
     * @param onClickListener A listener to be called back on.
     * @return This {@link AppRate} object to allow chaining.
     */
    public AppRate setOnClickListener(OnClickListener onClickListener){
        clickListener = onClickListener;
        return this;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {

        SharedPreferences.Editor editor = preferences.edit();

        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                try
                {
                    hostActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + hostActivity.getPackageName())));
                }catch (ActivityNotFoundException e) {
                    ToastCustom.makeText(hostActivity,"No Play Store installed on device",ToastCustom.LENGTH_LONG,ToastCustom.TYPE_ERROR);
                }
                editor.putBoolean(AppRate.PREF_DONT_SHOW_AGAIN, true);
                break;

            case DialogInterface.BUTTON_NEGATIVE:
                editor.putBoolean(AppRate.PREF_DONT_SHOW_AGAIN, true);
                break;

            case DialogInterface.BUTTON_NEUTRAL:

                setMinDaysUntilPrompt(7);
                editor.putLong(AppRate.PREF_DATE_REMIND_START, System.currentTimeMillis());
                editor.putLong(AppRate.PREF_TRANSACTION_COUNT, 0);

                break;

            default:
                break;
        }

        editor.commit();
        dialog.dismiss();

        if(clickListener != null){
            clickListener.onClick(dialog, which);
        }
    }
}