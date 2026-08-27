// Copyright Abundent Sdn Bhd (https://abundent.com)
// Dual-licensed: PolyForm Noncommercial 1.0.0 (see LICENSE.md)
// or a commercial license from Abundent (see COMMERCIAL-LICENSE.md).
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.OcrExecutableResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

class OcrExecutableResolverTest extends UnitTest {

    @TempDir
    Path tmp;

    @Test
    void configuredDirectoryWinsAndResolvesThePlatformExecutable() throws Exception {
        var executable = Files.createFile(tmp.resolve("tesseract.exe"));

        var result = OcrExecutableResolver.resolve(
                tmp.toString(), "Windows 11", Map.of(), "");

        assertEquals(executable, result.executable());
        assertEquals("ocr.tesseract.path", result.source());
        assertTrue(result.available());
    }

    @Test
    void configuredExecutableIsAcceptedDirectly() throws Exception {
        var executable = Files.createFile(tmp.resolve("custom-tesseract"));

        var result = OcrExecutableResolver.resolve(
                executable.toString(), "Linux", Map.of(), "");

        assertEquals(executable, result.executable());
        assertTrue(result.available());
    }

    @Test
    void invalidConfiguredPathFailsClosedInsteadOfFallingBackToPath() throws Exception {
        var pathTesseract = Files.createFile(tmp.resolve("tesseract.exe"));
        var missing = tmp.resolve("missing-tesseract.exe");

        var result = OcrExecutableResolver.resolve(
                missing.toString(), "Windows 11", Map.of(), tmp.toString());

        assertEquals(missing, result.executable());
        assertFalse(result.available());
        assertEquals("ocr.tesseract.path", result.source());
        assertNotEquals(pathTesseract, result.executable());
    }

    @Test
    void tesseractPathEnvironmentSupportsTheWindowsInstallerDirectory() throws Exception {
        var installDir = Files.createDirectories(tmp.resolve("Tesseract-OCR"));
        var executable = Files.createFile(installDir.resolve("tesseract.exe"));

        var result = OcrExecutableResolver.resolve(
                "", "Windows 11", Map.of("TESSERACT_PATH", installDir.toString()), "");

        assertEquals(executable, result.executable());
        assertEquals("TESSERACT_PATH", result.source());
    }

    @Test
    void windowsStandardProgramFilesLocationIsDiscoveredWithoutPathEntry() throws Exception {
        var programFiles = Files.createDirectories(tmp.resolve("Program Files"));
        var installDir = Files.createDirectories(programFiles.resolve("Tesseract-OCR"));
        var executable = Files.createFile(installDir.resolve("tesseract.exe"));
        var env = new HashMap<String, String>();
        env.put("ProgramFiles", programFiles.toString());

        var result = OcrExecutableResolver.resolve("", "Windows 11", env, "");

        assertEquals(executable, result.executable());
        assertEquals("standard Windows install", result.source());
    }

    @Test
    void pathLookupUsesWindowsPathAndPathExtRules() throws Exception {
        var executable = Files.createFile(tmp.resolve("tesseract.exe"));
        var env = Map.of("PATHEXT", ".COM;.EXE;.BAT;.CMD");

        var result = OcrExecutableResolver.resolve(
                "", "Windows 11", env, tmp.toString());

        assertEquals(executable, result.executable());
        assertEquals("PATH", result.source());
    }

    @Test
    void pathEntryWinsOverAStandardWindowsInstall() throws Exception {
        var pathDir = Files.createDirectories(tmp.resolve("path-bin"));
        var pathExecutable = Files.createFile(pathDir.resolve("tesseract.exe"));
        var programFiles = Files.createDirectories(tmp.resolve("Program Files"));
        var standardDir = Files.createDirectories(programFiles.resolve("Tesseract-OCR"));
        Files.createFile(standardDir.resolve("tesseract.exe"));

        var result = OcrExecutableResolver.resolve("", "Windows 11",
                Map.of("ProgramFiles", programFiles.toString()), pathDir.toString());

        assertEquals(pathExecutable, result.executable());
        assertEquals("PATH", result.source());
    }
}
