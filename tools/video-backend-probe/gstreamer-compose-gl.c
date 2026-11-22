/* Development probe: import a VA DMA-BUF into the Compose scene's Wayland EGL framebuffer. */
#include <SDL3/SDL.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <gst/allocators/gstdmabuf.h>
#include <gst/app/gstappsink.h>
#include <gst/video/gstvideometa.h>
#include <gst/video/video-info-dma.h>

typedef void (*ImportImage)(GLenum, void *);

typedef struct {
    SDL_Window *window;
    SDL_GLContext gl;
    EGLDisplay egl;
    ImportImage import_image;
    GstElement *pipeline;
    GstAppSink *sink;
    GstSample *borrowed;
    GLuint framebuffer;
    GLuint texture;
    EGLImage image;
    gboolean saw_texture;
} Probe;

static void release_sample(Probe *p) {
    if (p->borrowed == NULL) return;

    glFinish();
    glBindFramebuffer(GL_FRAMEBUFFER, p->framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    // Drop the imported texture's storage before the decoder can recycle the DMA-BUF.
    if (p->texture) glDeleteTextures(1, &p->texture);
    if (p->image != EGL_NO_IMAGE) eglDestroyImage(p->egl, p->image);
    gst_sample_unref(p->borrowed);
    p->borrowed = NULL;
    p->texture = 0;
    p->image = EGL_NO_IMAGE;
}

void probe_close(Probe *p) {
    if (p == NULL) return;
    release_sample(p);
    if (p->framebuffer) glDeleteFramebuffers(1, &p->framebuffer);
    if (p->pipeline != NULL) gst_element_set_state(p->pipeline, GST_STATE_NULL);
    gst_clear_object(&p->sink);
    gst_clear_object(&p->pipeline);
    if (p->gl != NULL) SDL_GL_DestroyContext(p->gl);
    if (p->window != NULL) SDL_DestroyWindow(p->window);
    SDL_Quit();
    g_free(p);
}

static gboolean propose_allocation(GstAppSink *sink, GstQuery *query, gpointer unused) {
    (void)sink;
    (void)unused;
    gst_query_add_allocation_meta(query, GST_VIDEO_META_API_TYPE, NULL);
    return TRUE;
}

Probe *probe_open(const char *clip) {
    gst_init(NULL, NULL);
    Probe *p = g_new0(Probe, 1);
    GError *error = NULL;
    if (!SDL_Init(SDL_INIT_VIDEO)) goto fail;
    SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);

    p->window = SDL_CreateWindow("BitChord Compose GPU texture probe", 640, 480, SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE);
    if (p->window == NULL) goto fail;
    p->gl = SDL_GL_CreateContext(p->window);
    if (p->gl == NULL || g_strcmp0(SDL_GetCurrentVideoDriver(), "wayland") != 0) goto fail;
    p->egl = eglGetCurrentDisplay();
    if (p->egl == EGL_NO_DISPLAY) goto fail;

    p->import_image = (ImportImage)SDL_GL_GetProcAddress("glEGLImageTargetTexture2DOES");
    if (p->import_image == NULL || !SDL_GL_ExtensionSupported("GL_OES_EGL_image")) goto fail;

    // The tested Intel driver exports tiled NV12. VA converts it into a single-plane
    // RGBA DMA-BUF that Mesa can import as a desktop OpenGL texture.
    p->pipeline = gst_parse_launch(
        "filesrc name=source ! qtdemux ! h264parse ! vah264dec ! "
        "video/x-raw(memory:VAMemory) ! vapostproc ! "
        "video/x-raw(memory:DMABuf),format=DMA_DRM,drm-format=AB24:0x0100000000000002 ! "
        "appsink name=pixels emit-signals=true max-buffers=1 drop=true enable-last-sample=false wait-on-eos=false", &error);
    if (p->pipeline == NULL || error != NULL) goto fail;
    GstElement *source = gst_bin_get_by_name(GST_BIN(p->pipeline), "source");
    g_object_set(source, "location", clip, NULL);
    gst_object_unref(source);
    p->sink = GST_APP_SINK(gst_bin_get_by_name(GST_BIN(p->pipeline), "pixels"));
    g_signal_connect(p->sink, "propose-allocation", G_CALLBACK(propose_allocation), NULL);
    glGenFramebuffers(1, &p->framebuffer);
    if (!p->framebuffer || gst_element_set_state(p->pipeline, GST_STATE_PLAYING) == GST_STATE_CHANGE_FAILURE) goto fail;
    g_print("compose_gpu_renderer=%s\n", (const char *)glGetString(GL_RENDERER));
    return p;

fail:
    g_printerr("texture probe initialization: %s (EGL 0x%x)\n",
               error != NULL ? error->message : SDL_GetError(), eglGetError());
    g_clear_error(&error);
    probe_close(p);
    return NULL;
}

