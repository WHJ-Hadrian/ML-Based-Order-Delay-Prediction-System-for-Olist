package com.olist.datasets.storage;

import java.io.InputStream;

public interface DatasetStorage {

    void put(String key, InputStream content, long size, String contentType);

    InputStream open(String key);

    void delete(String key);
}
