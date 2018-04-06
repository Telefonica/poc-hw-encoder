package com.telefonica.myapplication2;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;

import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.video.VideoQuality;

//import net.majorkernelpanic.streaming.SessionBuilder;

/**
 * A straightforward example of how to stream AMR and H.263 to some public IP using libstreaming.
 * Note that this example may not be using the latest version of libstreaming !
 */
public class MainActivityRtspServer extends Activity  {

    private final static String TAG = "MainActivity";

    private Button mButton1, mButton2;
    private SurfaceView mSurfaceView;
    private EditText mEditText;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        this.mSurfaceView = (SurfaceView) findViewById(R.id.surface);

        SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(this).edit();
        edit.putString(RtspServer.KEY_PORT, String.valueOf(1234));
        edit.commit();

        MySessionBuilder.getInstance().setSurfaceView(this.mSurfaceView)
                .setPreviewOrientation(0)
                .setVideoQuality(new VideoQuality(1280, 720, 30, 1600000))
                .setContext(getApplicationContext())
                .setAudioEncoder(0)
                .setVideoEncoder(1);
        startService(new Intent(this, RtspServer.class));
    }

}
