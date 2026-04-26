#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "libretro.h"
#include "audio_player.h"

#ifndef NIN64_LIBRETRO_CORE_NAME
#define NIN64_LIBRETRO_CORE_NAME "libmupen64plus_next_libretro.so"
#endif

#ifndef RETRO_ENVIRONMENT_RETROARCH_START_BLOCK
#define RETRO_ENVIRONMENT_RETROARCH_START_BLOCK 0x300000
#endif

#ifndef RETRO_ENVIRONMENT_GET_CLEAR_ALL_THREAD_WAITS_CB
#define RETRO_ENVIRONMENT_GET_CLEAR_ALL_THREAD_WAITS_CB (3 | RETRO_ENVIRONMENT_RETROARCH_START_BLOCK)
#endif

#ifndef RETRO_ENVIRONMENT_POLL_TYPE_OVERRIDE
#define RETRO_ENVIRONMENT_POLL_TYPE_OVERRIDE (4 | RETRO_ENVIRONMENT_RETROARCH_START_BLOCK)
#endif

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x00000040
#endif

#define LOG_TAG "Nin64Libretro"

#define N64_A_BUTTON   0x0080u
#define N64_B_BUTTON   0x0040u
#define N64_Z_TRIGGER  0x0020u
#define N64_START      0x0010u
#define N64_DPAD_UP    0x0008u
#define N64_DPAD_DOWN  0x0004u
#define N64_DPAD_LEFT  0x0002u
#define N64_DPAD_RIGHT 0x0001u
#define N64_L_TRIGGER  0x2000u
#define N64_R_TRIGGER  0x1000u
#define N64_C_UP       0x0800u
#define N64_C_DOWN     0x0400u
#define N64_C_LEFT     0x0200u
#define N64_C_RIGHT    0x0100u

#define DEFAULT_FRAME_INTERVAL_NS 16666667ULL
#define ANALOG_MAX 32767

typedef unsigned (*retro_api_version_fn)(void);
typedef void (*retro_deinit_fn)(void);
typedef void (*retro_get_system_av_info_fn)(struct retro_system_av_info *info);
typedef void (*retro_get_system_info_fn)(struct retro_system_info *info);
typedef void (*retro_init_fn)(void);
typedef bool (*retro_load_game_fn)(const struct retro_game_info *game);
typedef void (*retro_run_fn)(void);
typedef void (*retro_set_audio_sample_batch_fn)(retro_audio_sample_batch_t cb);
typedef void (*retro_set_audio_sample_fn)(retro_audio_sample_t cb);
typedef void (*retro_set_environment_fn)(retro_environment_t cb);
typedef void (*retro_set_input_poll_fn)(retro_input_poll_t cb);
typedef void (*retro_set_input_state_fn)(retro_input_state_t cb);
typedef void (*retro_set_video_refresh_fn)(retro_video_refresh_t cb);
typedef void (*retro_unload_game_fn)(void);

typedef struct BridgeOption {
    char *key;
    char *value;
} BridgeOption;

typedef struct LibretroBridge {
    void *handle;
    int core_initialized;
    int game_loaded;
    unsigned api_version;
    unsigned frame_width;
    unsigned frame_height;
    unsigned swap_count;
    uint64_t frame_interval_ns;
    uint64_t next_frame_deadline_ns;
    char root_path[1024];
    char last_error[512];
    char last_rom_path[1024];
    char option_43screensize[32];
    char option_169screensize[32];
    void *rom_data;
    size_t rom_size;
    uint32_t *framebuffer;
    size_t framebuffer_capacity_pixels;
    struct retro_system_info system_info;
    struct retro_system_av_info av_info;
    struct retro_hw_render_callback hw_render;
    ANativeWindow *window;
    unsigned surface_generation;
    unsigned active_surface_generation;
    int surface_width;
    int surface_height;
    int hw_requested;
    int hw_context_ready;
    BridgeOption *registered_options;
    size_t registered_option_count;
    size_t registered_option_capacity;
    BridgeOption *user_options;
    size_t user_option_count;
    size_t user_option_capacity;
    EGLDisplay egl_display;
    EGLConfig egl_config;
    EGLContext egl_context;
    EGLSurface egl_surface;
    retro_api_version_fn retro_api_version;
    retro_deinit_fn retro_deinit;
    retro_get_system_av_info_fn retro_get_system_av_info;
    retro_get_system_info_fn retro_get_system_info;
    retro_init_fn retro_init;
    retro_load_game_fn retro_load_game;
    retro_run_fn retro_run;
    retro_set_audio_sample_batch_fn retro_set_audio_sample_batch;
    retro_set_audio_sample_fn retro_set_audio_sample;
    retro_set_environment_fn retro_set_environment;
    retro_set_input_poll_fn retro_set_input_poll;
    retro_set_input_state_fn retro_set_input_state;
    retro_set_video_refresh_fn retro_set_video_refresh;
    retro_unload_game_fn retro_unload_game;
} LibretroBridge;

static const BridgeOption g_default_options[] = {
    { "mupen64plus-rdp-plugin", "gliden64" },
    { "mupen64plus-rsp-plugin", "hle" },
    { "mupen64plus-ThreadedRenderer", "False" },
    { "mupen64plus-FrameDuping", "True" },
    { "mupen64plus-aspect", "4:3" },
    { "mupen64plus-alt-map", "False" },
    { NULL, NULL }
};

static LibretroBridge g_bridge = {
    .egl_display = EGL_NO_DISPLAY,
    .egl_context = EGL_NO_CONTEXT,
    .egl_surface = EGL_NO_SURFACE,
    .frame_interval_ns = DEFAULT_FRAME_INTERVAL_NS,
};
static pthread_mutex_t g_bridge_mutex = PTHREAD_MUTEX_INITIALIZER;
static volatile unsigned int g_button_mask;
static volatile int g_stick_x;
static volatile int g_stick_y;
static unsigned g_hw_frame_log_count;
static unsigned g_sw_frame_log_count;
static unsigned g_run_frame_log_count;

static void bridge_audio_sample(int16_t left, int16_t right);
static size_t bridge_audio_sample_batch(const int16_t *data, size_t frames);
static bool bridge_clear_all_thread_waits_cb(unsigned cmd, void *data);
static uint64_t bridge_compute_frame_interval_ns(double fps);
static void bridge_copy_video_frame(const void *data, unsigned width, unsigned height, size_t pitch);
static void bridge_clear_registered_options(void);
static bool bridge_environment(unsigned cmd, void *data);
static const char *bridge_find_option_value(const char *key);
static const char *bridge_find_surface_option_value(const char *key);
static const char *bridge_find_registered_option_value(const char *key);
static const char *bridge_find_user_option_value(const char *key);
static int bridge_set_user_option(const char *key, const char *value);
static char *bridge_strdup(const char *src);
static uintptr_t bridge_get_current_framebuffer(void);
static retro_proc_address_t bridge_get_proc_address(const char *sym);
static int bridge_hw_activate(unsigned surface_generation);
static void bridge_hw_clear_request_state(void);
static int bridge_hw_context_is_supported(const struct retro_hw_render_callback *hw_render);
static void bridge_hw_destroy_context(int notify_core);
static int bridge_hw_ensure_ready(void);
static int bridge_hw_make_current_snapshot(EGLDisplay display, EGLSurface surface, EGLContext context);
static int bridge_hw_present(unsigned width, unsigned height);
static int16_t bridge_input_state(unsigned port, unsigned device, unsigned index, unsigned id);
static int bridge_initialize(const char *root_path);
static int bridge_load_game_from_path(const char *root_path, const char *rom_path);
static int bridge_load_rom_file(const char *path, void **data_out, size_t *size_out);
static int bridge_load_symbols(void);
static void bridge_log(enum retro_log_level level, const char *fmt, ...);
static uint64_t bridge_now_ns(void);
static void bridge_input_poll(void);
static void bridge_release_game_data(void);
static void bridge_release_framebuffer(void);
static void bridge_release_window(ANativeWindow *window);
static int bridge_register_option_default(const char *key, const char *value);
static int bridge_register_options_from_definitions(const struct retro_core_option_definition *definitions);
static int bridge_register_options_from_v2_definitions(const struct retro_core_option_v2_definition *definitions);
static int bridge_register_options_from_variables(const struct retro_variable *variables);
static int bridge_reserve_framebuffer(size_t pixel_count);
static void bridge_reset_video_state(void);
static void bridge_run_frame(void);
static void bridge_set_error(const char *fmt, ...);
static void bridge_update_surface_options(int width, int height);
static void bridge_set_surface_from_java(JNIEnv *env, jobject surface, jint width, jint height);
static void bridge_shutdown(void);
static void bridge_sleep_until_ns(uint64_t deadline_ns);
static void bridge_unload_game(void);
static void bridge_video_refresh(const void *data, unsigned width, unsigned height, size_t pitch);
static void copy_jstring(JNIEnv *env, jstring value, char *buffer, size_t buffer_size);
static int scale_n64_axis_to_libretro(int value);

