#include <jni.h>
#include <assert.h>
#include <dlfcn.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>

#include <EGL/egl.h>
#include <GL/osmesa.h>
#include "ctxbridges/osmesa_loader.h"
#include <androidnsbypass/nsbypass_t.h>
#include <androidnsbypass/nsbypass.h>
#ifdef GLES_TEST
#include <GLES2/gl2.h>
#endif

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <string.h>
#include <environ/environ.h>
#include <android/dlext.h>
#include "utils.h"
#include "ctxbridges/bridge_tbl.h"
#include "ctxbridges/osm_bridge.h"

#define GLFW_CLIENT_API 0x22001
/* Consider GLFW_NO_API as Vulkan API */
#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x30001

// This means that the function is an external API and that it will be used
#define EXTERNAL_API __attribute__((used))
// This means that you are forced to have this function/variable for ABI compatibility
#define ABI_COMPAT __attribute__((unused))


struct PotatoBridge {

    /* EGLContext */ void* eglContext;
    /* EGLDisplay */ void* eglDisplay;
    /* EGLSurface */ void* eglSurface;
/*
    void* eglSurfaceRead;
    void* eglSurfaceDraw;
*/
};
EGLConfig config;
struct PotatoBridge potatoBridge;

#include "ctxbridges/egl_loader.h"
#include "ctxbridges/osmesa_loader.h"

#define RENDERER_GL4ES 1
#define RENDERER_VK_ZINK 2
#define RENDERER_VULKAN 4

