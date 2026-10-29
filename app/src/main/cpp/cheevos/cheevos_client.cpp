#include "cheevos_client.h"

#include <android/log.h>
#include <cstring>

#include "rc_api_user.h"
#include "rc_client.h"
#include "rc_version.h"

#define TAG "Nin64Cheevos"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace nin64::cheevos {
namespace {

// "Warning" achievements (e.g. "unknown emulator") have ids from here up; rcheevos
// leaves them out of summaries. Casual-only, so they never need to be shown.
constexpr uint32_t WarningAchievementId = 101000001;
bool IsWarning(uint32_t id) { return id >= WarningAchievementId; }

int64_t NowMs()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

Client* SessionOf(const rc_client_t* c)
{
    return static_cast<Client*>(rc_client_get_userdata(c));
}

uint32_t RcReadMemory(uint32_t address, uint8_t* buffer, uint32_t num_bytes, rc_client_t* c)
{
    Client* self = SessionOf(c);
    if(!self) {
        std::memset(buffer, 0, num_bytes);
        return 0;
    }
    return self->read(address, buffer, num_bytes);
}

void RcServerCall(const rc_api_request_t* request, rc_client_server_callback_t callback,
                  void* callback_data, rc_client_t* c)
{
    Client* self = SessionOf(c);
    if(!self || !request) return;
    Request req;
    req.url = request->url ? request->url : "";
    req.body = request->post_data ? request->post_data : "";
    req.content_type = request->content_type ? request->content_type : "";
    self->enqueue(std::move(req), reinterpret_cast<void*>(callback), callback_data);
}

void RcEventHandler(const rc_client_event_t* event, rc_client_t* c)
{
    Client* self = SessionOf(c);
    if(self) self->handle_event(event);
}

void LoginDone(int result, const char* error_message, rc_client_t* c, void* /*userdata*/)
{
    Client* self = SessionOf(c);
    if(!self) return;
    if(result == RC_OK) {
        const rc_client_user_t* u = rc_client_get_user_info(c);
        self->on_signed_in(u && u->display_name ? u->display_name : "");
    } else {
        self->on_sign_in_failed(error_message ? error_message : "sign-in failed");
    }
}

void LoadDone(int result, const char* error_message, rc_client_t* c, void* /*userdata*/)
{
    Client* self = SessionOf(c);
    if(!self) return;
    if(result == RC_OK) {
        const rc_client_game_t* g = rc_client_get_game_info(c);
        // RC_OK with no achievements: a known dump whose game has no published set.
        if(self->achievements().empty()) {
            self->on_empty_set();
        } else {
            self->on_game_loaded(g && g->title ? g->title : "");
        }
    } else if(result == RC_NO_GAME_LOADED) {
        self->on_no_set();
    } else {
        self->on_game_failed(error_message ? error_message : "could not load achievements");
    }
}

} // namespace

const char* user_agent()
{
    // "Nin64/<versionName> rcheevos/<version>"
    static const std::string ua = std::string("Nin64/") + NIN64_VERSION + " rcheevos/" + RCHEEVOS_VERSION_STRING;
    return ua.c_str();
}

bool login_with_password(Backend& http, const std::string& username, const std::string& password,
                         std::string& out_username, std::string& out_token,
                         std::string& display_name, uint32_t& score, std::string& err)
{
    out_username.clear();
    out_token.clear();
    display_name.clear();
    score = 0;
    err.clear();

    rc_api_login_request_t params {};
    params.username = username.c_str();
    params.password = password.c_str();
    rc_api_request_t request {};
    if(rc_api_init_login_request(&request, &params) != RC_OK) {
        err = "could not build the sign-in request";
        return false;
    }
    Request req;
    req.url = request.url ? request.url : "";
    req.body = request.post_data ? request.post_data : "";
    req.content_type = request.content_type ? request.content_type : "";
    rc_api_destroy_request(&request);

    const Response res = http.perform(req, user_agent());
    if(res.status == 0) {
        err = res.error.empty() ? "could not reach RetroAchievements" : res.error;
        return false;
    }
    rc_api_server_response_t server {};
    server.body = res.body.c_str();
    server.body_length = res.body.size();
    server.http_status_code = res.status;
    rc_api_login_response_t login {};
    const int rc = rc_api_process_login_server_response(&login, &server);
    const bool ok = rc == RC_OK && login.response.succeeded && login.api_token && login.username;
    if(ok) {
        out_username = login.username;
        out_token = login.api_token;
        display_name = login.display_name ? login.display_name : login.username;
        score = login.score_softcore;
    } else {
        err = login.response.error_message ? login.response.error_message : "sign-in failed";
    }
    rc_api_destroy_login_response(&login);
    return ok;
}

