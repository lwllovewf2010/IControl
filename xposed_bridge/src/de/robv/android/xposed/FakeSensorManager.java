package de.robv.android.xposed;

import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import android.util.Log;
import android.os.Handler;
import android.hardware.SensorEventListener;
import android.hardware.TriggerEventListener;
import java.util.List;
import android.os.Looper;
import android.content.Context;
import java.util.ArrayList;
import android.hardware.Sensor;
import android.hardware.SensorManager;

public class FakeSensorManager extends SensorManager {
    private SensorManager orig;
    private Context ctx;
    public FakeSensorManager(SensorManager orig, Context ctx) {
        Log.e("sunway", "FakeSensorManager inited");
        this.orig = orig;
        this.ctx = ctx;
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
        orig.unregisterListener(listener, sensor);
    }
    
    public boolean registerListenerImpl(SensorEventListener listener, Sensor sensor,
                                        int delayUs, Handler handler, int maxBatchReportLatencyUs, int reservedFlags) {
        Log.e("sunway", "registerListener for "+sensor.toString());
        return orig.registerListener(listener, sensor, delayUs, maxBatchReportLatencyUs, handler);
        // return true;
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
