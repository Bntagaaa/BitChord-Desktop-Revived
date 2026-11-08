import com.music.bitchord.reworked.*;
import com.music.bitchord.data.model.Song;
import kotlin.Unit;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.ArrayList;

public final class MprisWireProbe {
    public static void main(String[] args) throws Exception {
        DesktopPlayback player = new DesktopPlayback();
        Song a = SongSerializer.INSTANCE.decode((JsonObject) Json.Default.parseToJsonElement("{\"videoId\":\"fixture-a\",\"title\":\"Private bus fixture\",\"artist\":\"Synthetic\",\"durationText\":\"3:00\",\"queueEntryId\":\"wire-a\",\"localPath\":\"/fixture/no-network-a.wav\"}"));
        Song b = SongSerializer.INSTANCE.decode((JsonObject) Json.Default.parseToJsonElement("{\"videoId\":\"fixture-b\",\"title\":\"Second fixture\",\"artist\":\"Synthetic\",\"durationText\":\"3:00\",\"queueEntryId\":\"wire-b\",\"localPath\":\"/fixture/no-network-b.wav\"}"));
        List<String> commands = java.util.Collections.synchronizedList(new ArrayList<>());
        player.setCommandInterceptor(command -> {
            if (command instanceof PlaybackCommand.Next || command instanceof PlaybackCommand.Resume) {
                commands.add(command.getClass().getSimpleName());
                return true;
            }
            return false;
        });
        player.restoreQueue(List.of(a, b), 0, 12000);
        DesktopMpris media = new DesktopMpris(player, () -> Unit.INSTANCE, () -> Unit.INSTANCE, uri -> Unit.INSTANCE);
        try {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!media.getStatus().getValue().equals("Connected")) {
                if (media.getStatus().getValue().startsWith("Unavailable") || System.nanoTime() > deadline)
                    throw new IllegalStateException(media.getStatus().getValue());
                Thread.sleep(20);
            }
            System.out.println("READY org.mpris.MediaPlayer2.BitChordReworked.instance" + ProcessHandle.current().pid());
            System.out.flush();
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
            for (String line; (line = input.readLine()) != null;) {
                if (line.equals("CLOSE")) { media.close(); System.out.println("CLOSED"); }
                else if (line.equals("COMMANDS")) System.out.println("COMMANDS " + String.join(",", commands));
                else if (line.equals("EXIT")) break;
                System.out.flush();
            }
        } finally { media.close(); player.close(); }
    }
}
