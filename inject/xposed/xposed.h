#ifndef XPOSED_H_
#define XPOSED_H_

#define XPOSED_JAR XPOSED_DIR "bin/XposedBridge.jar"

#define XPOSED_CLASS "de/robv/android/xposed/XposedBridge"
#define XPOSED_CLASS_DOTS "de.robv.android.xposed.XposedBridge"

#ifndef ALOGD
#define ALOGD LOGD
#define ALOGE LOGE
#define ALOGI LOGI
#define ALOGV LOGV
#endif

struct XposedHookInfo {
    struct {
        Method originalMethod;
        // copy a few bytes more than defined for Method in AOSP
        // to accomodate for (rare) extensions by the target ROM
        int dummyForRomExtensions[4];
    } originalMethodStruct;

    Object* reflectedMethod;
    Object* additionalInfo;
};

// called directoy by app_process
static void xposedPrepareSubclassReplacement(jclass clazz);
bool xposedOnVmCreated(JNIEnv* env, const char* className);
static bool xposedInitMemberOffsets(JNIEnv* env);
static inline void xposedSetObjectArrayElement(const ArrayObject* obj, int index, Object* val);

// handling hooked methods / helpers
static void xposedCallHandler(const u4* args, JValue* pResult, const Method* method, ::Thread* self);
static inline bool xposedIsHooked(const Method* method);

// JNI methods
static jboolean de_robv_android_xposed_XposedBridge_initNative(JNIEnv* env, jclass clazz);
static void de_robv_android_xposed_XposedBridge_hookMethodNative(JNIEnv* env, jclass clazz, jobject reflectedMethodIndirect,
            jobject declaredClassIndirect, jint slot, jobject additionalInfoIndirect);
static void de_robv_android_xposed_XposedBridge_invokeOriginalMethodNative(const u4* args, JValue* pResult, const Method* method, ::Thread* self);
static jobject de_robv_android_xposed_XposedBridge_getStartClassName(JNIEnv* env, jclass clazz);
static void de_robv_android_xposed_XposedBridge_setObjectClassNative(JNIEnv* env, jclass clazz, jobject objIndirect, jclass clzIndirect);
static void de_robv_android_xposed_XposedBridge_dumpObjectNative(JNIEnv* env, jclass clazz, jobject objIndirect);
static jobject de_robv_android_xposed_XposedBridge_cloneToSubclassNative(JNIEnv* env, jclass clazz, jobject objIndirect, jclass clzIndirect);

static int register_de_robv_android_xposed_XposedBridge(JNIEnv* env);
#endif  // XPOSED_H_
