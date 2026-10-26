package com.olist.datasets.mapper;

import com.olist.datasets.entity.StorageBlob;
import org.apache.ibatis.annotations.Param;

public interface StorageBlobMapper {

    int insertIfAbsent(StorageBlob blob);

    int deleteIfUnreferenced(@Param("sha256") String sha256);
}
