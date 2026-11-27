#define _WIN32_WINNT 0x0A00
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN

#include <jni.h>
#include <windows.h>
#include <windowsx.h>
#include <dwmapi.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <new>
#include <vector>

namespace {

constexpr wchar_t CAPTION_PROPERTY[] = L"BitChord.IntegratedCaption.v1";
constexpr wchar_t CHILD_CAPTION_PROPERTY[] = L"BitChord.IntegratedCaptionChild.v1";

JavaVM* g_caption_vm = nullptr;
jclass g_caption_cls = nullptr;
jmethodID g_state_method = nullptr;

void notify_caption_state(int hovered, int pressed) {
    if (g_caption_vm == nullptr || g_caption_cls == nullptr || g_state_method == nullptr) return;
    JNIEnv* env = nullptr;
    if (g_caption_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return;
    }
    env->CallStaticVoidMethod(g_caption_cls, g_state_method, static_cast<jint>(hovered), static_cast<jint>(pressed));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

struct PixelRect {
    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;

    bool valid() const { return right > left && bottom > top; }
    bool contains(int x, int y) const {
        return valid() && x >= left && x < right && y >= top && y < bottom;
    }
};

struct CaptionState {
    WNDPROC original = nullptr;
    std::mutex mutex;
    int title_bar_height_px = 0;
    bool fullscreen = false;
    bool overlay_blocked = false;
    int hovered_button = 0;
    int pressed_button = 0;
    HWND hovered_child = nullptr;
    PixelRect min_rect{};
    PixelRect max_rect{};
    PixelRect close_rect{};
    std::vector<PixelRect> interactive_rects;
};

struct ChildCaptionState {
    WNDPROC original = nullptr;
    HWND parent = nullptr;
};

CaptionState* get_state(HWND hwnd) {
    return reinterpret_cast<CaptionState*>(GetPropW(hwnd, CAPTION_PROPERTY));
}

ChildCaptionState* get_child_state(HWND hwnd) {
    return reinterpret_cast<ChildCaptionState*>(GetPropW(hwnd, CHILD_CAPTION_PROPERTY));
}

UINT window_dpi(HWND hwnd) {
    const UINT dpi = GetDpiForWindow(hwnd);
    return dpi == 0 ? 96 : dpi;
}

int border_x(HWND hwnd) {
    const UINT dpi = window_dpi(hwnd);
    return GetSystemMetricsForDpi(SM_CXFRAME, dpi) + GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
}

int border_y(HWND hwnd) {
    const UINT dpi = window_dpi(hwnd);
    return GetSystemMetricsForDpi(SM_CYFRAME, dpi) + GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
}

PixelRect decode_rect(JNIEnv* env, jintArray array) {
    if (array == nullptr) return {};
    if (env->GetArrayLength(array) < 4) return {};
    jint buf[4] = {0, 0, 0, 0};
    env->GetIntArrayRegion(array, 0, 4, buf);
    return PixelRect{buf[0], buf[1], buf[2], buf[3]};
}

LRESULT hit_test_window(HWND hwnd, CaptionState* state, POINT screen_pt) {
    bool is_fullscreen = false;
    bool overlay_blocked = false;
    int title_height = 0;
    PixelRect min_rect{};
    PixelRect max_rect{};
    PixelRect close_rect{};
    std::vector<PixelRect> interactive;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        is_fullscreen = state->fullscreen;
        overlay_blocked = state->overlay_blocked;
        title_height = state->title_bar_height_px;
        min_rect = state->min_rect;
        max_rect = state->max_rect;
        close_rect = state->close_rect;
        interactive = state->interactive_rects;
    }
    if (is_fullscreen) {
        return HTCLIENT;
    }

    if (!IsZoomed(hwnd)) {
        RECT win_rect{};
        if (GetWindowRect(hwnd, &win_rect)) {
            const int bx = border_x(hwnd);
            const int by = border_y(hwnd);
            const int top_by = std::min(by, MulDiv(5, static_cast<int>(window_dpi(hwnd)), 96));
            const bool on_left = screen_pt.x >= win_rect.left && screen_pt.x < win_rect.left + bx;
            const bool on_right = screen_pt.x < win_rect.right && screen_pt.x >= win_rect.right - bx;
            const bool on_top = screen_pt.y >= win_rect.top && screen_pt.y < win_rect.top + top_by;
            const bool on_bottom = screen_pt.y < win_rect.bottom && screen_pt.y >= win_rect.bottom - by;

            if (on_top && on_left) return HTTOPLEFT;
            if (on_top && on_right) return HTTOPRIGHT;
            if (on_bottom && on_left) return HTBOTTOMLEFT;
            if (on_bottom && on_right) return HTBOTTOMRIGHT;
            if (on_top) return HTTOP;
            if (on_bottom) return HTBOTTOM;
            if (on_left) return HTLEFT;
            if (on_right) return HTRIGHT;
        }
    }

    POINT client_pt = screen_pt;
    ScreenToClient(hwnd, &client_pt);

    if (close_rect.contains(client_pt.x, client_pt.y)) return HTCLOSE;
    if (max_rect.contains(client_pt.x, client_pt.y)) return HTMAXBUTTON;
    if (min_rect.contains(client_pt.x, client_pt.y)) return HTMINBUTTON;

    if (overlay_blocked) {
        return HTCLIENT;
    }

    for (const auto& rect : interactive) {
        if (rect.contains(client_pt.x, client_pt.y)) {
            return HTCLIENT;
        }
    }

    if (title_height > 0 && client_pt.y >= 0 && client_pt.y < title_height) {
        return HTCAPTION;
    }
    return HTCLIENT;
}

void detach_child(HWND child) {
    ChildCaptionState* child_state = get_child_state(child);
    if (child_state == nullptr) return;
    RemovePropW(child, CHILD_CAPTION_PROPERTY);
    if (child_state->original != nullptr) {
        SetWindowLongPtrW(child, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(child_state->original));
    }
    delete child_state;
}

BOOL CALLBACK enum_detach_child(HWND child, LPARAM) {
    detach_child(child);
    return TRUE;
}

LRESULT CALLBACK child_wnd_proc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    ChildCaptionState* child_state = get_child_state(hwnd);
    if (child_state == nullptr) {
        return DefWindowProcW(hwnd, msg, wParam, lParam);
    }
    WNDPROC original = child_state->original;
    HWND parent = child_state->parent;

