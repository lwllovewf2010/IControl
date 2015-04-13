package com.sunway.icontrol;

import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.Socket;
import java.net.ServerSocket;
import android.util.Log;
import java.io.BufferedReader;
import java.util.ArrayList;
import android.content.Intent;
import android.os.IBinder;
import android.app.Service;


public class IControlService extends Service {
    private boolean mIsStarted = false;
    private ArrayList<IControlClient> mClients = new ArrayList<IControlClient>();
    private IControlServer.Stub mBinder = new IControlServer.Stub() {
            public void registerClient(IBinder client) {
                mClients.add(IControlClient.Stub.asInterface(client));
            }
            public boolean isStarted() {
                return mIsStarted;
            }
        };
    
    @Override  
    public IBinder onBind(Intent intent) {  
        return mBinder;
    }  
      
    @Override  
    public void onCreate() {  
        super.onCreate();
    }  
      
    @Override  
    public void onStart(Intent intent, int startId) {  
        super.onStart(intent, startId);
        startServer();
    }  
      
    @Override  
    public int onStartCommand(Intent intent, int flags, int startId) {  
        return super.onStartCommand(intent, flags, startId);  
    }

    void startServer() {
        mIsStarted = true;
        new Thread() {
            public void run() {
                Log.e("sunway", "start tcp server");
                try {
                    ServerSocket ss = new ServerSocket(12345);
                    Socket client = ss.accept();
                    BufferedReader reader=new BufferedReader(new InputStreamReader(client.getInputStream()));
                    Log.e("sunway", "client connected from "+client.getInetAddress());
                    String line = reader.readLine();
                    while (line != null) {
                        String[] sp = line.split(";");
                        int type = Integer.parseInt(sp[0]);
                        float[] values = new float[sp.length-1];
                        for (int i=0;i<sp.length-1;++i) {
                            values[i] = Float.parseFloat(sp[i+1]);
                        }
                        dispatchSensorEvent(type, values);
                    }
                }
                catch (Throwable e) {
                    e.printStackTrace();
                }                
            }
        }.start();
    }

    void dispatchSensorEvent (int type, float[] value) {
        for(IControlClient client: mClients) {
            try {
                client.onSensorChanged(type, value);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }
}  
