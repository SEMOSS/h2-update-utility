package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Minimal remote object operations required by the cloud migration transaction. */
interface CloudObjectStore {
    String authenticationMode();
    List<String> listFiles(String remotePath, String include) throws IOException, InterruptedException;
    CloudObjectMetadata stat(String remotePath) throws IOException, InterruptedException;
    void download(String remotePath, Path localDest) throws IOException, InterruptedException;
    void upload(Path localFile, String remotePath) throws IOException, InterruptedException;
    void delete(String remotePath) throws IOException, InterruptedException;
}
