#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <stdint.h>

#include "log.h"
#include "utils.h"
#include <androidnsbypass/nsbypass.h>
#include "global_state.h"


typedef int (*Main_Function_t)(int, char**);
typedef void (*android_update_LD_LIBRARY_PATH_t)(char*);

long shared_awt_surface;

char** convert_to_char_array(JNIEnv *env, jobjectArray jstringArray) {
	int num_rows = (*env)->GetArrayLength(env, jstringArray);
	char **cArray = (char **) malloc(num_rows * sizeof(char*));
	jstring row;
	
	for (int i = 0; i < num_rows; i++) {
		row = (jstring) (*env)->GetObjectArrayElement(env, jstringArray, i);
		cArray[i] = (char*)(*env)->GetStringUTFChars(env, row, 0);
    }
	
    return cArray;
}

jobjectArray convert_from_char_array(JNIEnv *env, char **charArray, int num_rows) {
	jobjectArray resultArr = (*env)->NewObjectArray(env, num_rows, (*env)->FindClass(env, "java/lang/String"), NULL);
	jstring row;
	
	for (int i = 0; i < num_rows; i++) {
		row = (jstring) (*env)->NewStringUTF(env, charArray[i]);
		(*env)->SetObjectArrayElement(env, resultArr, i, row);
    }

	return resultArr;
}

void free_char_array(JNIEnv *env, jobjectArray jstringArray, const char **charArray) {
	int num_rows = (*env)->GetArrayLength(env, jstringArray);
	jstring row;
	
	for (int i = 0; i < num_rows; i++) {
		row = (jstring) (*env)->GetObjectArrayElement(env, jstringArray, i);
		(*env)->ReleaseStringUTFChars(env, row, charArray[i]);
	}
}

jstring convertStringJVM(JNIEnv* srcEnv, JNIEnv* dstEnv, jstring srcStr) {
    if (srcStr == NULL) {
        return NULL;
    }
    
    const char* srcStrC = (*srcEnv)->GetStringUTFChars(srcEnv, srcStr, 0);
    jstring dstStr = (*dstEnv)->NewStringUTF(dstEnv, srcStrC);
	(*srcEnv)->ReleaseStringUTFChars(srcEnv, srcStr, srcStrC);
    return dstStr;
}