static void copy_jstring(JNIEnv *env, jstring value, char *buffer, size_t buffer_size)
{
    const char *utf;

    if (!buffer || buffer_size == 0) {
        return;
    }

    buffer[0] = 0;
    if (!value) {
        return;
    }

    utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (!utf) {
        return;
    }

    snprintf(buffer, buffer_size, "%s", utf);
    (*env)->ReleaseStringUTFChars(env, value, utf);
}

static void bridge_set_error(const char *fmt, ...)
{
    va_list args;

    va_start(args, fmt);
    vsnprintf(g_bridge.last_error, sizeof(g_bridge.last_error), fmt, args);
    va_end(args);
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", g_bridge.last_error);
}

static void bridge_log(enum retro_log_level level, const char *fmt, ...)
{
    int priority = ANDROID_LOG_INFO;
    va_list args;

    switch (level) {
    case RETRO_LOG_DEBUG:
        priority = ANDROID_LOG_DEBUG;
        break;
    case RETRO_LOG_WARN:
        priority = ANDROID_LOG_WARN;
        break;
    case RETRO_LOG_ERROR:
        priority = ANDROID_LOG_ERROR;
        break;
    case RETRO_LOG_INFO:
    default:
        priority = ANDROID_LOG_INFO;
        break;
    }

    va_start(args, fmt);
    __android_log_vprint(priority, LOG_TAG, fmt, args);
    va_end(args);
}

static const char *bridge_find_option_value(const char *key)
{
    const BridgeOption *option = g_default_options;
    const char *user_value;
    const char *surface_value;
    const char *registered_value;

    if (!key) {
        return NULL;
    }

    /* User-set options have highest priority so they can override
     * surface-derived screensize values and the hardcoded defaults. */
    user_value = bridge_find_user_option_value(key);
    if (user_value) {
        return user_value;
    }

    surface_value = bridge_find_surface_option_value(key);
    if (surface_value) {
        return surface_value;
    }

    while (option->key) {
        if (strcmp(option->key, key) == 0) {
            return option->value;
        }
        option++;
    }

    registered_value = bridge_find_registered_option_value(key);
    return registered_value;
}

static const char *bridge_find_surface_option_value(const char *key)
{
    if (!key) {
        return NULL;
    }

    if (strcmp(key, "mupen64plus-43screensize") == 0 && g_bridge.option_43screensize[0]) {
        return g_bridge.option_43screensize;
    }

    if (strcmp(key, "mupen64plus-169screensize") == 0 && g_bridge.option_169screensize[0]) {
        return g_bridge.option_169screensize;
    }

    return NULL;
}

static const char *bridge_find_registered_option_value(const char *key)
{
    size_t i;

    if (!key) {
        return NULL;
    }

    for (i = 0; i < g_bridge.registered_option_count; i++) {
        BridgeOption *option = &g_bridge.registered_options[i];
        if (option->key && strcmp(option->key, key) == 0) {
            return option->value;
        }
    }

    return NULL;
}

static const char *bridge_find_user_option_value(const char *key)
{
    size_t i;

    if (!key) {
        return NULL;
    }

    for (i = 0; i < g_bridge.user_option_count; i++) {
        BridgeOption *option = &g_bridge.user_options[i];
        if (option->key && strcmp(option->key, key) == 0) {
            return option->value;
        }
    }

    return NULL;
}

static int bridge_set_user_option(const char *key, const char *value)
{
    BridgeOption *option;
    char *key_copy;
    char *value_copy;
    size_t i;

    if (!key || !key[0] || !value || !value[0]) {
        return 1;
    }

    for (i = 0; i < g_bridge.user_option_count; i++) {
        option = &g_bridge.user_options[i];
        if (option->key && strcmp(option->key, key) == 0) {
            value_copy = bridge_strdup(value);
            if (!value_copy) {
                return 0;
            }
            free(option->value);
            option->value = value_copy;
            return 1;
        }
    }

    if (g_bridge.user_option_count == g_bridge.user_option_capacity) {
        size_t new_capacity = g_bridge.user_option_capacity == 0 ? 8u : g_bridge.user_option_capacity * 2u;
        BridgeOption *new_options = (BridgeOption *)realloc(
            g_bridge.user_options,
            new_capacity * sizeof(BridgeOption));
        if (!new_options) {
            return 0;
        }

        memset(new_options + g_bridge.user_option_capacity, 0,
            (new_capacity - g_bridge.user_option_capacity) * sizeof(BridgeOption));
        g_bridge.user_options = new_options;
        g_bridge.user_option_capacity = new_capacity;
    }

    key_copy = bridge_strdup(key);
    value_copy = bridge_strdup(value);
    if (!key_copy || !value_copy) {
        free(key_copy);
        free(value_copy);
        return 0;
    }

    option = &g_bridge.user_options[g_bridge.user_option_count++];
    option->key = key_copy;
    option->value = value_copy;
    return 1;
}

static char *bridge_strdup(const char *src)
{
    size_t length;
    char *copy;

    if (!src) {
        return NULL;
    }

    length = strlen(src);
    copy = (char *)malloc(length + 1u);
    if (!copy) {
        return NULL;
    }

    memcpy(copy, src, length + 1u);
    return copy;
}

static void bridge_clear_registered_options(void)
{
    size_t i;

    for (i = 0; i < g_bridge.registered_option_count; i++) {
        free(g_bridge.registered_options[i].key);
        free(g_bridge.registered_options[i].value);
        g_bridge.registered_options[i].key = NULL;
        g_bridge.registered_options[i].value = NULL;
    }

    free(g_bridge.registered_options);
    g_bridge.registered_options = NULL;
    g_bridge.registered_option_count = 0;
    g_bridge.registered_option_capacity = 0;
}

static int bridge_register_option_default(const char *key, const char *value)
{
    BridgeOption *option;
    char *key_copy;
    char *value_copy;
    size_t i;

    if (!key || !key[0] || !value || !value[0]) {
        return 1;
    }

    for (i = 0; i < g_bridge.registered_option_count; i++) {
        option = &g_bridge.registered_options[i];
        if (option->key && strcmp(option->key, key) == 0) {
            value_copy = bridge_strdup(value);
            if (!value_copy) {
                return 0;
            }
            free(option->value);
            option->value = value_copy;
            return 1;
        }
    }

    if (g_bridge.registered_option_count == g_bridge.registered_option_capacity) {
        size_t new_capacity = g_bridge.registered_option_capacity == 0 ? 32u : g_bridge.registered_option_capacity * 2u;
        BridgeOption *new_options = (BridgeOption *)realloc(
            g_bridge.registered_options,
            new_capacity * sizeof(BridgeOption));
        if (!new_options) {
            return 0;
        }

        memset(new_options + g_bridge.registered_option_capacity, 0,
            (new_capacity - g_bridge.registered_option_capacity) * sizeof(BridgeOption));
        g_bridge.registered_options = new_options;
        g_bridge.registered_option_capacity = new_capacity;
    }

    key_copy = bridge_strdup(key);
    value_copy = bridge_strdup(value);
    if (!key_copy || !value_copy) {
        free(key_copy);
        free(value_copy);
        return 0;
    }

    option = &g_bridge.registered_options[g_bridge.registered_option_count++];
    option->key = key_copy;
    option->value = value_copy;
    return 1;
}

