package com.database.atypon.Node.utils.file_operations.fileUpdater;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Replaces a file's contents by writing to a sibling temp file and moving it over the target.
 *
 * <p>The move happens only after the writer is fully closed, and the replacement is a single
 * atomic move rather than a delete-then-rename. An earlier version attempted the rename while
 * the temp file's writer was still open and after deleting the target first: on Windows the
 * open handle made the rename fail, and the already-deleted target was lost — destroying, for
 * example, the user registry in {@code info.json} on a failed {@code createDatabase}.
 */
public class FileUpdater {

    private final File oldFile, newFile;
    private final String content;

    public FileUpdater(String oldFilePath, String oldFileName, String content, String type) throws IOException {
        this.oldFile = new File(oldFilePath + oldFileName + type);
        this.newFile = new File(oldFilePath + oldFileName + "temp" + type);
        newFile.createNewFile();
        this.content = content;
    }

    public void updateFile() throws Exception {
        if (!oldFile.exists())
            throw new FileNotFoundException("File not found");

        try {
            // Write the full new content first and close the writer, so no handle is held on the
            // temp file when it is moved over the target.
            try (BufferedWriter bufferedWriter = new BufferedWriter(new java.io.FileWriter(newFile))) {
                bufferedWriter.write(content);
                bufferedWriter.flush();
            }
            // Atomically replace the target. Falls back to a non-atomic replace only if the
            // filesystem does not support atomic moves (temp and target share a directory, so
            // this is normally atomic).
            try {
                Files.move(newFile.toPath(), oldFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(newFile.toPath(), oldFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            newFile.delete();
            throw new RuntimeException(e);
        }
    }
}
