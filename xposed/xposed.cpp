/*
 * Xposed enables "god mode" for developers
 */

#define LOG_TAG "Xposed"
#define ANDROID_SMP 0
#include "Dalvik.h"
#include "xposed.h"

#include <utils/Log.h>
#include <android_runtime/AndroidRuntime.h>

#define private public
#undef private

#include <stdio.h>
#include <sys/mman.h>
#include <cutils/properties.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <dlfcn.h>

#include "xposed_offsets.h"

ClassObject* objectArrayClass = NULL;
jclass xposedClass = NULL;
Method* xposedHandleHookedMethod = NULL;
const char* startClassName = NULL;
void* PTR_gDvmJit = NULL;
size_t arrayContentsOffset = 0;

bool addXposedToClasspath(bool zygote) {
    if (access("/system/framework/xposed_bridge.jar", R_OK) == 0) {
        char* oldClassPath = getenv("BOOTCLASSPATH");
        if (oldClassPath == NULL) {
            setenv("BOOTCLASSPATH", "/system/framework/xposed_bridge.jar", 1);
        } else {
            char classPath[4096];
            int neededLength = snprintf(classPath, sizeof(classPath), "%s:%s", "/system/framework/xposed_bridge.jar", oldClassPath);
            if (neededLength >= (int)sizeof(classPath)) {
                ALOGE("ERROR: CLASSPATH would exceed %d characters", sizeof(classPath));
                return false;
            }
            setenv("BOOTCLASSPATH", classPath, 1);
        }
        return true;
    } else {
        return false;
    }
}

static void xposedPrepareSubclassReplacement(jclass clazz) {
    // clazz is supposed to replace its superclass, so make sure enough memory is allocated
    ClassObject* sub = (ClassObject*) dvmDecodeIndirectRef(dvmThreadSelf(), clazz);
    ClassObject* super = sub->super;
    super->objectSize = sub->objectSize;
}


bool xposedOnVmCreated(JNIEnv* env, const char* className) {
    startClassName = className;
    xposedInitMemberOffsets(env);

    xposedClass = env->FindClass(XPOSED_CLASS);
    xposedClass = reinterpret_cast<jclass>(env->NewGlobalRef(xposedClass));
    
    if (xposedClass == NULL) {
        ALOGE("Error while loading Xposed class '%s':\n", XPOSED_CLASS);
        dvmLogExceptionStackTrace();
        env->ExceptionClear();
        return false;
    }
    
    ALOGI("Found Xposed class '%s', now initializing\n", XPOSED_CLASS);
    if (register_de_robv_android_xposed_XposedBridge(env) != JNI_OK) {
        ALOGE("Could not register natives for '%s'\n", XPOSED_CLASS);
        env->ExceptionClear();
        return false;
    }
    return true;
}


static bool xposedInitMemberOffsets(JNIEnv* env) {
    PTR_gDvmJit = dlsym(RTLD_DEFAULT, "gDvmJit");

    if (PTR_gDvmJit == NULL) {
        offsetMode = MEMBER_OFFSET_MODE_NO_JIT;
    } else {
        offsetMode = MEMBER_OFFSET_MODE_WITH_JIT;
    }
    ALOGD("Using structure member offsets for mode %s", xposedOffsetModesDesc[offsetMode]);

    MEMBER_OFFSET_COPY(DvmJitGlobals, codeCacheFull);

    // detect offset of ArrayObject->contents
    jintArray dummyArray = env->NewIntArray(1);
    if (dummyArray == NULL) {
        ALOGE("Could allocate int array for testing");
        dvmLogExceptionStackTrace();
        env->ExceptionClear();
        return false;
    }

    jint* dummyArrayElements = env->GetIntArrayElements(dummyArray, NULL);
    arrayContentsOffset = (size_t)dummyArrayElements - (size_t)dvmDecodeIndirectRef(dvmThreadSelf(), dummyArray);
    env->ReleaseIntArrayElements(dummyArray,dummyArrayElements, 0);
    env->DeleteLocalRef(dummyArray);

    if (arrayContentsOffset < 12 || arrayContentsOffset > 128) {
        ALOGE("Detected strange offset %d of ArrayObject->contents", arrayContentsOffset);
        return false;
    }

    return true;
}

