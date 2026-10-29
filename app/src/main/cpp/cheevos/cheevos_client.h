#pragma once

// RetroAchievements session: sign in, load a game's achievement set, evaluate it
// as the emulator runs, report unlocks. Casual (softcore) mode only.
//
// Threading: frame() runs on the emulation thread (Nin64-Emu) and is the only caller that
// makes rcheevos read game memory. HTTP runs on one worker thread; a ticker thread
// keeps rcheevos' pings and retries going while no frames run (paused). Every
// rc_client call is serialized by one recursive lock, so the UI thread can list
// achievements and the JNI thread can save/load progress at any time.

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace nin64::cheevos {

struct Request
{
    std::string url;
    std::string body;           // empty for GET, form-encoded for POST
    std::string content_type;
};

struct Response
{
    int status = 0;             // 0 = never reached the server; rcheevos retries
    std::string body;
    std::string error;          // transport-level reason when status == 0
};

// A blocking HTTP client. Called on the worker thread only (and by login_with_password).
class Backend
{
public:
    virtual ~Backend() = default;
    virtual Response perform(const Request& req, const char* user_agent) = 0;
};

// Something the player should be told about (shown as a popup over the game).
struct Message
{
    enum class Kind : uint8_t { Unlock, Info, Problem };
    Kind kind = Kind::Info;
    std::string text;
    std::string detail;
    uint32_t points = 0;        // unlock value; 0 for everything else
    std::string badge_url;      // the unlocked badge image, for an unlock
};

enum class State : uint8_t
{
    Off,
    SignedOut,
    SigningIn,
    SignedIn,
    LoadingGame,
    Playing,        // a set is loaded and being evaluated
    NoSet,          // signed in, but RetroAchievements does not know this dump
    EmptySet,       // dump known, game has no published achievements yet
};

// Reads guest memory for rcheevos: returns how many of `n` bytes were backed.
using MemoryReader = std::function<uint32_t(uint32_t address, uint8_t* buffer, uint32_t n)>;

class Client
{
public:
    Client() = default;
    ~Client();
    Client(const Client&) = delete;
    Client& operator=(const Client&) = delete;

    void start(std::unique_ptr<Backend> http, MemoryReader reader);
    void shutdown();

    State state() const { return _state.load(); }

    // Asynchronous; watch state() and take_messages().
    void sign_in_with_token(const std::string& username, const std::string& token);
    void load_game(const std::string& hash);

    std::string game_title();
    std::string game_badge_url();

    // Once per emulated frame, on the emulation thread.
    void frame();

    // Achievement progress (hit counts) for save states. deserialize_progress() is
    // false on a mismatched blob: reset() then, rather than risk a false unlock.
    bool serialize_progress(std::vector<uint8_t>& out);
    bool deserialize_progress(const uint8_t* data, size_t size);
    void reset();

    std::vector<Message> take_messages();

    struct Achievement
    {
        std::string title, description, progress;
        std::string badge_url, badge_locked_url;
        uint32_t id = 0, points = 0;
        bool unlocked = false;      // the account holds it
        bool unsupported = false;   // a condition reads memory we do not back
    };
    struct Summary
    {
        uint32_t total = 0, unlocked = 0, points = 0, points_earned = 0;
    };
    std::vector<Achievement> achievements();
    Summary summary();

    // Called by the rcheevos trampolines in the .cpp; public for that reason only.
    void on_signed_in(const std::string& display_name);
    void on_sign_in_failed(const std::string& why);
    void on_game_loaded(const std::string& title);
    void on_no_set();
    void on_empty_set();
    void on_game_failed(const std::string& why);
    void enqueue(Request req, void* callback, void* callback_data);
    void handle_event(const void* event);
    uint32_t read(uint32_t address, uint8_t* buffer, uint32_t num_bytes);
    void post(Message::Kind kind, std::string text, std::string detail = {}, uint32_t points = 0,
              std::string badge_url = {});

private:
    void worker_loop();
    void ticker_loop();
    void drain_completions();

    struct Job
    {
        Request req;
        void* callback = nullptr;       // rc_client_server_callback_t
        void* callback_data = nullptr;
    };
    struct Completion
    {
        Response res;
        void* callback = nullptr;
        void* callback_data = nullptr;
    };

    void* _client = nullptr;            // rc_client_t*
    std::unique_ptr<Backend> _http;
    MemoryReader _reader;
    std::atomic<State> _state { State::Off };
    std::string _hash;

    // Guards every rc_client call. Recursive: rcheevos calls back into us
    // (enqueue, events, completions) from inside the calls it guards.
    std::recursive_mutex _rcLock;
    std::atomic<int64_t> _lastFrameMs { 0 };

    std::thread _worker;
    std::thread _ticker;
    std::mutex _lock;                   // the queues below and _stop
    std::condition_variable _cv;
    std::deque<Job> _jobs;
    std::deque<Completion> _done;
    std::vector<Message> _messages;
    bool _stop = false;
};

// Signs in with a password outside any game (the settings screen), blocking on `http`.
// On success the username and the token to keep are returned; the password is not kept.
bool login_with_password(Backend& http, const std::string& username, const std::string& password,
                         std::string& out_username, std::string& out_token,
                         std::string& display_name, uint32_t& score, std::string& err);

// Product token sent to the server. A malformed value gets every endpoint refused.
const char* user_agent();

} // namespace nin64::cheevos
