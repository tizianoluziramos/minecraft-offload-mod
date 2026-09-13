package dev.remoteoffload.server.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Logger mínimo del servidor (sin dependencias externas). */
public final class Log {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static volatile Level level = Level.INFO;
    private static volatile PrintWriter file;

    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {}

    public static void configure(Level l, Path logFile) {
        level = l;
        if (logFile != null) {
            try {
                Files.createDirectories(logFile.toAbsolutePath().getParent());
                file = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND));
            } catch (IOException e) {
                System.err.println("cannot open log file: " + e.getMessage());
            }
        }
    }

    public static void debug(String msg, Object... args) { log(Level.DEBUG, msg, args); }
    public static void info(String msg, Object... args) { log(Level.INFO, msg, args); }
    public static void warn(String msg, Object... args) { log(Level.WARN, msg, args); }
    public static void error(String msg, Object... args) { log(Level.ERROR, msg, args); }

    public static void error(Throwable t, String msg, Object... args) {
        log(Level.ERROR, msg, args);
        if (file != null) {
            t.printStackTrace(file);
            file.flush();
        }
        if (level.ordinal() <= Level.ERROR.ordinal()) {
            t.printStackTrace();
        }
    }

    private static void log(Level l, String msg, Object... args) {
        if (l.ordinal() < level.ordinal()) {
            return;
        }
        String line = String.format("%s [%s] %s",
                LocalTime.now().format(TS), l.name(), args.length == 0 ? msg : String.format(msg, args));
        System.out.println(line);
        if (file != null) {
            file.println(line);
            file.flush();
        }
    }
}