static int bridge_register_options_from_definitions(const struct retro_core_option_definition *definitions)
{
    const struct retro_core_option_definition *definition = definitions;

    if (!definition) {
        return 1;
    }

    while (definition->key) {
        const char *default_value = definition->default_value;

        if ((!default_value || !default_value[0]) && definition->values[0].value) {
            default_value = definition->values[0].value;
        }

        if (!bridge_register_option_default(definition->key, default_value)) {
            return 0;
        }

        definition++;
    }

    return 1;
}

static int bridge_register_options_from_v2_definitions(const struct retro_core_option_v2_definition *definitions)
{
    const struct retro_core_option_v2_definition *definition = definitions;

    if (!definition) {
        return 1;
    }

    while (definition->key) {
        const char *default_value = definition->default_value;

        if ((!default_value || !default_value[0]) && definition->values[0].value) {
            default_value = definition->values[0].value;
        }

        if (!bridge_register_option_default(definition->key, default_value)) {
            return 0;
        }

        definition++;
    }

    return 1;
}

static int bridge_register_options_from_variables(const struct retro_variable *variables)
{
    const struct retro_variable *variable = variables;

    if (!variable) {
        return 1;
    }

    while (variable->key) {
        const char *option_string = variable->value;

        if (option_string) {
            const char *separator = strchr(option_string, ';');
            if (separator) {
                const char *value_begin = separator + 1;
                const char *value_end;
                size_t value_length;
                char *default_value;

                while (*value_begin == ' ') {
                    value_begin++;
                }

                value_end = strchr(value_begin, '|');
                if (!value_end) {
                    value_end = value_begin + strlen(value_begin);
                }

                value_length = (size_t)(value_end - value_begin);
                while (value_length > 0 && value_begin[value_length - 1u] == ' ') {
                    value_length--;
                }

                default_value = (char *)malloc(value_length + 1u);
                if (!default_value) {
                    return 0;
                }

                memcpy(default_value, value_begin, value_length);
                default_value[value_length] = 0;

                if (!bridge_register_option_default(variable->key, default_value)) {
                    free(default_value);
                    return 0;
                }
                free(default_value);
            }
        }

        variable++;
    }

    return 1;
}

static void bridge_release_window(ANativeWindow *window)
{
    if (window) {
        ANativeWindow_release(window);
    }
}

static void bridge_update_surface_options(int width, int height)
{
    unsigned render_width = width > 0 ? (unsigned)width : 640u;
    unsigned render_height = height > 0 ? (unsigned)height : 480u;
    unsigned render_width_43;
    unsigned render_height_43;

    if ((render_width & 1u) != 0u) {
        render_width--;
    }
    if ((render_height & 1u) != 0u) {
        render_height--;
    }
    if (render_width == 0u) {
        render_width = 640u;
    }
    if (render_height == 0u) {
        render_height = 480u;
    }

    render_height_43 = render_height;
    render_width_43 = (render_height_43 * 4u) / 3u;
    if (render_width_43 > render_width) {
        render_width_43 = render_width;
        render_height_43 = (render_width_43 * 3u) / 4u;
    }

    render_width_43 &= ~1u;
    render_height_43 &= ~1u;

    if (render_width_43 == 0u || render_height_43 == 0u) {
        render_width_43 = 640u;
        render_height_43 = 480u;
    }

    snprintf(g_bridge.option_43screensize, sizeof(g_bridge.option_43screensize),
        "%ux%u", render_width_43, render_height_43);
    snprintf(g_bridge.option_169screensize, sizeof(g_bridge.option_169screensize),
        "%ux%u", render_width, render_height);
}

static void bridge_set_surface_from_java(JNIEnv *env, jobject surface, jint width, jint height)
{
    ANativeWindow *new_window = NULL;
    ANativeWindow *old_window;

    if (surface) {
        new_window = ANativeWindow_fromSurface(env, surface);
    }

    pthread_mutex_lock(&g_bridge_mutex);
    old_window = g_bridge.window;
    g_bridge.window = new_window;
    g_bridge.surface_width = (int)width;
    g_bridge.surface_height = (int)height;
    bridge_update_surface_options((int)width, (int)height);
    g_bridge.surface_generation++;
    pthread_mutex_unlock(&g_bridge_mutex);

    bridge_log(
        RETRO_LOG_INFO,
        "setSurface surface=%s size=%dx%d generation=%u",
        new_window ? "attached" : "cleared",
        (int)width,
        (int)height,
        g_bridge.surface_generation);

    bridge_release_window(old_window);
}

static bool bridge_clear_all_thread_waits_cb(unsigned cmd, void *data)
{
    (void)cmd;
    (void)data;
    return true;
}

static int bridge_hw_context_is_supported(const struct retro_hw_render_callback *hw_render)
{
    if (!hw_render) {
        return 0;
    }

    if (hw_render->context_type == RETRO_HW_CONTEXT_OPENGLES3) {
        return 1;
    }

    if (hw_render->context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION &&
        hw_render->version_major >= 3) {
        return 1;
    }

    return 0;
}

static uintptr_t bridge_get_current_framebuffer(void)
{
    GLint framebuffer = 0;

    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &framebuffer);
    return (uintptr_t)framebuffer;
}

static retro_proc_address_t bridge_get_proc_address(const char *sym)
{
    retro_proc_address_t proc;

    if (!sym || !sym[0]) {
        return NULL;
    }

    proc = (retro_proc_address_t)eglGetProcAddress(sym);
    if (!proc) {
        proc = (retro_proc_address_t)dlsym(RTLD_DEFAULT, sym);
    }

    return proc;
}

static int bridge_hw_make_current_snapshot(EGLDisplay display, EGLSurface surface, EGLContext context)
{
    if (display == EGL_NO_DISPLAY || surface == EGL_NO_SURFACE || context == EGL_NO_CONTEXT) {
        return 0;
    }

    if (eglGetCurrentDisplay() == display &&
        eglGetCurrentContext() == context &&
        eglGetCurrentSurface(EGL_DRAW) == surface &&
        eglGetCurrentSurface(EGL_READ) == surface) {
        return 1;
    }

    if (!eglMakeCurrent(display, surface, surface, context)) {
        bridge_set_error("eglMakeCurrent failed: 0x%04X", eglGetError());
        return 0;
    }

    return 1;
}

static void bridge_hw_destroy_context(int notify_core)
{
    EGLDisplay display;
    EGLSurface surface;
    EGLContext context;
    retro_hw_context_reset_t context_destroy = NULL;
    int should_notify = 0;

    pthread_mutex_lock(&g_bridge_mutex);
    display = g_bridge.egl_display;
    surface = g_bridge.egl_surface;
    context = g_bridge.egl_context;
    if (notify_core && g_bridge.hw_context_ready && g_bridge.hw_render.context_destroy) {
        context_destroy = g_bridge.hw_render.context_destroy;
        should_notify = 1;
    }
    g_bridge.egl_display = EGL_NO_DISPLAY;
    g_bridge.egl_surface = EGL_NO_SURFACE;
    g_bridge.egl_context = EGL_NO_CONTEXT;
    g_bridge.egl_config = 0;
    g_bridge.active_surface_generation = 0;
    g_bridge.hw_context_ready = 0;
    pthread_mutex_unlock(&g_bridge_mutex);

    if (display == EGL_NO_DISPLAY) {
        return;
    }

    if (surface != EGL_NO_SURFACE && context != EGL_NO_CONTEXT) {
        if (bridge_hw_make_current_snapshot(display, surface, context) && should_notify) {
            context_destroy();
        }
    }

    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    if (surface != EGL_NO_SURFACE) {
        eglDestroySurface(display, surface);
    }

    if (context != EGL_NO_CONTEXT) {
        eglDestroyContext(display, context);
    }

    eglTerminate(display);
}

