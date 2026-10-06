package com.deerflow.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceManagerTest {

    @TempDir
    Path root;

    WorkspaceManager mgr() {
        return new WorkspaceManager(root.toString());
    }

    @Test
    void createsSessionDirOnDemand() {
        Path dir = mgr().sessionDir("s1");
        assertThat(dir).exists().isDirectory();
        assertThat(dir.startsWith(root)).isTrue();
    }

    @Test
    void resolvesRelativePathInsideWorkspace() {
        Path p = mgr().resolveSafe("s1", "sub/a.txt");
        assertThat(p.startsWith(root.resolve("s1"))).isTrue();
    }

    @Test
    void rejectsParentTraversal() {
        assertThatThrownBy(() -> mgr().resolveSafe("s1", "../evil.txt"))
                .isInstanceOf(SandboxSecurityException.class);
    }

    @Test
    void rejectsAbsolutePathOutside() {
        assertThatThrownBy(() -> mgr().resolveSafe("s1", "/etc/passwd"))
                .isInstanceOf(SandboxSecurityException.class);
    }

    @Test
    void rejectsWindowsStyleEscape() {
        assertThatThrownBy(() -> mgr().resolveSafe("s1", "..\\..\\evil"))
                .isInstanceOf(SandboxSecurityException.class);
    }
}
