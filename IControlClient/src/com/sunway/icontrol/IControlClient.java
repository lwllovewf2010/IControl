package com.sunway.icontrol;

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.SensorEvent;
import android.hardware.Sensor;
import java.io.OutputStreamWriter;
import android.hardware.SensorEventListener;
import java.net.Socket;
import java.net.InetAddress;
import android.widget.EditText;
import android.view.View;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import com.sunway.icontrol.R;

public class IControlClient extends Activity implements SensorEventListener
{
    private OutputStreamWriter mWriter = null;
    private EditText mEditText;
    private SensorManager mSensorManager;
    private Sensor mSensor;
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.main);
            mEditText = (EditText)findViewById(R.id.edit);
            mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
            mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

    public void connect(View v) {
        final String server = mEditText.getText().toString();
        new Thread() {
            public void run() {
                try {
                    InetAddress serverAddr = InetAddress.getByName(server);
                    Socket server = new Socket(serverAddr, 12345);
                    mWriter = new OutputStreamWriter(server.getOutputStream());
                    Log.e("sunway","connect to server");
                }
                catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public void onSensorChanged(SensorEvent event) {
        String info = String.valueOf(event.sensor.getType());
        for (float f: event.values) {
            info=info+";"+String.valueOf(f);
        }
        if (mWriter != null) {
            try {
                Log.e("sunway", "write to client: "+info);
                mWriter.write(info+"\n");
                mWriter.flush();
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
        };
    }
   

}
