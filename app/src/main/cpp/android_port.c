#include <android/log.h>
#include <stdint.h>
#include "stdsdk.h"

#define LOG_TAG "Nin64Port"
#define PORT_LOG_BUFFER_SIZE 32768
#define PORT_FRAMEBUFFER_MAX_WIDTH 640
#define PORT_FRAMEBUFFER_MAX_HEIGHT 480
#define PORT_ECHO_LOGCAT 0

#define CONT_A      0x0080
#define CONT_B      0x0040
#define CONT_G      0x0020
#define CONT_START  0x0010
#define CONT_UP     0x0008
#define CONT_DOWN   0x0004
#define CONT_LEFT   0x0002
#define CONT_RIGHT  0x0001
#define CONT_L      0x2000
#define CONT_R      0x1000
#define CONT_E      0x0800
#define CONT_D      0x0400
#define CONT_C      0x0200
#define CONT_F      0x0100

ROMLIST romList;
View view;
void *hwndMain = NULL;

static int g_rdp_active;
static int g_swap_count;
static int g_background_copy_count;
static int g_framebuffer_width;
static int g_framebuffer_height;
static int g_framebuffer_ready;
static volatile uint32_t g_pad_buttons;
static volatile int g_pad_stick_x;
static volatile int g_pad_stick_y;
static uint32_t g_framebuffer[PORT_FRAMEBUFFER_MAX_WIDTH * PORT_FRAMEBUFFER_MAX_HEIGHT];
static char g_log_buffer[PORT_LOG_BUFFER_SIZE];
static size_t g_log_buffer_length;

static unsigned char expand5(int value);
static void capture_framebuffer_base(dword base);
static int clamp_pad_axis(int value);

static void copy_rdram_framebuffer_rgba5551(dword base, int wid, int hig)
{
    int x;
    int y;
    int copy_width;
    int copy_height;

    if (wid <= 0 || hig <= 0) {
        return;
    }

    copy_width = wid;
    copy_height = hig;
    if (copy_width > PORT_FRAMEBUFFER_MAX_WIDTH) {
        copy_width = PORT_FRAMEBUFFER_MAX_WIDTH;
    }
    if (copy_height > PORT_FRAMEBUFFER_MAX_HEIGHT) {
        copy_height = PORT_FRAMEBUFFER_MAX_HEIGHT;
    }

    for (y = 0; y < copy_height; y++) {
        for (x = 0; x < copy_width; x++) {
            dword address = base + (dword)((y * wid + x) * 2);
            dword pixel = mem_read16(address);
            unsigned char red = expand5((int)(pixel >> 11));
            unsigned char green = expand5((int)(pixel >> 6));
            unsigned char blue = expand5((int)(pixel >> 1));

            g_framebuffer[y * PORT_FRAMEBUFFER_MAX_WIDTH + x] =
                0xFF000000u |
                ((uint32_t)red << 16) |
                ((uint32_t)green << 8) |
                (uint32_t)blue;
        }
    }

    g_framebuffer_width = copy_width;
    g_framebuffer_height = copy_height;
    g_framebuffer_ready = 1;
}

static void copy_rdram_framebuffer_rgba8888(dword base, int wid, int hig)
{
    int x;
    int y;
    int copy_width;
    int copy_height;

    if (wid <= 0 || hig <= 0) {
        return;
    }

    copy_width = wid;
    copy_height = hig;
    if (copy_width > PORT_FRAMEBUFFER_MAX_WIDTH) {
        copy_width = PORT_FRAMEBUFFER_MAX_WIDTH;
    }
    if (copy_height > PORT_FRAMEBUFFER_MAX_HEIGHT) {
        copy_height = PORT_FRAMEBUFFER_MAX_HEIGHT;
    }

    for (y = 0; y < copy_height; y++) {
        for (x = 0; x < copy_width; x++) {
            dword address = base + (dword)((y * wid + x) * 4);
            unsigned char red = (unsigned char)mem_read8(address + 0);
            unsigned char green = (unsigned char)mem_read8(address + 1);
            unsigned char blue = (unsigned char)mem_read8(address + 2);
            unsigned char alpha = (unsigned char)mem_read8(address + 3);

            if (!alpha) {
                alpha = 0xFF;
            }

            g_framebuffer[y * PORT_FRAMEBUFFER_MAX_WIDTH + x] =
                ((uint32_t)alpha << 24) |
                ((uint32_t)red << 16) |
                ((uint32_t)green << 8) |
                (uint32_t)blue;
        }
    }

    g_framebuffer_width = copy_width;
    g_framebuffer_height = copy_height;
    g_framebuffer_ready = 1;
}

