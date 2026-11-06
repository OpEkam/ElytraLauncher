#include <EGL/egl.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <limits.h>
#include <time.h>
#include <environ/environ.h>
#include "gl_bridge.h"
#include "egl_loader.h"

#define TAG __FILE_NAME__
#include <unistd.h>
#include <log.h>

//
// Created by maks on 17.09.2022.
//

static __thread gl_render_window_t* currentBundle;
static EGLDisplay g_EglDisplay;

/*
 * Read the completed EGL back buffer on the render thread, before eglSwapBuffers
 * presents it. Resolving GL entry points through this EGL implementation is
 * important: MobileGlues supplies its own EGL/GLES implementation.
 */
typedef void (*capture_glReadPixels_t)(int, int, int, int, unsigned int, unsigned int, void*);
typedef void (*capture_glGenBuffers_t)(int, unsigned int*);
typedef void (*capture_glDeleteBuffers_t)(int, const unsigned int*);
typedef void (*capture_glBindBuffer_t)(unsigned int, unsigned int);
typedef void (*capture_glBufferData_t)(unsigned int, intptr_t, const void*, unsigned int);
typedef void* (*capture_glMapBufferRange_t)(unsigned int, intptr_t, intptr_t, unsigned int);
typedef unsigned char (*capture_glUnmapBuffer_t)(unsigned int);
typedef void* (*capture_glFenceSync_t)(unsigned int, unsigned int);
typedef unsigned int (*capture_glClientWaitSync_t)(void*, unsigned int, uint64_t);
typedef void (*capture_glDeleteSync_t)(void*);
typedef void (*capture_glGetIntegerv_t)(unsigned int, int*);
typedef const unsigned char* (*capture_glGetString_t)(unsigned int);
typedef void (*capture_glFinish_t)(void);

static capture_glReadPixels_t capture_glReadPixels;
static capture_glGenBuffers_t capture_glGenBuffers;
static capture_glDeleteBuffers_t capture_glDeleteBuffers;
static capture_glBindBuffer_t capture_glBindBuffer;
static capture_glBufferData_t capture_glBufferData;
static capture_glMapBufferRange_t capture_glMapBufferRange;
static capture_glUnmapBuffer_t capture_glUnmapBuffer;
static capture_glFenceSync_t capture_glFenceSync;
static capture_glClientWaitSync_t capture_glClientWaitSync;
static capture_glDeleteSync_t capture_glDeleteSync;
static capture_glGetIntegerv_t capture_glGetIntegerv;
static capture_glGetString_t capture_glGetString;
static capture_glFinish_t capture_glFinish;

static pthread_mutex_t capture_mutex = PTHREAD_MUTEX_INITIALIZER;
static atomic_bool capture_enabled = false;
static uint8_t* capture_pixels;
static uint8_t* capture_row;
static size_t capture_capacity;
static size_t capture_row_capacity;
static int capture_width, capture_height, capture_stride, capture_sequence;
/* Do not force a GPU readback on every game swap. The recorder currently
 * consumes at most 30 frames per second, so capture at that cadence. */
#define GL_CAPTURE_INTERVAL_NS (1000000000LL / 30LL)
#define GL_CAPTURE_PBO_COUNT 3
#define GL_PIXEL_PACK_BUFFER 0x88EB
#define GL_PIXEL_PACK_BUFFER_BINDING 0x88ED
#define GL_STREAM_READ 0x88E1
#define GL_MAP_READ_BIT 0x0001
#define GL_SYNC_GPU_COMMANDS_COMPLETE 0x9117
#define GL_ALREADY_SIGNALED 0x911A
#define GL_CONDITION_SATISFIED 0x911C
#define GL_WAIT_FAILED 0x911D
#define GL_VERSION 0x1F02

typedef struct {
    unsigned int buffer;
    void* fence;
    bool pending;
} capture_pbo_slot_t;

static capture_pbo_slot_t capture_pbo_slots[GL_CAPTURE_PBO_COUNT];
static bool capture_pbo_checked;
static bool capture_pbo_supported;
static EGLContext capture_pbo_context = EGL_NO_CONTEXT;
static int capture_pbo_width, capture_pbo_height;
static int64_t capture_next_time_ns;