jintArray convertIntArrayJVM(JNIEnv* srcEnv, JNIEnv* dstEnv, jintArray srcIntArray) {
	if (srcIntArray == NULL) {
		return NULL;
	}

	jsize len = (*srcEnv)->GetArrayLength(srcEnv, srcIntArray);
	jint* srcPtr = (*srcEnv)->GetIntArrayElements(srcEnv, srcIntArray, NULL);

	jintArray dstIntArray = (*dstEnv)->NewIntArray(dstEnv, len);
	(*dstEnv)->SetIntArrayRegion(dstEnv, dstIntArray, 0, len, srcPtr);

	(*srcEnv)->ReleaseIntArrayElements(srcEnv, srcIntArray, srcPtr, JNI_ABORT);

	return dstIntArray;
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeSurfaceAWT(JNIEnv *env, jclass clazz, jlong surface) {
	shared_awt_surface = surface;
}

JNIEXPORT jlong JNICALL Java_android_view_Surface_nativeGetBridgeSurfaceAWT(JNIEnv *env, jclass clazz) {
	return (jlong) shared_awt_surface;
}

JNIEXPORT jint JNICALL Java_android_os_OpenJDKNativeRegister_nativeRegisterNatives(JNIEnv *env, jclass clazz, jstring registerSymbol) {
	const char *register_symbol_c = (*env)->GetStringUTFChars(env, registerSymbol, 0);
	void *symbol = dlsym(RTLD_DEFAULT, register_symbol_c);
	if (symbol == NULL) {
		printf("dlsym %s failed: %s\n", register_symbol_c, dlerror());
		return -1;
	}
	
	int (*registerNativesForClass)(JNIEnv*) = symbol;
	int result = registerNativesForClass(env);
	(*env)->ReleaseStringUTFChars(env, registerSymbol, register_symbol_c);
	
	return (jint) result;
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setLdLibraryPath(JNIEnv *env, jclass clazz, jstring ldLibraryPath) {
	// jclass exception_cls = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
	
	android_update_LD_LIBRARY_PATH_t android_update_LD_LIBRARY_PATH;
	
	void *libdl_handle = dlopen("libdl.so", RTLD_LAZY);
	void *updateLdLibPath = dlsym(libdl_handle, "android_update_LD_LIBRARY_PATH");
	if (updateLdLibPath == NULL) {
		updateLdLibPath = dlsym(libdl_handle, "__loader_android_update_LD_LIBRARY_PATH");
		if (updateLdLibPath == NULL) {
			char *dl_error_c = dlerror();
			LOGE("Error getting symbol android_update_LD_LIBRARY_PATH: %s", dl_error_c);
			// (*env)->ThrowNew(env, exception_cls, dl_error_c);
		}
	}
	
	android_update_LD_LIBRARY_PATH = (android_update_LD_LIBRARY_PATH_t) updateLdLibPath;
	const char* ldLibPathUtf = (*env)->GetStringUTFChars(env, ldLibraryPath, 0);
	android_update_LD_LIBRARY_PATH(ldLibPathUtf);
	(*env)->ReleaseStringUTFChars(env, ldLibraryPath, ldLibPathUtf);
}

JNIEXPORT jboolean JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_dlopen(JNIEnv *env, jclass clazz, jstring name) {
	const char *nameUtf = (*env)->GetStringUTFChars(env, name, 0);
	void* handle = dlopen(nameUtf, RTLD_GLOBAL | RTLD_LAZY);
	if (!handle) {
		LOGE("dlopen %s failed: %s", nameUtf, dlerror());
		// If fail, attempt to use escaped namespace. This gives us access to the symbols while
		// still tricking android into loading private API libs :p

        if(!linker_ns_dlopen(nameUtf, RTLD_GLOBAL | RTLD_LAZY, app_escapeNs)){
			LOGE("escaped dlopen %s failed: %s", nameUtf, dlerror());
		} else LOGW("escaped dlopen %s success. are you sure this was supposed to be escaped?", nameUtf);
	} else {
		LOGD("dlopen %s success", nameUtf);
	}
	(*env)->ReleaseStringUTFChars(env, name, nameUtf);
	return handle != NULL;
}

JNIEXPORT jint JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_chdir(JNIEnv *env, jclass clazz, jstring nameStr) {
	const char *name = (*env)->GetStringUTFChars(env, nameStr, NULL);
	int retval = chdir(name);
	(*env)->ReleaseStringUTFChars(env, nameStr, name);
	return retval;
}

JNIEXPORT jint JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_executeBinary(JNIEnv *env, jclass clazz, jobjectArray cmdArgs) {
	jclass exception_cls = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
	jstring execFile = (*env)->GetObjectArrayElement(env, cmdArgs, 0);
	
	char *exec_file_c = (char*) (*env)->GetStringUTFChars(env, execFile, 0);
	void *exec_binary_handle = dlopen(exec_file_c, RTLD_LAZY);
	
	// (*env)->ReleaseStringUTFChars(env, ldLibraryPath, ld_library_path_c);
	(*env)->ReleaseStringUTFChars(env, execFile, exec_file_c);
	
	char *exec_error_c = dlerror();
	if (exec_error_c != NULL) {
		LOGE("Error: %s", exec_error_c);
		(*env)->ThrowNew(env, exception_cls, exec_error_c);
		return -1;
	}
	
	Main_Function_t Main_Function;
	Main_Function = (Main_Function_t) dlsym(exec_binary_handle, "main");
	
	exec_error_c = dlerror();
	if (exec_error_c != NULL) {
		LOGE("Error: %s", exec_error_c);
		(*env)->ThrowNew(env, exception_cls, exec_error_c);
		return -1;
	}
	
	int cmd_argv = (*env)->GetArrayLength(env, cmdArgs);
	char **cmd_args_c = convert_to_char_array(env, cmdArgs);
	int result = Main_Function(cmd_argv, cmd_args_c);
	free_char_array(env, cmdArgs, cmd_args_c);
	return result;
}

JNIEnv* get_attached_env(JavaVM* jvm) {
    JNIEnv *jvm_env = NULL;
    jint env_result = (*jvm)->GetEnv(jvm, (void**)&jvm_env, JNI_VERSION_1_4);
    if(env_result == JNI_EDETACHED) {
        env_result = (*jvm)->AttachCurrentThread(jvm, &jvm_env, NULL);
    }
    if(env_result != JNI_OK) {
        printf("get_attached_env failed: %i\n", env_result);
        return NULL;
    }
    return jvm_env;
}

// METHOD 2
/*
JNIEXPORT jint JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_executeForkedBinary(JNIEnv *env, jclass clazz, jobjectArray cmdArgs) {
	int x, status;
	x = fork();
	if (x > 0) {
		wait(&status);
	} else {
		execvpe();
	}
	return status;
}
*/

// WARNING: This does not release the global ref, this can be a memory leak
JNIEXPORT jstring JNICALL
Java_net_kdt_pojavlaunch_Tools_jObjectToString(JNIEnv *env, jclass clazz, jobject object) {
    if (object == NULL) {
        return NULL;
    }

    jobject globalRef = (*env)->NewGlobalRef(env, object);
    if (globalRef == NULL) {
        return NULL;
    }

    char buf[32];
    snprintf(buf, sizeof(buf), "%p", globalRef);

    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jlong JNICALL
Java_net_kdt_pojavlaunch_Tools_getJavaVMPointer(JNIEnv *env, jclass clazz) {
	JavaVM *vm;
	if ((*env)->GetJavaVM(env, &vm) != JNI_OK) {
		return -1;
	}

	return (jlong)(uintptr_t)vm;
}

/*
 * Copies packed I420 into MediaCodec's flexible-YUV input planes without
 * crossing JNI once per pixel. Handles both planar (pixelStride=1) and
 * interleaved/padded chroma layouts (commonly pixelStride=2).
 */
static int copy_i420_plane_native(const uint8_t *source, int64_t source_capacity,
                                  int source_offset, int source_limit,
                                  uint8_t *destination, int64_t destination_capacity,
                                  int destination_offset, int destination_limit,
                                  int plane_width, int plane_height,
                                  int row_stride, int pixel_stride) {
    if (source == NULL || destination == NULL || plane_width <= 0 || plane_height <= 0
            || row_stride <= 0 || pixel_stride <= 0
            || source_offset < 0 || source_limit < source_offset
            || source_limit > source_capacity
            || destination_offset < 0 || destination_limit < destination_offset
            || destination_limit > destination_capacity) {
        return 0;
    }

    int64_t source_end = (int64_t) source_offset
            + (int64_t) (plane_height - 1) * plane_width + plane_width;
    int64_t destination_last = (int64_t) destination_offset
            + (int64_t) (plane_height - 1) * row_stride
            + (int64_t) (plane_width - 1) * pixel_stride;
    if (source_end > source_limit || destination_last >= destination_limit) {
        return 0;
    }

    if (pixel_stride == 1) {
        for (int row = 0; row < plane_height; row++) {
            memcpy(destination + destination_offset + (int64_t) row * row_stride,
                   source + source_offset + (int64_t) row * plane_width,
                   (size_t) plane_width);
        }
    } else {
        for (int row = 0; row < plane_height; row++) {
            const uint8_t *src_row = source + source_offset + (int64_t) row * plane_width;
            uint8_t *dst_row = destination + destination_offset + (int64_t) row * row_stride;
            for (int col = 0; col < plane_width; col++) {
                dst_row[(int64_t) col * pixel_stride] = src_row[col];
            }
        }
    }
    return 1;
}

JNIEXPORT jboolean JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_copyI420ToYuvPlanes(
        JNIEnv *env, jclass clazz,
        jobject source_buffer, jint source_offset, jint source_limit,
        jobject y_buffer, jint y_offset, jint y_limit, jint y_row_stride, jint y_pixel_stride,
        jobject u_buffer, jint u_offset, jint u_limit, jint u_row_stride, jint u_pixel_stride,
        jobject v_buffer, jint v_offset, jint v_limit, jint v_row_stride, jint v_pixel_stride,
        jint width, jint height) {
    if (width < 2 || height < 2 || (width & 1) != 0 || (height & 1) != 0) {
        return JNI_FALSE;
    }

    uint8_t *source = (uint8_t *) (*env)->GetDirectBufferAddress(env, source_buffer);
    uint8_t *y = (uint8_t *) (*env)->GetDirectBufferAddress(env, y_buffer);
    uint8_t *u = (uint8_t *) (*env)->GetDirectBufferAddress(env, u_buffer);
    uint8_t *v = (uint8_t *) (*env)->GetDirectBufferAddress(env, v_buffer);
    jlong source_capacity = (*env)->GetDirectBufferCapacity(env, source_buffer);
    jlong y_capacity = (*env)->GetDirectBufferCapacity(env, y_buffer);
    jlong u_capacity = (*env)->GetDirectBufferCapacity(env, u_buffer);
    jlong v_capacity = (*env)->GetDirectBufferCapacity(env, v_buffer);
    if (source == NULL || y == NULL || u == NULL || v == NULL
            || source_capacity < 0 || y_capacity < 0 || u_capacity < 0 || v_capacity < 0) {
        return JNI_FALSE;
    }

    int64_t y_size = (int64_t) width * height;
    if (y_size > INT32_MAX) return JNI_FALSE;
    int chroma_width = width / 2;
    int chroma_height = height / 2;
    int u_offset_source = source_offset + (int) y_size;
    int v_offset_source = u_offset_source + (int) (y_size / 4);
    if (u_offset_source < source_offset || v_offset_source < u_offset_source) {
        return JNI_FALSE;
    }

    int ok_y = copy_i420_plane_native(source, source_capacity,
            source_offset, source_limit, y, y_capacity, y_offset, y_limit,
            width, height, y_row_stride, y_pixel_stride);
    int ok_u = copy_i420_plane_native(source, source_capacity,
            u_offset_source, source_limit, u, u_capacity, u_offset, u_limit,
            chroma_width, chroma_height, u_row_stride, u_pixel_stride);
    int ok_v = copy_i420_plane_native(source, source_capacity,
            v_offset_source, source_limit, v, v_capacity, v_offset, v_limit,
            chroma_width, chroma_height, v_row_stride, v_pixel_stride);
    return (ok_y && ok_u && ok_v) ? JNI_TRUE : JNI_FALSE;
}