static gboolean import_sample(Probe *p, GstSample *sample, int *width, int *height) {
    GstBuffer *buffer = gst_sample_get_buffer(sample);
    GstMemory *memory = gst_buffer_n_memory(buffer) == 1 ? gst_buffer_peek_memory(buffer, 0) : NULL;
    GstVideoMeta *meta = gst_buffer_get_video_meta(buffer);
    GstVideoInfoDmaDrm info;
    if (memory == NULL || !gst_is_dmabuf_memory(memory) || meta == NULL || meta->n_planes != 1
        || !gst_video_info_dma_drm_from_caps(&info, gst_sample_get_caps(sample))) return FALSE;

    gsize mem_offset;
    gst_memory_get_sizes(memory, &mem_offset, NULL);
    *width = GST_VIDEO_INFO_WIDTH(&info.vinfo);
    *height = GST_VIDEO_INFO_HEIGHT(&info.vinfo);
    const EGLAttrib attributes[] = {
        EGL_WIDTH, *width, EGL_HEIGHT, *height, EGL_LINUX_DRM_FOURCC_EXT, info.drm_fourcc,
        EGL_DMA_BUF_PLANE0_FD_EXT, gst_dmabuf_memory_get_fd(memory),
        EGL_DMA_BUF_PLANE0_OFFSET_EXT, (EGLAttrib)(mem_offset + meta->offset[0]),
        EGL_DMA_BUF_PLANE0_PITCH_EXT, meta->stride[0],
        EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT, (EGLAttrib)(info.drm_modifier & 0xffffffff),
        EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT, (EGLAttrib)(info.drm_modifier >> 32),
        EGL_NONE,
    };
    p->image = eglCreateImage(p->egl, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, NULL, attributes);
    if (p->image == EGL_NO_IMAGE) {
        g_printerr("texture probe: DMA-BUF EGL import failed (0x%x)\n", eglGetError());
        return FALSE;
    }
    glGenTextures(1, &p->texture);
    glBindTexture(GL_TEXTURE_2D, p->texture);
    p->import_image(GL_TEXTURE_2D, p->image);
    glBindFramebuffer(GL_FRAMEBUFFER, p->framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, p->texture, 0);
    gboolean complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    return complete;
}

/* The sample stays alive until the consumer has finished using its shared texture. */
int probe_pull(Probe *p, int *width, int *height) {
    if (p->borrowed != NULL) return -1;
    SDL_Event event;
    while (SDL_PollEvent(&event)) if (event.type == SDL_EVENT_QUIT) return -1;

    GstSample *sample = gst_app_sink_try_pull_sample(p->sink, 50 * GST_MSECOND);
    if (sample == NULL) {
        GstBus *bus = gst_element_get_bus(p->pipeline);
        GstMessage *error = gst_bus_pop_filtered(bus, GST_MESSAGE_ERROR);
        gst_object_unref(bus);
        if (error != NULL) {
            GError *failure = NULL;
            gst_message_parse_error(error, &failure, NULL);
            g_printerr("texture probe pipeline: %s\n", failure->message);
            g_error_free(failure);
            gst_message_unref(error);
            return -1;
        }
        return p->saw_texture && gst_app_sink_is_eos(p->sink) ? -2 : 0;
    }

    p->borrowed = sample;
    if (!import_sample(p, sample, width, height)) {
        g_printerr("texture probe: could not attach the DMA-BUF texture\n");
        return -1;
    }
    if (!p->saw_texture) {
        gchar *description = gst_caps_to_string(gst_sample_get_caps(sample));
        g_print("compose_texture_caps=%s\n", description);
        g_free(description);
        p->saw_texture = TRUE;
    }
    return (int)p->framebuffer;
}

int probe_window_size(Probe *p, int *width, int *height) {
    return SDL_GetWindowSizeInPixels(p->window, width, height);
}

int probe_resize(Probe *p) {
    return SDL_SetWindowSize(p->window, 720, 520);
}

int probe_swap(Probe *p) {
    return SDL_GL_SwapWindow(p->window);
}

void probe_finish_frame(Probe *p) {
    release_sample(p);
}

static void *load_gl(void *context, const char *name) {
    (void)context;
    return (void *)SDL_GL_GetProcAddress(name);
}

void *probe_gl_loader(void) {
    return (void *)load_gl;
}