static void bridge_hw_clear_request_state(void)
{
    memset(&g_bridge.hw_render, 0, sizeof(g_bridge.hw_render));
    g_bridge.hw_requested = 0;
    g_bridge.hw_context_ready = 0;
}

static int bridge_hw_activate(unsigned surface_generation)
{
    EGLDisplay display;
    EGLConfig config;
    EGLContext context;
    EGLSurface surface;
    EGLint config_count = 0;
    ANativeWindow *window = NULL;
    int width = 0;
    int height = 0;
    const EGLint config_attributes[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 0,
        EGL_DEPTH_SIZE, 24,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    const EGLint context_attributes[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    pthread_mutex_lock(&g_bridge_mutex);
    if (g_bridge.window) {
        window = g_bridge.window;
        ANativeWindow_acquire(window);
    }
    width = g_bridge.surface_width;
    height = g_bridge.surface_height;
    pthread_mutex_unlock(&g_bridge_mutex);

    if (!window) {
        bridge_set_error("No Android Surface is attached for the GLES3 frontend.");
        return 0;
    }

    bridge_log(
        RETRO_LOG_INFO,
        "bridge_hw_activate generation=%u size=%dx%d",
        surface_generation,
        width,
        height);

    bridge_hw_destroy_context(1);

    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        bridge_release_window(window);
        bridge_set_error("eglGetDisplay failed: 0x%04X", eglGetError());
        return 0;
    }

    if (!eglInitialize(display, NULL, NULL)) {
        bridge_release_window(window);
        bridge_set_error("eglInitialize failed: 0x%04X", eglGetError());
        return 0;
    }

    if (!eglChooseConfig(display, config_attributes, &config, 1, &config_count) || config_count < 1) {
        eglTerminate(display);
        bridge_release_window(window);
        bridge_set_error("Unable to choose an EGL ES3 config: 0x%04X", eglGetError());
        return 0;
    }

    if (!eglBindAPI(EGL_OPENGL_ES_API)) {
        eglTerminate(display);
        bridge_release_window(window);
        bridge_set_error("eglBindAPI(EGL_OPENGL_ES_API) failed: 0x%04X", eglGetError());
        return 0;
    }

    if (width > 0 && height > 0) {
        ANativeWindow_setBuffersGeometry(window, width, height, 0);
    }

    context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
    if (context == EGL_NO_CONTEXT) {
        eglTerminate(display);
        bridge_release_window(window);
        bridge_set_error("eglCreateContext failed: 0x%04X", eglGetError());
        return 0;
    }

    surface = eglCreateWindowSurface(display, config, window, NULL);
    bridge_release_window(window);
    if (surface == EGL_NO_SURFACE) {
        eglDestroyContext(display, context);
        eglTerminate(display);
        bridge_set_error("eglCreateWindowSurface failed: 0x%04X", eglGetError());
        return 0;
    }

    if (!bridge_hw_make_current_snapshot(display, surface, context)) {
        eglDestroySurface(display, surface);
        eglDestroyContext(display, context);
        eglTerminate(display);
        return 0;
    }

    eglSwapInterval(display, 0);

    pthread_mutex_lock(&g_bridge_mutex);
    g_bridge.egl_display = display;
    g_bridge.egl_config = config;
    g_bridge.egl_context = context;
    g_bridge.egl_surface = surface;
    g_bridge.active_surface_generation = surface_generation;
    pthread_mutex_unlock(&g_bridge_mutex);

    if (g_bridge.hw_render.context_reset) {
        bridge_log(RETRO_LOG_INFO, "Calling libretro context_reset()");
        g_bridge.hw_render.context_reset();
    }

    pthread_mutex_lock(&g_bridge_mutex);
    g_bridge.hw_context_ready = 1;
    pthread_mutex_unlock(&g_bridge_mutex);
    bridge_log(RETRO_LOG_INFO, "GLES3 frontend ready");
    return 1;
}

static int bridge_hw_ensure_ready(void)
{
    EGLDisplay display;
    EGLSurface surface;
    EGLContext context;
    unsigned desired_surface_generation;
    unsigned active_surface_generation;
    int should_activate;

    pthread_mutex_lock(&g_bridge_mutex);
    if (!g_bridge.hw_requested) {
        pthread_mutex_unlock(&g_bridge_mutex);
        return 1;
    }

    desired_surface_generation = g_bridge.surface_generation;
    active_surface_generation = g_bridge.active_surface_generation;
    display = g_bridge.egl_display;
    surface = g_bridge.egl_surface;
    context = g_bridge.egl_context;
    should_activate =
        (display == EGL_NO_DISPLAY) ||
        (surface == EGL_NO_SURFACE) ||
        (context == EGL_NO_CONTEXT) ||
        (desired_surface_generation != active_surface_generation);
    pthread_mutex_unlock(&g_bridge_mutex);

    if (should_activate) {
        return bridge_hw_activate(desired_surface_generation);
    }

    return bridge_hw_make_current_snapshot(display, surface, context);
}

static int bridge_hw_present(unsigned width, unsigned height)
{
    EGLDisplay display;
    EGLSurface surface;

    if (!bridge_hw_ensure_ready()) {
        return 0;
    }

    pthread_mutex_lock(&g_bridge_mutex);
    display = g_bridge.egl_display;
    surface = g_bridge.egl_surface;
    pthread_mutex_unlock(&g_bridge_mutex);

    if (display == EGL_NO_DISPLAY || surface == EGL_NO_SURFACE) {
        bridge_set_error("GLES3 frontend has no active EGL surface.");
        return 0;
    }

    if (width > 0 && height > 0) {
        g_bridge.frame_width = width;
        g_bridge.frame_height = height;
    }

    if (!eglSwapBuffers(display, surface)) {
        bridge_set_error("eglSwapBuffers failed: 0x%04X", eglGetError());
        bridge_hw_destroy_context(0);
        return 0;
    }

    g_bridge.swap_count++;
    if (g_hw_frame_log_count < 12 || (g_bridge.swap_count % 120u) == 0u) {
        bridge_log(
            RETRO_LOG_INFO,
            "Presented HW frame #%u size=%ux%u",
            g_bridge.swap_count,
            width,
            height);
        g_hw_frame_log_count++;
    }
    return 1;
}

static bool bridge_environment(unsigned cmd, void *data)
{
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        if (data) {
            struct retro_log_callback *callback = (struct retro_log_callback *)data;
            callback->log = bridge_log;
            return true;
        }
        return false;

    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        if (data) {
            const char **path = (const char **)data;
            *path = g_bridge.root_path[0] ? g_bridge.root_path : ".";
            return true;
        }
        return false;

    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        if (data) {
            unsigned *version = (unsigned *)data;
            *version = 2;
            return true;
        }
        return false;

    case RETRO_ENVIRONMENT_GET_LANGUAGE:
        if (data) {
            unsigned *language = (unsigned *)data;
            *language = RETRO_LANGUAGE_ENGLISH;
            return true;
        }
        return false;

    case RETRO_ENVIRONMENT_GET_VARIABLE:
        if (data) {
            struct retro_variable *variable = (struct retro_variable *)data;
            variable->value = bridge_find_option_value(variable->key);
            return variable->value != NULL;
        }
        return false;

    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        if (data) {
            bool *updated = (bool *)data;
            *updated = false;
            return true;
        }
        return false;

    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        if (data) {
            bool *can_dupe = (bool *)data;
            *can_dupe = true;
            return true;
        }
        return false;

    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        return true;

    case RETRO_ENVIRONMENT_GET_PERF_INTERFACE:
        if (data) {
            memset(data, 0, sizeof(struct retro_perf_callback));
        }
        return false;

    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
        if (data) {
            memset(data, 0, sizeof(struct retro_rumble_interface));
            return true;
        }
        return false;

    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        if (data && *(const unsigned *)data == RETRO_PIXEL_FORMAT_XRGB8888) {
            return true;
        }
        bridge_set_error("Only RETRO_PIXEL_FORMAT_XRGB8888 is supported by this frontend.");
        return false;

    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
    case RETRO_ENVIRONMENT_POLL_TYPE_OVERRIDE:
        return true;

    case RETRO_ENVIRONMENT_SET_VARIABLES:
        if (bridge_register_options_from_variables((const struct retro_variable *)data)) {
            return true;
        }
        bridge_set_error("Failed to cache libretro core option defaults from SET_VARIABLES.");
        return false;

    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        if (bridge_register_options_from_definitions((const struct retro_core_option_definition *)data)) {
            return true;
        }
        bridge_set_error("Failed to cache libretro core option defaults from SET_CORE_OPTIONS.");
        return false;

    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
        if (data) {
            const struct retro_core_options_intl *options_intl =
                (const struct retro_core_options_intl *)data;
            const struct retro_core_option_definition *definitions =
                options_intl->local ? options_intl->local : options_intl->us;
            if (bridge_register_options_from_definitions(definitions)) {
                return true;
            }
        }
        bridge_set_error("Failed to cache libretro core option defaults from SET_CORE_OPTIONS_INTL.");
        return false;

    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        if (data) {
            const struct retro_core_options_v2 *options_v2 =
                (const struct retro_core_options_v2 *)data;
            if (bridge_register_options_from_v2_definitions(options_v2->definitions)) {
                return true;
            }
        }
        bridge_set_error("Failed to cache libretro core option defaults from SET_CORE_OPTIONS_V2.");
        return false;

    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
        if (data) {
            const struct retro_core_options_v2_intl *options_v2_intl =
                (const struct retro_core_options_v2_intl *)data;
            const struct retro_core_options_v2 *options_v2 =
                options_v2_intl->local ? options_v2_intl->local : options_v2_intl->us;
            if (options_v2 && bridge_register_options_from_v2_definitions(options_v2->definitions)) {
                return true;
            }
        }
        bridge_set_error("Failed to cache libretro core option defaults from SET_CORE_OPTIONS_V2_INTL.");
        return false;

    case RETRO_ENVIRONMENT_SET_GEOMETRY:
        if (data) {
            const struct retro_game_geometry *geometry = (const struct retro_game_geometry *)data;
            g_bridge.av_info.geometry = *geometry;
            if (geometry->base_width > 0 && geometry->base_height > 0) {
                g_bridge.frame_width = geometry->base_width;
                g_bridge.frame_height = geometry->base_height;
            }
            return true;
        }
        return false;

    case RETRO_ENVIRONMENT_SET_MESSAGE:
        if (data) {
            const struct retro_message *message = (const struct retro_message *)data;
            if (message && message->msg) {
                bridge_log(RETRO_LOG_INFO, "%s", message->msg);
            }
            return true;
        }
        return false;

    case RETRO_ENVIRONMENT_GET_CLEAR_ALL_THREAD_WAITS_CB:
        if (data) {
            retro_environment_t *callback = (retro_environment_t *)data;
            *callback = bridge_clear_all_thread_waits_cb;
            return true;
        }
        return false;

    case RETRO_ENVIRONMENT_SET_HW_RENDER:
        if (data) {
            struct retro_hw_render_callback *hw_render = (struct retro_hw_render_callback *)data;

            bridge_log(
                RETRO_LOG_INFO,
                "SET_HW_RENDER context_type=%d version=%u.%u cache=%d depth=%d stencil=%d",
                (int)hw_render->context_type,
                hw_render->version_major,
                hw_render->version_minor,
                hw_render->cache_context ? 1 : 0,
                hw_render->depth ? 1 : 0,
                hw_render->stencil ? 1 : 0);

            if (!bridge_hw_context_is_supported(hw_render)) {
                bridge_set_error(
                    "This frontend only supports GLES3 hardware rendering. Requested context_type=%d version=%u.%u",
                    (int)hw_render->context_type,
                    hw_render->version_major,
                    hw_render->version_minor);
                return false;
            }

            pthread_mutex_lock(&g_bridge_mutex);
            g_bridge.hw_render = *hw_render;
            g_bridge.hw_render.get_current_framebuffer = bridge_get_current_framebuffer;
            g_bridge.hw_render.get_proc_address = bridge_get_proc_address;
            g_bridge.hw_requested = 1;
            g_bridge.hw_context_ready = 0;
            pthread_mutex_unlock(&g_bridge_mutex);

            *hw_render = g_bridge.hw_render;
            return true;
        }
        return false;

    case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE:
        bridge_set_error("Vulkan context negotiation is not supported by the GLES3 frontend.");
        return false;

    default:
        return false;
    }
}