void gl_set_frame_capture_enabled(bool enabled) {
    if (enabled) capture_next_time_ns = 0;
    atomic_store(&capture_enabled, enabled);
}

bool gl_copy_latest_frame(void* destination, size_t capacity, int* metadata) {
    if (destination == NULL || metadata == NULL || pthread_mutex_lock(&capture_mutex) != 0)
        return false;
    size_t bytes = 0;
    if (capture_stride > 0 && capture_height > 0
            && (size_t) capture_stride <= SIZE_MAX / (size_t) capture_height / 4) {
        bytes = (size_t) capture_stride * (size_t) capture_height * 4;
    }
    bool has_frame = capture_pixels != NULL && bytes > 0;
    if (has_frame) {
        metadata[0] = capture_width;
        metadata[1] = capture_height;
        metadata[2] = capture_stride;
        metadata[3] = capture_sequence;
    }
    bool copied = has_frame && capacity >= bytes;
    if (copied) memcpy(destination, capture_pixels, bytes);
    pthread_mutex_unlock(&capture_mutex);
    return copied;
}

static void capture_resolve_pbo_functions(void) {
    if (capture_pbo_checked) return;
    capture_pbo_checked = true;
    if (eglGetProcAddress_p == NULL) return;

#define CAPTURE_RESOLVE(name, type) capture_##name = (type) eglGetProcAddress_p(#name)
    CAPTURE_RESOLVE(glGenBuffers, capture_glGenBuffers_t);
    CAPTURE_RESOLVE(glDeleteBuffers, capture_glDeleteBuffers_t);
    CAPTURE_RESOLVE(glBindBuffer, capture_glBindBuffer_t);
    CAPTURE_RESOLVE(glBufferData, capture_glBufferData_t);
    CAPTURE_RESOLVE(glMapBufferRange, capture_glMapBufferRange_t);
    CAPTURE_RESOLVE(glUnmapBuffer, capture_glUnmapBuffer_t);
    CAPTURE_RESOLVE(glFenceSync, capture_glFenceSync_t);
    CAPTURE_RESOLVE(glClientWaitSync, capture_glClientWaitSync_t);
    CAPTURE_RESOLVE(glDeleteSync, capture_glDeleteSync_t);
    CAPTURE_RESOLVE(glGetIntegerv, capture_glGetIntegerv_t);
    CAPTURE_RESOLVE(glGetString, capture_glGetString_t);
    CAPTURE_RESOLVE(glFinish, capture_glFinish_t);
#undef CAPTURE_RESOLVE

    if (capture_glGenBuffers == NULL || capture_glDeleteBuffers == NULL
            || capture_glBindBuffer == NULL || capture_glBufferData == NULL
            || capture_glMapBufferRange == NULL || capture_glUnmapBuffer == NULL
            || capture_glFenceSync == NULL || capture_glClientWaitSync == NULL
            || capture_glDeleteSync == NULL || capture_glGetIntegerv == NULL
            || capture_glGetString == NULL) return;

    const char* version = (const char*) capture_glGetString(GL_VERSION);
    if (version == NULL) return;
    /* Pixel-pack buffers, map-buffer-range and sync objects are core in ES 3.0
     * and desktop GL 3.0. Older/limited renderers keep using the safe fallback. */
    bool version_supported = strstr(version, "OpenGL ES 3.") != NULL
            || strstr(version, "OpenGL ES 3 ") != NULL;
    if (!version_supported && version[0] >= '3' && version[0] <= '9'
            && version[1] == '.') version_supported = true;
    capture_pbo_supported = version_supported;
}

static bool capture_publish_pbo_frame(const uint8_t* pixels, int width, int height) {
    if (pixels == NULL || width < 2 || height < 2
            || (size_t) width > SIZE_MAX / (size_t) height / 4) return false;
    if (pthread_mutex_trylock(&capture_mutex) != 0) return false;

    size_t bytes = (size_t) width * (size_t) height * 4;
    size_t row_bytes = (size_t) width * 4;
    if (bytes > capture_capacity) {
        uint8_t* replacement = (uint8_t*) realloc(capture_pixels, bytes);
        if (replacement == NULL) {
            pthread_mutex_unlock(&capture_mutex);
            return false;
        }
        capture_pixels = replacement;
        capture_capacity = bytes;
    }

    /* PBO data is bottom-up; publish it top-down without a second full-frame buffer. */
    for (int y = 0; y < height; ++y) {
        const uint8_t* source = pixels + (size_t) (height - 1 - y) * row_bytes;
        uint8_t* destination = capture_pixels + (size_t) y * row_bytes;
        memcpy(destination, source, row_bytes);
    }
    capture_width = width;
    capture_height = height;
    capture_stride = width;
    ++capture_sequence;
    pthread_mutex_unlock(&capture_mutex);
    return true;
}

