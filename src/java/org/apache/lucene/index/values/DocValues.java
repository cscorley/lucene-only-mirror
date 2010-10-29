package org.apache.lucene.index.values;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.io.Closeable;
import java.io.IOException;
import java.util.Comparator;

import org.apache.lucene.util.AttributeSource;
import org.apache.lucene.util.BytesRef;

public abstract class DocValues implements Closeable {

  private final Object lock = new Object();

  private Source cachedReference;

  public static final DocValues[] EMPTY_ARRAY = new DocValues[0];

  public ValuesEnum getEnum() throws IOException {
    return getEnum(null);
  }

  public abstract ValuesEnum getEnum(AttributeSource attrSource)
      throws IOException;

  public abstract Source load() throws IOException;

  public Source getCached(boolean load) throws IOException {
    synchronized (lock) { // TODO make sorted source cachable too 
      if (load && cachedReference == null)
        cachedReference = load();
      return cachedReference;
    }
  }

  public Source releaseCached() {
    synchronized (lock) {
      final Source retVal = cachedReference;
      cachedReference = null;
      return retVal;
    }
  }

  public SortedSource loadSorted(Comparator<BytesRef> comparator)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  public abstract Values type();
  
  public void close() throws IOException {
    releaseCached();
  }

  /**
   * Source of integer (returned as java long), per document. The underlying
   * implementation may use different numbers of bits per value; long is only
   * used since it can handle all precisions.
   */
  public static abstract class Source {

    public long getInt(int docID) {
      throw new UnsupportedOperationException("ints are not supported");
    }

    public double getFloat(int docID) {
      throw new UnsupportedOperationException("floats are not supported");
    }

    public BytesRef getBytes(int docID) {
      throw new UnsupportedOperationException("bytes are not supported");
    }

    /**
     * Returns number of unique values. Some impls may throw
     * UnsupportedOperationException.
     */
    public int getValueCount() {
      throw new UnsupportedOperationException();
    }

    public ValuesEnum getEnum() throws IOException {
      return getEnum(null);
    }

    // nocommit - enable obtaining enum from source since this is already in
    // memory
    public/* abstract */ValuesEnum getEnum(AttributeSource attrSource)
        throws IOException {
      throw new UnsupportedOperationException();
    }

    public abstract long ramBytesUsed();
  }

  public static abstract class SortedSource extends Source {

    @Override
    public BytesRef getBytes(int docID) {
      return getByOrd(ord(docID));
    }

    /**
     * Returns ord for specified docID. If this docID had not been added to the
     * Writer, the ord is 0. Ord is dense, ie, starts at 0, then increments by 1
     * for the next (as defined by {@link Comparator} value.
     */
    public abstract int ord(int docID);

    /** Returns value for specified ord. */
    public abstract BytesRef getByOrd(int ord);

    public static class LookupResult {
      public boolean found;
      public int ord;
    }

    /**
     * Finds the largest ord whose value is <= the requested value. If
     * {@link LookupResult#found} is true, then ord is an exact match. The
     * returned {@link LookupResult} may be reused across calls.
     */
    public abstract LookupResult getByValue(BytesRef value);
  }

}
