package com.music.bitchord.desktop;

import java.nio.file.Files;
import java.nio.file.Path;

/** Offline child process; never connects to YouTube and needs no native tools. */
public final class YtDlpProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "success" -> System.out.print("{\"ok\":true}");
            case "both-pipes" -> {
                for (int i = 0; i < 48; i++) {
                    System.out.print("o".repeat(1024));
                    System.err.print("e".repeat(1024));
                }
            }
            case "oversize" -> {
                for (int i = 0; i < 4096; i++) System.out.print("x".repeat(1024));
            }
            case "fail" -> {
                System.err.print("ERROR secret-cookie https://r1.googlevideo.com/?sig=secret-url");
                System.exit(2);
            }
            case "sleep" -> {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(60000);
            }
            default -> throw new IllegalArgumentException("Unknown fixture mode");
        }
    }
}