static void capture_reset_pbo_slots(bool delete_objects) {
    for (int i = 0; i < GL_CAPTURE_PBO_COUNT; ++i) {
        if (delete_objects && capture_pbo_slots[i].fence != NULL
                && capture_glDeleteSync != NULL) {
            capture_glDeleteSync(capture_pbo_slots[i].fence);
        }
        capture_pbo_slots[i].fence = NULL;
        capture_pbo_slots[i].pending = false;
    }
    if (delete_objects && capture_glDeleteBuffers != NULL) {
        unsigned int ids[GL_CAPTURE_PBO_COUNT];
        int count = 0;
        for (int i = 0; i < GL_CAPTURE_PBO_COUNT; ++i) {
            if (capture_pbo_slots[i].buffer != 0) ids[count++] = capture_pbo_slots[i].buffer;
            capture_pbo_slots[i].buffer = 0;
        }
        if (count > 0) capture_glDeleteBuffers(count, ids);
    } else {
        for (int i = 0; i < GL_CAPTURE_PBO_COUNT; ++i) capture_pbo_slots[i].buffer = 0;
    }
    capture_pbo_width = 0;
    capture_pbo_height = 0;
}

static bool capture_pbo_frame(int width, int height) {
    capture_resolve_pbo_functions();
    if (!capture_pbo_supported || currentBundle == NULL) return false;

    EGLContext context = currentBundle->context;
    if (capture_pbo_context != context) {
        /* GL names belong to their creating context. Do not delete stale names
         * through a different context; the old context owns their lifetime. */
        capture_reset_pbo_slots(false);
        capture_pbo_context = context;
    }

    int previous_binding = 0;
    capture_glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &previous_binding);

    if (capture_pbo_width != width || capture_pbo_height != height) {
        capture_reset_pbo_slots(true);
        unsigned int ids[GL_CAPTURE_PBO_COUNT] = {0};
        capture_glGenBuffers(GL_CAPTURE_PBO_COUNT, ids);
        for (int i = 0; i < GL_CAPTURE_PBO_COUNT; ++i) {
            capture_pbo_slots[i].buffer = ids[i];
            if (ids[i] == 0) {
                capture_reset_pbo_slots(true);
                capture_glBindBuffer(GL_PIXEL_PACK_BUFFER, (unsigned int) previous_binding);
                return false;
            }
        }
        size_t bytes = (size_t) width * (size_t) height * 4;
        for (int i = 0; i < GL_CAPTURE_PBO_COUNT; ++i) {
            capture_glBindBuffer(GL_PIXEL_PACK_BUFFER, capture_pbo_slots[i].buffer);
            capture_glBufferData(GL_PIXEL_PACK_BUFFER, (intptr_t) bytes, NULL, GL_STREAM_READ);
        }
        capture_pbo_width = width;
        capture_pbo_height = height;
    }

    /* Collect only already-completed GPU transfers. A zero timeout means this
     * render thread never waits for a frame to become ready. */
    for (int i = 0; i < GL_CAPTURE_PBO_COUNT; ++i) {
        capture_pbo_slot_t* slot = &capture_pbo_slots[i];
        if (!slot->pending || slot->fence == NULL) continue;
        unsigned int status = capture_glClientWaitSync(slot->fence, 0, 0);
        if (status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED) {
            capture_glBindBuffer(GL_PIXEL_PACK_BUFFER, slot->buffer);
            size_t bytes = (size_t) width * (size_t) height * 4;
            const uint8_t* mapped = (const uint8_t*) capture_glMapBufferRange(
                    GL_PIXEL_PACK_BUFFER, 0, (intptr_t) bytes, GL_MAP_READ_BIT);
            if (mapped != NULL) {
                capture_publish_pbo_frame(mapped, width, height);
                capture_glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
            }
            capture_glDeleteSync(slot->fence);
            slot->fence = NULL;
            slot->pending = false;
        } else if (status == GL_WAIT_FAILED) {
            /* A broken sync path is safer to abandon than to block on the GPU. */
            capture_glBindBuffer(GL_PIXEL_PACK_BUFFER, (unsigned int) previous_binding);
            if (capture_glFinish != NULL) capture_glFinish();
            capture_reset_pbo_slots(true);
            capture_pbo_supported = false;
            return false;
        }
    }

    int free_slot = -1;
    for (int i = 0; i < GL_CAPTURE_PBO_COUNT; ++i) {
        if (!capture_pbo_slots[i].pending) {
            free_slot = i;
            break;
        }
    }
    if (free_slot >= 0) {
        capture_pbo_slot_t* slot = &capture_pbo_slots[free_slot];
        capture_glBindBuffer(GL_PIXEL_PACK_BUFFER, slot->buffer);
        capture_glReadPixels(0, 0, width, height, 0x1908 /* GL_RGBA */,
                             0x1401 /* GL_UNSIGNED_BYTE */, (void*) 0);
        slot->fence = capture_glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (slot->fence != NULL) {
            slot->pending = true;
        } else {
            capture_glBindBuffer(GL_PIXEL_PACK_BUFFER, (unsigned int) previous_binding);
            if (capture_glFinish != NULL) capture_glFinish();
            capture_reset_pbo_slots(true);
            capture_pbo_supported = false;
            return false;
        }
    }

    capture_glBindBuffer(GL_PIXEL_PACK_BUFFER, (unsigned int) previous_binding);
    return true;
}

