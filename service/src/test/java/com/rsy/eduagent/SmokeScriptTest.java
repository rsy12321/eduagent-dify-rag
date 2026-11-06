package com.rsy.eduagent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SmokeScriptTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void smokeJsonUsesUnicodeFlagWhenSupportedAndFallsBackOtherwise(boolean supportsFlag) throws Exception {
        Process python = new ProcessBuilder("python3", "-c", "import sys; print(sys.executable)").start();
        assertThat(python.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(python.exitValue()).isZero();
        String realPython = new String(python.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        Path bin = Files.createDirectories(temporary.resolve("bin"));
        Path curlStub = bin.resolve("curl");
        Files.writeString(curlStub, """
                #!/usr/bin/env bash
                set -eu
                printf 'called\n' >> "$SMOKE_TEST_CURL_LOG"
                case "${!#}" in
                  */api/chat/stream) printf 'event:message\ndata:{"answer":"流式回答"}\n\nevent:done\ndata:{"citations":[]}\n\n' ;;
                  */messages) printf '[{"answer":"历史回答"}]\n' ;;
                  */api/eval/run) printf '{"note":"评测完成"}\n' ;;
                  */api/chat) printf '{"answer":"中文回答","conversationId":"test-conversation","messageId":"test-message","citations":[],"latencyMs":1}\n' ;;
                  *) exit 99 ;;
                esac
                """);
        assertThat(curlStub.toFile().setExecutable(true)).isTrue();
        Path pythonStub = bin.resolve("python3");
        Files.writeString(pythonStub, """
                #!/usr/bin/env bash
                set -eu
                if [ "${1:-}" = -m ] && [ "${2:-}" = json.tool ]; then
                  if [ "${3:-}" = --help ]; then
                    if [ "$SMOKE_TEST_SUPPORTS_FLAG" = 1 ]; then
                      printf '%s\n' '--no-ensure-ascii'
                    else
                      printf '%s\n' 'usage: json.tool'
                    fi
                    exit 0
                  fi
                  printf '%s\n' "$*" >> "$SMOKE_TEST_PYTHON_LOG"
                  if [ "$SMOKE_TEST_SUPPORTS_FLAG" = 0 ] && [ "${3:-}" = --no-ensure-ascii ]; then
                    exit 64
                  fi
                fi
                exec "$SMOKE_TEST_REAL_PYTHON" "$@"
                """);
        assertThat(pythonStub.toFile().setExecutable(true)).isTrue();
        Path stdout = temporary.resolve("output.txt");
        Path pythonLog = temporary.resolve("python-calls.txt");
        Path curlLog = temporary.resolve("curl-calls.txt");
        ProcessBuilder command = new ProcessBuilder("bash", Path.of("..", "scripts", "smoke.sh").toAbsolutePath().normalize().toString());
        command.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        command.environment().put("SERVICE_BASE_URL", "http://smoke.invalid");
        command.environment().put("SMOKE_RUN_EVAL", "1");
        command.environment().put("SMOKE_TEST_REAL_PYTHON", realPython);
        command.environment().put("SMOKE_TEST_SUPPORTS_FLAG", supportsFlag ? "1" : "0");
        command.environment().put("SMOKE_TEST_PYTHON_LOG", pythonLog.toString());
        command.environment().put("SMOKE_TEST_CURL_LOG", curlLog.toString());
        Process smoke = command.redirectErrorStream(true).redirectOutput(stdout.toFile()).start();
        boolean completed = smoke.waitFor(15, TimeUnit.SECONDS);
        if (!completed) smoke.destroyForcibly();
        assertThat(completed).isTrue();
        assertThat(smoke.exitValue()).withFailMessage(Files.readString(stdout)).isZero();
        assertThat(Files.readAllLines(pythonLog)).hasSize(4);
        assertThat(Files.readAllLines(curlLog)).hasSize(5);
        if (supportsFlag) {
            assertThat(Files.readAllLines(pythonLog)).allMatch(line -> line.contains("--no-ensure-ascii"));
            assertThat(Files.readString(stdout)).contains("中文回答", "历史回答", "评测完成");
        } else {
            assertThat(Files.readString(pythonLog)).doesNotContain("--no-ensure-ascii");
            assertThat(Files.readString(stdout)).contains("\\u4e2d\\u6587");
        }
    }
}