static inline void xposedSetObjectArrayElement(const ArrayObject* obj, int index, Object* val) {
    uintptr_t arrayContents = (uintptr_t)obj + arrayContentsOffset;
    ((Object **)arrayContents)[index] = val;
    dvmWriteBarrierArray(obj, index, index + 1);
}

static void xposedCallHandler(const u4* args, JValue* pResult, const Method* method, ::Thread* self) {
    if (!xposedIsHooked(method)) {
        dvmThrowNoSuchMethodError("could not find Xposed original method - how did you even get here?");
        return;
    }

    XposedHookInfo* hookInfo = (XposedHookInfo*) method->insns;
    Method* original = (Method*) hookInfo;
    Object* originalReflected = hookInfo->reflectedMethod;
    Object* additionalInfo = hookInfo->additionalInfo;
  
    // convert/box arguments
    const char* desc = &method->shorty[1]; // [0] is the return type.
    Object* thisObject = NULL;
    size_t srcIndex = 0;
    size_t dstIndex = 0;
    
    // for non-static methods determine the "this" pointer
    if (!dvmIsStaticMethod(original)) {
        thisObject = (Object*) args[0];
        srcIndex++;
    }
    
    ArrayObject* argsArray = dvmAllocArrayByClass(objectArrayClass, strlen(method->shorty) - 1, ALLOC_DEFAULT);
    if (argsArray == NULL) {
        return;
    }
    
    while (*desc != '\0') {
        char descChar = *(desc++);
        JValue value;
        Object* obj;

        switch (descChar) {
        case 'Z':
        case 'C':
        case 'F':
        case 'B':
        case 'S':
        case 'I':
            value.i = args[srcIndex++];
            obj = (Object*) dvmBoxPrimitive(value, dvmFindPrimitiveClass(descChar));
            dvmReleaseTrackedAlloc(obj, self);
            break;
        case 'D':
        case 'J':
            value.j = dvmGetArgLong(args, srcIndex);
            srcIndex += 2;
            obj = (Object*) dvmBoxPrimitive(value, dvmFindPrimitiveClass(descChar));
            dvmReleaseTrackedAlloc(obj, self);
            break;
        case '[':
        case 'L':
            obj  = (Object*) args[srcIndex++];
            break;
        default:
            ALOGE("Unknown method signature description character: %c\n", descChar);
            obj = NULL;
            srcIndex++;
        }
        xposedSetObjectArrayElement(argsArray, dstIndex++, obj);
    }
    
    // call the Java handler function
    JValue result;
    dvmCallMethod(self, xposedHandleHookedMethod, NULL, &result,
        originalReflected, (int) original, additionalInfo, thisObject, argsArray);
        
    dvmReleaseTrackedAlloc(argsArray, self);

    // exceptions are thrown to the caller
    if (dvmCheckException(self)) {
        return;
    }

    // return result with proper type
    ClassObject* returnType = dvmGetBoxedReturnType(method);
    if (returnType->primitiveType == PRIM_VOID) {
        // ignored
    } else if (result.l == NULL) {
        if (dvmIsPrimitiveClass(returnType)) {
            dvmThrowNullPointerException("null result when primitive expected");
        }
        pResult->l = NULL;
    } else {
        if (!dvmUnboxPrimitive(result.l, returnType, pResult)) {
            dvmThrowClassCastException(result.l->clazz, returnType);
        }
    }
}



////////////////////////////////////////////////////////////
// JNI methods
////////////////////////////////////////////////////////////

