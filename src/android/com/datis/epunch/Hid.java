package com.datis.epunch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.smartcardio.ATR;
import android.smartcardio.Card;
import android.smartcardio.CardException;
import android.smartcardio.CardNotPresentException;
import android.smartcardio.CardTerminal;
import android.smartcardio.TerminalFactory;
import android.smartcardio.ipc.CardService;
import android.smartcardio.ipc.ICardService;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Hid extends CordovaPlugin {
    private static final String TAG = Hid.class.getName();
    private static final String ACTION_CONNECT_DEVICE = "connectToDevice";
    private static final String ACTION_REGISTER_LISTENER = "registerListener";
    private static final String MANAGEMENT_APP = "CardReaderManager.apk";
	private static final String MANAGEMENT_PACKAGE = "com.hidglobal.cardreadermanager";
	private static final int REQUEST_APP_INSTALL = 0xbeef;

    private ICardService mService = null;
	private TerminalFactory mFactory = null;
	private CardTerminal mReader = null;
    private AsyncTask<Void, String, Void> mReadCardTask = null;
    private Context mContext;

    private CallbackContext readCallback;
    private boolean tryConnect = false;

    @Override
    protected void pluginInitialize() {
        Log.d("pluginInitialize");
        mContext = this.cordova.getActivity().getApplicationContext();
        if (!alreadyInstalled(MANAGEMENT_PACKAGE)) {
			/* If the management App cannot be installed, further processing
			 * is impossible. */
			if (!installManagementApp()) {
				showToast("Error: unable to install the management App");
				// this.finish();
			}
		}
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        Log.d("initialize");
        super.initialize(cordova, webView);
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
        JSONObject arg_object = args.optJSONObject(0);
        if (ACTION_CONNECT_DEVICE.equals(action)) {
            mService = CardService.getInstance(mContext);
            tryConnect = true;
            return true;
        }
        return false;
    }

    @Override
	public void onDestroy() {
		super.onDestroy();

		if (mService != null) {
			mService.releaseService();
		}
	}

    private CardTerminal getFirstReader() {
		if (mFactory == null) {
			try {
				mFactory = mService.getTerminalFactory();
			} catch (Exception e) {
				Log.e(TAG, "unable to get terminal factory");
				return null;
			}
		}

		CardTerminal firstReader = null;
		try {
			/* Get the available card readers as list. */
			List<CardTerminal> readerList = mFactory.terminals().list();
			if (readerList.size() == 0) {
				return null;
			}

			/* Establish a connection with the first reader from the list. */
			firstReader = readerList.get(0);
		} catch (CardException e) {
			Log.e(TAG, e.toString());
		}
		return firstReader;
	}

    private void updateReceivedData(byte[] data) {
		if (readCallback != null) {
			PluginResult result = new PluginResult(PluginResult.Status.OK, data);
			result.setKeepCallback(true);
			readCallback.sendPluginResult(result);
		}
	}

    private boolean alreadyInstalled(String packageName) {
		try {
			PackageManager pm = mContext.getPackageManager();
			pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
			return true;
		} catch (PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	private boolean installManagementApp() {
		String cachePath = null;
		try {
			/* Copy the .apk file from the assets directory to the external
			 * cache, from where it can be installed. */
			File temp = File.createTempFile("CardReaderManager", "apk", mContext.getExternalCacheDir());
			temp.setWritable(true);
			FileOutputStream out = new FileOutputStream(temp);
			InputStream in = mContext.getResources().getAssets().open(MANAGEMENT_APP);
			byte[] buffer = new byte[1024];
			int bytes = 0;

			while ((bytes = in.read(buffer)) != -1) {
				out.write(buffer, 0, bytes);
			}
			in.close();
			out.close();
			cachePath = temp.getPath();
		} catch (IOException e) {
			return false;
		}

		/* Actual installation, calls external Activity that is shown to the
		 * user and returns with call to onActivityResult() to this Activity. */
		Intent promptInstall = new Intent(Intent.ACTION_VIEW);
		promptInstall.setDataAndType(Uri.fromFile(new File(cachePath)), "application/vnd.android.package-archive");
		((Activity) mContext).startActivityForResult(promptInstall, REQUEST_APP_INSTALL);
		return true;
	}

    private class ReadCardTask extends AsyncTask<Void, String, Void> {
		@Override
		public Void doInBackground(Void... params) {
			/* Wait until we have the reader instance. */
			while (!tryConnect) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}
				mReader = getFirstReader();
			}

			/* This is done until the button is clicked, which cancels this
			 * AsyncTask. */
			for (; !isCancelled();) {
				try {
					if (mReader.isCardPresent()) {
						/* Connect to the reader. This returns a card object.
						 * "*" indicates that either protocol T=0 or T=1 can be
						 * used. */
						Card card = mReader.connect("*");
						ATR atr = card.getATR();
						card.disconnect(true);
                        updateReceivedData(atr.getBytes());
					}
					try {
						/* Don't overtax the USB/Bluetooth connection.*/
						Thread.sleep(1000);
					} catch (InterruptedException e) {
					}
				} catch (CardException e) {
					Log.e(TAG, e.toString());
					publishProgress("Error: " + e.toString(), "");
				}
			}
			return null;
		}
	}
}
