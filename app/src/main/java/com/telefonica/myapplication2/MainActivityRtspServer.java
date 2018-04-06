package com.telefonica.myapplication2;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.TextView;

import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.video.VideoQuality;


/**
 * A straightforward example of how to stream AMR and H.263 to some public IP using libstreaming.
 * Note that this example may not be using the latest version of libstreaming !
 */
public class MainActivityRtspServer extends Activity implements Session.Callback {

    private final static String TAG = "MainActivity";

    private SurfaceView mSurfaceView;
    private TextView mTextBitrate;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);
        this.mSurfaceView = (SurfaceView) findViewById(R.id.surface);
        mTextBitrate = (TextView) findViewById(R.id.bitrate);

        SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(this).edit();
        edit.putString(RtspServer.KEY_PORT, String.valueOf(1234));
        edit.commit();

        MySessionBuilder.getInstance().setSurfaceView(this.mSurfaceView)
                .setPreviewOrientation(0)
                .setVideoQuality(new VideoQuality(1280, 720, 30, 5000000))
                .setContext(getApplicationContext())
                .setAudioEncoder(0)
                .setVideoEncoder(1)
                .setCallback(this);
        startService(new Intent(this, RtspServer.class));
    }

    @Override
    public void onBitrateUpdate(long bitrate) {
        Log.d(TAG, "Bitrate: " + bitrate);
        mTextBitrate.setText(""+bitrate/1000+" kbps");
    }

    @Override
    public void onSessionError(int message, int streamType, Exception e) {
        if (e != null) {
            logError(e.getMessage());
        }
    }

    @Override
    public void onPreviewStarted() {
        Log.d(TAG, "Preview started.");
    }

    @Override
    public void onSessionConfigured() {
        Log.d(TAG, "Preview configured.");
    }

    @Override
    public void onSessionStarted() {
        Log.d(TAG, "Session started.");
    }

    @Override
    public void onSessionStopped() {
        Log.d(TAG, "Session stopped.");
    }


    /**
     * Displays a popup to report the eror to the user
     */
    private void logError(final String msg) {
        final String error = (msg == null) ? "Error unknown" : msg;
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivityRtspServer.this);
        builder.setMessage(error).setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
            }
        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

}
