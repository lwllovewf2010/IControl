package de.robv.android.xposed;

import android.hardware.SensorEvent;
import android.hardware.SensorEvent;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import android.util.Log;
import android.os.Handler;
import android.hardware.SystemSensorManager;
import android.hardware.SensorEventListener;
import android.hardware.TriggerEventListener;
import java.util.List;
import android.os.Looper;
import android.content.Context;
import java.util.ArrayList;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import com.sunway.icontrol.IControlServer;
import com.sunway.icontrol.IControlClient;

class SensorEventListenerInfo {
    public SensorEventListener mListener;
    public Sensor mSensor;
    public Handler mHandler;
};

public class FakeSensorManager extends SensorManager {
    private SensorManager mOrig;
    private Context mCtx;
    private Handler mHandler;
    private IControlServer mServer;

    private ArrayList<SensorEventListenerInfo>[] mEventListeners = new ArrayList[21];
    
    private IControlClient.Stub mClient = new IControlClient.Stub() {
            public void onSensorChanged(int type, float[] values) {
                for (SensorEventListenerInfo info: mEventListeners[type]) {
                    final SensorEventListener listener = info.mListener;

                    try {
                        Constructor ctor =Class.forName("android.hardware.SensorEvent").getDeclaredConstructor(int.class);
                        ctor.setAccessible(true);
                        final SensorEvent event =(SensorEvent)(ctor.newInstance(values.length));
                        event.sensor = info.mSensor;
                        for (int i=0;i<values.length;++i) {
                            event.values[i] = values[i];
                        }
                        Handler handler = info.mHandler;
                        if (handler == null) {
                            handler = mHandler;
                        }
                        handler.post(new Runnable() {
                                public void run() {
                                    listener.onSensorChanged(event);
                                }
                            });
                    }
                    catch (Throwable e) {
                        e.printStackTrace();
                    }
                }
            }
        };
    
    private ServiceConnection mConnection = new ServiceConnection() {  
            public void onServiceConnected(ComponentName className, IBinder service) {  
                Log.e("sunway", "connect service");  
                mServer = IControlServer.Stub.asInterface(service);
                try {
                    mServer.registerClient(mClient);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }  
            public void onServiceDisconnected(ComponentName className) {  
                Log.e("sunway","disconnect service");  
                mServer = null;  
            }  
        };  

    private void bindService() {
        Intent intent = new Intent("com.sunway.icontrol");
        mCtx.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }
    
    public FakeSensorManager(SensorManager orig, Context ctx) {
        Log.e("sunway", "FakeSensorManager inited");
        this.mOrig = orig;

        try {
            SystemSensorManager tmp = (SystemSensorManager)orig;
            Field type = tmp.getClass().getDeclaredField("mMainLooper");
            type.setAccessible(true);
            mHandler = new Handler((Looper)(type.get(tmp)));
        }
        catch (Throwable e) {
            e.printStackTrace();
        }

        this.mCtx = ctx;

        for (int i=0;i<21;++i) {
            mEventListeners[i] = new ArrayList<SensorEventListenerInfo>();
        }
        
        bindService();
    }
    
    public List<Sensor> getFullSensorList() {
        Log.e("sunway","getFullSensorList");
        ArrayList<Sensor> ret = new ArrayList<Sensor>();
        try {
            Constructor ctor =Class.forName("android.hardware.Sensor").getDeclaredConstructor();
            ctor.setAccessible(true);
            Sensor s=(Sensor)(ctor.newInstance());
            
            Field type = s.getClass().getDeclaredField("mType");
            type.setAccessible(true);
            type.setInt(s,1);

            Field name = s.getClass().getDeclaredField("mName");
            name.setAccessible(true);
            name.set(s,"fake accelerometer sensor");
            ret.add(s);

            Log.e("sunway", "add fake sensor:"+s.toString());
            s=(Sensor)(ctor.newInstance());
            type.setInt(s,3);
            name.set(s,"fake orientation sensor");
            ret.add(s);
            Log.e("sunway", "add fake sensor:"+s.toString());
        } catch (Throwable e) {
            Log.e("sunway", Log.getStackTraceString(e));
        }
        return ret;
    }
    
    public void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {
        // orig.unregisterListener(listener, sensor);
    }
    
    public boolean registerListenerImpl(SensorEventListener listener, Sensor sensor,
                                        int delayUs, Handler handler, int maxBatchReportLatencyUs, int reservedFlags) {
        Log.e("sunway", "registerListener for "+sensor.toString());
        // return orig.registerListener(listener, sensor, delayUs,
        // maxBatchReportLatencyUs, handler);

        SensorEventListenerInfo  info = new SensorEventListenerInfo();
        info.mListener = listener;
        info.mSensor = sensor;
        info.mHandler = handler;

        mEventListeners[sensor.getType()].add(info);
        
        return true;
    }
    
    public boolean flushImpl(SensorEventListener listener) {
        return true;
    }
    
    public boolean requestTriggerSensorImpl(TriggerEventListener listener,
                                            Sensor sensor) {
        return false;
    }
    
    public boolean cancelTriggerSensorImpl(TriggerEventListener listener,
                                           Sensor sensor, boolean disable) {
        return false;
    }

}