static void gl_capture_current_frame(void) {
    if (!atomic_load(&capture_enabled) || currentBundle == NULL
            || currentBundle->surface == EGL_NO_SURFACE) return;

    /* Throttle before touching GL state or synchronizing with the GPU. */
    struct timespec capture_now;
    if (clock_gettime(CLOCK_MONOTONIC, &capture_now) != 0) return;
    int64_t now_ns = (int64_t) capture_now.tv_sec * 1000000000LL
            + (int64_t) capture_now.tv_nsec;
    if (capture_next_time_ns != 0 && now_ns < capture_next_time_ns) return;
    capture_next_time_ns = now_ns + GL_CAPTURE_INTERVAL_NS;
    if (capture_glReadPixels == NULL && eglGetProcAddress_p != NULL) {
        capture_glReadPixels = (capture_glReadPixels_t) eglGetProcAddress_p("glReadPixels");
    }
    if (capture_glReadPixels == NULL) return;

    EGLint width = 0, height = 0;
    if (!eglQuerySurface_p(g_EglDisplay, currentBundle->surface, EGL_WIDTH, &width)
            || !eglQuerySurface_p(g_EglDisplay, currentBundle->surface, EGL_HEIGHT, &height)
            || width < 2 || height < 2
            || (size_t) width > SIZE_MAX / (size_t) height / 4) return;

    /* Prefer asynchronous PBO readback on ES 3+/desktop GL 3+. */
    if (capture_pbo_frame(width, height)) return;

    /* Compatibility fallback for renderers that do not expose PBO/sync APIs. */
    if (pthread_mutex_trylock(&capture_mutex) != 0) return;
    size_t bytes = (size_t) width * (size_t) height * 4;
    size_t row_bytes = (size_t) width * 4;
    if (bytes > capture_capacity) {
        uint8_t* replacement = (uint8_t*) realloc(capture_pixels, bytes);
        if (replacement == NULL) {
            pthread_mutex_unlock(&capture_mutex);
            return;
        }
        capture_pixels = replacement;
        capture_capacity = bytes;
    }

    if (row_bytes > capture_row_capacity) {
        uint8_t* replacement = (uint8_t*) realloc(capture_row, row_bytes);
        if (replacement == NULL) {
            pthread_mutex_unlock(&capture_mutex);
            return;
        }
        capture_row = replacement;
        capture_row_capacity = row_bytes;
    }
    capture_glReadPixels(0, 0, width, height, 0x1908 /* GL_RGBA */,
                         0x1401 /* GL_UNSIGNED_BYTE */, capture_pixels);
    /* Flip in place using a reusable pixel-row buffer. */
    for (int y = 0; y < height / 2; ++y) {
        uint8_t* top = capture_pixels + (size_t) y * row_bytes;
        uint8_t* bottom = capture_pixels + (size_t) (height - 1 - y) * row_bytes;
        memcpy(capture_row, top, row_bytes);
        memcpy(top, bottom, row_bytes);
        memcpy(bottom, capture_row, row_bytes);
    }
    capture_width = width;
    capture_height = height;
    capture_stride = width;
    ++capture_sequence;
    pthread_mutex_unlock(&capture_mutex);
}
bool gl_init() {
    if(!dlsym_EGL()) return false;
    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        LOGE("%s", "eglGetDisplay_p(EGL_DEFAULT_DISPLAY) returned EGL_NO_DISPLAY");
        return false;
    }
    if (eglInitialize_p(g_EglDisplay, 0, 0) != EGL_TRUE) {
        LOGE("eglInitialize_p() failed: %04x", eglGetError_p());
        return false;
    }
    capture_glReadPixels = (capture_glReadPixels_t) eglGetProcAddress_p("glReadPixels");
    if (capture_glReadPixels == NULL) LOGW("GL frame capture unavailable: glReadPixels was not exposed by EGL");
    return true;
}