static void capture_framebuffer_base(dword base)
{
    int width;
    int height;
    int pixel_mode;

    if (!base) {
        return;
    }

    width = (int)RVI[2];
    if (width <= 0 || width > PORT_FRAMEBUFFER_MAX_WIDTH) {
        width = 320;
    }

    height = 240;
    if (height > PORT_FRAMEBUFFER_MAX_HEIGHT) {
        height = PORT_FRAMEBUFFER_MAX_HEIGHT;
    }

    pixel_mode = (int)(RVI[0] & 0x3);
    if (pixel_mode == 3) {
        copy_rdram_framebuffer_rgba8888(base, width, height);
    } else {
        copy_rdram_framebuffer_rgba5551(base, width, height);
    }
}

static void capture_swap_framebuffer(void)
{
    dword base;

    base = st.fb_next ? st.fb_next : st.fb_current;
    capture_framebuffer_base(base);
}

static void log_append(const char *text)
{
    size_t keep;

    if (!text) {
        return;
    }

    while (*text) {
        unsigned char ch = (unsigned char)*text++;

        if (ch == '\r') {
            continue;
        }
        if (ch < 32 && ch != '\n' && ch != '\t') {
            continue;
        }

        if (g_log_buffer_length + 2 >= PORT_LOG_BUFFER_SIZE) {
            keep = PORT_LOG_BUFFER_SIZE / 2;
            memmove(g_log_buffer, g_log_buffer + g_log_buffer_length - keep, keep);
            g_log_buffer_length = keep;
            g_log_buffer[g_log_buffer_length] = 0;
        }

        g_log_buffer[g_log_buffer_length++] = (char)ch;
    }

    g_log_buffer[g_log_buffer_length] = 0;
}

static unsigned char expand5(int value)
{
    value &= 31;
    return (unsigned char)((value << 3) | (value >> 2));
}

static int clamp_pad_axis(int value)
{
    if (value < -80) {
        return -80;
    }
    if (value > 80) {
        return 80;
    }
    return value;
}

void android_port_reset_runtime(void)
{
    g_rdp_active = 0;
    g_swap_count = 0;
    g_background_copy_count = 0;
    g_framebuffer_width = 0;
    g_framebuffer_height = 0;
    g_framebuffer_ready = 0;
    g_pad_buttons = 0;
    g_pad_stick_x = 0;
    g_pad_stick_y = 0;
    g_log_buffer_length = 0;
    g_log_buffer[0] = 0;
}

void android_port_set_pad_state(unsigned int button_mask, int stick_x, int stick_y)
{
    g_pad_buttons = button_mask & 0x0000FFFFu;
    g_pad_stick_x = clamp_pad_axis(stick_x);
    g_pad_stick_y = clamp_pad_axis(stick_y);
}

void android_port_copy_logtail(char *buffer, size_t buffer_size)
{
    size_t start;
    size_t length;

    if (!buffer || buffer_size == 0) {
        return;
    }

    if (!g_log_buffer_length) {
        buffer[0] = 0;
        return;
    }

    if (g_log_buffer_length + 1 > buffer_size) {
        start = g_log_buffer_length - (buffer_size - 1);
        length = buffer_size - 1;
    } else {
        start = 0;
        length = g_log_buffer_length;
    }

    memcpy(buffer, g_log_buffer + start, length);
    buffer[length] = 0;
}

int android_port_frame_width(void)
{
    return g_framebuffer_ready ? g_framebuffer_width : 0;
}

int android_port_frame_height(void)
{
    return g_framebuffer_ready ? g_framebuffer_height : 0;
}

int android_port_swap_count(void)
{
    return g_swap_count;
}

