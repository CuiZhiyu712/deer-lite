package com.deerflow.tool;

import com.deerflow.sandbox.SandboxSecurityException;
import com.deerflow.sandbox.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileToolsTest {

    @TempDir
    Path root;

    FileTools tools() {
        return new FileTools(new WorkspaceManager(root.toString()));
    }

    @Test
    void writeThenReadRoundTrip() {
        var t = tools();
        t.writeFile("s1", "a.txt", "你好 world");
        assertThat(t.readFile("s1", "a.txt")).isEqualTo("你好 world");
    }

    @Test
    void strReplaceReplacesFirstOccurrenceOrErrors() {
        var t = tools();
        t.writeFile("s1", "a.txt", "foo bar foo");
        t.strReplace("s1", "a.txt", "foo", "baz");
        String out = t.readFile("s1", "a.txt");
        assertThat(out).contains("baz bar foo");
        assertThatThrownBy(() -> t.strReplace("s1", "a.txt", "not-exist", "x"))
                .hasMessageContaining("未找到");
    }

    @Test
    void lsListsWorkspaceEntries() {
        var t = tools();
        t.writeFile("s1", "x.txt", "1");
        t.writeFile("s1", "sub/y.txt", "2");
        String listing = t.ls("s1", ".");
        assertThat(listing).contains("x.txt").contains("sub");
    }

    @Test
    void readRejectsEscape() {
        assertThatThrownBy(() -> tools().readFile("s1", "../../secret"))
                .isInstanceOf(SandboxSecurityException.class);
    }
}
