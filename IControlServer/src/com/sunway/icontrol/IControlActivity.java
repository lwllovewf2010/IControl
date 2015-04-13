package com.sunway.icontrol;

import android.view.View;
import android.content.Intent;
import android.app.Activity;
import android.os.Bundle;

public class IControlConfigActivity extends Activity
{
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
    }
    
    public void start(View v) {
        startService(new Intent("com.sunway.icontrol"));
    }

    public void stop(View v) {
        stopService(new Intent("com.sunway.icontrol"));        
    }
}
