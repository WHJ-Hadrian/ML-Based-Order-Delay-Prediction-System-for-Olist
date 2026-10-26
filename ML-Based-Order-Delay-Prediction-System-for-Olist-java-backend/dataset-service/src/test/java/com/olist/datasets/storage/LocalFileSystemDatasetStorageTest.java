package com.olist.datasets.storage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.olist.datasets.exception.StorageException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileSystemDatasetStorageTest {

    @TempDir
    Path root;

    @Test
    void removesTemporaryObjectWhenInputFailsDuringWrite() throws IOException {
        LocalFileSystemDatasetStorage storage = new LocalFileSystemDatasetStorage(root);
        InputStream brokenInput = new InputStream() {
            private boolean firstRead = true;

            @Override
            public int read() throws IOException {
                if (firstRead) {
                    firstRead = false;
                    return 'x';
                }
                throw new IOException("input interrupted");
            }
        };

        assertThrows(StorageException.class,
                () -> storage.put("datasets/1/raw/data.csv", brokenInput, 2, "text/csv"));

        Path objectDirectory = root.resolve("datasets/1/raw");
        try (Stream<Path> files = Files.list(objectDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".upload-")));
        }
    }
}
