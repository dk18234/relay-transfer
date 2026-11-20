package dev.dineshrama.relay;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

public interface Storage {
    String kind();
    String putPart(UUID id, int number, Path staged) throws IOException;
    InputStream openPart(String key) throws IOException;
    void assemble(UUID id, List<Transfer.Part> parts, long size) throws IOException;
    InputStream openObject(UUID id) throws IOException;
    void cleanupParts(UUID id) throws IOException;
}
