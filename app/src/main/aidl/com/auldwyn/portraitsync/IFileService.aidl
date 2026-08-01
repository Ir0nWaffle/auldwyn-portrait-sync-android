package com.auldwyn.portraitsync;

interface IFileService {
    boolean mkdirs(String path);
    boolean exists(String path);
    byte[] readFile(String path);
    boolean writeFile(String path, in byte[] data);
    void destroy();
}