static int bridge_reserve_framebuffer(size_t pixel_count)
{
    uint32_t *new_buffer;

    if (pixel_count <= g_bridge.framebuffer_capacity_pixels) {
        return 1;
    }

    new_buffer = (uint32_t *)realloc(g_bridge.framebuffer, pixel_count * sizeof(uint32_t));
    if (!new_buffer) {
        bridge_set_error("Unable to allocate %zu pixels for the libretro framebuffer.", pixel_count);
        return 0;
    }

    g_bridge.framebuffer = new_buffer;
    g_bridge.framebuffer_capacity_pixels = pixel_count;
    return 1;
}

static void bridge_copy_video_frame(const void *data, unsigned width, unsigned height, size_t pitch)
{
    const uint8_t *src_bytes;
    size_t y;
    size_t pixels;

    if (!data || width == 0 || height == 0 || pitch < width * sizeof(uint32_t)) {
        return;
    }

    pixels = (size_t)width * (size_t)height;
    if (!bridge_reserve_framebuffer(pixels)) {
        return;
    }

    src_bytes = (const uint8_t *)data;
    for (y = 0; y < height; y++) {
        const uint32_t *src_row = (const uint32_t *)(src_bytes + y * pitch);
        uint32_t *dst_row = g_bridge.framebuffer + ((size_t)y * width);
        unsigned x;

        for (x = 0; x < width; x++) {
            dst_row[x] = src_row[x] | 0xFF000000u;
        }
    }

    g_bridge.frame_width = width;
    g_bridge.frame_height = height;
    g_bridge.swap_count++;
}

static void bridge_video_refresh(const void *data, unsigned width, unsigned height, size_t pitch)
{
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        bridge_hw_present(width, height);
        return;
    }

    if (!data) {
        return;
    }

    bridge_copy_video_frame(data, width, height, pitch);
    if (g_sw_frame_log_count < 12) {
        bridge_log(
            RETRO_LOG_INFO,
            "Received SW frame #%u size=%ux%u pitch=%zu",
            g_sw_frame_log_count + 1u,
            width,
            height,
            pitch);
        g_sw_frame_log_count++;
    }
}

static void bridge_audio_sample(int16_t left, int16_t right)
{
    int16_t frame[2] = {left, right};
    audio_player_push_batch(frame, 1);
}

static size_t bridge_audio_sample_batch(const int16_t *data, size_t frames)
{
    audio_player_push_batch(data, frames);
    return frames;
}

static void bridge_input_poll(void)
{
}

static int scale_n64_axis_to_libretro(int value)
{
    int clamped = value;

    if (clamped < -80) {
        clamped = -80;
    } else if (clamped > 80) {
        clamped = 80;
    }

    return (clamped * ANALOG_MAX) / 80;
}

