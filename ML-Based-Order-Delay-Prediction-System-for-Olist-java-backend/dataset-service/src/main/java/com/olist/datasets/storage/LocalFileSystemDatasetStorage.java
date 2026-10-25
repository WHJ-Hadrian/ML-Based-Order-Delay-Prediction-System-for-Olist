package com.olist.datasets.storage;

import com.olist.datasets.exception.StorageException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

public class LocalFileSystemDatasetStorage implements DatasetStorage {

    private final Path root;

    public LocalFileSystemDatasetStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                content.transferTo(output);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new StorageException("Failed to store dataset object " + key, exception);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException exception) {
            throw new StorageException("Failed to read dataset object " + key, exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Path target = resolve(key);
            Files.deleteIfExists(target);
            removeEmptyParents(target.getParent());
        } catch (IOException exception) {
            throw new StorageException("Failed to delete dataset object " + key, exception);
        }
    }

    private void removeEmptyParents(Path directory) throws IOException {
        Path current = directory;
        while (current != null && current.startsWith(root) && !current.equals(root)) {
            try (Stream<Path> entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new StorageException("Invalid dataset storage key: " + key);
        }
        return path;
    }
}