gl_render_window_t* gl_get_current() {
    return currentBundle;
}

static void gl4esi_get_display_dimensions(int* width, int* height) {
    if(currentBundle == NULL) goto zero;
    EGLSurface surface = currentBundle->surface;
    // Fetch dimensions from the EGL surface - the most reliable way
    EGLBoolean result_width = eglQuerySurface_p(g_EglDisplay, surface, EGL_WIDTH, width);
    EGLBoolean result_height = eglQuerySurface_p(g_EglDisplay, surface, EGL_HEIGHT, height);
    if(!result_width || !result_height) goto zero;
    return;

    zero:
    // No idea what to do, but feeding gl4es incorrect or non-initialized dimensions may be
    // a bad idea. Set to zero in case of errors.
    *width = 0;
    *height = 0;
}

gl_render_window_t* gl_init_context(gl_render_window_t *share) {
    gl_render_window_t* bundle = malloc(sizeof(gl_render_window_t));
    memset(bundle, 0, sizeof(gl_render_window_t));
    // Should fix old Angelica wanting no ES bit. It'll still get it in opengles_nothing :p
    int glBit = strncmp(getenv("AMETHYST_RENDERER"), "opengles3_desktopgl", 19) ?  EGL_OPENGL_ES3_BIT : EGL_OPENGL_BIT;
    EGLint egl_attributes[] = { EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_SURFACE_TYPE, EGL_WINDOW_BIT|EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
    EGLint num_configs = 0;

    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE) {
        LOGE("eglChooseConfig_p() failed: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    if (num_configs == 0) {
        LOGE("%s", "eglChooseConfig_p() found no matching config");
        free(bundle);
        return NULL;
    }

    // Get the first matching config
    eglChooseConfig_p(g_EglDisplay, egl_attributes, &bundle->config, 1, &num_configs);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format);

    {
        EGLBoolean bindResult;
        if (strncmp(getenv("AMETHYST_RENDERER"), "opengles3_desktopgl", 19) == 0) {
            printf("EGLBridge: Binding to desktop OpenGL\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_API);
        } else {
            printf("EGLBridge: Binding to OpenGL ES\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
        }
        if (!bindResult) printf("EGLBridge: bind failed: %p\n", eglGetError_p());
    }

    int libgl_es = strtol(getenv("LIBGL_ES"), NULL, 0);
    if(libgl_es < 0 || libgl_es > INT16_MAX) libgl_es = 2;
    const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, libgl_es, EGL_NONE };
    bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, share == NULL ? EGL_NO_CONTEXT : share->context, egl_context_attributes);

    if (bundle->context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext_p() finished with error: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    return bundle;
}

void gl_swap_surface(gl_render_window_t* bundle) {
    /*
     * In some cases (see MinecraftGLSurface.start(), android kills the surface automatically for
     * us, if we try to release/destroy it, we SIGSEGV. Check if we are -19x-19 or some other
     * invalid value and skip the release because Android decided to handle releasing it for us.
     * This goes against every piece of documentation I have ever seen but who actually reads those?
     *
     * Some drivers take forever to properly destroy the surface, they do it part at a time or
     * some other garbage while SIGSEGVing us if we try releasing while they're in the middle of
     * turning the surface dead. This makes the width and height make it look valid when it actually
     * isn't so we wait for them and hope there is no race condition of both us and Android trying
     * to release the surface. This seems driver dependent as AVD and Waydroid do not need 0.75s
     * to set the bloody height and width to their proper values. They just do it, instantly.
     */
    usleep(750000); // An overkill amount of time to wait for a surface to finish dying
    int32_t nativeWindowWidth = ANativeWindow_getWidth(pojav_environ->pojavWindow);
    int32_t nativeWindowHeight = ANativeWindow_getHeight(pojav_environ->pojavWindow);
    if ((nativeWindowWidth > 0) || (nativeWindowHeight > 0)) {
        LOGI("Native surface dimensions (%d x %d)\n",
             nativeWindowWidth, nativeWindowHeight);
        if (bundle->nativeSurface != NULL) {
            ANativeWindow_release(bundle->nativeSurface);
        }
        if (bundle->surface != NULL) eglDestroySurface_p(g_EglDisplay, bundle->surface);
    } else {
        LOGW("Native surface dimensions (%d x %d) are invalid! Assuming given nativeSurface is bad.\n",
             nativeWindowWidth, nativeWindowHeight);
    }
    if(bundle->newNativeSurface != NULL) {
        LOGI("Switching to new native surface");
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        ANativeWindow_acquire(bundle->nativeSurface);
        ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, bundle->format);
        bundle->surface = eglCreateWindowSurface_p(g_EglDisplay, bundle->config, bundle->nativeSurface, NULL);
    }else{
        LOGI("No new native surface, switching to 1x1 pbuffer");
        bundle->nativeSurface = NULL;
        const EGLint pbuffer_attrs[] = {EGL_WIDTH, 1 , EGL_HEIGHT, 1, EGL_NONE};
        bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
    }
    //eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context);
}

