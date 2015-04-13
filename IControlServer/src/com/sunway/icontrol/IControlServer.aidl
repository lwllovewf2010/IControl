package com.sunway.icontrol;

import android.os.IBinder;

interface IControlServer {
    void registerClient(IBinder b);
    boolean isStarted();
}