// ── Session ───────────────────────────────────────────────────────────────────

Client::~Client()
{
    shutdown();
}

void Client::post(Message::Kind kind, std::string text, std::string detail, uint32_t points, std::string badge_url)
{
    std::lock_guard<std::mutex> lk(_lock);
    _messages.push_back(Message { kind, std::move(text), std::move(detail), points, std::move(badge_url) });
}

std::vector<Message> Client::take_messages()
{
    std::lock_guard<std::mutex> lk(_lock);
    std::vector<Message> out;
    out.swap(_messages);
    return out;
}

void Client::start(std::unique_ptr<Backend> http, MemoryReader reader)
{
    if(_client) return;
    _http = std::move(http);
    _reader = std::move(reader);

    rc_client_t* c = rc_client_create(&RcReadMemory, &RcServerCall);
    if(!c) {
        LOGW("rc_client_create failed");
        return;
    }
    _client = c;
    rc_client_set_userdata(c, this);
    // rc_client defaults to hardcore; save states and cheats rule that out here.
    rc_client_set_hardcore_enabled(c, 0);
    // Only frame() (the emulation thread) may make rcheevos read game memory.
    rc_client_set_allow_background_memory_reads(c, 0);
    rc_client_set_event_handler(c, &RcEventHandler);

    _lastFrameMs = NowMs();
    _worker = std::thread([this] { worker_loop(); });
    _ticker = std::thread([this] { ticker_loop(); });
    _state = State::SignedOut;
    LOGI("session up, user agent %s", user_agent());
}

void Client::shutdown()
{
    {
        std::lock_guard<std::mutex> lk(_lock);
        _stop = true;
    }
    _cv.notify_all();
    if(_worker.joinable()) _worker.join();
    if(_ticker.joinable()) _ticker.join();
    if(_client) {
        // Queued work is dropped: calling back into rc_client mid-teardown would crash.
        std::lock_guard<std::recursive_mutex> rc(_rcLock);
        rc_client_destroy(static_cast<rc_client_t*>(_client));
        _client = nullptr;
    }
    {
        std::lock_guard<std::mutex> lk(_lock);
        _jobs.clear();
        _done.clear();
    }
    _http.reset();
    _state = State::Off;
}

// ── Worker and ticker ─────────────────────────────────────────────────────────

void Client::worker_loop()
{
    for(;;) {
        Job job;
        {
            std::unique_lock<std::mutex> lk(_lock);
            _cv.wait(lk, [this] { return _stop || !_jobs.empty(); });
            if(_stop) return;
            job = std::move(_jobs.front());
            _jobs.pop_front();
        }
        Response res = _http->perform(job.req, user_agent());
        {
            std::lock_guard<std::mutex> lk(_lock);
            _done.push_back(Completion { std::move(res), job.callback, job.callback_data });
        }
    }
}

void Client::ticker_loop()
{
    // While paused no frames run, but sign-in/load answers, pings and retries
    // still need handling. rc_client_idle reads no game memory (see start()).
    for(;;) {
        {
            std::unique_lock<std::mutex> lk(_lock);
            if(_cv.wait_for(lk, std::chrono::milliseconds(500), [this] { return _stop; })) return;
        }
        if(NowMs() - _lastFrameMs.load() < 1000) continue;
        std::lock_guard<std::recursive_mutex> rc(_rcLock);
        if(!_client) return;
        drain_completions();
        rc_client_idle(static_cast<rc_client_t*>(_client));
    }
}

void Client::enqueue(Request req, void* callback, void* callback_data)
{
    {
        std::lock_guard<std::mutex> lk(_lock);
        _jobs.push_back(Job { std::move(req), callback, callback_data });
    }
    _cv.notify_all();
}

