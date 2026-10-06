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
        assertThat(t.strReplace("s1", "a.txt", "not-exist", "x")).contains("未找到");
    }

    @Test
    void guardsNullAndDirectoryInputs() {
        var t = tools();
        t.writeFile("s1", "d/x.txt", "1");
        assertThatThrownBy(() -> t.strReplace("s1", "a.txt", null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.strReplace("s1", "a.txt", "", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.strReplace("s1", "a.txt", "a", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.writeFile("s1", "a.txt", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(t.readFile("s1", "d")).contains("目录");
        assertThat(t.writeFile("s1", "d", "x")).contains("目录");
        assertThat(t.strReplace("s1", "missing.txt", "a", "b")).contains("不存在");
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