int android_port_background_copy_count(void)
{
    return g_background_copy_count;
}

int android_port_copy_framebuffer(uint32_t *buffer, int max_pixels)
{
    int total_pixels;
    int copy_width;
    int copy_height;
    int y;

    if (!buffer || max_pixels <= 0 || !g_framebuffer_ready) {
        return 0;
    }

    copy_width = g_framebuffer_width;
    copy_height = g_framebuffer_height;
    total_pixels = copy_width * copy_height;
    if (total_pixels <= 0) {
        return 0;
    }
    if (total_pixels > max_pixels) {
        total_pixels = max_pixels;
    }

    copy_height = total_pixels / copy_width;
    for (y = 0; y < copy_height; y++) {
        memcpy(
            buffer + y * copy_width,
            g_framebuffer + y * PORT_FRAMEBUFFER_MAX_WIDTH,
            (size_t)copy_width * sizeof(uint32_t)
        );
    }

    return copy_height * copy_width;
}

void android_port_present_framebuffer(const uint32_t *pixels, int width, int height)
{
    int copy_width;
    int copy_height;
    int y;

    if (!pixels || width <= 0 || height <= 0) {
        return;
    }

    copy_width = width;
    copy_height = height;
    if (copy_width > PORT_FRAMEBUFFER_MAX_WIDTH) {
        copy_width = PORT_FRAMEBUFFER_MAX_WIDTH;
    }
    if (copy_height > PORT_FRAMEBUFFER_MAX_HEIGHT) {
        copy_height = PORT_FRAMEBUFFER_MAX_HEIGHT;
    }

    for (y = 0; y < copy_height; y++) {
        memcpy(
            g_framebuffer + y * PORT_FRAMEBUFFER_MAX_WIDTH,
            pixels + y * width,
            (size_t)copy_width * sizeof(uint32_t)
        );
    }

    g_framebuffer_width = copy_width;
    g_framebuffer_height = copy_height;
    g_framebuffer_ready = 1;
    g_rdp_active = 1;
    g_swap_count++;
}

void android_port_set_render_active(int active)
{
    g_rdp_active = active ? 1 : 0;
}

void android_port_note_background_copy(void)
{
    g_background_copy_count++;
}

void android_port_capture_current_frame(void)
{
    if (g_rdp_active) {
        return;
    }
    capture_framebuffer_base(st.fb_current);
}

int memicmp(const void *left, const void *right, size_t size)
{
    const unsigned char *a = (const unsigned char *)left;
    const unsigned char *b = (const unsigned char *)right;
    size_t i;

    for (i = 0; i < size; i++) {
        int ca = tolower(a[i]);
        int cb = tolower(b[i]);
        if (ca != cb) {
            return ca - cb;
        }
    }

    return 0;
}

int stricmp(const char *left, const char *right)
{
    return strcasecmp(left, right);
}

char *strlwr(char *text)
{
    char *p = text;
    while (*p) {
        *p = (char)tolower((unsigned char)*p);
        p++;
    }
    return text;
}

void fixpath(char *path, int striplastname)
{
    char *p;

    if (!path || !*path) {
        return;
    }

    if (striplastname) {
        p = path + strlen(path);
        while (p > path && *p != '/' && *p != '\\') {
            p--;
        }
    } else {
        p = path + strlen(path) - 1;
    }

    if (*p != '/') {
        *++p = '/';
    }
    p[1] = 0;
}

static void log_line(const char *prefix, const char *text)
{
    if (!text) {
        return;
    }

    if (prefix) {
        log_append(prefix);
    }
    log_append(text);
    if (text[0] && text[strlen(text) - 1] != '\n') {
        log_append("\n");
    }

#if PORT_ECHO_LOGCAT
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s%s", prefix ? prefix : "", text);
#endif
}

void outputhook(char *txt, char *full)
{
    (void)full;
    log_line("", txt);
}

void *main_gethwnd(void)
{
    return NULL;
}

void command(char *cmd)
{
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "command() stub invoked: %s", cmd ? cmd : "(null)");
}

void breakcommand(char *cmd)
{
    command(cmd);
}

void view_open(void)
{
}

