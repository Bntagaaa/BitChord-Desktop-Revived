// Fresh Windows equivalent of the original mobile MediaSession boundary.
// Uses the documented HWND interop API; no prior desktop bridge is retained.
#include <jni.h>
#include <windows.h>
#include <roapi.h>
#include <dwmapi.h>
#include <systemmediatransportcontrolsinterop.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.Streams.h>
#include <algorithm>
#include <atomic>
#include <deque>
#include <memory>
#include <iterator>
#include <mutex>
#include <string>
#include <unordered_map>

using namespace winrt;
using namespace winrt::Windows::Media;

namespace {
struct Apartment {
    HRESULT result = RoInitialize(RO_INIT_MULTITHREADED);
    ~Apartment() { if (SUCCEEDED(result)) RoUninitialize(); }
};
void apartment() { thread_local Apartment current; check_hresult(current.result); }
struct MediaSession {
    SystemMediaTransportControls controls{nullptr};
    event_token button{};
    event_token seek{};
    std::mutex mutex;
    std::deque<std::pair<jlong, jlong>> commands;
    std::wstring title, artist, album, artwork;
    void enqueue(jlong command, jlong value = 0) {
        std::lock_guard<std::mutex> lock(mutex);
        if (commands.size() == 64) commands.pop_front();
        commands.emplace_back(command, value);
    }
    ~MediaSession() {
        try {
            if (controls) {
                controls.ButtonPressed(button);
                controls.PlaybackPositionChangeRequested(seek);
                controls.IsEnabled(false);
            }
        } catch (...) { }
    }
};
std::mutex sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<MediaSession>> sessions;
std::atomic<jlong> next_id{1};
std::shared_ptr<MediaSession> lookup(jlong id) {
    std::lock_guard<std::mutex> lock(sessions_mutex);
    auto it = sessions.find(id);
    return it == sessions.end() ? nullptr : it->second;
}
std::wstring text(JNIEnv* env, jstring value) {
    if (!value) return {};
    auto chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_music_bitchord_reworked_WindowsMediaNative_attach(JNIEnv*, jobject, jlong window) {
    try {
        apartment();
        auto hwnd = reinterpret_cast<HWND>(window);
        DWORD owner = 0;
        if (!IsWindow(hwnd) || !GetWindowThreadProcessId(hwnd, &owner) || owner != GetCurrentProcessId()) return 0;
        auto session = std::make_shared<MediaSession>();
        auto interop = get_activation_factory<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>();
        check_hresult(interop->GetForWindow(hwnd, guid_of<SystemMediaTransportControls>(), put_abi(session->controls)));
        auto& controls = session->controls;
        controls.IsPlayEnabled(true); controls.IsPauseEnabled(true);
        controls.IsNextEnabled(true); controls.IsPreviousEnabled(true); controls.IsStopEnabled(true);
        controls.IsFastForwardEnabled(true); controls.IsRewindEnabled(true);
        std::weak_ptr<MediaSession> weak = session;
        session->button = controls.ButtonPressed([weak](auto const&, SystemMediaTransportControlsButtonPressedEventArgs const& args) {
            if (auto current = weak.lock()) switch (args.Button()) {
                case SystemMediaTransportControlsButton::Play: current->enqueue(0); break;
                case SystemMediaTransportControlsButton::Pause: current->enqueue(1); break;
                case SystemMediaTransportControlsButton::Next: current->enqueue(2); break;
                case SystemMediaTransportControlsButton::Previous: current->enqueue(3); break;
                case SystemMediaTransportControlsButton::Stop: current->enqueue(4); break;
                case SystemMediaTransportControlsButton::FastForward: current->enqueue(6, 10000); break;
                case SystemMediaTransportControlsButton::Rewind: current->enqueue(6, -10000); break;
                default: break;
            }
        });
        session->seek = controls.PlaybackPositionChangeRequested([weak](auto const&, PlaybackPositionChangeRequestedEventArgs const& args) {
            if (auto current = weak.lock()) current->enqueue(5, args.RequestedPlaybackPosition().count() / 10000);
        });
        controls.PlaybackStatus(MediaPlaybackStatus::Stopped); controls.IsEnabled(true);
        jlong id = next_id.fetch_add(1);
        { std::lock_guard<std::mutex> lock(sessions_mutex); sessions.emplace(id, session); }
        return id;
    } catch (...) { return 0; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_reworked_WindowsMediaNative_update(JNIEnv* env, jobject, jlong id,
    jstring title, jstring artist, jstring album, jstring artwork, jboolean playing, jlong duration, jlong position) {
    try {
        apartment();
        auto session = lookup(id); if (!session) return JNI_FALSE;
        auto t = text(env, title), a = text(env, artist), b = text(env, album), cover = text(env, artwork);
        if (env->ExceptionCheck()) return JNI_FALSE;
        session->controls.PlaybackStatus(t.empty() ? MediaPlaybackStatus::Stopped : playing ? MediaPlaybackStatus::Playing : MediaPlaybackStatus::Paused);
        if (t != session->title || a != session->artist || b != session->album || cover != session->artwork) {
            auto display = session->controls.DisplayUpdater();
            display.Type(MediaPlaybackType::Music);
            display.MusicProperties().Title(t); display.MusicProperties().Artist(a); display.MusicProperties().AlbumTitle(b);
            try {
                if (cover.empty()) display.Thumbnail(nullptr);
                else display.Thumbnail(winrt::Windows::Storage::Streams::RandomAccessStreamReference::CreateFromUri(winrt::Windows::Foundation::Uri(cover)));
            } catch (...) { display.Thumbnail(nullptr); }
            display.Update();
            session->title = t; session->artist = a; session->album = b; session->artwork = cover;
        }
        duration = std::max<jlong>(0, duration); position = std::clamp<jlong>(position, 0, duration);
        if (duration > 0) {
            SystemMediaTransportControlsTimelineProperties timeline;
            timeline.StartTime(winrt::Windows::Foundation::TimeSpan{0}); timeline.MinSeekTime(winrt::Windows::Foundation::TimeSpan{0});
            timeline.EndTime(winrt::Windows::Foundation::TimeSpan{duration * 10000}); timeline.MaxSeekTime(winrt::Windows::Foundation::TimeSpan{duration * 10000});
            timeline.Position(winrt::Windows::Foundation::TimeSpan{position * 10000});
            session->controls.UpdateTimelineProperties(timeline);
        }
        return JNI_TRUE;
    } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_music_bitchord_reworked_WindowsMediaNative_poll(JNIEnv* env, jobject, jlong id) {
    auto session = lookup(id);
    if (!session) return env->NewLongArray(0);
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->commands.empty()) return env->NewLongArray(0);
    const auto command = session->commands.front(); session->commands.pop_front();
    jlong data[2] = {command.first, command.second};
    auto result = env->NewLongArray(2);
    if (result) env->SetLongArrayRegion(result, 0, 2, data);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_reworked_WindowsMediaNative_detach(JNIEnv*, jobject, jlong id) {
    // JNI never permits a C++ exception to escape, including apartment initialization failure.
    try { apartment(); } catch (...) { }
    std::shared_ptr<MediaSession> session;
    { std::lock_guard<std::mutex> lock(sessions_mutex); auto it = sessions.find(id); if (it != sessions.end()) { session = it->second; sessions.erase(it); } }
    session.reset();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_reworked_WindowsMediaNative_backdrop(JNIEnv*, jobject, jlong window, jint kind, jboolean dark) {
    auto hwnd = reinterpret_cast<HWND>(window);
    DWORD owner = 0;
    if (!IsWindow(hwnd) || !GetWindowThreadProcessId(hwnd, &owner) || owner != GetCurrentProcessId()) return JNI_FALSE;
    BOOL dark_caption = dark ? TRUE : FALSE;
    // Attributes introduced in Windows 10/11. Unsupported systems return a failure;
    // the existing standard caption stays usable.
    DwmSetWindowAttribute(hwnd, 20, &dark_caption, sizeof(dark_caption));
    int type = std::clamp<int>(kind, 0, 4);
    return SUCCEEDED(DwmSetWindowAttribute(hwnd, 38, &type, sizeof(type))) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_music_bitchord_reworked_WindowsMediaNative_wallpaper(JNIEnv* env, jobject) {
    wchar_t path[32768]{};
    if (!SystemParametersInfoW(SPI_GETDESKWALLPAPER, static_cast<UINT>(std::size(path)), path, 0)) return env->NewStringUTF("");
    return env->NewString(reinterpret_cast<const jchar*>(path), static_cast<jsize>(wcslen(path)));
}
