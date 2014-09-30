package org.apache.lucene.codecs.lucene50;

/*
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

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.NormsConsumer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.FilterIterator;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.packed.BlockPackedWriter;
import org.apache.lucene.util.packed.MonotonicBlockPackedWriter;
import org.apache.lucene.util.packed.PackedInts;
import org.apache.lucene.util.packed.PackedInts.FormatAndBits;

import static org.apache.lucene.codecs.lucene50.Lucene50NormsFormat.VERSION_CURRENT;

/**
 * Writer for {@link Lucene50NormsFormat}
 */
class Lucene50NormsConsumer extends NormsConsumer { 
  static final byte DELTA_COMPRESSED = 0;
  static final byte TABLE_COMPRESSED = 1;
  static final byte CONST_COMPRESSED = 2;
  static final byte UNCOMPRESSED = 3;
  static final byte INDIRECT = 4;
  static final int BLOCK_SIZE = 1 << 14;
  
  // threshold for indirect encoding, computed as 1 - 1/log2(maxint)
  // norms are only read for matching postings... so this is the threshold
  // where n log n operations < maxdoc (e.g. it performs similar to other fields)
  static final float INDIRECT_THRESHOLD = 1 - 1 / 31F;

  IndexOutput data, meta;
  
  Lucene50NormsConsumer(SegmentWriteState state, String dataCodec, String dataExtension, String metaCodec, String metaExtension) throws IOException {
    boolean success = false;
    try {
      String dataName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, dataExtension);
      data = state.directory.createOutput(dataName, state.context);
      CodecUtil.writeSegmentHeader(data, dataCodec, VERSION_CURRENT, state.segmentInfo.getId());
      String metaName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, metaExtension);
      meta = state.directory.createOutput(metaName, state.context);
      CodecUtil.writeSegmentHeader(meta, metaCodec, VERSION_CURRENT, state.segmentInfo.getId());
      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(this);
      }
    }
  }
  
  // we explicitly use only certain bits per value and a specified format, so we statically check this will work
  static {
    assert PackedInts.Format.PACKED_SINGLE_BLOCK.isSupported(1);
    assert PackedInts.Format.PACKED_SINGLE_BLOCK.isSupported(2);
    assert PackedInts.Format.PACKED_SINGLE_BLOCK.isSupported(4);
  }

  @Override
  public void addNormsField(FieldInfo field, Iterable<Number> values) throws IOException {
    meta.writeVInt(field.number);
    long minValue = Long.MAX_VALUE;
    long maxValue = Long.MIN_VALUE;
    // TODO: more efficient?
    NormMap uniqueValues = new NormMap();
    
    int count = 0;
    int missingCount = 0;
    
    for (Number nv : values) {
      if (nv == null) {
        throw new IllegalStateException("illegal norms data for field " + field.name + ", got null for value: " + count);
      }
      final long v = nv.longValue();
      if (v == 0) {
        missingCount++;
      }
      
      minValue = Math.min(minValue, v);
      maxValue = Math.max(maxValue, v);
      
      if (uniqueValues != null) {
        if (uniqueValues.add(v)) {
          if (uniqueValues.size > 256) {
            uniqueValues = null;
          }
        }
      }
      count++;
    }
    if (uniqueValues != null && uniqueValues.size == 1) {
      // 0 bpv
      addConstant(minValue);
    } else if (count > 256 && missingCount > count * INDIRECT_THRESHOLD) {
      // sparse encoding
      addIndirect(field, values, count, missingCount);
    } else if (uniqueValues != null) {
      // small number of unique values: this is the typical case:
      FormatAndBits compression = fastestFormatAndBits(uniqueValues.size-1);
      
      if (compression.bitsPerValue == 8 && minValue >= Byte.MIN_VALUE && maxValue <= Byte.MAX_VALUE) {
        addUncompressed(values, count);
      } else {
        addTableCompressed(values, compression, count, uniqueValues);
      }
    } else {
      addDeltaCompressed(values, count);
    }
  }
  
  private FormatAndBits fastestFormatAndBits(int max) {
    // we only use bpv=1,2,4,8     
    PackedInts.Format format = PackedInts.Format.PACKED_SINGLE_BLOCK;
    int bitsPerValue = PackedInts.bitsRequired(max);
    if (bitsPerValue == 3) {
      bitsPerValue = 4;
    } else if (bitsPerValue > 4) {
      bitsPerValue = 8;
    }
    return new FormatAndBits(format, bitsPerValue);
  }
  
  private void addConstant(long constant) throws IOException {
    meta.writeVInt(0);
    meta.writeByte(CONST_COMPRESSED);
    meta.writeLong(constant);
  }
  
  private void addUncompressed(Iterable<Number> values, int count) throws IOException {
    meta.writeVInt(count);
    meta.writeByte(UNCOMPRESSED); // uncompressed byte[]
    meta.writeLong(data.getFilePointer());
    for (Number nv : values) {
      data.writeByte((byte) nv.longValue());
    }
  }
  
  private void addTableCompressed(Iterable<Number> values, FormatAndBits compression, int count, NormMap uniqueValues) throws IOException {
    meta.writeVInt(count);
    meta.writeByte(TABLE_COMPRESSED); // table-compressed
    meta.writeLong(data.getFilePointer());
    data.writeVInt(PackedInts.VERSION_CURRENT);
    
    long[] decode = uniqueValues.getDecodeTable();
    // upgrade to power of two sized array
    int size = 1 << compression.bitsPerValue;
    data.writeVInt(size);
    for (int i = 0; i < decode.length; i++) {
      data.writeLong(decode[i]);
    }
    for (int i = decode.length; i < size; i++) {
      data.writeLong(0);
    }

    data.writeVInt(compression.format.getId());
    data.writeVInt(compression.bitsPerValue);

    final PackedInts.Writer writer = PackedInts.getWriterNoHeader(data, compression.format, count, compression.bitsPerValue, PackedInts.DEFAULT_BUFFER_SIZE);
    for(Number nv : values) {
      writer.add(uniqueValues.getOrd(nv.longValue()));
    }
    writer.finish();
  }
  
  private void addDeltaCompressed(Iterable<Number> values, int count) throws IOException {
    meta.writeVInt(count);
    meta.writeByte(DELTA_COMPRESSED); // delta-compressed
    meta.writeLong(data.getFilePointer());
    data.writeVInt(PackedInts.VERSION_CURRENT);
    data.writeVInt(BLOCK_SIZE);

    final BlockPackedWriter writer = new BlockPackedWriter(data, BLOCK_SIZE);
    for (Number nv : values) {
      writer.add(nv.longValue());
    }
    writer.finish();
  }
  
  private void addIndirect(FieldInfo field, final Iterable<Number> values, int count, int missingCount) throws IOException {
    meta.writeVInt(count - missingCount);
    meta.writeByte(INDIRECT);
    meta.writeLong(data.getFilePointer());
    data.writeVInt(PackedInts.VERSION_CURRENT);
    data.writeVInt(BLOCK_SIZE);
    
    // write docs with value
    final MonotonicBlockPackedWriter writer = new MonotonicBlockPackedWriter(data, BLOCK_SIZE);
    int doc = 0;
    for (Number n : values) {
      long v = n.longValue();
      if (v != 0) {
        writer.add(doc);
      }
      doc++;
    }
    writer.finish();
    
    // write actual values
    addNormsField(field, new Iterable<Number>() {
      @Override
      public Iterator<Number> iterator() {
        return new FilterIterator<Number,Number>(values.iterator()) {
          @Override
          protected boolean predicateFunction(Number value) {
            return value.longValue() != 0;
          }
        };
      }
    });
  }
  
  @Override
  public void close() throws IOException {
    boolean success = false;
    try {
      if (meta != null) {
        meta.writeVInt(-1); // write EOF marker
        CodecUtil.writeFooter(meta); // write checksum
      }
      if (data != null) {
        CodecUtil.writeFooter(data); // write checksum
      }
      success = true;
    } finally {
      if (success) {
        IOUtils.close(data, meta);
      } else {
        IOUtils.closeWhileHandlingException(data, meta);
      }
      meta = data = null;
    }
  }
  
  // specialized deduplication of long->ord for norms: 99.99999% of the time this will be a single-byte range.
  static class NormMap {
    // we use short: at most we will add 257 values to this map before its rejected as too big above.
    final short[] singleByteRange = new short[256];
    final Map<Long,Short> other = new HashMap<Long,Short>();
    int size;
    
    {
      Arrays.fill(singleByteRange, (short)-1);
    }

    /** adds an item to the mapping. returns true if actually added */
    public boolean add(long l) {
      assert size <= 256; // once we add > 256 values, we nullify the map in addNumericField and don't use this strategy
      if (l >= Byte.MIN_VALUE && l <= Byte.MAX_VALUE) {
        int index = (int) (l + 128);
        short previous = singleByteRange[index];
        if (previous < 0) {
          singleByteRange[index] = (short) size;
          size++;
          return true;
        } else {
          return false;
        }
      } else {
        if (!other.containsKey(l)) {
          other.put(l, (short)size);
          size++;
          return true;
        } else {
          return false;
        }
      }
    }
    
    /** gets the ordinal for a previously added item */
    public int getOrd(long l) {
      if (l >= Byte.MIN_VALUE && l <= Byte.MAX_VALUE) {
        int index = (int) (l + 128);
        return singleByteRange[index];
      } else {
        // NPE if something is screwed up
        return other.get(l);
      }
    }
    
    /** retrieves the ordinal table for previously added items */
    public long[] getDecodeTable() {
      long decode[] = new long[size];
      for (int i = 0; i < singleByteRange.length; i++) {
        short s = singleByteRange[i];
        if (s >= 0) {
          decode[s] = i - 128;
        }
      }
      for (Map.Entry<Long,Short> entry : other.entrySet()) {
        decode[entry.getValue()] = entry.getKey();
      }
      return decode;
    }
  }
}