static int16_t bridge_input_state(unsigned port, unsigned device, unsigned index, unsigned id)
{
    unsigned int button_mask = g_button_mask;
    int stick_x = g_stick_x;
    int stick_y = g_stick_y;

    if (port > 0) {
        return 0;
    }

    if (device == RETRO_DEVICE_JOYPAD) {
        unsigned int joypad_mask = 0;

        if (button_mask & N64_DPAD_RIGHT) joypad_mask |= 1u << RETRO_DEVICE_ID_JOYPAD_RIGHT;
        if (button_mask & N64_DPAD_LEFT) joypad_mask |= 1u << RETRO_DEVICE_ID_JOYPAD_LEFT;
        if (button_mask & N64_DPAD_DOWN) joypad_mask |= 1u << RETRO_DEVICE_ID_JOYPAD_DOWN;
        if (button_mask & N64_DPAD_UP) joypad_mask |= 1u << RETRO_DEVICE_ID_JOYPAD_UP;
        if (button_mask & N64_START) joypad_mask |= 1u << RETRO_DEVICE_ID_JOYPAD_START;
        if (button_mask & N64_Z_TRIGGER) joypad_mask |= 1u << RETRO_DEVICE_ID_JOYPAD_L2;
        if (button_mask & N64_L_TRIGGER) joypad_mask |= 1u << RETRO_DEVICE_ID_JOYPAD_L;
        if (button_mask & N64_R_TRIGGER) joypad_mask |= 1u << RETRO_DEVICE_ID_JOYPAD_R;
        if (button_mask & N64_A_BUTTON) joypad_mask |= 1u << RETRO_DEVICE_ID_JOYPAD_B;
        if (button_mask & N64_B_BUTTON) joypad_mask |= 1u << RETRO_DEVICE_ID_JOYPAD_Y;

        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
            return (int16_t)joypad_mask;
        }

        return (joypad_mask & (1u << id)) ? 1 : 0;
    }

    if (device == RETRO_DEVICE_ANALOG) {
        if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {
            if (id == RETRO_DEVICE_ID_ANALOG_X) {
                return (int16_t)scale_n64_axis_to_libretro(stick_x);
            }
            if (id == RETRO_DEVICE_ID_ANALOG_Y) {
                return (int16_t)(-scale_n64_axis_to_libretro(stick_y));
            }
        } else if (index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
            if (id == RETRO_DEVICE_ID_ANALOG_X) {
                if (button_mask & N64_C_RIGHT) {
                    return (int16_t)-ANALOG_MAX;
                }
                if (button_mask & N64_C_LEFT) {
                    return (int16_t)ANALOG_MAX;
                }
            } else if (id == RETRO_DEVICE_ID_ANALOG_Y) {
                if (button_mask & N64_C_UP) {
                    return (int16_t)-ANALOG_MAX;
                }
                if (button_mask & N64_C_DOWN) {
                    return (int16_t)ANALOG_MAX;
                }
            }
        }
    }

    return 0;
}

static int bridge_load_symbols(void)
{
#define LOAD_SYMBOL(symbol_name)                                                                \
    do {                                                                                        \
        const char *symbol_error;                                                               \
                                                                                                \
        dlerror();                                                                              \
        g_bridge.symbol_name = (symbol_name##_fn)dlsym(g_bridge.handle, #symbol_name);         \
        if (!g_bridge.symbol_name) {                                                            \
            symbol_error = dlerror();                                                           \
            bridge_set_error("Missing symbol %s in %s: %s", #symbol_name,                      \
                NIN64_LIBRETRO_CORE_NAME, symbol_error ? symbol_error : "unknown error");      \
            return 0;                                                                           \
        }                                                                                       \
    } while (0)

    LOAD_SYMBOL(retro_api_version);
    LOAD_SYMBOL(retro_deinit);
    LOAD_SYMBOL(retro_get_system_av_info);
    LOAD_SYMBOL(retro_get_system_info);
    LOAD_SYMBOL(retro_init);
    LOAD_SYMBOL(retro_load_game);
    LOAD_SYMBOL(retro_run);
    LOAD_SYMBOL(retro_set_audio_sample);
    LOAD_SYMBOL(retro_set_audio_sample_batch);
    LOAD_SYMBOL(retro_set_environment);
    LOAD_SYMBOL(retro_set_input_poll);
    LOAD_SYMBOL(retro_set_input_state);
    LOAD_SYMBOL(retro_set_video_refresh);
    LOAD_SYMBOL(retro_unload_game);

#undef LOAD_SYMBOL

    return 1;
}

static int bridge_initialize(const char *root_path)
{
    g_bridge.last_error[0] = 0;

    if (root_path && root_path[0]) {
        snprintf(g_bridge.root_path, sizeof(g_bridge.root_path), "%s", root_path);
    } else if (!g_bridge.root_path[0]) {
        snprintf(g_bridge.root_path, sizeof(g_bridge.root_path), "%s", ".");
    }

    if (!g_bridge.handle) {
        const char *load_error;

        dlerror();
        g_bridge.handle = dlopen(NIN64_LIBRETRO_CORE_NAME, RTLD_NOW | RTLD_LOCAL);
        if (!g_bridge.handle) {
            load_error = dlerror();
            bridge_set_error(
                "Unable to load %s. Run scripts/build_mupen64plus_next_android.sh first. dlerror=%s",
                NIN64_LIBRETRO_CORE_NAME,
                load_error ? load_error : "unknown error");
            return 0;
        }

        if (!bridge_load_symbols()) {
            dlclose(g_bridge.handle);
            g_bridge.handle = NULL;
            return 0;
        }

        g_bridge.api_version = g_bridge.retro_api_version();
        memset(&g_bridge.system_info, 0, sizeof(g_bridge.system_info));
        g_bridge.retro_get_system_info(&g_bridge.system_info);
    }

    g_bridge.retro_set_environment(bridge_environment);
    g_bridge.retro_set_video_refresh(bridge_video_refresh);
    g_bridge.retro_set_audio_sample(bridge_audio_sample);
    g_bridge.retro_set_audio_sample_batch(bridge_audio_sample_batch);
    g_bridge.retro_set_input_poll(bridge_input_poll);
    g_bridge.retro_set_input_state(bridge_input_state);

    if (!g_bridge.core_initialized) {
        g_bridge.retro_init();
        g_bridge.core_initialized = 1;
    }

    return 1;
}

static int bridge_load_rom_file(const char *path, void **data_out, size_t *size_out)
{
    FILE *file;
    long length;
    void *data;
    size_t read_bytes;

    if (!path || !path[0] || !data_out || !size_out) {
        bridge_set_error("Invalid ROM path.");
        return 0;
    }

    file = fopen(path, "rb");
    if (!file) {
        bridge_set_error("Unable to open ROM: %s", path);
        return 0;
    }

    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        bridge_set_error("Unable to seek ROM: %s", path);
        return 0;
    }

    length = ftell(file);
    if (length <= 0) {
        fclose(file);
        bridge_set_error("ROM is empty or unreadable: %s", path);
        return 0;
    }

    if (fseek(file, 0, SEEK_SET) != 0) {
        fclose(file);
        bridge_set_error("Unable to rewind ROM: %s", path);
        return 0;
    }

    data = malloc((size_t)length);
    if (!data) {
        fclose(file);
        bridge_set_error("Unable to allocate %ld bytes for ROM data.", length);
        return 0;
    }

    read_bytes = fread(data, 1, (size_t)length, file);
    fclose(file);

    if (read_bytes != (size_t)length) {
        free(data);
        bridge_set_error("Failed to read the full ROM payload from %s.", path);
        return 0;
    }

    *data_out = data;
    *size_out = read_bytes;
    return 1;
}

static void bridge_release_game_data(void)
{
    free(g_bridge.rom_data);
    g_bridge.rom_data = NULL;
    g_bridge.rom_size = 0;
}

static void bridge_reset_video_state(void)
{
    g_bridge.frame_width = 0;
    g_bridge.frame_height = 0;
    g_bridge.swap_count = 0;
    g_bridge.frame_interval_ns = DEFAULT_FRAME_INTERVAL_NS;
    g_bridge.next_frame_deadline_ns = 0;
}

static void bridge_unload_game(void)
{
    audio_player_stop();
    
    if (g_bridge.game_loaded) {
        g_bridge.retro_unload_game();
        g_bridge.game_loaded = 0;
    }

    g_bridge.last_rom_path[0] = 0;
    bridge_release_game_data();
    bridge_reset_video_state();
}

static uint64_t bridge_compute_frame_interval_ns(double fps)
{
    if (fps < 1.0 || fps > 240.0) {
        return DEFAULT_FRAME_INTERVAL_NS;
    }

    return (uint64_t)(1000000000.0 / fps);
}

