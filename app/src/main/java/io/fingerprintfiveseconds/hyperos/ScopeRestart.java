package io.fingerprintfiveseconds.hyperos;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

final class ScopeRestart {
    enum Result { RESTARTED, ROOT_REQUIRED, FAILED, TIMED_OUT }

    private static final int RESTART_FAILED = 20;

    // The module's sole scope is the persistent SystemUI process, which Android starts again.
    private static final String RESTART_COMMAND = """
            [ "$(id -u)" = "0" ] || exit 1
            old_pid=$(pidof %1$s)
            case "$old_pid" in
                ''|0|1|*[!0-9]*) exit %2$s ;;
            esac
            kill -TERM "$old_pid" || exit %2$s
            attempts=0
            while [ "$attempts" -lt 20 ]; do
                sleep 0.5
                new_pid=$(pidof %1$s)
                if [ -n "$new_pid" ] && [ "$new_pid" != "$old_pid" ]; then
                    exit 0
                fi
                attempts=$((attempts + 1))
            done
            exit %2$s
            """.formatted(FingerprintModule.SYSTEM_UI, RESTART_FAILED);

    static Result restart() {
        Process process = null;
        try {
            File nullDevice = new File("/dev/null");
            process = new ProcessBuilder("su", "-c", RESTART_COMMAND)
                    .redirectInput(nullDevice)
                    .redirectErrorStream(true)
                    .redirectOutput(nullDevice)
                    .start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                return Result.TIMED_OUT;
            }
            return switch (process.exitValue()) {
                case 0 -> Result.RESTARTED;
                case RESTART_FAILED -> Result.FAILED;
                default -> Result.ROOT_REQUIRED;
            };
        } catch (IOException | SecurityException error) {
            return Result.ROOT_REQUIRED;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