void gl_make_current(gl_render_window_t* bundle) {

    if(bundle == NULL) {
        if(eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
            currentBundle = NULL;
        }
        return;
    }
    bool hasSetMainWindow = false;
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
        LOGI("Main window bundle is now %p", pojav_environ->mainWindowBundle);
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
        hasSetMainWindow = true;
    }
    LOGI("Making current, surface=%p, nativeSurface=%p, newNativeSurface=%p", bundle->surface, bundle->nativeSurface, bundle->newNativeSurface);
    if(bundle->surface == NULL) { //it likely will be on the first run
        gl_swap_surface(bundle);
    }
    if(eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context)) {
        currentBundle = bundle;
    }else {
        if(hasSetMainWindow) {
            pojav_environ->mainWindowBundle->newNativeSurface = NULL;
            gl_swap_surface((gl_render_window_t*)pojav_environ->mainWindowBundle);
            pojav_environ->mainWindowBundle = NULL;
        }
        LOGE("eglMakeCurrent returned with error: %04x", eglGetError_p());
    }

}

void gl_swap_buffers() {
    if (currentBundle == NULL) return;
    if(currentBundle->state == STATE_RENDERER_NEW_WINDOW) {
        eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); //detach everything to destroy the old EGLSurface
        gl_swap_surface(currentBundle);
        eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context);
        currentBundle->state = STATE_RENDERER_ALIVE;
    }
    if(currentBundle->surface != NULL) {
        gl_capture_current_frame();
        if(!eglSwapBuffers_p(g_EglDisplay, currentBundle->surface) && eglGetError_p() == EGL_BAD_SURFACE) {
            eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            currentBundle->newNativeSurface = NULL;
            gl_swap_surface(currentBundle);
            eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context);
            LOGI("The window has died, awaiting window change");
        }
    }

}

void gl_setup_window() {
    if(pojav_environ->mainWindowBundle != NULL) {
        LOGI("Main window bundle is not NULL, changing state");
        pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
    }
}

void gl_swap_interval(int swapInterval) {
    if(pojav_environ->force_vsync) swapInterval = 1;

    eglSwapInterval_p(g_EglDisplay, swapInterval);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    LOGI("GL4ES internals initializing...");
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        LOGI("GL4ES internals initialized dimension callback");
        set_getmainfbsize(gl4esi_get_display_dimensions);
    }

#undef GETSYM
}
