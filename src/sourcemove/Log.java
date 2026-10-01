package sourcemove;

final class Log {
    private Log() {}

    static void info(String msg) {
        System.out.println("[SourceMovement] " + msg);
    }

    static void warn(String msg) {
        System.out.println("[SourceMovement] WARN: " + msg);
    }
}
