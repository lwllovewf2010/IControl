package com.sunway.icontrol;

import android.os.IBinder;

interface IControlClient {
    void onSensorChanged(int type, in float[] values);
}
