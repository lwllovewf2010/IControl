package de.robv.android.xposed;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookConstructor;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static de.robv.android.xposed.XposedHelpers.setStaticObjectField;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.AndroidAppHelper;
import android.app.LoadedApk;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.android.internal.os.RuntimeInit;
import com.android.internal.os.ZygoteInit;

import dalvik.system.PathClassLoader;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.callbacks.XCallback;

public final class XposedBridge {
    private static boolean disableHooks = false;

    private static final Object[] EMPTY_ARRAY = new Object[0];
    public static final ClassLoader BOOTCLASSLOADER = ClassLoader.getSystemClassLoader();

    private static final Map<Member, CopyOnWriteSortedSet<XC_MethodHook>> sHookedMethodCallbacks = new HashMap<Member, CopyOnWriteSortedSet<XC_MethodHook>>();
    private static final CopyOnWriteSortedSet<XC_LoadPackage> sLoadedPackageCallbacks = new CopyOnWriteSortedSet<XC_LoadPackage>();

    /**
     * Called when native methods and other things are initialized, but before preloading classes etc.
     */
    private static void main(String[] args) {
        log("XposedBridge: start");
        String startClassName = getStartClassName();

        try {
            if (initNative()) {
                if (startClassName == null) {
                    initXbridgeZygote();
                }
            } else {
                log("Errors during native Xposed initialization");
            }
        } catch (Throwable t) {
            log("Errors during Xposed initialization");
            log(t);
            disableHooks = true;
        }

        // call the original startup code
        if (startClassName == null)
            ZygoteInit.main(args);
        else
            RuntimeInit.main(args);
    }

    private static native String getStartClassName();

