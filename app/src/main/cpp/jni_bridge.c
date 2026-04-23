#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ultra.h"

extern void android_port_reset_runtime(void);
extern void android_port_copy_logtail(char *buffer, size_t buffer_size);
extern int android_port_frame_width(void);
extern int android_port_frame_height(void);
extern int android_port_swap_count(void);
extern int android_port_background_copy_count(void);
extern int android_port_copy_framebuffer(unsigned int *buffer, int max_pixels);
extern void android_port_set_pad_state(unsigned int button_mask, int stick_x, int stick_y);
extern void os_dumpinfo(void);

static void copy_jstring(JNIEnv *env, jstring value, char *buffer, size_t buffer_size)
{
    const char *utf;

    if(!buffer || buffer_size==0)
    {
        return;
    }

    buffer[0]=0;
    if(!value)
    {
        return;
    }

    utf=(*env)->GetStringUTFChars(env,value,NULL);
    if(!utf)
    {
        return;
    }

    snprintf(buffer,buffer_size,"%s",utf);
    (*env)->ReleaseStringUTFChars(env,value,utf);
}

static void configure_paths(const char *root)
{
    memset(&init,0,sizeof(init));
    memset(&view,0,sizeof(view));

    snprintf(init.rootpath,sizeof(init.rootpath),"%s",root);
    snprintf(init.savepath,sizeof(init.savepath),"%s",root);
    snprintf(init.rompath,sizeof(init.rompath),"%s",root);

    fixpath(init.rootpath,0);
    fixpath(init.savepath,0);
    fixpath(init.rompath,0);

    init.gfxwid=320;
    init.gfxhig=240;
    init.showconsole=0;
    init.nomemmap=1;
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_nin64_NativeBridge_init(JNIEnv *env, jobject thiz, jstring rootPath)
{
    char root[1024];
    char message[512];

    (void)thiz;

    copy_jstring(env,rootPath,root,sizeof(root));
    configure_paths(root);
    android_port_reset_runtime();

    snprintf(message,sizeof(message),
        "Native core ready\n"
        "root=%s\n"
        "save=%s\n"
        "rom=%s\n"
        "renderer=headless stub\n"
        "audio=stub\n"
        "cpu=interpreter",
        init.rootpath,
        init.savepath,
        init.rompath);

    return (*env)->NewStringUTF(env,message);
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_nin64_NativeBridge_smokeTest(JNIEnv *env, jobject thiz)
{
    char message[2048];
    char logtail[1024];

    (void)thiz;

    if(!init.rootpath[0])
    {
        return (*env)->NewStringUTF(env,"Native core is not initialized yet.");
    }

    android_port_reset_runtime();
    boot(NULL,0);
    cpu_exec(512,0);
    android_port_copy_logtail(logtail,sizeof(logtail));

    snprintf(message,sizeof(message),
        "Smoke test complete\n"
        "cart=%s\n"
        "pc=%08X\n"
        "codebase=%08X\n"
        "cputime=%d\n"
        "frames=%d\n"
        "retraces=%d\n"
        "swaps=%d\n"
        "backgroundCopies=%d\n"
        "\nRecent log:\n%s",
        cart.title,
        st.pc,
        cart.codebase,
        (int)st.cputime,
        st.frames,
        st.retraces,
        android_port_swap_count(),
        android_port_background_copy_count(),
        logtail);

    return (*env)->NewStringUTF(env,message);
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
    char logtail[3072];
    char message[8192];
    int ops_per_step;
    int steps_to_run;
    int total_ops;

    (void)thiz;

    copy_jstring(env,rootPath,root,sizeof(root));
    copy_jstring(env,romPath,rom,sizeof(rom));

    configure_paths(root);
    android_port_reset_runtime();
    boot(rom,0);

    ops_per_step = (int)stepOps;
    if(ops_per_step<=0)
    {
        ops_per_step=250000;
    }

    steps_to_run = (int)stepCount;
    if(steps_to_run<=0)
    {
        steps_to_run=24;
    }

    total_ops = ops_per_step * steps_to_run;
    if(steps_to_run > 0 && total_ops / steps_to_run != ops_per_step)
    {
        total_ops = 250000 * 24;
    }
    cpu_exec(total_ops,0);
    os_dumpinfo();

    android_port_copy_logtail(logtail,sizeof(logtail));

    snprintf(message,sizeof(message),
        "ROM launch complete\n"
        "file=%s\n"
        "cart=%s\n"
        "pc=%08X\n"
        "codebase=%08X\n"
        "cputime=%d\n"
        "retraces=%d\n"
        "frames=%d\n"
        "swaps=%d\n"
        "backgroundCopies=%d\n"
        "preview=%dx%d\n"
        "steps=%d x %d\n"
        "\nRecent log:\n%s",
        rom,
        cart.title,
        st.pc,
        cart.codebase,
        (int)st.cputime,
        st.retraces,
        st.frames,
        android_port_swap_count(),
        android_port_background_copy_count(),
        android_port_frame_width(),
        android_port_frame_height(),
        steps_to_run,
        ops_per_step,
        logtail);

    return (*env)->NewStringUTF(env,message);
}

JNIEXPORT jint JNICALL
Java_com_izzy2lost_nin64_NativeBridge_getFrameWidth(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    return android_port_frame_width();
}

JNIEXPORT jint JNICALL
Java_com_izzy2lost_nin64_NativeBridge_getFrameHeight(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    return android_port_frame_height();
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

    (void)thiz;

    copy_jstring(env, rootPath, root, sizeof(root));
    copy_jstring(env, romPath, rom, sizeof(rom));

    configure_paths(root);
    android_port_reset_runtime();
    boot(rom, 0);

    return (*env)->NewStringUTF(env, "booted");
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_nin64_NativeBridge_runFrame(JNIEnv *env, jobject thiz, jint ops)
{
    (void)env;
    (void)thiz;
    cpu_exec((int)ops, 0);
}

JNIEXPORT jint JNICALL
Java_com_izzy2lost_nin64_NativeBridge_getSwapCount(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    return (jint)android_port_swap_count();
}

JNIEXPORT jintArray JNICALL
Java_com_izzy2lost_nin64_NativeBridge_copyFrameBufferArgb(JNIEnv *env, jobject thiz)
{
    jintArray array;
    unsigned int *buffer;
    int width;
    int height;
    int total_pixels;

    (void)thiz;

    width=android_port_frame_width();
    height=android_port_frame_height();
    total_pixels=width*height;

    if(total_pixels<=0)
    {
        return (*env)->NewIntArray(env,0);
    }

    buffer=(unsigned int *)malloc((size_t)total_pixels*sizeof(unsigned int));
    if(!buffer)
    {
        return (*env)->NewIntArray(env,0);
    }

    android_port_copy_framebuffer(buffer,total_pixels);

    array=(*env)->NewIntArray(env,total_pixels);
    if(array)
    {
        (*env)->SetIntArrayRegion(env,array,0,total_pixels,(const jint *)buffer);
    }

    free(buffer);
    return array;
}

JNIEXPORT jint JNICALL
Java_com_izzy2lost_nin64_NativeBridge_copyFrameBufferArgbInto(JNIEnv *env, jobject thiz, jintArray array)
{
    jint *pixels;
    jsize max_pixels;
    int copied_pixels;

    (void)thiz;

    if(!array)
    {
        return 0;
    }

    max_pixels=(*env)->GetArrayLength(env,array);
    if(max_pixels<=0)
    {
        return 0;
    }

    pixels=(jint *)(*env)->GetPrimitiveArrayCritical(env,array,NULL);
    if(!pixels)
    {
        return 0;
    }

    copied_pixels=android_port_copy_framebuffer((uint32_t *)pixels,(int)max_pixels);
    (*env)->ReleasePrimitiveArrayCritical(env,array,pixels,0);
    return (jint)copied_pixels;
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
    android_port_set_pad_state((unsigned int)buttonMask, (int)stickX, (int)stickY);
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
    (void)env;
    (void)thiz;
    (void)surface;
    (void)width;
    (void)height;
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_nin64_NativeBridge_clearSurface(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_nin64_NativeBridge_shutdownSession(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
}