    switch (msg) {
        case WM_NCDESTROY: {
            RemovePropW(hwnd, CHILD_CAPTION_PROPERTY);
            SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(original));
            delete child_state;
            return CallWindowProcW(original, hwnd, msg, wParam, lParam);
        }

        case WM_NCHITTEST: {
            if (IsWindow(parent)) {
                if (CaptionState* parent_state = get_state(parent)) {
                    const POINT screen_pt{GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
                    const LRESULT hit = hit_test_window(parent, parent_state, screen_pt);
                    if (hit != HTCLIENT) {
                        std::lock_guard<std::mutex> lock(parent_state->mutex);
                        parent_state->hovered_child = hwnd;
                        return HTTRANSPARENT;
                    }
                }
            }
            break;
        }

        case WM_MOUSEMOVE: {
            if (IsWindow(parent)) {
                if (CaptionState* parent_state = get_state(parent)) {
                    bool changed = false;
                    {
                        std::lock_guard<std::mutex> lock(parent_state->mutex);
                        if (parent_state->hovered_button != 0 || parent_state->pressed_button != 0) {
                            parent_state->hovered_button = 0;
                            parent_state->pressed_button = 0;
                            changed = true;
                        }
                    }
                    if (changed) notify_caption_state(0, 0);
                }
            }
            break;
        }
    }

    return CallWindowProcW(original, hwnd, msg, wParam, lParam);
}

void attach_child(HWND child, HWND parent) {
    if (!IsWindow(child) || child == parent || get_child_state(child) != nullptr) return;
    DWORD child_pid = 0;
    DWORD parent_pid = 0;
    GetWindowThreadProcessId(child, &child_pid);
    GetWindowThreadProcessId(parent, &parent_pid);
    if (child_pid == 0 || child_pid != parent_pid) return;

    auto* child_state = new (std::nothrow) ChildCaptionState();
    if (child_state == nullptr) return;
    child_state->parent = parent;
    if (!SetPropW(child, CHILD_CAPTION_PROPERTY, child_state)) {
        delete child_state;
        return;
    }

    SetLastError(0);
    const LONG_PTR prev = SetWindowLongPtrW(
        child, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(child_wnd_proc)
    );
    if (prev == 0 && GetLastError() != 0) {
        RemovePropW(child, CHILD_CAPTION_PROPERTY);
        delete child_state;
        return;
    }
    child_state->original = reinterpret_cast<WNDPROC>(prev);
}

