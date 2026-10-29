// RetroAchievements for the running game: ties a cheevos Client to the libretro
// bridge (frames, RDRAM, save states) and serves its HTTP through Java (CheevosHttp).

#include "cheevos_bridge.h"

#include <algorithm>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "cheevos_client.h"
#include "rc_consoles.h"
#include "rc_hash.h"

#define TAG "Nin64Cheevos"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace nin64::cheevos {
namespace {

// ── HTTP through Java ─────────────────────────────────────────────────────────

JavaVM* g_vm = nullptr;
jclass g_httpClass = nullptr;       // global ref to CheevosHttp
jmethodID g_perform = nullptr;

// A native thread is attached on its first request and detached when it exits.
struct AttachedThread
{
    JNIEnv* env = nullptr;
    bool ours = false;
    ~AttachedThread() { if(ours && g_vm) g_vm->DetachCurrentThread(); }
};

JNIEnv* ThreadEnv()
{
    thread_local AttachedThread a;
    if(a.env) return a.env;
    if(!g_vm) return nullptr;
    if(g_vm->GetEnv(reinterpret_cast<void**>(&a.env), JNI_VERSION_1_6) == JNI_OK) return a.env;
    JavaVMAttachArgs args { JNI_VERSION_1_6, "Nin64 cheevos", nullptr };
    if(g_vm->AttachCurrentThread(&a.env, &args) != JNI_OK) {
        a.env = nullptr;
        return nullptr;
    }
    a.ours = true;
    return a.env;
}

class JniBackend final : public Backend
{
public:
    // CheevosHttp.perform returns the status as four big-endian bytes, then the
    // body; status 0 carries the transport's error message as the body.
    Response perform(const Request& req, const char* userAgent) override
    {
        Response res;
        JNIEnv* env = ThreadEnv();
        if(!env || !g_perform) {
            res.error = "no Java transport";
            return res;
        }
        jstring url = env->NewStringUTF(req.url.c_str());
        jstring body = env->NewStringUTF(req.body.c_str());
        jstring type = env->NewStringUTF(req.content_type.c_str());
        jstring ua = env->NewStringUTF(userAgent ? userAgent : "");
        auto out = static_cast<jbyteArray>(env->CallStaticObjectMethod(g_httpClass, g_perform, url, body, type, ua));
        if(env->ExceptionCheck()) {
            env->ExceptionClear();
            out = nullptr;
        }
        env->DeleteLocalRef(url);
        env->DeleteLocalRef(body);
        env->DeleteLocalRef(type);
        env->DeleteLocalRef(ua);
        if(!out) {
            res.error = "the request failed";
            return res;
        }
        const jsize n = env->GetArrayLength(out);
        std::string bytes((size_t)n, '\0');
        if(n > 0) env->GetByteArrayRegion(out, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
        env->DeleteLocalRef(out);
        if(bytes.size() < 4) {
            res.error = "the request failed";
            return res;
        }
        const auto b = reinterpret_cast<const unsigned char*>(bytes.data());
        res.status = (int)((b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]);
        if(res.status == 0) {
            res.error = bytes.substr(4);
            LOGW("no answer from %s: %s", req.url.c_str(), res.error.c_str());
        } else {
            res.body = bytes.substr(4);
        }
        return res;
    }
};

// ── N64 memory ────────────────────────────────────────────────────────────────

// Set on the emulation thread just before each frame is evaluated, which is the
// only time rcheevos reads memory (see Client::start).
const uint8_t* g_frameRam = nullptr;
size_t g_frameRamSize = 0;

// RetroAchievements' N64 addresses are RDRAM offsets, $000000-$7FFFFF. The core
// keeps RDRAM as host-order 32-bit words; RetroAchievements' N64 sets are written
// against that same layout (RetroArch and RALibretro expose it unchanged).
uint32_t ReadRdram(uint32_t address, uint8_t* buffer, uint32_t n)
{
    const uint8_t* ram = g_frameRam;
    if(!ram || address >= g_frameRamSize) {
        std::memset(buffer, 0, n);
        return 0;
    }
    const uint32_t backed = (uint32_t)std::min<size_t>(n, g_frameRamSize - address);
    std::memcpy(buffer, ram + address, backed);
    if(backed < n) std::memset(buffer + backed, 0, n - backed);
    return backed;
}

std::mutex g_sessionLock;
std::unique_ptr<Client> g_session;

bool ReadWholeFile(const std::string& path, std::vector<uint8_t>& out)
{
    out.clear();
    FILE* f = std::fopen(path.c_str(), "rb");
    if(!f) return false;
    uint8_t buf[16 * 1024];
    size_t n;
    while((n = std::fread(buf, 1, sizeof(buf), f)) > 0) out.insert(out.end(), buf, buf + n);
    std::fclose(f);
    return true;
}

bool WriteWholeFile(const std::string& path, const std::vector<uint8_t>& data)
{
    const std::string tmp = path + ".tmp";
    FILE* f = std::fopen(tmp.c_str(), "wb");
    if(!f) return false;
    const bool ok = std::fwrite(data.data(), 1, data.size(), f) == data.size();
    std::fclose(f);
    if(!ok || std::rename(tmp.c_str(), path.c_str()) != 0) {
        std::remove(tmp.c_str());
        return false;
    }
    return true;
}

const char* StateName(State state)
{
    switch(state) {
        case State::Off: return "off";
        case State::SignedOut: return "signed_out";
        case State::SigningIn: return "signing_in";
        case State::SignedIn: return "signed_in";
        case State::LoadingGame: return "loading";
        case State::Playing: return "playing";
        case State::NoSet: return "no_set";
        case State::EmptySet: return "empty_set";
    }
    return "off";
}

constexpr char Sep = '\x1f';

std::string FromJavaString(JNIEnv* env, jstring value)
{
    if(!value) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if(chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jobjectArray ToJavaStrings(JNIEnv* env, const std::vector<std::string>& values)
{
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray((jsize)values.size(), stringClass, nullptr);
    for(size_t i = 0; i < values.size(); i++) {
        jstring value = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(out, (jsize)i, value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(stringClass);
    return out;
}

// For the game just booted: signs in, hashes the ROM, loads its achievement set,
// all in the background; the game does not wait.
void BeginSession(const std::string& user, const std::string& token)
{
    nin64_cheevos_end_session();
    if(user.empty() || token.empty()) return;

    // rcheevos converts .v64/.n64 byte orders to .z64 before hashing.
    size_t romSize = 0;
    const uint8_t* rom = nin64_bridge_rom_data(&romSize);
    char hash[33] = {};
    if(!rom || romSize == 0 || !rc_hash_generate_from_buffer(hash, RC_CONSOLE_NINTENDO_64, rom, romSize)) {
        LOGW("cannot hash the loaded ROM");
        return;
    }

    auto client = std::make_unique<Client>();
    client->start(std::make_unique<JniBackend>(), &ReadRdram);
    client->sign_in_with_token(user, token);
    client->load_game(hash);

    std::lock_guard<std::mutex> lk(g_sessionLock);
    g_session = std::move(client);
}

// Messages to show: kind (unlock/info/problem), title, detail, points, badge URL.
std::vector<std::string> TakeMessages()
{
    std::vector<std::string> out;
    std::lock_guard<std::mutex> lk(g_sessionLock);
    if(!g_session) return out;
    for(const Message& m : g_session->take_messages()) {
        const char* kind = m.kind == Message::Kind::Unlock ? "unlock"
                         : m.kind == Message::Kind::Problem ? "problem" : "info";
        out.push_back(std::string(kind) + Sep + m.text + Sep + m.detail + Sep +
                      std::to_string(m.points) + Sep + m.badge_url);
    }
    return out;
}

// First row: state, game title, game badge, unlocked, total, points earned, points total.
// Then one per achievement: unlocked, unsupported, points, badge, locked badge, title,
// description, progress.
std::vector<std::string> ListAchievements()
{
    std::vector<std::string> out;
    std::lock_guard<std::mutex> lk(g_sessionLock);
    if(!g_session) {
        out.push_back("off");
        return out;
    }
    Client& c = *g_session;
    const std::vector<Client::Achievement> list = c.achievements();
    uint32_t unlocked = 0, points = 0, earned = 0;
    for(const Client::Achievement& a : list) {
        points += a.points;
        if(a.unlocked) {
            unlocked++;
            earned += a.points;
        }
    }
    out.push_back(std::string(StateName(c.state())) + Sep + c.game_title() + Sep + c.game_badge_url() + Sep +
                  std::to_string(unlocked) + Sep + std::to_string(list.size()) + Sep +
                  std::to_string(earned) + Sep + std::to_string(points));
    for(const Client::Achievement& a : list) {
        out.push_back(std::string(a.unlocked ? "1" : "0") + Sep + (a.unsupported ? "1" : "0") + Sep +
                      std::to_string(a.points) + Sep + a.badge_url + Sep + a.badge_locked_url + Sep +
                      a.title + Sep + a.description + Sep + a.progress);
    }
    return out;
}

} // namespace
} // namespace nin64::cheevos

using namespace nin64::cheevos;

// ── Bridge hooks ──────────────────────────────────────────────────────────────

extern "C" void nin64_cheevos_init_jni(JavaVM* vm, JNIEnv* env)
{
    g_vm = vm;
    // From JNI_OnLoad: a native worker thread's FindClass would not see the app's classes.
    jclass c = env->FindClass("com/izzy2lost/nin64/CheevosHttp");
    if(!c) {
        env->ExceptionClear();
        LOGW("no CheevosHttp class");
        return;
    }
    g_httpClass = static_cast<jclass>(env->NewGlobalRef(c));
    env->DeleteLocalRef(c);
    g_perform = env->GetStaticMethodID(g_httpClass, "perform",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)[B");
    if(!g_perform) env->ExceptionClear();
}

extern "C" void nin64_cheevos_frame(void)
{
    std::lock_guard<std::mutex> lk(g_sessionLock);
    if(!g_session) return;
    g_frameRam = nin64_bridge_system_ram(&g_frameRamSize);
    g_session->frame();
}

extern "C" void nin64_cheevos_end_session(void)
{
    std::unique_ptr<Client> session;
    {
        std::lock_guard<std::mutex> lk(g_sessionLock);
        session = std::move(g_session);
        g_frameRam = nullptr;
        g_frameRamSize = 0;
    }
    if(!session) return;
    // Shutting down waits for the request in flight (an unlock, say) to finish,
    // which can take seconds: not on the emulation thread.
    std::thread([s = std::move(session)]() mutable { s.reset(); }).detach();
}

// Achievement progress beside a save state, so loading one cannot half-trigger anything.
extern "C" void nin64_cheevos_after_save_state(const char* state_path)
{
    std::lock_guard<std::mutex> lk(g_sessionLock);
    const std::string path = std::string(state_path) + ".rap";
    std::vector<uint8_t> progress;
    if(g_session && g_session->serialize_progress(progress)) {
        WriteWholeFile(path, progress);
    } else {
        std::remove(path.c_str());
    }
}

extern "C" void nin64_cheevos_after_load_state(const char* state_path)
{
    std::lock_guard<std::mutex> lk(g_sessionLock);
    if(!g_session) return;
    std::vector<uint8_t> progress;
    if(!ReadWholeFile(std::string(state_path) + ".rap", progress) || progress.empty() ||
       !g_session->deserialize_progress(progress.data(), progress.size())) {
        g_session->reset();
    }
}

// ── JNI (NativeBridge) ────────────────────────────────────────────────────────

// After bootRomForPlay, on the emulation thread: signs in and loads the game's
// achievements in the background.
extern "C" JNIEXPORT void JNICALL
Java_com_izzy2lost_nin64_NativeBridge_cheevosBegin(JNIEnv* env, jobject /*thiz*/, jstring user, jstring token)
{
    BeginSession(FromJavaString(env, user), FromJavaString(env, token));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_izzy2lost_nin64_NativeBridge_cheevosMessages(JNIEnv* env, jobject /*thiz*/)
{
    return ToJavaStrings(env, TakeMessages());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_izzy2lost_nin64_NativeBridge_cheevosList(JNIEnv* env, jobject /*thiz*/)
{
    return ToJavaStrings(env, ListAchievements());
}

// Settings' sign-in: blocking, off the UI thread, no game needed. Returns
// {username, token, display name, score} on success, {"", error} on failure.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_izzy2lost_nin64_NativeBridge_cheevosLogin(JNIEnv* env, jobject /*thiz*/, jstring user, jstring password)
{
    JniBackend http;
    std::string username, token, displayName, err;
    uint32_t score = 0;
    if(login_with_password(http, FromJavaString(env, user), FromJavaString(env, password),
                           username, token, displayName, score, err)) {
        return ToJavaStrings(env, { username, token, displayName, std::to_string(score) });
    }
    return ToJavaStrings(env, { "", err });
}
