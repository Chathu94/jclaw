// Copyright Abundent Sdn Bhd (https://abundent.com)
// Dual-licensed: PolyForm Noncommercial 1.0.0 (see LICENSE.md)
// or a commercial license from Abundent (see COMMERCIAL-LICENSE.md).
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import utils.PlatformProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class PlatformProcessTest extends UnitTest {

    @TempDir
    Path tmp;

    @Test
    void posixShellKeepsTheExistingBinShContract() {
        assertEquals(List.of("/bin/sh", "-c", "echo hello"),
                PlatformProcess.shellCommandFor("echo hello", "Linux", ""));
    }

    @Test
    void windowsShellUsesGitBashWhenAvailable() throws Exception {
        var sh = Files.createFile(tmp.resolve("sh.exe"));

        assertEquals(List.of(sh.toString(), "-c", "echo hello"),
                PlatformProcess.shellCommandFor("echo hello", "Windows 11", tmp.toString()));
    }

    @Test
    void windowsShellFallsBackToPowerShellWithoutGitBash() {
        assertEquals(List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                        "-Command", "echo hello"),
                PlatformProcess.shellCommandFor("echo hello", "Windows Server 2025", tmp.toString()));
    }

    @Test
    void windowsCmdShimIsWrappedForProcessBuilder() throws Exception {
        var npx = Files.createFile(tmp.resolve("npx.cmd"));

        var command = PlatformProcess.commandFor(List.of("npx", "-y", "server"), "Windows 11",
                tmp.toString(), ".COM;.EXE;.BAT;.CMD", "cmd.exe");

        assertEquals(List.of("cmd.exe", "/d", "/s", "/c"), command.subList(0, 4));
        assertTrue(command.get(4).equalsIgnoreCase(npx.toString()));
        assertEquals(List.of("-y", "server"), command.subList(5, 7));
    }

    @Test
    void nativeWindowsExecutableDoesNotNeedCmdWrapper() throws Exception {
        var uv = Files.createFile(tmp.resolve("uv.exe"));

        var command = PlatformProcess.commandFor(List.of("uv", "run", "serve.py"), "Windows 11",
                tmp.toString(), ".COM;.EXE;.BAT;.CMD", "cmd.exe");

        assertTrue(command.getFirst().equalsIgnoreCase(uv.toString()));
        assertEquals(List.of("run", "serve.py"), command.subList(1, 3));
    }

    @Test
    void commandIsUnchangedOffWindows() {
        var command = List.of("npx", "-y", "server");
        assertSame(command,
                PlatformProcess.commandFor(command, "Linux", tmp.toString(), "", "cmd.exe"));
    }
}