BOOL CALLBACK enum_attach_child(HWND child, LPARAM lParam) {
    attach_child(child, reinterpret_cast<HWND>(lParam));
    return TRUE;
}

LRESULT CALLBACK caption_wnd_proc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    CaptionState* state = get_state(hwnd);
    if (state == nullptr) {
        return DefWindowProcW(hwnd, msg, wParam, lParam);
    }
    WNDPROC original = state->original;

    switch (msg) {
        case WM_NCDESTROY: {
            EnumChildWindows(hwnd, enum_detach_child, 0);
            RemovePropW(hwnd, CAPTION_PROPERTY);
            SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(original));
            delete state;
            return CallWindowProcW(original, hwnd, msg, wParam, lParam);
        }

        case WM_PARENTNOTIFY: {
            if (LOWORD(wParam) == WM_CREATE) {
                attach_child(reinterpret_cast<HWND>(lParam), hwnd);
            }
            break;
        }

        case WM_NCCALCSIZE: {
            if (wParam == TRUE) {
                bool is_fullscreen = false;
                {
                    std::lock_guard<std::mutex> lock(state->mutex);
                    is_fullscreen = state->fullscreen;
                }
                if (!is_fullscreen) {
                    auto* params = reinterpret_cast<NCCALCSIZE_PARAMS*>(lParam);
                    if (IsZoomed(hwnd)) {
                        HMONITOR monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
                        MONITORINFO mi{};
                        mi.cbSize = sizeof(mi);
                        if (GetMonitorInfoW(monitor, &mi)) {
                            params->rgrc[0] = mi.rcWork;
                            // Leave 1px at the bottom when taskbar is auto-hidden so it can still reveal.
                            if (EqualRect(&mi.rcWork, &mi.rcMonitor)) {
                                params->rgrc[0].bottom -= 1;
                            }
                            return 0;
                        }
                    } else {
                        const LONG saved_top = params->rgrc[0].top;
                        const LRESULT res = CallWindowProcW(original, hwnd, msg, wParam, lParam);
                        params->rgrc[0].top = saved_top;
                        return res;
                    }
                }
            }
            break;
        }

        case WM_NCHITTEST: {
            const POINT screen_pt{GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            return hit_test_window(hwnd, state, screen_pt);
        }

        case WM_NCMOUSEMOVE: {
            if (wParam == HTMINBUTTON || wParam == HTMAXBUTTON || wParam == HTCLOSE) {
                const int btn = static_cast<int>(wParam);
                int pressed = 0;
                bool changed = false;
                HWND target = nullptr;
                {
                    std::lock_guard<std::mutex> lock(state->mutex);
                    if (state->hovered_button != btn) {
                        state->hovered_button = btn;
                        changed = true;
                    }
                    pressed = state->pressed_button;
                    target = state->hovered_child;
                }
                if (changed) {
                    notify_caption_state(btn, pressed);
                }
                if (target != nullptr && IsWindow(target)) {
                    POINT target_pt{GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
                    ScreenToClient(target, &target_pt);
                    PostMessageW(target, WM_MOUSEMOVE, 0, MAKELPARAM(target_pt.x, target_pt.y));
                }

                TRACKMOUSEEVENT tme{};
                tme.cbSize = sizeof(tme);
                tme.dwFlags = TME_NONCLIENT | TME_LEAVE;
                tme.hwndTrack = hwnd;
                TrackMouseEvent(&tme);
            } else {
                int pressed = 0;
                bool changed = false;
                {
                    std::lock_guard<std::mutex> lock(state->mutex);
                    if (state->hovered_button != 0) {
                        state->hovered_button = 0;
                        changed = true;
                    }
                    pressed = state->pressed_button;
                }
                if (changed) {
                    notify_caption_state(0, pressed);
                }
            }
            break;
        }

        case WM_NCMOUSELEAVE: {
            HWND target = nullptr;
            bool changed = false;
            {
                std::lock_guard<std::mutex> lock(state->mutex);
                target = state->hovered_child;
                if (state->hovered_button != 0 || state->pressed_button != 0) {
                    state->hovered_button = 0;
                    state->pressed_button = 0;
                    changed = true;
                }
            }
            if (changed) {
                notify_caption_state(0, 0);
            }
            if (target != nullptr && IsWindow(target)) {
                PostMessageW(target, WM_MOUSELEAVE, 0, 0);
            }
            break;
        }

        case WM_NCLBUTTONDOWN: {
            if (wParam == HTMINBUTTON || wParam == HTMAXBUTTON || wParam == HTCLOSE) {
                const int btn = static_cast<int>(wParam);
                {
                    std::lock_guard<std::mutex> lock(state->mutex);
                    state->hovered_button = btn;
                    state->pressed_button = btn;
                }
                notify_caption_state(btn, btn);
                // Suppress legacy Windows non-client caption button painting; click executes on UP.
                return 0;
            }
            break;
        }

        case WM_NCLBUTTONUP: {
            if (wParam == HTMINBUTTON || wParam == HTMAXBUTTON || wParam == HTCLOSE) {
                const int btn = static_cast<int>(wParam);
                int pressed = 0;
                int hovered = 0;
                {
                    std::lock_guard<std::mutex> lock(state->mutex);
                    pressed = state->pressed_button;
                    hovered = state->hovered_button;
                    state->pressed_button = 0;
                }
                notify_caption_state(hovered, 0);
                if (pressed == btn) {
                    if (wParam == HTMINBUTTON) {
                        PostMessageW(hwnd, WM_SYSCOMMAND, SC_MINIMIZE, 0);
                    } else if (wParam == HTMAXBUTTON) {
                        PostMessageW(hwnd, WM_SYSCOMMAND, IsZoomed(hwnd) ? SC_RESTORE : SC_MAXIMIZE, 0);
                    } else if (wParam == HTCLOSE) {
                        PostMessageW(hwnd, WM_CLOSE, 0, 0);
                    }
                }
                return 0;
            }
            break;
        }

        case WM_CAPTURECHANGED: {
            bool changed = false;
            {
                std::lock_guard<std::mutex> lock(state->mutex);
                if (state->pressed_button != 0) {
                    state->pressed_button = 0;
                    changed = true;
                }
            }
            if (changed) notify_caption_state(state->hovered_button, 0);
            break;
        }

        case WM_ACTIVATE: {
            if (LOWORD(wParam) == WA_INACTIVE) {
                bool changed = false;
                {
                    std::lock_guard<std::mutex> lock(state->mutex);
                    if (state->hovered_button != 0 || state->pressed_button != 0) {
                        state->hovered_button = 0;
                        state->pressed_button = 0;
                        changed = true;
                    }
                }
                if (changed) notify_caption_state(0, 0);
            }
            break;
        }
    }

    return CallWindowProcW(original, hwnd, msg, wParam, lParam);
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsNativeCaption_nativeAttach(
    JNIEnv* env, jclass clazz, jlong hwndPtr, jint titleBarHeightPx
) {
    if (g_caption_vm == nullptr) {
        env->GetJavaVM(&g_caption_vm);
    }
    if (g_caption_cls == nullptr && clazz != nullptr) {
        g_caption_cls = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
        if (g_caption_cls != nullptr) {
            g_state_method = env->GetStaticMethodID(g_caption_cls, "onCaptionButtonState", "(II)V");
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                g_state_method = nullptr;
            }
        }
    }
    HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(hwndPtr));
    if (!IsWindow(hwnd)) return JNI_FALSE;

    if (CaptionState* existing = get_state(hwnd)) {
        {
            std::lock_guard<std::mutex> lock(existing->mutex);
            existing->title_bar_height_px = std::max(0, static_cast<int>(titleBarHeightPx));
        }
        EnumChildWindows(hwnd, enum_attach_child, reinterpret_cast<LPARAM>(hwnd));
        return JNI_TRUE;
    }

    auto* state = new (std::nothrow) CaptionState();
    if (state == nullptr) return JNI_FALSE;
    state->title_bar_height_px = std::max(0, static_cast<int>(titleBarHeightPx));

    if (!SetPropW(hwnd, CAPTION_PROPERTY, state)) {
        delete state;
        return JNI_FALSE;
    }

    SetLastError(0);
    const LONG_PTR prev = SetWindowLongPtrW(
        hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(caption_wnd_proc)
    );
    if (prev == 0 && GetLastError() != 0) {
        RemovePropW(hwnd, CAPTION_PROPERTY);
        delete state;
        return JNI_FALSE;
    }
    state->original = reinterpret_cast<WNDPROC>(prev);
    EnumChildWindows(hwnd, enum_attach_child, reinterpret_cast<LPARAM>(hwnd));

    SetWindowPos(
        hwnd, nullptr, 0, 0, 0, 0,
        SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED
    );
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsNativeCaption_nativeDetach(
    JNIEnv*, jclass, jlong hwndPtr
) {
    HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(hwndPtr));
    if (!IsWindow(hwnd)) return;
    CaptionState* state = get_state(hwnd);
    if (state == nullptr) return;

    EnumChildWindows(hwnd, enum_detach_child, 0);
    RemovePropW(hwnd, CAPTION_PROPERTY);
    if (state->original != nullptr) {
        SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(state->original));
    }
    delete state;
    SetWindowPos(
        hwnd, nullptr, 0, 0, 0, 0,
        SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED
    );
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsNativeCaption_nativeSetTitleBarHeight(
    JNIEnv*, jclass, jlong hwndPtr, jint titleBarHeightPx
) {
    HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(hwndPtr));
    if (!IsWindow(hwnd)) return;
    if (CaptionState* state = get_state(hwnd)) {
        std::lock_guard<std::mutex> lock(state->mutex);
        state->title_bar_height_px = std::max(0, static_cast<int>(titleBarHeightPx));
    }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsNativeCaption_nativeSetFullscreen(
    JNIEnv*, jclass, jlong hwndPtr, jboolean fullscreen
) {
    HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(hwndPtr));
    if (!IsWindow(hwnd)) return;
    if (CaptionState* state = get_state(hwnd)) {
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            state->fullscreen = (fullscreen == JNI_TRUE);
        }
        SetWindowPos(
            hwnd, nullptr, 0, 0, 0, 0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED
        );
    }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsNativeCaption_nativeSetOverlayBlocked(
    JNIEnv*, jclass, jlong hwndPtr, jboolean blocked
) {
    HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(hwndPtr));
    if (!IsWindow(hwnd)) return;
    if (CaptionState* state = get_state(hwnd)) {
        std::lock_guard<std::mutex> lock(state->mutex);
        state->overlay_blocked = (blocked == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopWindowsNativeCaption_nativeSetHitTestRects(
    JNIEnv* env,
    jclass,
    jlong hwndPtr,
    jintArray minRect,
    jintArray maxRect,
    jintArray closeRect,
    jintArray interactiveRects
) {
    HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(hwndPtr));
    if (!IsWindow(hwnd)) return;
    CaptionState* state = get_state(hwnd);
    if (state == nullptr) return;

    EnumChildWindows(hwnd, enum_attach_child, reinterpret_cast<LPARAM>(hwnd));

    PixelRect min_r = decode_rect(env, minRect);
    PixelRect max_r = decode_rect(env, maxRect);
    PixelRect close_r = decode_rect(env, closeRect);
    std::vector<PixelRect> interactive;
    if (interactiveRects != nullptr) {
        const jsize len = env->GetArrayLength(interactiveRects);
        if (len >= 4) {
            std::vector<jint> flat(len);
            env->GetIntArrayRegion(interactiveRects, 0, len, flat.data());
            for (jsize i = 0; i + 3 < len; i += 4) {
                PixelRect r{flat[i], flat[i + 1], flat[i + 2], flat[i + 3]};
                if (r.valid()) interactive.push_back(r);
            }
        }
    }

    std::lock_guard<std::mutex> lock(state->mutex);
    state->min_rect = min_r;
    state->max_rect = max_r;
    state->close_rect = close_r;
    state->interactive_rects = std::move(interactive);
}

} // extern "C"
