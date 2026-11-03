package com.music.bitchord.playback.smart;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** Exercise the real JNI ABI without Compose, network access, audio devices or MinGW on PATH. */
public final class TrackFeatures {
    private static native String nativeAnalyze(float[] samples, double rate, double duration);
    private static native double nativeSampleRate();
    private static native float[] nativeResample(float[] samples, double from, double to);

    public static void main(String[] args) {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        double rate = nativeSampleRate();
        if (!(rate > 0 && Double.isFinite(rate))) throw new AssertionError("invalid analysis rate");
        // Accented four-on-the-floor, with a decaying kick and an offbeat high-frequency tap.
        // Sixty seconds gives the real DSP enough rhythmic evidence to resolve a grid.
        float[] pcm = new float[(int) (rate * 60)];
        for (int beat = 0; beat < 120; beat++) {
            int start = (int) ((0.25 + beat * 0.5) * rate);
            double accent = beat % 4 == 0 ? 1.0 : 0.65;
            for (int i = 0; i < rate * 0.18 && start + i < pcm.length; i++) {
                double time = i / rate;
                pcm[start + i] += (float) (accent * Math.exp(-time * 35) * Math.sin(2 * Math.PI * 90 * time));
            }
        }
        String result = nativeAnalyze(pcm, rate, 60.0);
        var bpm = Pattern.compile("\"bpm\":([0-9.eE+-]+)").matcher(result);
        if (!bpm.find()) throw new AssertionError("analysis omitted BPM");
        double measured = Double.parseDouble(bpm.group(1));
        double folded = measured;
        while (folded > 180) folded /= 2;
        while (folded > 0 && folded < 90) folded *= 2;
        if (Math.abs(folded - 120) > 4) throw new AssertionError("click track BPM: " + measured);
        var grid = Pattern.compile("\"downbeats\":\\[([^]]*)]").matcher(result);
        if (!grid.find() || grid.group(1).isBlank()) throw new AssertionError("analysis omitted downbeats");
        double last = -1;
        for (String value : grid.group(1).split(",")) {
            double time = Double.parseDouble(value);
            if (!Double.isFinite(time) || time <= last || time < 0 || time > 60) {
                throw new AssertionError("invalid downbeat grid");
            }
            last = time;
        }
        float[] converted = nativeResample(pcm, rate, rate / 2);
        if (Math.abs(converted.length - pcm.length / 2) > 2) throw new AssertionError("resampler length");
        for (float value : converted) if (!Float.isFinite(value)) throw new AssertionError("non-finite PCM");
        System.out.println("JNI load, 120 BPM analysis, ordered downbeats and PCM resampling passed (BPM=" + measured + ")");
    }
}