void view_close(void)
{
}

void view_changed(int which)
{
    view.changed |= which;
}

void view_redraw(void)
{
}

void view_setlast(void)
{
}

void view_writeconsole(char *text)
{
    log_line("", text);
}

void view_status(char *text)
{
    log_line("[status] ", text);
}

void flushdisplay(void)
{
}

void debugui(void)
{
}

void con_init(void)
{
}

void con_initdummy(void)
{
}

void con_deinit(void)
{
}

void con_sleep(int ms)
{
    Sleep((unsigned int)ms);
}

int con_resized(void)
{
    return 0;
}

int con_rows(void)
{
    return 25;
}

int con_cols(void)
{
    return 80;
}

void con_clear(void)
{
}

void con_gotoxy(int x, int y)
{
    (void)x;
    (void)y;
}

void con_cursorxy(int x, int y, int size)
{
    (void)x;
    (void)y;
    (void)size;
}

void con_attr(int fg)
{
    (void)fg;
}

void con_attr2(int fg, int bg)
{
    (void)fg;
    (void)bg;
}

void con_printchar(int ch)
{
    (void)ch;
}

void con_print(char *text)
{
    log_line("", text);
}

void con_printf(char *text, ...)
{
    char buffer[512];
    va_list args;

    va_start(args, text);
    vsnprintf(buffer, sizeof(buffer), text, args);
    va_end(args);

    log_line("", buffer);
}

void con_tabto(int ch, int x)
{
    (void)ch;
    (void)x;
}

void con_readmouserelative(int *xp, int *yp, int *bp)
{
    if (xp) *xp = 0;
    if (yp) *yp = 0;
    if (bp) *bp = 0;
}

int con_readkey(void)
{
    return 0;
}

int con_readkey_noblock(void)
{
    return 0;
}

void pad_key(int key)
{
    (void)key;
}

void pad_misckey(int key)
{
    (void)key;
}

void pad_frame(void)
{
}

dword pad_getdata(int pad)
{
    dword state;
    uint32_t buttons;
    int stick_x;
    int stick_y;

    if (pad != 0) {
        return 0;
    }

    buttons = g_pad_buttons & (
        CONT_A | CONT_B | CONT_G | CONT_START |
        CONT_UP | CONT_DOWN | CONT_LEFT | CONT_RIGHT |
        CONT_L | CONT_R | CONT_E | CONT_D | CONT_C | CONT_F
    );
    stick_x = clamp_pad_axis(g_pad_stick_x);
    stick_y = clamp_pad_axis(g_pad_stick_y);

    state = buttons |
        ((dword)(uint8_t)(signed char)stick_x << 16) |
        ((dword)(uint8_t)(signed char)stick_y << 24);

    st.padstate = state;
    return FLIP32(state);
}

void pad_writedata(dword addr)
{
    mem_write32(addr, pad_getdata(0));
}

void pad_drawframe(void)
{
}

void pad_enablejoy(int enable)
{
    (void)enable;
}

int sound_init(int rate)
{
    (void)rate;
    return 0;
}

void sound_start(int rate)
{
    (void)rate;
}

void sound_stop(void)
{
}

void sound_debugsavebuffer(char *file)
{
    (void)file;
}

int sound_buffered(void)
{
    return 0;
}

int sound_position(int *bufsize)
{
    if (bufsize) {
        *bufsize = 0;
    }
    return 0;
}

int sound_add(short *data, int bytes)
{
    (void)data;
    (void)bytes;
    return 0;
}

void sound_resync(int target)
{
    (void)target;
}

void sound_addwavfile(char *file, short *data, int cnt, int stereo)
{
    (void)file;
    (void)data;
    (void)cnt;
    (void)stereo;
}

void x_fastfpu(int fast)
{
    (void)fast;
}

void a_clearcodecache(void)
{
}

void a_cleardeadgroups(void)
{
}

void a_stats(void)
{
    print("dynarec disabled in Android headless port\n");
}

void a_stats2(void)
{
}

void a_stats3(void)
{
}

void a_exec(void)
{
    c_exec();
}

void a_compilegroupat(dword x)
{
    (void)x;
}