static int bridge_load_game_from_path(const char *root_path, const char *rom_path)
{
    struct retro_game_info game = {0};

    bridge_log(RETRO_LOG_INFO, "bridge_load_game_from_path rom=%s", rom_path ? rom_path : "(null)");
    bridge_shutdown();
    bridge_reset_video_state();

    if (!bridge_initialize(root_path)) {
        return 0;
    }

    if (!bridge_load_rom_file(rom_path, &g_bridge.rom_data, &g_bridge.rom_size)) {
        return 0;
    }

    game.path = rom_path;
    game.data = g_bridge.rom_data;
    game.size = g_bridge.rom_size;
    game.meta = NULL;

    if (!g_bridge.retro_load_game(&game)) {
        bridge_release_game_data();
        if (!g_bridge.last_error[0]) {
            bridge_set_error("retro_load_game() failed for %s", rom_path);
        }
        return 0;
    }

    g_bridge.game_loaded = 1;
    snprintf(g_bridge.last_rom_path, sizeof(g_bridge.last_rom_path), "%s", rom_path);

    memset(&g_bridge.av_info, 0, sizeof(g_bridge.av_info));
    g_bridge.retro_get_system_av_info(&g_bridge.av_info);
    
    // Start Oboe Audio
    int sample_rate = (int)(g_bridge.av_info.timing.sample_rate > 0 ? g_bridge.av_info.timing.sample_rate : 44100);
    audio_player_start(sample_rate);
    
    g_bridge.frame_width = g_bridge.av_info.geometry.base_width;
    g_bridge.frame_height = g_bridge.av_info.geometry.base_height;
    g_bridge.frame_interval_ns = bridge_compute_frame_interval_ns(g_bridge.av_info.timing.fps);
    g_bridge.next_frame_deadline_ns = bridge_now_ns();
    g_hw_frame_log_count = 0;
    g_sw_frame_log_count = 0;
    g_run_frame_log_count = 0;

    bridge_log(
        RETRO_LOG_INFO,
        "retro_load_game ok fps=%.2f base=%ux%u max=%ux%u hw_requested=%d",
        g_bridge.av_info.timing.fps,
        g_bridge.av_info.geometry.base_width,
        g_bridge.av_info.geometry.base_height,
        g_bridge.av_info.geometry.max_width,
        g_bridge.av_info.geometry.max_height,
        g_bridge.hw_requested);

    bridge_log(
        RETRO_LOG_INFO,
        "Libretro options rdp=%s rsp=%s MaxTxCacheSize=%s EnableFBEmulation=%s EnableTextureCache=%s ThreadedRenderer=%s cpucore=%s 43screensize=%s 169screensize=%s",
        bridge_find_option_value("mupen64plus-rdp-plugin"),
        bridge_find_option_value("mupen64plus-rsp-plugin"),
        bridge_find_option_value("mupen64plus-MaxTxCacheSize"),
        bridge_find_option_value("mupen64plus-EnableFBEmulation"),
        bridge_find_option_value("mupen64plus-EnableTextureCache"),
        bridge_find_option_value("mupen64plus-ThreadedRenderer"),
        bridge_find_option_value("mupen64plus-cpucore"),
        bridge_find_option_value("mupen64plus-43screensize"),
        bridge_find_option_value("mupen64plus-169screensize"));

    if (g_bridge.hw_requested && !bridge_hw_ensure_ready()) {
        bridge_set_error(
            "Failed to initialize the GLES3 frontend for %s: %s",
            rom_path,
            g_bridge.last_error[0] ? g_bridge.last_error : "unknown error");
        bridge_unload_game();
        return 0;
    }

    return 1;
}

static void bridge_release_framebuffer(void)
{
    free(g_bridge.framebuffer);
    g_bridge.framebuffer = NULL;
    g_bridge.framebuffer_capacity_pixels = 0;
}

static void bridge_shutdown(void)
{
    bridge_unload_game();
    bridge_hw_destroy_context(1);
    bridge_hw_clear_request_state();

    if (g_bridge.handle) {
        if (g_bridge.core_initialized) {
            g_bridge.retro_deinit();
            g_bridge.core_initialized = 0;
        }
        dlclose(g_bridge.handle);
        g_bridge.handle = NULL;
    }

    bridge_clear_registered_options();
}

static uint64_t bridge_now_ns(void)
{
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ((uint64_t)ts.tv_sec * 1000000000ULL) + (uint64_t)ts.tv_nsec;
}

static void bridge_sleep_until_ns(uint64_t deadline_ns)
{
    uint64_t now_ns = bridge_now_ns();

    if (deadline_ns <= now_ns) {
        return;
    }

    while (deadline_ns > now_ns) {
        uint64_t remaining_ns = deadline_ns - now_ns;
        struct timespec sleep_time;

        sleep_time.tv_sec = (time_t)(remaining_ns / 1000000000ULL);
        sleep_time.tv_nsec = (long)(remaining_ns % 1000000000ULL);

        if (nanosleep(&sleep_time, NULL) == 0) {
            break;
        }

        if (errno != EINTR) {
            break;
        }

        now_ns = bridge_now_ns();
    }
}

