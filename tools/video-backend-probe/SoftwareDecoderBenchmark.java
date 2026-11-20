import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import kotlin.ResultKt;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.Job;
import org.jetbrains.skia.ColorAlphaType;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.ImageInfo;
import org.jetbrains.skia.Rect;
import org.jetbrains.skia.SamplingMode;
import org.jetbrains.skia.Surface;

/** Runs the packaged software decoder with the same raster target on either side of the change. */
class SoftwareDecoderBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("baseline") || args[0].equals("candidate"))) {
            throw new IllegalArgumentException("Usage: SoftwareDecoderBenchmark.java baseline|candidate CLIP.mp4");
        }
        boolean baseline = args[0].equals("baseline");
        Class<?> decoderType = Class.forName("com.music.bitchord.desktop.DesktopCanvasDecoder");
        Method open = Arrays.stream(decoderType.getMethods())
            .filter(method -> method.getName().startsWith("open-") && method.getParameterCount() == 2)
            .findFirst().orElseThrow();
        Method next = decoderType.getMethod("nextFrame", baseline ? byte[].class : Function1.class);
        Object decoder = baseline
            ? decoderType.getConstructor(int.class, boolean.class).newInstance(8192, false)
            : decoderType.getConstructor(int.class, boolean.class, boolean.class, Job.class)
                .newInstance(8192, false, false, null);
        var thread = (ThreadMXBean)ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        long started = System.nanoTime();
        int count = 0;
        long first = 0;
        long allocated = 0;
        var destination = Rect.Companion.makeWH(320f, 180f);
        try (var surface = Surface.Companion.makeRasterN32Premul(320, 180)) {
            ResultKt.throwOnFailure(open.invoke(decoder, args[1], Map.of()));
            int width = (int)decoderType.getMethod("getWidth").invoke(decoder);
            int height = (int)decoderType.getMethod("getHeight").invoke(decoder);
            var src = Rect.Companion.makeWH(width, height);
            var info = ImageInfo.Companion.makeN32(width, height, ColorAlphaType.OPAQUE);
            byte[] scratch = baseline ? new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)] : null;
            Function1<Long, Boolean> accept = time -> true;
            while (true) {
                long before = thread.getThreadAllocatedBytes(threadId);
                if (baseline) {
                    if (!(boolean)next.invoke(decoder, scratch)) break;
                    try (Image image = Image.Companion.makeRaster(info, scratch.clone(), width * 4)) {
                        surface.getCanvas().drawImageRect(image, src, destination, SamplingMode.Companion.getLINEAR(), null, true);
                    }
                } else {
                    Object frame = next.invoke(decoder, accept);
                    if (frame == null) break;
                    try (var ownedFrame = (AutoCloseable)frame) {
                        frame.getClass().getMethod("drawTo", org.jetbrains.skia.Canvas.class, Rect.class)
                            .invoke(frame, surface.getCanvas(), destination);
                    }
                }
                allocated += thread.getThreadAllocatedBytes(threadId) - before;
                if (count++ == 0) first = System.nanoTime() - started;
                var nextFrameAt = started + first + count * 1_000_000_000L / 30;
                long delay = nextFrameAt - System.nanoTime();
                if (delay > 0) Thread.sleep(delay / 1_000_000, (int)(delay % 1_000_000));
            }
        } finally {
            decoderType.getMethod("close").invoke(decoder);
        }
        if (count == 0) throw new AssertionError("No video frames decoded");
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf("mode=%s frames=%d seconds=%.3f first_ms=%.3f allocated_bytes=%d bytes_per_frame=%d MiB_per_second=%.3f%n",
            args[0], count, seconds, first / 1_000_000.0, allocated, allocated / count, allocated / 1048576.0 / seconds);
    }
}