static jboolean de_robv_android_xposed_XposedBridge_initNative(JNIEnv* env, jclass clazz) {
    ::Thread* self = dvmThreadSelf();

    xposedHandleHookedMethod = (Method*) env->GetStaticMethodID(xposedClass, "handleHookedMethod",
        "(Ljava/lang/reflect/Member;ILjava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    if (xposedHandleHookedMethod == NULL) {
        ALOGE("ERROR: could not find method %s.handleHookedMethod(Member, int, Object, Object, Object[])\n", XPOSED_CLASS);
        dvmLogExceptionStackTrace();
        env->ExceptionClear();
        return false;
    }

    Method* xposedInvokeOriginalMethodNative = (Method*) env->GetStaticMethodID(xposedClass, "invokeOriginalMethodNative",
        "(Ljava/lang/reflect/Member;I[Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    if (xposedInvokeOriginalMethodNative == NULL) {
        ALOGE("ERROR: could not find method %s.invokeOriginalMethodNative(Member, int, Class[], Class, Object, Object[])\n", XPOSED_CLASS);
        dvmLogExceptionStackTrace();
        env->ExceptionClear();
        return false;
    }
    dvmSetNativeFunc(xposedInvokeOriginalMethodNative, de_robv_android_xposed_XposedBridge_invokeOriginalMethodNative, NULL);

    objectArrayClass = dvmFindArrayClass("[Ljava/lang/Object;", NULL);
    if (objectArrayClass == NULL) {
        ALOGE("Error while loading Object[] class");
        dvmLogExceptionStackTrace();
        env->ExceptionClear();
        return false;
    }

    return true;
}

static void de_robv_android_xposed_XposedBridge_hookMethodNative(JNIEnv* env, jclass clazz, jobject reflectedMethodIndirect,
            jobject declaredClassIndirect, jint slot, jobject additionalInfoIndirect) {
    // Usage errors?
    if (declaredClassIndirect == NULL || reflectedMethodIndirect == NULL) {
        dvmThrowIllegalArgumentException("method and declaredClass must not be null");
        return;
    }
    
    // Find the internal representation of the method
    ClassObject* declaredClass = (ClassObject*) dvmDecodeIndirectRef(dvmThreadSelf(), declaredClassIndirect);
    Method* method = dvmSlotToMethod(declaredClass, slot);
    if (method == NULL) {
        dvmThrowNoSuchMethodError("could not get internal representation for method");
        return;
    }
    
    if (xposedIsHooked(method)) {
        // already hooked
        return;
    }
    
    // Save a copy of the original method and other hook info
    XposedHookInfo* hookInfo = (XposedHookInfo*) calloc(1, sizeof(XposedHookInfo));
    memcpy(hookInfo, method, sizeof(hookInfo->originalMethodStruct));
    hookInfo->reflectedMethod = dvmDecodeIndirectRef(dvmThreadSelf(), env->NewGlobalRef(reflectedMethodIndirect));
    hookInfo->additionalInfo = dvmDecodeIndirectRef(dvmThreadSelf(), env->NewGlobalRef(additionalInfoIndirect));

    // Replace method with our own code
    SET_METHOD_FLAG(method, ACC_NATIVE);
    method->nativeFunc = &xposedCallHandler;
    method->insns = (const u2*) hookInfo;
    method->registersSize = method->insSize;
    method->outsSize = 0;

    if (PTR_gDvmJit != NULL) {
        // reset JIT cache
        char currentValue = *((char*)PTR_gDvmJit + MEMBER_OFFSET_VAR(DvmJitGlobals,codeCacheFull));
        if (currentValue == 0 || currentValue == 1) {
            MEMBER_VAL(PTR_gDvmJit, DvmJitGlobals, codeCacheFull) = true;
        } else {
            ALOGE("Unexpected current value for codeCacheFull: %d", currentValue);
        }
    }
}

static inline bool xposedIsHooked(const Method* method) {
    return (method->nativeFunc == &xposedCallHandler);
}

