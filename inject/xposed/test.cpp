#define ANDROID_SMP 0

#include "Dalvik.h"
#include <utils/Log.h>
#include <android_runtime/AndroidRuntime.h>
#include <stdio.h>
#include <sys/mman.h>
#include <cutils/properties.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <dlfcn.h>

using namespace android;

void* hook_entry_internal(void*) {
    ALOGE("sunway:hook_entry_internal:1");
    JNIEnv *jni_env = NULL;
    JavaVM *jvm = AndroidRuntime::getJavaVM();
    jvm->AttachCurrentThread(&jni_env, NULL);
    ALOGE("sunway:hook_entry_internal:2");
    jclass class_loader_clazz = jni_env->FindClass("java/lang/ClassLoader");
    jmethodID getSystemClassLoader_method = jni_env->GetStaticMethodID(class_loader_clazz, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject system_class_loader = jni_env->CallStaticObjectMethod(class_loader_clazz, getSystemClassLoader_method);

    ALOGE("sunway:hook_entry_internal:3");
    jclass pathloader_clazz = jni_env->FindClass("dalvik/system/PathClassLoader");
    ALOGE("sunway:hook_entry_internal:3.1");
    jmethodID pathloader_init_method = jni_env->GetMethodID(pathloader_clazz, "<init>",
                                                          "(Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    ALOGE("sunway:hook_entry_internal:3.2");
    jstring jar_file = jni_env->NewStringUTF("/system/framework/xposed_bridge_lite.jar");
    jobject path_loader_obj = jni_env->NewObject(pathloader_clazz, pathloader_init_method, jar_file, system_class_loader);

    ALOGE("sunway:hook_entry_internal:4");
    jmethodID loadClass_method = jni_env->GetMethodID(pathloader_clazz, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring class_name = jni_env->NewStringUTF("de.robv.android.xposed.XposedBridge");
    jclass entry_class = static_cast<jclass>(jni_env->CallObjectMethod(path_loader_obj, loadClass_method, class_name));

    jmethodID invoke_method = jni_env->GetStaticMethodID(entry_class, "main", "()V");
    ALOGE("sunway:hook_entry_internal:5");
    jni_env->CallStaticObjectMethod(entry_class, invoke_method, 0);
    ALOGE("sunway:hook_entry_internal end");
    // jvm->DetachCurrentThread();
    return 0;
}


extern "C" {
    int hook_entry(char * not_used) {
        ALOGE("sunway:hook_entry");
        hook_entry_internal(NULL);
        // pthread_t tid;
        // pthread_create(&tid, NULL, hook_entry_internal, NULL);
        return 0;
    }
}
