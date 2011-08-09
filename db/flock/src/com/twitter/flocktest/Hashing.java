package com.twitter.flocktest;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hash function implementation.
 *
 * @author John Corwin
 */
public final class Hashing {
  public static long hash(String key) {
    ByteBuffer keyData = UTF8_CHARSET.encode(key);
    return hashByteBuffer(keyData);
  }

  public static long hash(long key) {
    ByteBuffer keyData = ByteBuffer.allocate(8);
    keyData.putLong(0, key);
    return hashByteBuffer(keyData);
  }

  private static Logger log = Logger.getLogger(Hashing.class.getName());
  private static final String HASH_ALGORITHM = "SHA";
  private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");

  private static final ThreadLocal<MessageDigest> LOCAL_DIGEST =
    new ThreadLocal<MessageDigest>() {
      @Override
      protected MessageDigest initialValue() {
        try {
          return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
          log.log(Level.SEVERE, String.format("No Such Algorithm: %s:", HASH_ALGORITHM), e);
          throw new RuntimeException(e);
        }
      }
    };

  private Hashing() {
  }

  private static long hashByteBuffer(ByteBuffer keyData) {
    MessageDigest sha = LOCAL_DIGEST.get();
    sha.reset();
    sha.update(keyData);
    byte[] digest = sha.digest();
    return ByteBuffer.wrap(digest).getLong();
  }
}
