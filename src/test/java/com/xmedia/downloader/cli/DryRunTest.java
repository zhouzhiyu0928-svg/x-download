package com.xmedia.downloader.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** CLI 骨架：--dry-run 只读校验、退出码、无网络依赖（R1-7 验收）。 */
class DryRunTest {

    @TempDir
    Path dir;

    private record Run(int code, String out, String err) {
    }

    private Run run(String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new MainCommand()).execute(args);
            return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private String writeConfig(String name, int perAccountDaily) throws IOException {
        Path cfg = dir.resolve(name);
        Path save = dir.resolve("downloads");
        String yaml = """
                save-path: "%s"
                db-path: "%s"
                log:
                  level: INFO
                  file: "%s"
                accounts:
                  - name: account1
                    note: "本人账号"
                    cookie: "auth_token=t; ct0=c;"
                proxy:
                  mode: single
                  list: ["http://127.0.0.1:10808"]
                download:
                  max-concurrent: 2
                rate-limit:
                  request-interval-ms: 0
                quota:
                  per-account-daily: %d
                  reserve-for-retry: 5
                  estimate-tweets-per-call: 19
                """.formatted(save.toString().replace("\\", "/"),
                dir.resolve("xmedia.db").toString().replace("\\", "/"),
                dir.resolve("x.log").toString().replace("\\", "/"),
                perAccountDaily);
        Files.writeString(cfg, yaml, StandardCharsets.UTF_8);
        return cfg.toString();
    }

    @Test
    @DisplayName("--dry-run：退出码 0，打印配置/数据库/配额三段摘要，并创建数据库")
    void dryRunHappyPath() throws IOException {
        String cfg = writeConfig("ok.yaml", 100);
        Run r = run("--dry-run", "-c", cfg);

        assertEquals(ExitCodes.OK, r.code(), r.err());
        assertTrue(r.out().contains("dry-run：未发起任何网络请求"), r.out());
        assertTrue(r.out().contains("结构版本"), r.out());
        assertTrue(r.out().contains("journal    : wal"), r.out());
        assertTrue(r.out().contains("账号       : 1 个"), r.out());
        assertTrue(r.out().contains("account1(本人)"), r.out());
        assertTrue(Files.exists(dir.resolve("xmedia.db")), "dry-run 也应完成建表");
    }

    @Test
    @DisplayName("--tweets 估算：配额充足 → 0；严重不足 → 退出码 5 且提示所需账号数")
    void quotaEstimateExitCodes() throws IOException {
        String cfg = writeConfig("q.yaml", 100);

        Run enough = run("--dry-run", "-c", cfg, "--tweets", "100");
        assertEquals(ExitCodes.OK, enough.code(), enough.err());
        assertTrue(enough.out().contains("充足"), enough.out());

        Run notEnough = run("--dry-run", "-c", cfg, "--tweets", "100000");
        assertEquals(ExitCodes.QUOTA, notEnough.code(), notEnough.out());
        assertTrue(notEnough.err().contains("[配额不足]"), notEnough.err());
        assertTrue(notEnough.err().contains("至少需要"), notEnough.err());
    }

    @Test
    @DisplayName("配置不存在 / 校验失败 → 退出码 3，错误可读")
    void configErrors() throws IOException {
        Run missing = run("--dry-run", "-c", dir.resolve("nope.yaml").toString());
        assertEquals(ExitCodes.CONFIG, missing.code());
        assertTrue(missing.err().contains("[配置错误]"), missing.err());
        assertTrue(missing.err().contains("nope.yaml"), missing.err());

        Path bad = dir.resolve("bad.yaml");
        Files.writeString(bad, """
                accounts:
                  - name: a1
                    cookie: "auth_token=t;"
                """, StandardCharsets.UTF_8);
        Run invalid = run("--dry-run", "-c", bad.toString());
        assertEquals(ExitCodes.CONFIG, invalid.code());
        assertTrue(invalid.err().contains("ct0"), invalid.err());
    }

    @Test
    @DisplayName("CLI 覆盖（--save-path / --max-concurrent）优先于配置文件")
    void cliOverrides() throws IOException {
        String cfg = writeConfig("o.yaml", 100);
        Path other = dir.resolve("other-save");
        Run r = run("--dry-run", "-c", cfg, "--save-path", other.toString(), "--max-concurrent", "1");

        assertEquals(ExitCodes.OK, r.code(), r.err());
        assertTrue(r.out().contains("other-save"), r.out());
        assertTrue(r.out().contains("并发/间隔  : 1 /"), r.out());
    }
}
