package com.database.atypon.Node.utils.file_operations.fileUpdater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileUpdaterTest {

    @TempDir
    Path dir;

    private String base() {
        // FileUpdater builds paths as <path><name><type>, so the path must end with a separator.
        return dir.toString() + java.io.File.separator;
    }

    @Test
    void replacesContentOfExistingFile() throws Exception {
        Path target = dir.resolve("info.json");
        Files.writeString(target, "{\"old\":true}");

        new FileUpdater(base(), "info", "{\"new\":true}", ".json").updateFile();

        assertThat(target).exists();
        assertThat(Files.readString(target)).isEqualTo("{\"new\":true}");
    }

    @Test
    void doesNotLoseTargetAndLeavesNoTempFile() throws Exception {
        Path target = dir.resolve("info.json");
        Path temp = dir.resolve("infotemp.json");
        Files.writeString(target, "original");

        new FileUpdater(base(), "info", "updated", ".json").updateFile();

        // Regression: an earlier version deleted the target before a rename that failed while the
        // temp file was still open (Windows), losing the target entirely. The target must survive
        // and the temp file must not linger.
        assertThat(target).exists();
        assertThat(Files.readString(target)).isEqualTo("updated");
        assertThat(temp).doesNotExist();
    }

    @Test
    void throwsWhenTargetMissing() throws Exception {
        FileUpdater updater = new FileUpdater(base(), "missing", "content", ".json");
        assertThatThrownBy(updater::updateFile)
                .isInstanceOf(FileNotFoundException.class);
    }
}