// simplified copy of Method.invokeNative, but calls the original (non-hooked) method and has no access checks
// used when a method has been hooked
static void de_robv_android_xposed_XposedBridge_invokeOriginalMethodNative(const u4* args, JValue* pResult,
            const Method* method, ::Thread* self) {
    Method* meth = (Method*) args[1];
    if (meth == NULL) {
        meth = dvmGetMethodFromReflectObj((Object*) args[0]);
        if (xposedIsHooked(meth)) {
            meth = (Method*) meth->insns;
        }
    }
    ArrayObject* params = (ArrayObject*) args[2];
    ClassObject* returnType = (ClassObject*) args[3];
    Object* thisObject = (Object*) args[4]; // null for static methods
    ArrayObject* argList = (ArrayObject*) args[5];

    // invoke the method
    pResult->l = dvmInvokeMethod(thisObject, meth, argList, params, returnType, true);
    return;
}

static jobject de_robv_android_xposed_XposedBridge_getStartClassName(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF(startClassName);
}

static void de_robv_android_xposed_XposedBridge_setObjectClassNative(JNIEnv* env, jclass clazz, jobject objIndirect, jclass clzIndirect) {
    Object* obj = (Object*) dvmDecodeIndirectRef(dvmThreadSelf(), objIndirect);
    ClassObject* clz = (ClassObject*) dvmDecodeIndirectRef(dvmThreadSelf(), clzIndirect);
    if (clz->status < CLASS_INITIALIZED && !dvmInitClass(clz)) {
        ALOGE("Could not initialize class %s", clz->descriptor);
        return;
    }
    obj->clazz = clz;
}

static void de_robv_android_xposed_XposedBridge_dumpObjectNative(JNIEnv* env, jclass clazz, jobject objIndirect) {
    Object* obj = (Object*) dvmDecodeIndirectRef(dvmThreadSelf(), objIndirect);
    dvmDumpObject(obj);
}

static jobject de_robv_android_xposed_XposedBridge_cloneToSubclassNative(JNIEnv* env, jclass clazz, jobject objIndirect, jclass clzIndirect) {
    Object* obj = (Object*) dvmDecodeIndirectRef(dvmThreadSelf(), objIndirect);
    ClassObject* clz = (ClassObject*) dvmDecodeIndirectRef(dvmThreadSelf(), clzIndirect);

    jobject copyIndirect = env->AllocObject(clzIndirect);
    if (copyIndirect == NULL)
        return NULL;

    Object* copy = (Object*) dvmDecodeIndirectRef(dvmThreadSelf(), copyIndirect);
    size_t size = obj->clazz->objectSize;
    size_t offset = sizeof(Object);
    memcpy((char*)copy + offset, (char*)obj + offset, size - offset);

    if (IS_CLASS_FLAG_SET(clz, CLASS_ISFINALIZABLE))
        dvmSetFinalizable(copy);

    return copyIndirect;
}

static const JNINativeMethod xposedMethods[] = {
    {"getStartClassName", "()Ljava/lang/String;", (void*)de_robv_android_xposed_XposedBridge_getStartClassName},
    {"initNative", "()Z", (void*)de_robv_android_xposed_XposedBridge_initNative},
    {"hookMethodNative", "(Ljava/lang/reflect/Member;Ljava/lang/Class;ILjava/lang/Object;)V", (void*)de_robv_android_xposed_XposedBridge_hookMethodNative},
    {"setObjectClassNative", "(Ljava/lang/Object;Ljava/lang/Class;)V", (void*)de_robv_android_xposed_XposedBridge_setObjectClassNative},
    {"dumpObjectNative", "(Ljava/lang/Object;)V", (void*)de_robv_android_xposed_XposedBridge_dumpObjectNative},
    {"cloneToSubclassNative", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", (void*)de_robv_android_xposed_XposedBridge_cloneToSubclassNative},
};

static int register_de_robv_android_xposed_XposedBridge(JNIEnv* env) {
    return env->RegisterNatives(xposedClass, xposedMethods, NELEM(xposedMethods));
}