void Client::drain_completions()
{
    for(;;) {
        Completion done;
        {
            std::lock_guard<std::mutex> lk(_lock);
            if(_done.empty()) return;
            done = std::move(_done.front());
            _done.pop_front();
        }
        rc_api_server_response_t res {};
        res.body = done.res.body.c_str();
        res.body_length = done.res.body.size();
        // Status 0 (never reached the server) is retryable to rcheevos.
        res.http_status_code = done.res.status;
        auto cb = reinterpret_cast<rc_client_server_callback_t>(done.callback);
        if(cb) cb(&res, done.callback_data);
    }
}

// ── Per frame ─────────────────────────────────────────────────────────────────

void Client::frame()
{
    if(!_client) return;
    _lastFrameMs = NowMs();
    std::lock_guard<std::recursive_mutex> rc(_rcLock);
    drain_completions();
    rc_client_do_frame(static_cast<rc_client_t*>(_client));
}

uint32_t Client::read(uint32_t address, uint8_t* buffer, uint32_t num_bytes)
{
    if(!_reader) {
        std::memset(buffer, 0, num_bytes);
        return 0;
    }
    return _reader(address, buffer, num_bytes);
}

// ── Sign-in and game ──────────────────────────────────────────────────────────

void Client::sign_in_with_token(const std::string& username, const std::string& token)
{
    if(!_client) return;
    std::lock_guard<std::recursive_mutex> rc(_rcLock);
    _state = State::SigningIn;
    rc_client_begin_login_with_token(static_cast<rc_client_t*>(_client), username.c_str(), token.c_str(),
                                     &LoginDone, nullptr);
}

void Client::load_game(const std::string& hash)
{
    if(!_client) return;
    std::lock_guard<std::recursive_mutex> rc(_rcLock);
    _hash = hash;
    _state = State::LoadingGame;
    rc_client_begin_load_game(static_cast<rc_client_t*>(_client), hash.c_str(), &LoadDone, nullptr);
}

std::string Client::game_title()
{
    if(!_client) return {};
    std::lock_guard<std::recursive_mutex> rc(_rcLock);
    const rc_client_game_t* g = rc_client_get_game_info(static_cast<rc_client_t*>(_client));
    return g && g->title ? g->title : std::string {};
}

std::string Client::game_badge_url()
{
    if(!_client) return {};
    std::lock_guard<std::recursive_mutex> rc(_rcLock);
    const rc_client_game_t* g = rc_client_get_game_info(static_cast<rc_client_t*>(_client));
    return g && g->badge_url ? g->badge_url : std::string {};
}

void Client::on_signed_in(const std::string& display_name)
{
    // The game load usually finishes after this; don't step back from it.
    State expected = State::SigningIn;
    _state.compare_exchange_strong(expected, State::SignedIn);
    LOGI("signed in as %s", display_name.c_str());
}

void Client::on_sign_in_failed(const std::string& why)
{
    _state = State::SignedOut;
    post(Message::Kind::Problem, "RetroAchievements sign-in failed", why);
}

void Client::on_game_loaded(const std::string& title)
{
    _state = State::Playing;
    post(Message::Kind::Info, title.empty() ? "Achievements loaded" : title, "achievements active");
}

// No popup: nothing has gone wrong and there's nothing to do.
void Client::on_empty_set()
{
    _state = State::EmptySet;
}

void Client::on_no_set()
{
    _state = State::NoSet;
    // A hash identifies a dump, not a game: a legitimate ROM can still miss.
    post(Message::Kind::Problem, "No achievements for this ROM",
         "RetroAchievements doesn't recognize this dump (hash " + _hash + ")");
}

void Client::on_game_failed(const std::string& why)
{
    // A failed sign-in fails the pending load too; that must not read as signed in.
    if(_state != State::SignedOut) _state = State::SignedIn;
    post(Message::Kind::Problem, "Could not load achievements", why);
}

// ── The loaded set ────────────────────────────────────────────────────────────