static void bridge_run_frame(void)
{
    uint64_t now_ns;

    if (!g_bridge.game_loaded) {
        return;
    }

    if (g_bridge.hw_requested && !bridge_hw_ensure_ready()) {
        return;
    }

    if (g_run_frame_log_count < 12) {
        bridge_log(
            RETRO_LOG_INFO,
            "bridge_run_frame #%u game_loaded=%d hw_requested=%d size=%ux%u",
            g_run_frame_log_count + 1u,
            g_bridge.game_loaded,
            g_bridge.hw_requested,
            g_bridge.frame_width,
            g_bridge.frame_height);
        g_run_frame_log_count++;
    }

    if (!g_bridge.next_frame_deadline_ns) {
        g_bridge.next_frame_deadline_ns = bridge_now_ns();
    }

    bridge_sleep_until_ns(g_bridge.next_frame_deadline_ns);
    g_bridge.retro_run();

    now_ns = bridge_now_ns();
    if (!g_bridge.frame_interval_ns) {
        g_bridge.frame_interval_ns = DEFAULT_FRAME_INTERVAL_NS;
    }

    g_bridge.next_frame_deadline_ns += g_bridge.frame_interval_ns;
    if (g_bridge.next_frame_deadline_ns + g_bridge.frame_interval_ns < now_ns) {
        g_bridge.next_frame_deadline_ns = now_ns + g_bridge.frame_interval_ns;
    }
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_nin64_NativeBridge_init(JNIEnv *env, jobject thiz, jstring rootPath)
{
    char root[1024];
    char message[2048];

    (void)thiz;

    copy_jstring(env, rootPath, root, sizeof(root));

    if (!bridge_initialize(root)) {
        snprintf(message, sizeof(message),
            "Libretro bridge ready\n"
            "backend=libretro\n"
            "video=gles3 frontend\n"
            "core=%s\n"
            "status=not loaded\n"
            "error=%s",
            NIN64_LIBRETRO_CORE_NAME,
            g_bridge.last_error[0] ? g_bridge.last_error : "unknown error");
        return (*env)->NewStringUTF(env, message);
    }

    snprintf(message, sizeof(message),
        "Libretro bridge ready\n"
        "backend=libretro\n"
        "video=gles3 frontend\n"
        "packagedCore=%s\n"
        "api=%u\n"
        "library=%s\n"
        "version=%s\n"
        "extensions=%s\n"
        "systemDir=%s\n"
        "rendererPlan=GLideN64 on a native EGL ES3 surface",
        NIN64_LIBRETRO_CORE_NAME,
        g_bridge.api_version,
        g_bridge.system_info.library_name ? g_bridge.system_info.library_name : "(unknown)",
        g_bridge.system_info.library_version ? g_bridge.system_info.library_version : "(unknown)",
        g_bridge.system_info.valid_extensions ? g_bridge.system_info.valid_extensions : "(unknown)",
        g_bridge.root_path);

    return (*env)->NewStringUTF(env, message);
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_nin64_NativeBridge_smokeTest(JNIEnv *env, jobject thiz)
{
    char message[2048];

    (void)thiz;

    if (!g_bridge.handle) {
        return (*env)->NewStringUTF(env, "Libretro bridge is not initialized yet.");
    }

    snprintf(message, sizeof(message),
        "Libretro bridge status\n"
        "backend=libretro\n"
        "video=gles3 frontend\n"
        "packagedRenderer=GLES3\n"
        "api=%u\n"
        "library=%s\n"
        "version=%s\n"
        "rom=%s\n"
        "loaded=%s\n"
        "frame=%ux%u\n"
        "fps=%.2f\n"
        "swaps=%u\n"
        "error=%s",
        g_bridge.api_version,
        g_bridge.system_info.library_name ? g_bridge.system_info.library_name : "(unknown)",
        g_bridge.system_info.library_version ? g_bridge.system_info.library_version : "(unknown)",
        g_bridge.last_rom_path[0] ? g_bridge.last_rom_path : "(none)",
        g_bridge.game_loaded ? "yes" : "no",
        g_bridge.frame_width,
        g_bridge.frame_height,
        g_bridge.av_info.timing.fps,
        g_bridge.swap_count,
        g_bridge.last_error[0] ? g_bridge.last_error : "(none)");

    return (*env)->NewStringUTF(env, message);
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_nin64_NativeBridge_bootRom(
    JNIEnv *env,
    jobject thiz,
    jstring rootPath,
    jstring romPath,
    jint stepOps,
    jint stepCount
)
{
    char root[1024];
    char rom[1024];
    char message[2048];
    int frames_to_run;
    int frame_index;

    (void)thiz;
    (void)stepOps;

    copy_jstring(env, rootPath, root, sizeof(root));
    copy_jstring(env, romPath, rom, sizeof(rom));

    if (!bridge_load_game_from_path(root, rom)) {
        snprintf(message, sizeof(message),
            "Libretro boot failed\n"
            "rom=%s\n"
            "error=%s",
            rom,
            g_bridge.last_error[0] ? g_bridge.last_error : "unknown error");
        return (*env)->NewStringUTF(env, message);
    }

    frames_to_run = (int)stepCount;
    if (frames_to_run <= 0) {
        frames_to_run = 2;
    }

    for (frame_index = 0; frame_index < frames_to_run; frame_index++) {
        bridge_run_frame();
    }

    snprintf(message, sizeof(message),
        "Libretro ROM launch complete\n"
        "rom=%s\n"
        "video=gles3 frontend\n"
        "packagedRenderer=GLES3\n"
        "loaded=yes\n"
        "frame=%ux%u\n"
        "fps=%.2f\n"
        "swaps=%u",
        rom,
        g_bridge.frame_width,
        g_bridge.frame_height,
        g_bridge.av_info.timing.fps,
        g_bridge.swap_count);

    return (*env)->NewStringUTF(env, message);
}

JNIEXPORT jint JNICALL
Java_com_izzy2lost_nin64_NativeBridge_getFrameWidth(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    return (jint)g_bridge.frame_width;
}

JNIEXPORT jint JNICALL
Java_com_izzy2lost_nin64_NativeBridge_getFrameHeight(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    return (jint)g_bridge.frame_height;
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_nin64_NativeBridge_bootRomForPlay(
    JNIEnv *env,
    jobject thiz,
    jstring rootPath,
    jstring romPath
)
{
    char root[1024];
    char rom[1024];
    char message[2048];

    (void)thiz;

    copy_jstring(env, rootPath, root, sizeof(root));
    copy_jstring(env, romPath, rom, sizeof(rom));
    bridge_log(RETRO_LOG_INFO, "bootRomForPlay root=%s rom=%s", root, rom);

    if (!bridge_load_game_from_path(root, rom)) {
        snprintf(message, sizeof(message),
            "Libretro boot failed\n"
            "rom=%s\n"
            "error=%s",
            rom,
            g_bridge.last_error[0] ? g_bridge.last_error : "unknown error");
        return (*env)->NewStringUTF(env, message);
    }

    return (*env)->NewStringUTF(env, "booted");
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_nin64_NativeBridge_runFrame(JNIEnv *env, jobject thiz, jint ops)
{
    (void)env;
    (void)thiz;
    (void)ops;
    bridge_run_frame();
}

JNIEXPORT jint JNICALL
Java_com_izzy2lost_nin64_NativeBridge_getSwapCount(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    return (jint)g_bridge.swap_count;
}

JNIEXPORT jintArray JNICALL
Java_com_izzy2lost_nin64_NativeBridge_copyFrameBufferArgb(JNIEnv *env, jobject thiz)
{
    jintArray array;
    int total_pixels;

    (void)thiz;

    total_pixels = (int)(g_bridge.frame_width * g_bridge.frame_height);
    if (total_pixels <= 0 || !g_bridge.framebuffer) {
        return (*env)->NewIntArray(env, 0);
    }

    array = (*env)->NewIntArray(env, total_pixels);
    if (array) {
        (*env)->SetIntArrayRegion(env, array, 0, total_pixels, (const jint *)g_bridge.framebuffer);
    }

    return array;
}

JNIEXPORT jint JNICALL
Java_com_izzy2lost_nin64_NativeBridge_copyFrameBufferArgbInto(JNIEnv *env, jobject thiz, jintArray array)
{
    jint *pixels;
    jsize max_pixels;
    int total_pixels;

    (void)thiz;

    if (!array || !g_bridge.framebuffer) {
        return 0;
    }

    total_pixels = (int)(g_bridge.frame_width * g_bridge.frame_height);
    if (total_pixels <= 0) {
        return 0;
    }

    max_pixels = (*env)->GetArrayLength(env, array);
    if (max_pixels < total_pixels) {
        return 0;
    }

    pixels = (jint *)(*env)->GetPrimitiveArrayCritical(env, array, NULL);
    if (!pixels) {
        return 0;
    }

    memcpy(pixels, g_bridge.framebuffer, (size_t)total_pixels * sizeof(uint32_t));
    (*env)->ReleasePrimitiveArrayCritical(env, array, pixels, 0);
    return total_pixels;
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_nin64_NativeBridge_setControllerState(
    JNIEnv *env,
    jobject thiz,
    jint buttonMask,
    jint stickX,
    jint stickY
)
{
    (void)env;
    (void)thiz;

    g_button_mask = (unsigned int)buttonMask;
    g_stick_x = (int)stickX;
    g_stick_y = (int)stickY;
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_nin64_NativeBridge_setSurface(
    JNIEnv *env,
    jobject thiz,
    jobject surface,
    jint width,
    jint height
)
{
    (void)thiz;
    bridge_set_surface_from_java(env, surface, width, height);
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_nin64_NativeBridge_clearSurface(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    bridge_set_surface_from_java(NULL, NULL, 0, 0);
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_nin64_NativeBridge_shutdownSession(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    bridge_log(RETRO_LOG_INFO, "shutdownSession");
    bridge_shutdown();
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_nin64_NativeBridge_setOption(JNIEnv *env, jobject thiz, jstring key, jstring value)
{
    const char *key_utf;
    const char *value_utf;

    (void)thiz;

    if (!key || !value) {
        return;
    }

    key_utf = (*env)->GetStringUTFChars(env, key, NULL);
    value_utf = (*env)->GetStringUTFChars(env, value, NULL);

    if (key_utf && value_utf) {
        bridge_set_user_option(key_utf, value_utf);
    }

    if (key_utf) (*env)->ReleaseStringUTFChars(env, key, key_utf);
    if (value_utf) (*env)->ReleaseStringUTFChars(env, value, value_utf);
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    g_bridge.frame_interval_ns = DEFAULT_FRAME_INTERVAL_NS;
    g_bridge.egl_display = EGL_NO_DISPLAY;
    g_bridge.egl_context = EGL_NO_CONTEXT;
    g_bridge.egl_surface = EGL_NO_SURFACE;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM *vm, void *reserved)
{
    ANativeWindow *window;

    (void)vm;
    (void)reserved;

    bridge_shutdown();

    pthread_mutex_lock(&g_bridge_mutex);
    window = g_bridge.window;
    g_bridge.window = NULL;
    pthread_mutex_unlock(&g_bridge_mutex);

    bridge_release_window(window);
    bridge_release_framebuffer();
}
