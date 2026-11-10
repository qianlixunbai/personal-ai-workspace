package io.github.qianlixunbai.workspace.persistence;

import io.github.qianlixunbai.workspace.security.WindowsPrivateAcl;
import java.nio.file.Path;

/** Connection syntax only; callers must retain their existing path and ownership checks. */
public final class SqliteFileUrl {
    private SqliteFileUrl() {}

    public static String forFile(Path file) {
        return "jdbc:sqlite:" + forNativeFile(file);
    }

    /** SQLite JDBC's native backup API accepts a filename/URI, without the JDBC prefix. */
    public static String forNativeFile(Path file) {
        if (!WindowsPrivateAcl.isWindows()) return file.toString();
        // The wide Win32 API needs the extended prefix; the default VFS still caps UTF-8
        // paths at 1040 bytes. Encode '?' and filename characters before adding VFS options.
        // win32-longpath retains ordinary Windows locking (never use its -none variant).
        return "file:////%3F" + file.toUri().getRawPath() + "?vfs=win32-longpath";
    }
}