EXTERNAL_API void pojavTerminate() {
    printf("EGLBridge: Terminating\n");

    switch (pojav_environ->config_renderer) {
        case RENDERER_GL4ES: {
            eglMakeCurrent_p(potatoBridge.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface_p(potatoBridge.eglDisplay, potatoBridge.eglSurface);
            eglDestroyContext_p(potatoBridge.eglDisplay, potatoBridge.eglContext);
            eglTerminate_p(potatoBridge.eglDisplay);
            eglReleaseThread_p();

            potatoBridge.eglContext = EGL_NO_CONTEXT;
            potatoBridge.eglDisplay = EGL_NO_DISPLAY;
            potatoBridge.eglSurface = EGL_NO_SURFACE;
        } break;

            //case RENDERER_VIRGL:
        case RENDERER_VK_ZINK: {
            // Nothing to do here
        } break;
    }
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow(JNIEnv* env, ABI_COMPAT jclass clazz, jobject surface) {
    pojav_environ->pojavWindow = ANativeWindow_fromSurface(env, surface);
    if(br_setup_window != NULL) br_setup_window();
}


JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass clazz) {
    ANativeWindow_release(pojav_environ->pojavWindow);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setFrameCaptureEnabled(JNIEnv* env, jclass clazz, jboolean enabled) {
    if (pojav_environ->config_renderer == RENDERER_VK_ZINK)
        osm_set_frame_capture_enabled(enabled == JNI_TRUE);
    else if (pojav_environ->config_renderer == RENDERER_GL4ES)
        gl_set_frame_capture_enabled(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_convertRgbaToI420(JNIEnv* env, jclass clazz,
        jobject source, jint sourceOffset, jint width, jint height, jint rowStridePixels,
        jobject destination, jint destinationOffset) {
    if (source == NULL || destination == NULL || sourceOffset < 0 || destinationOffset < 0
            || width < 2 || height < 2 || (width & 1) != 0 || (height & 1) != 0
            || rowStridePixels < width) return JNI_FALSE;

    uint8_t* src = (uint8_t*) (*env)->GetDirectBufferAddress(env, source);
    uint8_t* dst = (uint8_t*) (*env)->GetDirectBufferAddress(env, destination);
    jlong srcCapacity = (*env)->GetDirectBufferCapacity(env, source);
    jlong dstCapacity = (*env)->GetDirectBufferCapacity(env, destination);
    if (src == NULL || dst == NULL || srcCapacity <= 0 || dstCapacity <= 0) return JNI_FALSE;

    uint64_t sourceBytes = ((uint64_t) (height - 1) * (uint64_t) rowStridePixels
            + (uint64_t) width) * 4U;
    uint64_t ySize = (uint64_t) width * (uint64_t) height;
    uint64_t outputBytes = ySize + ySize / 2U;
    if ((uint64_t) sourceOffset + sourceBytes > (uint64_t) srcCapacity
            || (uint64_t) destinationOffset + outputBytes > (uint64_t) dstCapacity
            || sourceBytes > SIZE_MAX || outputBytes > SIZE_MAX) return JNI_FALSE;

    const uint8_t* input = src + sourceOffset;
    uint8_t* yPlane = dst + destinationOffset;
    uint8_t* uPlane = yPlane + ySize;
    uint8_t* vPlane = uPlane + ySize / 4U;

    /*
     * Process each 2x2 pixel block once. The previous implementation walked
     * the complete RGBA frame once for luma and a second time for chroma.
     * Reusing the four loaded pixels here cuts redundant source reads and
     * keeps the I420 output byte-for-byte equivalent to the same BT.601 math.
     */
    const size_t sourceRowBytes = (size_t) rowStridePixels * 4U;
    const size_t chromaWidth = (size_t) width / 2U;
    for (int y = 0; y < height; y += 2) {
        const uint8_t* row0 = input + (size_t) y * sourceRowBytes;
        const uint8_t* row1 = row0 + sourceRowBytes;
        uint8_t* yOut0 = yPlane + (size_t) y * (size_t) width;
        uint8_t* yOut1 = yOut0 + width;
        size_t chromaRow = (size_t) (y / 2) * chromaWidth;

        for (int x = 0; x < width; x += 2) {
            const uint8_t* p0 = row0 + (size_t) x * 4U;
            const uint8_t* p1 = p0 + 4U;
            const uint8_t* p2 = row1 + (size_t) x * 4U;
            const uint8_t* p3 = p2 + 4U;

            int r0 = p0[0], g0 = p0[1], b0 = p0[2];
            int r1 = p1[0], g1 = p1[1], b1 = p1[2];
            int r2 = p2[0], g2 = p2[1], b2 = p2[2];
            int r3 = p3[0], g3 = p3[1], b3 = p3[2];

            size_t xIndex = (size_t) x;
            yOut0[xIndex] = (uint8_t) (((66 * r0 + 129 * g0 + 25 * b0 + 128) >> 8) + 16);
            yOut0[xIndex + 1U] = (uint8_t) (((66 * r1 + 129 * g1 + 25 * b1 + 128) >> 8) + 16);
            yOut1[xIndex] = (uint8_t) (((66 * r2 + 129 * g2 + 25 * b2 + 128) >> 8) + 16);
            yOut1[xIndex + 1U] = (uint8_t) (((66 * r3 + 129 * g3 + 25 * b3 + 128) >> 8) + 16);

            int red = (r0 + r1 + r2 + r3 + 2) >> 2;
            int green = (g0 + g1 + g2 + g3 + 2) >> 2;
            int blue = (b0 + b1 + b2 + b3 + 2) >> 2;
            size_t chromaIndex = chromaRow + (size_t) x / 2U;
            uPlane[chromaIndex] = (uint8_t) (((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128);
            vPlane[chromaIndex] = (uint8_t) (((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128);
        }
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_copyLatestFrame(JNIEnv* env, jclass clazz,
                                                         jobject destination, jintArray info) {
    if ((pojav_environ->config_renderer != RENDERER_VK_ZINK
            && pojav_environ->config_renderer != RENDERER_GL4ES)
            || destination == NULL || info == NULL || (*env)->GetArrayLength(env, info) < 4)
        return JNI_FALSE;
    void* address = (*env)->GetDirectBufferAddress(env, destination);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, destination);
    if (address == NULL || capacity <= 0) return JNI_FALSE;
    jint metadata[4] = {0, 0, 0, 0};
    bool result = pojav_environ->config_renderer == RENDERER_VK_ZINK
            ? osm_copy_latest_frame(address, (size_t) capacity, metadata)
            : gl_copy_latest_frame(address, (size_t) capacity, metadata);
    jboolean copied = result ? JNI_TRUE : JNI_FALSE;
    // Metadata is also returned when the supplied buffer is too small, allowing
    // Java to size a correctly-sized direct buffer and retry the snapshot.
    (*env)->SetIntArrayRegion(env, info, 0, 4, metadata);
    return copied;
}

EXTERNAL_API void* pojavGetCurrentContext() {
    return br_get_current();
}

static void *g_vulkan_ptr = NULL;
static struct android_namespace_t* vulkanLoaderNs;
void* load_turnip_vulkan() {
    if(getenv("POJAV_LOAD_TURNIP") == NULL) return NULL;
    if(g_vulkan_ptr) return g_vulkan_ptr;

    const char* native_dir = getenv("POJAV_NATIVEDIR");
    const char* cache_dir = getenv("TMPDIR");
    vulkanLoaderNs = private_create_namespace(
            "vulkan-loader-NS",
            NULL,
            NULL,
            ANDROID_NAMESPACE_TYPE_SHARED_ISOLATED,
            NULL,
            NULL,
            __builtin_return_address(0)
            );
    // Load the hook so it's first to the symbol table and thus hooks
    linker_ns_dlopen("liblinkerhook.so", RTLD_LOCAL | RTLD_NOW, vulkanLoaderNs);
    // Grants the namespace access to system libs.
    private_link_namespaces_all_libs(vulkanLoaderNs, get_escape_namespace());
    #if defined(__aarch64__) || defined(__x86_64__)
    #define VULKAN_LOADER_PATH "/system/lib64/libvulkan.so"
    #elif defined(__arm__) || defined(__i386__)
    #define VULKAN_LOADER_PATH "/system/lib/libvulkan.so"
    #endif
    return linker_ns_dlopen_unique(
            VULKAN_LOADER_PATH,
            cache_dir,
            RTLD_LOCAL | RTLD_NOW,
            vulkanLoaderNs);
}

static void set_vulkan_ptr(void* ptr) {
    char envval[64];
    sprintf(envval, "%"PRIxPTR, (uintptr_t)ptr);
    g_vulkan_ptr = ptr;
    setenv("VULKAN_PTR", envval, 1);
}

void load_vulkan() {
    if(android_get_device_api_level() >= 28) { // the loader does not support below that
#ifdef ADRENO_POSSIBLE
        void* result = load_turnip_vulkan();
        if(result != NULL) {
            printf("AdrenoSupp: Loaded Turnip, loader address: %p\n", result);
            set_vulkan_ptr(result);
            return;
        }
#endif
    }
    printf("OSMDroid: loading vulkan regularly...\n");
    void* vulkan_ptr = dlopen("libvulkan.so", RTLD_LAZY | RTLD_LOCAL);
    printf("OSMDroid: loaded vulkan, ptr=%p\n", vulkan_ptr);
    set_vulkan_ptr(vulkan_ptr);
}

int pojavInitOpenGL() {
    // Only affects GL4ES as of now
    const char *forceVsync = getenv("FORCE_VSYNC");
    if (strcmp(forceVsync, "true") == 0)
        pojav_environ->force_vsync = true;

    // NOTE: Override for now.
    const char *renderer = getenv("AMETHYST_RENDERER");
    if (strncmp("opengles", renderer, 8) == 0) {
        pojav_environ->config_renderer = RENDERER_GL4ES;
        if (!strcmp(renderer, "opengles3_desktopgl_zink_kopper")) {
            load_vulkan();
            setenv("GALLIUM_DRIVER", "zink", 1);
            setenv("MESA_ANDROID_NO_KMS_SWRAST", "1", 1);
            setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", true);
        }
        set_gl_bridge_tbl();
    } else if (strcmp(renderer, "vulkan_zink") == 0) {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        load_vulkan();
        setenv("GALLIUM_DRIVER","zink",1);
        set_osm_bridge_tbl();
    } else printf("EGLBridge: Renderer was not configured as a bridge. Consider adding \"opengles\" to the start of renderer name if it crashes");
    if(br_init()) {
        br_setup_window();
    }
    return 0;
}

extern void updateMonitorSize(int width, int height);

EXTERNAL_API int pojavInit() {
    pojav_environ->glfwThreadVmEnv = get_attached_env(pojav_environ->runtimeJavaVMPtr);
    if(pojav_environ->glfwThreadVmEnv == NULL) {
        printf("Failed to attach Java-side JNIEnv to GLFW thread\n");
        return 0;
    }
    ANativeWindow_acquire(pojav_environ->pojavWindow);
    pojav_environ->savedWidth = ANativeWindow_getWidth(pojav_environ->pojavWindow);
    pojav_environ->savedHeight = ANativeWindow_getHeight(pojav_environ->pojavWindow);
    ANativeWindow_setBuffersGeometry(pojav_environ->pojavWindow,pojav_environ->savedWidth,pojav_environ->savedHeight,AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
    updateMonitorSize(pojav_environ->savedWidth, pojav_environ->savedHeight);
    pojavInitOpenGL();
    return 1;
}

EXTERNAL_API void pojavSetWindowHint(int hint, int value) {
    if (hint != GLFW_CLIENT_API) return;
    switch (value) {
        case GLFW_NO_API:
            pojav_environ->config_renderer = RENDERER_VULKAN;
            /* Nothing to do: initialization is handled in Java-side */
            // pojavInitVulkan();
            break;
        case GLFW_OPENGL_API: {
            const char *renderer = getenv("AMETHYST_RENDERER");
            if (strncmp(renderer, "opengles", 8) == 0) {
                pojav_environ->config_renderer = RENDERER_GL4ES;
            } else if (strcmp(renderer, "vulkan_zink") == 0) {
                pojav_environ->config_renderer = RENDERER_VK_ZINK;
            }
            /* Nothing to do: initialization is called in pojavCreateContext */
            // pojavInitOpenGL();
            break;
        }
        default:
            printf("GLFW: Unimplemented API 0x%x\n", value);
            abort();
    }
}

EXTERNAL_API void pojavSwapBuffers() {
    br_swap_buffers();
}


EXTERNAL_API void pojavMakeCurrent(void* window) {
    br_make_current((basic_render_window_t*)window);
}

EXTERNAL_API void* pojavCreateContext(void* contextSrc) {
    if (pojav_environ->config_renderer == RENDERER_VULKAN) {
        return (void *) pojav_environ->pojavWindow;
    }
    return br_init_context((basic_render_window_t*)contextSrc);
}

void* maybe_load_vulkan() {
    // We use the env var because
    // 1. it's easier to do that
    // 2. it won't break if something will try to load vulkan and osmesa simultaneously
    if(getenv("VULKAN_PTR") == NULL) load_vulkan();
    return (void*) strtoul(getenv("VULKAN_PTR"), NULL, 0x10);
}

EXTERNAL_API JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass thiz) {
    printf("EGLBridge: LWJGL-side Vulkan loader requested the Vulkan handle\n");
    return (jlong) maybe_load_vulkan();
}

EXTERNAL_API void pojavSwapInterval(int interval) {
    br_swap_interval(interval);
}