    private static void initXbridgeZygote() throws Throwable {
        final HashSet<String> loadedPackagesInProcess = new HashSet<String>(1);

        // normal process initialization (for new Activity, Service,
        // BroadcastReceiver etc.)
        log("initXbridgeZygote");
        findAndHookMethod("android.app.ContextImpl", BOOTCLASSLOADER, "getSystemService", "java.lang.String", new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String serviceName = (String)param.args[0];
                    log("getSystemService for "+serviceName);
                    if (serviceName.equals("sensor")) {
                        param.setResult(new FakeSensorManager());   
                    }
                }
            });
    }

    public synchronized static void log(String text) {
        Log.e("sunway", text);
    }

    public synchronized static void log(Throwable t) {
        Log.e("sunway", Log.getStackTraceString(t));
    }

    /**
     * Hook any method with the specified callback
     *
     * @param hookMethod The method to be hooked
     * @param callback
     */
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (!(hookMethod instanceof Method) && !(hookMethod instanceof Constructor<?>)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked: " + hookMethod.toString());
        } else if (hookMethod.getDeclaringClass().isInterface()) {
            throw new IllegalArgumentException("Cannot hook interfaces: " + hookMethod.toString());
        } else if (Modifier.isAbstract(hookMethod.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + hookMethod.toString());
        }

        boolean newMethod = false;
        CopyOnWriteSortedSet<XC_MethodHook> callbacks;
        synchronized (sHookedMethodCallbacks) {
            callbacks = sHookedMethodCallbacks.get(hookMethod);
            if (callbacks == null) {
                callbacks = new CopyOnWriteSortedSet<XC_MethodHook>();
                sHookedMethodCallbacks.put(hookMethod, callbacks);
                newMethod = true;
            }
        }
        callbacks.add(callback);
        if (newMethod) {
            Class<?> declaringClass = hookMethod.getDeclaringClass();
            int slot = (int) getIntField(hookMethod, "slot");

            Class<?>[] parameterTypes;
            Class<?> returnType;
            if (hookMethod instanceof Method) {
                parameterTypes = ((Method) hookMethod).getParameterTypes();
                returnType = ((Method) hookMethod).getReturnType();
            } else {
                parameterTypes = ((Constructor<?>) hookMethod).getParameterTypes();
                returnType = null;
            }

            AdditionalHookInfo additionalInfo = new AdditionalHookInfo(callbacks, parameterTypes, returnType);
            hookMethodNative(hookMethod, declaringClass, slot, additionalInfo);
        }

        return callback.new Unhook(hookMethod);
    }

    /**
     * Removes the callback for a hooked method
     * @param hookMethod The method for which the callback should be removed
     * @param callback The reference to the callback as specified in {@link #hookMethod}
     */
    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        CopyOnWriteSortedSet<XC_MethodHook> callbacks;
        synchronized (sHookedMethodCallbacks) {
            callbacks = sHookedMethodCallbacks.get(hookMethod);
            if (callbacks == null)
                return;
        }
        callbacks.remove(callback);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<XC_MethodHook.Unhook>();
        for (Member method : hookClass.getDeclaredMethods())
            if (method.getName().equals(methodName))
                unhooks.add(hookMethod(method, callback));
        return unhooks;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<XC_MethodHook.Unhook>();
        for (Member constructor : hookClass.getDeclaredConstructors())
            unhooks.add(hookMethod(constructor, callback));
        return unhooks;
    }

    /**
     * This method is called as a replacement for hooked methods.
     */
    private static Object handleHookedMethod(Member method, int originalMethodId, Object additionalInfoObj,
                                             Object thisObject, Object[] args) throws Throwable {
        AdditionalHookInfo additionalInfo = (AdditionalHookInfo) additionalInfoObj;

        if (disableHooks) {
            try {
                return invokeOriginalMethodNative(method, originalMethodId, additionalInfo.parameterTypes,
                                                  additionalInfo.returnType, thisObject, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        Object[] callbacksSnapshot = additionalInfo.callbacks.getSnapshot();
        final int callbacksLength = callbacksSnapshot.length;
        if (callbacksLength == 0) {
            try {
                return invokeOriginalMethodNative(method, originalMethodId, additionalInfo.parameterTypes,
                                                  additionalInfo.returnType, thisObject, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        MethodHookParam param = new MethodHookParam();
        param.method = method;
        param.thisObject = thisObject;
        param.args = args;

        // call "before method" callbacks
        int beforeIdx = 0;
        do {
            try {
                ((XC_MethodHook) callbacksSnapshot[beforeIdx]).beforeHookedMethod(param);
            } catch (Throwable t) {
                XposedBridge.log(t);

                // reset result (ignoring what the unexpectedly exiting callback did)
                param.setResult(null);
                param.returnEarly = false;
                continue;
            }

            if (param.returnEarly) {
                // skip remaining "before" callbacks and corresponding "after" callbacks
                beforeIdx++;
                break;
            }
        } while (++beforeIdx < callbacksLength);

        // call original method if not requested otherwise
        if (!param.returnEarly) {
            try {
                param.setResult(invokeOriginalMethodNative(method, originalMethodId,
                                                           additionalInfo.parameterTypes, additionalInfo.returnType, param.thisObject, param.args));
            } catch (InvocationTargetException e) {
                param.setThrowable(e.getCause());
            }
        }

        // call "after method" callbacks
        int afterIdx = beforeIdx - 1;
        do {
            Object lastResult =  param.getResult();
            Throwable lastThrowable = param.getThrowable();

            try {
                ((XC_MethodHook) callbacksSnapshot[afterIdx]).afterHookedMethod(param);
            } catch (Throwable t) {
                XposedBridge.log(t);

                // reset to last result (ignoring what the unexpectedly exiting callback did)
                if (lastThrowable == null)
                    param.setResult(lastResult);
                else
                    param.setThrowable(lastThrowable);
            }
        } while (--afterIdx >= 0);

        // return
        if (param.hasThrowable())
            throw param.getThrowable();
        else
            return param.getResult();
    }

    /**
     * Get notified when a package is loaded. This is especially useful to hook some package-specific methods.
     */
    public static XC_LoadPackage.Unhook hookLoadPackage(XC_LoadPackage callback) {
        synchronized (sLoadedPackageCallbacks) {
            sLoadedPackageCallbacks.add(callback);
        }
        return callback.new Unhook();
    }

    public static void unhookLoadPackage(XC_LoadPackage callback) {
        synchronized (sLoadedPackageCallbacks) {
            sLoadedPackageCallbacks.remove(callback);
        }
    }

    private native static boolean initNative();

    /**
     * Intercept every call to the specified method and call a handler function instead.
     * @param method The method to intercept
     */
    private native synchronized static void hookMethodNative(Member method, Class<?> declaringClass, int slot, Object additionalInfo);

    private native static Object invokeOriginalMethodNative(Member method, int methodId,
                                                            Class<?>[] parameterTypes, Class<?> returnType, Object thisObject, Object[] args)
        throws IllegalAccessException, IllegalArgumentException, InvocationTargetException;

    /** Old method signature to avoid crashes if only XposedBridge.jar is updated, will be removed in the next version */
    @Deprecated
    private native synchronized static void hookMethodNative(Class<?> declaringClass, int slot);

    @Deprecated
    private native static Object invokeOriginalMethodNative(Member method, Class<?>[] parameterTypes, Class<?> returnType, Object thisObject, Object[] args)
        throws IllegalAccessException, IllegalArgumentException, InvocationTargetException;

    /**
     * Basically the same as {@link Method#invoke}, but calls the original method
     * as it was before the interception by Xposed. Also, access permissions are not checked.
     *
     * @param method Method to be called
     * @param thisObject For non-static calls, the "this" pointer
     * @param args Arguments for the method call as Object[] array
     * @return The result returned from the invoked method
     * @throws NullPointerException
     *             if {@code receiver == null} for a non-static method
     * @throws IllegalAccessException
     *             if this method is not accessible (see {@link AccessibleObject})
     * @throws IllegalArgumentException
     *             if the number of arguments doesn't match the number of parameters, the receiver
     *             is incompatible with the declaring class, or an argument could not be unboxed
     *             or converted by a widening conversion to the corresponding parameter type
     * @throws InvocationTargetException
     *             if an exception was thrown by the invoked method

     */
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
        throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (args == null) {
            args = EMPTY_ARRAY;
        }

        Class<?>[] parameterTypes;
        Class<?> returnType;
        if (method instanceof Method) {
            parameterTypes = ((Method) method).getParameterTypes();
            returnType = ((Method) method).getReturnType();
        } else if (method instanceof Constructor) {
            parameterTypes = ((Constructor<?>) method).getParameterTypes();
            returnType = null;
        } else {
            throw new IllegalArgumentException("method must be of type Method or Constructor");
        }

        return invokeOriginalMethodNative(method, 0, parameterTypes, returnType, thisObject, args);
    }

    /** Framework only, don't call this from your module! */
    private static void setObjectClass(Object obj, Class<?> clazz) {
        if (obj == null)
            return;
        if (obj.getClass() != clazz.getSuperclass())
            throw new IllegalArgumentException("Cannot transfer object from " + obj.getClass() + " to " + clazz);

        setObjectClassNative(obj, clazz);
    }

    private static native void setObjectClassNative(Object obj, Class<?> clazz);
    /*package*/ static native void dumpObjectNative(Object obj);

    /** Framework only, don't call this from your module! */
    private static Object cloneToSubclass(Object obj, Class<?> targetClazz) {
        if (obj == null)
            return null;

        if (!obj.getClass().isAssignableFrom(targetClazz))
            throw new ClassCastException(targetClazz + " doesn't extend " + obj.getClass());

        return cloneToSubclassNative(obj, targetClazz);
    }

    private static native Object cloneToSubclassNative(Object obj, Class<?> targetClazz);

    public static class CopyOnWriteSortedSet<E> {
        private transient volatile Object[] elements = EMPTY_ARRAY;

        public synchronized boolean add(E e) {
            int index = indexOf(e);
            if (index >= 0)
                return false;

            Object[] newElements = new Object[elements.length + 1];
            System.arraycopy(elements, 0, newElements, 0, elements.length);
            newElements[elements.length] = e;
            Arrays.sort(newElements);
            elements = newElements;
            return true;
        }

        public synchronized boolean remove(E e) {
            int index = indexOf(e);
            if (index == -1)
                return false;

            Object[] newElements = new Object[elements.length - 1];
            System.arraycopy(elements, 0, newElements, 0, index);
            System.arraycopy(elements, index + 1, newElements, index, elements.length - index - 1);
            elements = newElements;
            return true;
        }

        private int indexOf(Object o) {
            for (int i = 0; i < elements.length; i++) {
                if (o.equals(elements[i]))
                    return i;
            }
            return -1;
        }

        public Object[] getSnapshot() {
            return elements;
        }
    }

    private static class AdditionalHookInfo {
        final CopyOnWriteSortedSet<XC_MethodHook> callbacks;
        final Class<?>[] parameterTypes;
        final Class<?> returnType;

        private AdditionalHookInfo(CopyOnWriteSortedSet<XC_MethodHook> callbacks, Class<?>[] parameterTypes, Class<?> returnType) {
            this.callbacks = callbacks;
            this.parameterTypes = parameterTypes;
            this.returnType = returnType;
        }
    }
}
