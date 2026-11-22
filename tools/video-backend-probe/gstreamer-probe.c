/*
 * Finite video-only probe. A sink-input buffer and the sink's rendered counter
 * describe submission/rendering, not a compositor presentation acknowledgement.
 */
#include <gst/gst.h>
#include <gst/video/videooverlay.h>
#include <stdio.h>
#include <string.h>

#define PROBE_STARTUP_TIMEOUT (5 * GST_SECOND)
#define PROBE_RUN_TIMEOUT (30 * GST_SECOND)

typedef struct {
    gint64 started_at;
    gint64 first_decoded_us;
    gint64 first_sink_input_us;
    gchar *decoder_caps;
    gchar *sink_caps;
} Probe;

static void record_first_buffer(GstPad *pad, gint64 start, gint64 *time, gchar **description) {
    if (*time >= 0)
        return;

    *time = g_get_monotonic_time() - start;
    GstCaps *caps = gst_pad_get_current_caps(pad);
    if (caps != NULL) {
        *description = gst_caps_to_string(caps);
        gst_caps_unref(caps);
    }
}

static GstPadProbeReturn on_decoded(GstPad *pad, GstPadProbeInfo *info, gpointer data) {
    (void)info;
    Probe *probe = data;
    record_first_buffer(pad, probe->started_at, &probe->first_decoded_us, &probe->decoder_caps);
    return GST_PAD_PROBE_OK;
}

static GstPadProbeReturn on_sink_input(GstPad *pad, GstPadProbeInfo *info, gpointer data) {
    (void)info;
    Probe *probe = data;
    record_first_buffer(pad, probe->started_at, &probe->first_sink_input_us, &probe->sink_caps);
    return GST_PAD_PROBE_OK;
}

static GstBusSyncReply set_overlay_handle(GstBus *bus, GstMessage *message, gpointer data) {
    (void)bus;
    if (!gst_is_video_overlay_prepare_window_handle_message(message))
        return GST_BUS_PASS;

    guintptr window = *(const guintptr *)data;
    gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(GST_MESSAGE_SRC(message)), window);
    gst_message_unref(message);
    return GST_BUS_DROP;
}

static void read_sink_stats(GstElement *sink, guint64 *rendered, guint64 *dropped) {
    // glimagesink is a bin whose inner glimagesinkelement owns the counters.
    GstElement *inner = NULL;
    if (GST_IS_BIN(sink) && g_object_class_find_property(G_OBJECT_GET_CLASS(sink), "sink") != NULL)
        g_object_get(sink, "sink", &inner, NULL);
    GstElement *stats_owner = inner != NULL ? inner : sink;
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(stats_owner), "stats") != NULL) {
        GstStructure *stats = NULL;
        g_object_get(stats_owner, "stats", &stats, NULL);
        if (stats != NULL) {
            gst_structure_get_uint64(stats, "rendered", rendered);
            gst_structure_get_uint64(stats, "dropped", dropped);
            gst_structure_free(stats);
        }
    }
    gst_clear_object(&inner);
}

