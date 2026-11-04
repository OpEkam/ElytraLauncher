//
// Created by maks on 18.10.2023.
//
#include <malloc.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <environ/environ.h>
#include "osm_bridge.h"
#define TAG __FILE_NAME__
#include <log.h>

static __thread osm_render_window_t* currentBundle;

// Latest-frame staging buffer. trylock keeps capture from blocking the game thread.
static pthread_mutex_t capture_mutex = PTHREAD_MUTEX_INITIALIZER;
static atomic_bool capture_enabled = false;
static uint8_t* capture_pixels = NULL;
static size_t capture_capacity = 0;
static int capture_width = 0, capture_height = 0, capture_stride = 0;
static int capture_sequence = 0;

void osm_set_frame_capture_enabled(bool enabled) {
    atomic_store(&capture_enabled, enabled);
}

bool osm_copy_latest_frame(void* destination, size_t capacity, int* metadata) {
    if (destination == NULL || metadata == NULL || pthread_mutex_lock(&capture_mutex) != 0)
        return false;
    size_t bytes = (size_t) capture_stride * (size_t) capture_height * 4;
    bool valid = capture_pixels != NULL && bytes > 0 && capacity >= bytes;
    if (valid) {
        memcpy(destination, capture_pixels, bytes);
        metadata[0] = capture_width;
        metadata[1] = capture_height;
        metadata[2] = capture_stride;
        metadata[3] = capture_sequence;
    }
    pthread_mutex_unlock(&capture_mutex);
    return valid;
}

static void capture_current_frame(const ANativeWindow_Buffer* buffer) {
    if (!atomic_load(&capture_enabled) || buffer == NULL || buffer->bits == NULL
            || buffer->width <= 0 || buffer->height <= 0 || buffer->stride < buffer->width)
        return;
    if (pthread_mutex_trylock(&capture_mutex) != 0) return; // Drop this frame if reader is copying.
    size_t bytes = (size_t) buffer->stride * (size_t) buffer->height * 4;
    if (bytes > capture_capacity) {
        uint8_t* replacement = (uint8_t*) realloc(capture_pixels, bytes);
        if (replacement == NULL) {
            pthread_mutex_unlock(&capture_mutex);
            return;
        }
        capture_pixels = replacement;
        capture_capacity = bytes;
    }
    memcpy(capture_pixels, buffer->bits, bytes);
    capture_width = buffer->width;
    capture_height = buffer->height;
    capture_stride = buffer->stride;
    ++capture_sequence;
    pthread_mutex_unlock(&capture_mutex);
}
// a tiny buffer for rendering when there's nowhere t render
static char no_render_buffer[4];

// Its not in a .h file because it is not supposed to be used outsife of this file.
void setNativeWindowSwapInterval(struct ANativeWindow* nativeWindow, int swapInterval);

bool osm_init() {
    if(!dlsym_OSMesa()) return false;
    return true;
}

osm_render_window_t* osm_get_current() {
    return currentBundle;
}

osm_render_window_t* osm_init_context(osm_render_window_t* share) {
    osm_render_window_t* render_window = malloc(sizeof(osm_render_window_t));
    if(render_window == NULL) return NULL;
    memset(render_window, 0, sizeof(osm_render_window_t));
    OSMesaContext osmesa_share = NULL;
    if(share != NULL) osmesa_share = share->context;
    OSMesaContext context = OSMesaCreateContext_p(GL_RGBA, osmesa_share);
    if(context == NULL) {
        free(render_window);
        return NULL;
    }
    render_window->context = context;
    return render_window;
}

void osm_set_no_render_buffer(ANativeWindow_Buffer* buffer) {
    buffer->bits = &no_render_buffer;
    buffer->width = 1;
    buffer->height = 1;
    buffer->stride = 0;
}

void osm_swap_surfaces(osm_render_window_t* bundle) {
    if(bundle->nativeSurface != NULL && bundle->newNativeSurface != bundle->nativeSurface) {
        if(!bundle->disable_rendering) {
            LOGI("Unlocking for cleanup...");
            ANativeWindow_unlockAndPost(bundle->nativeSurface);
        }
        ANativeWindow_release(bundle->nativeSurface);
    }
    if(bundle->newNativeSurface != NULL) {
        LOGI("Switching to new native surface");
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        ANativeWindow_acquire(bundle->nativeSurface);
        ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, WINDOW_FORMAT_RGBX_8888);
        bundle->disable_rendering = false;
        return;
    }else {
        LOGI("No new native surface, switching to dummy framebuffer");
        bundle->nativeSurface = NULL;
        osm_set_no_render_buffer(&bundle->buffer);
        bundle->disable_rendering = true;
    }

}

void osm_release_window() {
    currentBundle->newNativeSurface = NULL;
    osm_swap_surfaces(currentBundle);
}

void osm_apply_current_ll() {
    ANativeWindow_Buffer* buffer = &currentBundle->buffer;
    OSMesaMakeCurrent_p(currentBundle->context, buffer->bits, GL_UNSIGNED_BYTE, buffer->width, buffer->height);
    if(buffer->stride != currentBundle->last_stride)
        OSMesaPixelStore_p(OSMESA_ROW_LENGTH, buffer->stride);
    currentBundle->last_stride = buffer->stride;
}

void osm_make_current(osm_render_window_t* bundle) {
    if(bundle == NULL) {
        //technically this does nothing as its not possible to unbind a context in OSMesa
        OSMesaMakeCurrent_p(NULL, NULL, 0, 0, 0);
        currentBundle = NULL;
        return;
    }
    bool hasSetMainWindow = false;
    currentBundle = bundle;
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*) bundle;
        LOGI("Main window bundle is now %p", pojav_environ->mainWindowBundle);
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
        hasSetMainWindow = true;
    }
    if(bundle->nativeSurface == NULL) {
        //prepare the buffer for our first render!
        osm_swap_surfaces(bundle);
        if(hasSetMainWindow) pojav_environ->mainWindowBundle->state = STATE_RENDERER_ALIVE;
    }
    osm_set_no_render_buffer(&bundle->buffer);
    osm_apply_current_ll();
    OSMesaPixelStore_p(OSMESA_Y_UP,0);
}

void osm_swap_buffers() {
    if(currentBundle->state == STATE_RENDERER_NEW_WINDOW) {
        osm_swap_surfaces(currentBundle);
        currentBundle->state = STATE_RENDERER_ALIVE;
    }

    if(currentBundle->nativeSurface != NULL && !currentBundle->disable_rendering)
        if(ANativeWindow_lock(currentBundle->nativeSurface, &currentBundle->buffer, NULL) != 0)
            osm_release_window();

    osm_apply_current_ll();
    glFinish_p(); // this will force osmesa to write the last rendered image into the buffer

    // Copy the completed RGBX frame before Android releases the window buffer.
    if(currentBundle->nativeSurface != NULL && !currentBundle->disable_rendering)
        capture_current_frame(&currentBundle->buffer);

    if(currentBundle->nativeSurface != NULL && !currentBundle->disable_rendering)
        if(ANativeWindow_unlockAndPost(currentBundle->nativeSurface) != 0)
            osm_release_window();
}

void osm_setup_window() {
    if(pojav_environ->mainWindowBundle != NULL) {
        LOGI("Main window bundle is not NULL, changing state");
        pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
    }
}

void osm_swap_interval(int swapInterval) {
    if(pojav_environ->mainWindowBundle != NULL && pojav_environ->mainWindowBundle->nativeSurface != NULL) {
        setNativeWindowSwapInterval(pojav_environ->mainWindowBundle->nativeSurface, swapInterval);
    }
}