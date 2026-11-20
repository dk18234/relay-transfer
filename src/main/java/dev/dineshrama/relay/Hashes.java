package dev.dineshrama.relay;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

public final class Hashes {
  private Hashes() {}

  public static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hex(MessageDigest digest) {
    return HexFormat.of().formatHex(digest.digest());
  }

  public static boolean valid(String s) {
    return s != null && s.matches("[a-fA-F0-9]{64}");
  }

  public static String copyExact(
      InputStream input, OutputStream output, long expected, MessageDigest full)
      throws IOException {
    byte[] buffer = new byte[64 * 1024];
    long count = 0;
    MessageDigest part = sha256();
    for (int n;
        (n = input.read(buffer, 0, (int) Math.min(buffer.length, expected - count + 1))) != -1; ) {
      count += n;
      if (count > expected)
        throw new ApiException(
            422, "LENGTH_MISMATCH", "Received more bytes than the manifest allows");
      part.update(buffer, 0, n);
      if (full != null) full.update(buffer, 0, n);
      if (output != null) output.write(buffer, 0, n);
    }
    if (count != expected)
      throw new ApiException(
          422, "LENGTH_MISMATCH", "Received fewer bytes than the manifest requires");
    return hex(part);
  }

  public static void sync(Path file) throws IOException {
    try (FileChannel c = FileChannel.open(file, StandardOpenOption.WRITE)) {
      c.force(true);
    }
  }
}