static gboolean run_cycle(const gchar *description, const gchar *clip, guint cycle, guintptr window) {
    GError *error = NULL;
    GstElement *pipeline = gst_parse_launch(description, &error);
    if (error != NULL || pipeline == NULL) {
        g_printerr("pipeline setup: %s\n", error != NULL ? error->message : "no pipeline");
        g_clear_error(&error);
        gst_clear_object(&pipeline);
        return FALSE;
    }

    GstElement *source = gst_bin_get_by_name(GST_BIN(pipeline), "source");
    GstElement *decoder = gst_bin_get_by_name(GST_BIN(pipeline), "decoder");
    GstElement *sink = gst_bin_get_by_name(GST_BIN(pipeline), "sink");
    GstPad *decoded_pad = gst_element_get_static_pad(decoder, "src");
    GstPad *sink_pad = gst_element_get_static_pad(sink, "sink");
    GstBus *bus = gst_element_get_bus(pipeline);
    g_object_set(source, "location", clip, NULL);

    Probe probe = {.started_at = g_get_monotonic_time(), .first_decoded_us = -1, .first_sink_input_us = -1};
    gst_pad_add_probe(decoded_pad, GST_PAD_PROBE_TYPE_BUFFER, on_decoded, &probe, NULL);
    gst_pad_add_probe(sink_pad, GST_PAD_PROBE_TYPE_BUFFER, on_sink_input, &probe, NULL);
    if (window != 0) {
        gst_bus_set_sync_handler(bus, set_overlay_handle, &window, NULL);
        gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(sink), window);
    }

    gst_element_set_state(pipeline, GST_STATE_PLAYING);
    GstState state;
    GstStateChangeReturn startup = gst_element_get_state(pipeline, &state, NULL, PROBE_STARTUP_TIMEOUT);
    GstMessage *message = gst_bus_timed_pop_filtered(
        bus, startup == GST_STATE_CHANGE_SUCCESS ? PROBE_RUN_TIMEOUT : 0,
        GST_MESSAGE_ERROR | GST_MESSAGE_EOS
    );
    gboolean success = message != NULL && GST_MESSAGE_TYPE(message) == GST_MESSAGE_EOS;
    if (message != NULL && GST_MESSAGE_TYPE(message) == GST_MESSAGE_ERROR) {
        gchar *details = NULL;
        gst_message_parse_error(message, &error, &details);
        g_printerr("cycle %u: %s\n", cycle, error->message);
        if (details != NULL)
            g_printerr("%s\n", details);
        g_free(details);
        g_clear_error(&error);
    } else if (startup == GST_STATE_CHANGE_FAILURE) {
        g_printerr("cycle %u: pipeline failed to initialize (state %s)\n", cycle, gst_element_state_get_name(state));
    } else if (!success) {
        g_printerr("cycle %u: timed out (pipeline state %s)\n", cycle, gst_element_state_get_name(state));
    }
    if (message != NULL)
        gst_message_unref(message);

    guint64 rendered = 0;
    guint64 dropped = 0;
    read_sink_stats(sink, &rendered, &dropped);

    gint64 stop_started = g_get_monotonic_time();
    GstStateChangeReturn stopped = gst_element_set_state(pipeline, GST_STATE_NULL);
    gst_bus_set_sync_handler(bus, NULL, NULL, NULL);
    gst_object_unref(bus);
    gst_object_unref(sink_pad);
    gst_object_unref(decoded_pad);
    gst_object_unref(sink);
    gst_object_unref(decoder);
    gst_object_unref(source);
    gst_object_unref(pipeline);
    gint64 teardown_us = g_get_monotonic_time() - stop_started;

    g_print("cycle=%u first_decoded_us=%" G_GINT64_FORMAT " first_sink_input_us=%" G_GINT64_FORMAT
            " rendered=%" G_GUINT64_FORMAT " dropped=%" G_GUINT64_FORMAT
            " teardown_us=%" G_GINT64_FORMAT "\n",
            cycle, probe.first_decoded_us, probe.first_sink_input_us, rendered, dropped, teardown_us);
    if (cycle == 1) {
        g_print("decoder_caps=%s\nsink_caps=%s\n",
                probe.decoder_caps ? probe.decoder_caps : "(none)",
                probe.sink_caps ? probe.sink_caps : "(none)");
    }
    g_free(probe.decoder_caps);
    g_free(probe.sink_caps);
    return success && rendered > 0 && stopped == GST_STATE_CHANGE_SUCCESS;
}

int main(int argc, char **argv) {
    gst_init(NULL, NULL);
    if (argc < 4 || argc > 5) {
        g_printerr("usage: %s MODE CLIP.mp4 CYCLES [XID-or-HWND]\n"
                   "modes: wayland-va, wayland-cpu, x11-va, x11-cpu, d3d11, d3d11-cpu, headless-cpu\n", argv[0]);
        return 2;
    }

    static const struct {
        const gchar *name;
        const gchar *path;
        gboolean supports_external_window;
    } modes[] = {
        {"wayland-va", "vah264dec name=decoder ! video/x-raw(memory:DMABuf),format=DMA_DRM ! waylandsink name=sink", FALSE},
        {"wayland-cpu", "avdec_h264 name=decoder ! videoconvert ! video/x-raw,format=BGRx ! waylandsink name=sink", FALSE},
        {"x11-va", "vah264dec name=decoder ! video/x-raw(memory:DMABuf),format=DMA_DRM ! glimagesink name=sink", TRUE},
        {"x11-cpu", "avdec_h264 name=decoder ! glimagesink name=sink", TRUE},
        {"d3d11", "d3d11h264dec name=decoder ! video/x-raw(memory:D3D11Memory) ! d3d11videosink name=sink", TRUE},
        {"d3d11-cpu", "avdec_h264 name=decoder ! d3d11upload ! d3d11videosink name=sink", TRUE},
        {"headless-cpu", "avdec_h264 name=decoder ! fakesink name=sink sync=true", FALSE},
    };
    gchar *end = NULL;
    guint64 cycles = g_ascii_strtoull(argv[3], &end, 10);
    if (*end != '\0' || cycles < 1 || cycles > 1000) {
        g_printerr("CYCLES must be an integer between 1 and 1000\n");
        return 2;
    }
    guintptr window = 0;
    if (argc == 5) {
        guint64 value = g_ascii_strtoull(argv[4], &end, 10);
        if (*end != '\0' || value == 0 || value > G_MAXSIZE) {
            g_printerr("invalid native window handle\n");
            return 2;
        }
        window = (guintptr)value;
    }

    for (gsize mode = 0; mode < G_N_ELEMENTS(modes); mode++) {
        if (strcmp(modes[mode].name, argv[1]) != 0)
            continue;
        if (window != 0 && !modes[mode].supports_external_window) {
            g_printerr("this mode cannot use a window handle from another process\n");
            return 2;
        }
        gchar *description = g_strconcat(
            "filesrc name=source ! qtdemux ! h264parse ! ", modes[mode].path, NULL
        );
        gboolean success = TRUE;
        for (guint cycle = 1; cycle <= cycles && success; cycle++)
            success = run_cycle(description, argv[2], cycle, window);
        g_free(description);
        gst_deinit();
        return success ? 0 : 1;
    }

    g_printerr("unknown mode: %s\n", argv[1]);
    return 2;
}
