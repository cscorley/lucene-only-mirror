package org.apache.lucene.search.suggest;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import java.util.AbstractMap.SimpleEntry;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.apache.lucene.search.spell.TermFreqPayloadIterator;
import org.apache.lucene.store.ByteArrayDataOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util._TestUtil;

public class TestTermFreqPayloadIterator extends LuceneTestCase {
  
  public void testEmpty() throws Exception {
    TermFreqPayloadArrayIterator iterator = new TermFreqPayloadArrayIterator(new TermFreqPayload[0]);
    TermFreqPayloadIterator wrapper = new SortedTermFreqPayloadIteratorWrapper(iterator, BytesRef.getUTF8SortedAsUnicodeComparator());
    assertNull(wrapper.next());
    wrapper = new UnsortedTermFreqPayloadIteratorWrapper(iterator);
    assertNull(wrapper.next());
  }
  
  public void testTerms() throws Exception {
    Random random = random();
    int num = atLeast(10000);
    
    Comparator<BytesRef> comparator = random.nextBoolean() ? BytesRef.getUTF8SortedAsUnicodeComparator() : BytesRef.getUTF8SortedAsUTF16Comparator();
    TreeMap<BytesRef, SimpleEntry<Long, BytesRef>> sorted = new TreeMap<>(comparator);
    TreeMap<BytesRef, Long> sortedWithoutPayload = new TreeMap<>(comparator);
    TermFreqPayload[] unsorted = new TermFreqPayload[num];
    TermFreqPayload[] unsortedWithoutPayload = new TermFreqPayload[num];

    for (int i = 0; i < num; i++) {
      BytesRef key;
      BytesRef payload;
      do {
        key = new BytesRef(_TestUtil.randomUnicodeString(random));
        payload = new BytesRef(_TestUtil.randomUnicodeString(random));
      } while (sorted.containsKey(key));
      long value = random.nextLong();
      sortedWithoutPayload.put(key, value);
      sorted.put(key, new SimpleEntry<>(value, payload));
      unsorted[i] = new TermFreqPayload(key, value, payload);
      unsortedWithoutPayload[i] = new TermFreqPayload(key, value);
    }
    
    // test the sorted iterator wrapper with payloads
    TermFreqPayloadIterator wrapper = new SortedTermFreqPayloadIteratorWrapper(new TermFreqPayloadArrayIterator(unsorted), comparator);
    Iterator<Map.Entry<BytesRef, SimpleEntry<Long, BytesRef>>> expected = sorted.entrySet().iterator();
    while (expected.hasNext()) {
      Map.Entry<BytesRef,SimpleEntry<Long, BytesRef>> entry = expected.next();
      
      assertEquals(entry.getKey(), wrapper.next());
      assertEquals(entry.getValue().getKey().longValue(), wrapper.weight());
      assertEquals(entry.getValue().getValue(), wrapper.payload());
    }
    assertNull(wrapper.next());
    
    // test the unsorted iterator wrapper with payloads
    wrapper = new UnsortedTermFreqPayloadIteratorWrapper(new TermFreqPayloadArrayIterator(unsorted));
    TreeMap<BytesRef, SimpleEntry<Long, BytesRef>> actual = new TreeMap<>();
    BytesRef key;
    while ((key = wrapper.next()) != null) {
      long value = wrapper.weight();
      BytesRef payload = wrapper.payload();
      actual.put(BytesRef.deepCopyOf(key), new SimpleEntry<>(value, BytesRef.deepCopyOf(payload)));
    }
    assertEquals(sorted, actual);

    // test the sorted iterator wrapper without payloads
    TermFreqPayloadIterator wrapperWithoutPayload = new SortedTermFreqPayloadIteratorWrapper(new TermFreqPayloadArrayIterator(unsortedWithoutPayload), comparator);
    Iterator<Map.Entry<BytesRef, Long>> expectedWithoutPayload = sortedWithoutPayload.entrySet().iterator();
    while (expectedWithoutPayload.hasNext()) {
      Map.Entry<BytesRef, Long> entry = expectedWithoutPayload.next();
      
      assertEquals(entry.getKey(), wrapperWithoutPayload.next());
      assertEquals(entry.getValue().longValue(), wrapperWithoutPayload.weight());
      assertNull(wrapperWithoutPayload.payload());
    }
    assertNull(wrapperWithoutPayload.next());
    
    // test the unsorted iterator wrapper without payloads
    wrapperWithoutPayload = new UnsortedTermFreqPayloadIteratorWrapper(new TermFreqPayloadArrayIterator(unsortedWithoutPayload));
    TreeMap<BytesRef, Long> actualWithoutPayload = new TreeMap<>();
    while ((key = wrapperWithoutPayload.next()) != null) {
      long value = wrapperWithoutPayload.weight();
      assertNull(wrapperWithoutPayload.payload());
      actualWithoutPayload.put(BytesRef.deepCopyOf(key), value);
    }
    assertEquals(sortedWithoutPayload, actualWithoutPayload);
  }
  
  public static long asLong(BytesRef b) {
    return (((long) asIntInternal(b, b.offset) << 32) | asIntInternal(b,
        b.offset + 4) & 0xFFFFFFFFL);
  }

  private static int asIntInternal(BytesRef b, int pos) {
    return ((b.bytes[pos++] & 0xFF) << 24) | ((b.bytes[pos++] & 0xFF) << 16)
        | ((b.bytes[pos++] & 0xFF) << 8) | (b.bytes[pos] & 0xFF);
  }
}