std::vector<Client::Achievement> Client::achievements()
{
    std::vector<Achievement> out;
    if(!_client) return out;
    std::lock_guard<std::recursive_mutex> rc(_rcLock);
    rc_client_t* c = static_cast<rc_client_t*>(_client);
    // Core set only; unofficial achievements are not offered.
    rc_client_achievement_list_t* list = rc_client_create_achievement_list(
        c, RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE);
    if(!list) return out;
    for(uint32_t b = 0; b < list->num_buckets; ++b) {
        const rc_client_achievement_bucket_t& bucket = list->buckets[b];
        for(uint32_t i = 0; i < bucket.num_achievements; ++i) {
            const rc_client_achievement_t* a = bucket.achievements[i];
            if(!a || IsWarning(a->id)) continue;
            Achievement info;
            info.title = a->title ? a->title : "";
            info.description = a->description ? a->description : "";
            info.progress = a->measured_progress;
            info.badge_url = a->badge_url ? a->badge_url : "";
            info.badge_locked_url = a->badge_locked_url ? a->badge_locked_url : "";
            info.id = a->id;
            info.points = a->points;
            info.unlocked = a->state == RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED ||
                            (a->unlocked & RC_CLIENT_ACHIEVEMENT_UNLOCKED_SOFTCORE) != 0;
            info.unsupported = a->state == RC_CLIENT_ACHIEVEMENT_STATE_DISABLED ||
                               bucket.bucket_type == RC_CLIENT_ACHIEVEMENT_BUCKET_UNSUPPORTED;
            out.push_back(std::move(info));
        }
    }
    rc_client_destroy_achievement_list(list);
    return out;
}

Client::Summary Client::summary()
{
    Summary s;
    for(const Achievement& a : achievements()) {
        ++s.total;
        s.points += a.points;
        if(a.unlocked) {
            ++s.unlocked;
            s.points_earned += a.points;
        }
    }
    return s;
}

// ── Progress, for save states ─────────────────────────────────────────────────

bool Client::serialize_progress(std::vector<uint8_t>& out)
{
    out.clear();
    if(!_client) return false;
    std::lock_guard<std::recursive_mutex> rc(_rcLock);
    rc_client_t* c = static_cast<rc_client_t*>(_client);
    if(!rc_client_get_game_info(c)) return false;
    const size_t n = rc_client_progress_size(c);
    if(n == 0) return false;
    out.resize(n);
    if(rc_client_serialize_progress_sized(c, out.data(), out.size()) != RC_OK) {
        out.clear();
        return false;
    }
    return true;
}

bool Client::deserialize_progress(const uint8_t* data, size_t size)
{
    if(!_client || !data || size == 0) return false;
    std::lock_guard<std::recursive_mutex> rc(_rcLock);
    rc_client_t* c = static_cast<rc_client_t*>(_client);
    if(!rc_client_get_game_info(c)) return false;
    // rcheevos checks the blob against the loaded set.
    return rc_client_deserialize_progress_sized(c, data, size) == RC_OK;
}

void Client::reset()
{
    if(!_client) return;
    std::lock_guard<std::recursive_mutex> rc(_rcLock);
    rc_client_reset(static_cast<rc_client_t*>(_client));
}

// ── Events from rcheevos ──────────────────────────────────────────────────────

void Client::handle_event(const void* event_ptr)
{
    const rc_client_event_t* e = static_cast<const rc_client_event_t*>(event_ptr);
    if(!e) return;

    switch(e->type) {
        case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED:
            if(e->achievement && !IsWarning(e->achievement->id)) {
                post(Message::Kind::Unlock,
                     e->achievement->title ? e->achievement->title : "Achievement unlocked",
                     e->achievement->description ? e->achievement->description : "",
                     e->achievement->points,
                     e->achievement->badge_url ? e->achievement->badge_url : "");
            }
            break;
        case RC_CLIENT_EVENT_GAME_COMPLETED:
            post(Message::Kind::Info, "All achievements earned", game_title());
            break;
        case RC_CLIENT_EVENT_SERVER_ERROR:
            post(Message::Kind::Problem, "RetroAchievements error",
                 e->server_error && e->server_error->error_message ? e->server_error->error_message : "");
            break;
        case RC_CLIENT_EVENT_DISCONNECTED:
            post(Message::Kind::Problem, "RetroAchievements offline",
                 "Unlocks will be sent when the connection returns");
            break;
        case RC_CLIENT_EVENT_RECONNECTED:
            post(Message::Kind::Info, "RetroAchievements reconnected", "Pending unlocks sent");
            break;
        default:
            break;
    }
}

} // namespace nin64::cheevos
