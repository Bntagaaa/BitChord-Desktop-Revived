import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.lang.management.ManagementFactory;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipFile;

/** Isolate ONNX CPU session and tensor work from decoding and feature extraction. */
class AutoMixOrtBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Usage: MODEL_DIRECTORY RUNTIME_JAR THREADS RUNS ALL_OPT|BASIC_OPT");
        }
        Locale.setDefault(Locale.ROOT);
        var modelDir = Path.of(args[0]);
        var runtimeJar = Path.of(args[1]);
        int threads = Integer.parseInt(args[2]);
        int runs = Integer.parseInt(args[3]);
        if (runs < 2 || threads < 1) throw new IllegalArgumentException("Use at least two runs and one thread");
        var level = OrtSession.SessionOptions.OptLevel.valueOf(args[4]);
        String platform = System.getProperty("os.name").startsWith("Windows") ? "win-x64" : "linux-x64";
        String binary = platform.startsWith("win") ? "onnxruntime.dll" : "libonnxruntime.so";
        try (var zip = new ZipFile(runtimeJar.toFile())) {
            var nativeBinary = zip.getEntry("ai/onnxruntime/native/" + platform + "/" + binary);
            if (nativeBinary == null) throw new IllegalArgumentException("Runtime is missing " + binary);
            System.out.printf("platform=%s jar_bytes=%d binary_bytes=%d threads=%d optimization=%s%n",
                platform, Files.size(runtimeJar), nativeBinary.getSize(), threads, level);
        }

        long start = System.nanoTime();
        var environment = OrtEnvironment.getEnvironment();
        System.out.printf("environment_ms=%.2f initial_rss_kib=%d%n", millis(start), resident("VmRSS"));
        run(environment, modelDir.resolve("beat_this_final0_q8.onnx"),
            new long[] {1, 1500, 128}, threads, runs, level);
        run(environment, modelDir.resolve("vocals_umxhq_int8.onnx"),
            new long[] {1, 2, 2049, 960}, threads, runs, level);
    }

    private static void run(OrtEnvironment environment, Path path, long[] shape, int threads, int runs,
                            OrtSession.SessionOptions.OptLevel level) throws Exception {
        int count = Math.toIntExact(Arrays.stream(shape).reduce(1, Math::multiplyExact));
        float[] samples = new float[count];
        var random = new Random(110);
        for (int i = 0; i < count; i++) samples[i] = random.nextFloat() * 2 - 1;

        long start = System.nanoTime();
        var cold = open(environment, path, threads, level);
        double coldMs = millis(start);
        double[] latencies = new double[runs];
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double cpu;
        long openRss;
        var signature = new java.util.ArrayList<Double>();
        try (cold; var input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(samples), shape)) {
            var feed = Map.of(cold.getInputNames().iterator().next(), input);
            long inferenceStart = System.nanoTime();
            long cpuStart = os.getProcessCpuTime();
            for (int i = 0; i < runs; i++) {
                long started = System.nanoTime();
                try (var outputs = cold.run(feed)) {
                    latencies[i] = millis(started);
                    if (i == 0) {
                        for (var output : outputs) {
                            FloatBuffer tensor = ((OnnxTensor) output.getValue()).getFloatBuffer();
                            double sum = 0;
                            for (int at = 0; at < tensor.limit(); at += 37) {
                                float value = tensor.get(at);
                                if (!Float.isFinite(value)) throw new AssertionError("Non-finite output");
                                sum += value;
                            }
                            signature.add(sum);
                        }
                    }
                }
            }
            cpu = (os.getProcessCpuTime() - cpuStart) * 100.0 / (System.nanoTime() - inferenceStart);
            openRss = resident("VmRSS");
        }

        long closedRss = resident("VmRSS");
        start = System.nanoTime();
        try (var warm = open(environment, path, threads, level)) {
            double warmMs = millis(start);
            double coldInferMs = latencies[0];
            Arrays.sort(latencies, 1, latencies.length);
            System.out.printf("model=%s cold_open_ms=%.2f warm_open_ms=%.2f cold_run_ms=%.2f " +
                    "warm_run_median_ms=%.2f cpu_pct=%.1f peak_rss_kib=%d open_rss_kib=%d " +
                    "closed_rss_kib=%d output_signature=%s%n",
                path.getFileName(), coldMs, warmMs, coldInferMs, latencies[1 + (runs - 1) / 2],
                cpu, resident("VmHWM"), openRss, closedRss, signature);
        }
    }

    private static OrtSession open(OrtEnvironment env, Path path, int threads,
                                   OrtSession.SessionOptions.OptLevel level) throws Exception {
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(threads);
            options.setOptimizationLevel(level);
            options.setCPUArenaAllocator(false);
            options.setMemoryPatternOptimization(false);
            return env.createSession(path.toString(), options);
        }
    }

    private static long resident(String key) throws Exception {
        var status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status)) return -1;
        try (var lines = Files.lines(status)) {
            return lines.filter(line -> line.startsWith(key + ":"))
                .mapToLong(line -> Long.parseLong(line.split("\\s+")[1])).findFirst().orElse(-1);
        }
    }

    private static double millis(long start) {
        return (System.nanoTime() - start) / 1e6;
    }
}
