/* libmpv render API control on a native Wayland OpenGL surface. */
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <mpv/client.h>
#include <mpv/render_gl.h>
#include <stdio.h>
#include <string.h>

static void *get_gl_proc(void *context, const char *name) {
    (void)context;
    return (void *)SDL_GL_GetProcAddress(name);
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s CLIP.mp4\n", argv[0]);
        return 2;
    }
    if (!SDL_Init(SDL_INIT_VIDEO)) {
        fprintf(stderr, "SDL init: %s\n", SDL_GetError());
        return 1;
    }

    int result = 1;
    SDL_Window *window = SDL_CreateWindow(
        "BitChord libmpv render probe", 760, 540, SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE
    );
    SDL_GLContext gl = window != NULL ? SDL_GL_CreateContext(window) : NULL;
    mpv_handle *player = NULL;
    mpv_render_context *renderer = NULL;
    if (gl == NULL) {
        fprintf(stderr, "SDL window/context: %s\n", SDL_GetError());
        goto close;
    }
    void *display = SDL_GetPointerProperty(
        SDL_GetWindowProperties(window), SDL_PROP_WINDOW_WAYLAND_DISPLAY_POINTER, NULL
    );
    if (display == NULL) {
        fprintf(stderr, "A native Wayland window is required\n");
        goto close;
    }

    player = mpv_create();
    if (player == NULL || mpv_set_option_string(player, "config", "no") < 0
        || mpv_set_option_string(player, "audio", "no") < 0
        || mpv_set_option_string(player, "vo", "libmpv") < 0
        || mpv_set_option_string(player, "hwdec", "vaapi") < 0 || mpv_initialize(player) < 0) {
        fprintf(stderr, "libmpv initialization failed\n");
        goto close;
    }

    mpv_opengl_init_params gl_init = { .get_proc_address = get_gl_proc };
    mpv_render_param init[] = {
        {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_OPENGL},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
        {MPV_RENDER_PARAM_WL_DISPLAY, display},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    int status = mpv_render_context_create(&renderer, player, init);
    if (status < 0) {
        fprintf(stderr, "libmpv renderer: %s\n", mpv_error_string(status));
        goto close;
    }

    const char *load[] = {"loadfile", argv[1], NULL};
    if (mpv_command(player, load) < 0)
        goto close;

    Uint64 deadline = SDL_GetTicks() + 10000;
    int swaps = 0;
    bool hardware = false;
    while (SDL_GetTicks() < deadline) {
        SDL_Event window_event;
        while (SDL_PollEvent(&window_event)) {
            if (window_event.type == SDL_EVENT_QUIT)
                goto close;
        }

        mpv_event *event;
        while ((event = mpv_wait_event(player, 0))->event_id != MPV_EVENT_NONE) {
            if (event->event_id == MPV_EVENT_END_FILE) {
                mpv_event_end_file *end = event->data;
                printf("successful_swaps=%d vaapi_observed=%s end_reason=%d\n",
                       swaps, hardware ? "true" : "false", end->reason);
                result = swaps > 0 && hardware && end->reason == MPV_END_FILE_REASON_EOF ? 0 : 1;
                goto close;
            }
        }

        if ((mpv_render_context_update(renderer) & MPV_RENDER_UPDATE_FRAME) != 0) {
            mpv_opengl_fbo fbo = {0};
            SDL_GetWindowSizeInPixels(window, &fbo.w, &fbo.h);
            int flip = 1;
            mpv_render_param draw[] = {
                {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
                {MPV_RENDER_PARAM_FLIP_Y, &flip},
                {MPV_RENDER_PARAM_INVALID, NULL},
            };
            if (mpv_render_context_render(renderer, draw) < 0 || !SDL_GL_SwapWindow(window))
                goto close;
            mpv_render_context_report_swap(renderer);
            if (!hardware) {
                char *hwdec = mpv_get_property_string(player, "hwdec-current");
                hardware = hwdec != NULL && strcmp(hwdec, "vaapi") == 0;
                mpv_free(hwdec);
            }
            swaps++;
        }
        SDL_Delay(4);
    }
    fprintf(stderr, "libmpv playback timed out\n");

close:
    if (renderer != NULL)
        mpv_render_context_free(renderer);
    if (player != NULL)
        mpv_terminate_destroy(player);
    if (gl != NULL)
        SDL_GL_DestroyContext(gl);
    if (window != NULL)
        SDL_DestroyWindow(window);
    SDL_Quit();
    return result;
}
