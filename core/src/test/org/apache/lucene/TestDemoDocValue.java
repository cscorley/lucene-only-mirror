package org.apache.lucene;

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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.PackedLongDocValuesField;
import org.apache.lucene.document.SortedBytesDocValuesField;
import org.apache.lucene.document.StraightBytesDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.StoredDocument;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.LuceneTestCase;
import org.junit.Ignore;

/**
 * A very simple demo used in the API documentation (src/java/overview.html).
 *
 * Please try to keep src/java/overview.html up-to-date when making changes
 * to this class.
 */
public class TestDemoDocValue extends LuceneTestCase {

  public void testDemo() throws IOException {
    Analyzer analyzer = new MockAnalyzer(random());

    // Store the index in memory:
    Directory directory = newDirectory();
    // To store an index on disk, use this instead:
    // Directory directory = FSDirectory.open(new File("/tmp/testindex"));
    // we don't use RandomIndexWriter because it might add more docvalues than we expect !!!!1
    IndexWriter iwriter = new IndexWriter(directory, newIndexWriterConfig(TEST_VERSION_CURRENT, analyzer));
    Document doc = new Document();
    String longTerm = "longtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongterm";
    String text = "This is the text to be indexed. " + longTerm;
    doc.add(newTextField("fieldname", text, Field.Store.YES));
    doc.add(new PackedLongDocValuesField("dv", 5));
    iwriter.addDocument(doc);
    iwriter.close();
    
    // Now search the index:
    IndexReader ireader = DirectoryReader.open(directory); // read-only=true
    IndexSearcher isearcher = new IndexSearcher(ireader);

    assertEquals(1, isearcher.search(new TermQuery(new Term("fieldname", longTerm)), 1).totalHits);
    Query query = new TermQuery(new Term("fieldname", "text"));
    TopDocs hits = isearcher.search(query, null, 1);
    assertEquals(1, hits.totalHits);
    // Iterate through the results:
    for (int i = 0; i < hits.scoreDocs.length; i++) {
      StoredDocument hitDoc = isearcher.doc(hits.scoreDocs[i].doc);
      assertEquals(text, hitDoc.get("fieldname"));
      assert ireader.leaves().size() == 1;
      DocValues dv = ireader.leaves().get(0).reader().docValues("dv");
      assertEquals(5, dv.getSource().getInt(hits.scoreDocs[i].doc));
    }

    // Test simple phrase query
    PhraseQuery phraseQuery = new PhraseQuery();
    phraseQuery.add(new Term("fieldname", "to"));
    phraseQuery.add(new Term("fieldname", "be"));
    assertEquals(1, isearcher.search(phraseQuery, null, 1).totalHits);

    ireader.close();
    directory.close();
  }
  
  public void testTwoDocuments() throws IOException {
    Analyzer analyzer = new MockAnalyzer(random());

    // Store the index in memory:
    Directory directory = newDirectory();
    // To store an index on disk, use this instead:
    // Directory directory = FSDirectory.open(new File("/tmp/testindex"));
    // we don't use RandomIndexWriter because it might add more docvalues than we expect !!!!1
    IndexWriterConfig iwc = newIndexWriterConfig(TEST_VERSION_CURRENT, analyzer);
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter iwriter = new IndexWriter(directory, iwc);
    Document doc = new Document();
    doc.add(new PackedLongDocValuesField("dv", 1));
    iwriter.addDocument(doc);
    doc = new Document();
    doc.add(new PackedLongDocValuesField("dv", 2));
    iwriter.addDocument(doc);
    iwriter.forceMerge(1);
    iwriter.close();
    
    // Now search the index:
    IndexReader ireader = DirectoryReader.open(directory); // read-only=true
    assert ireader.leaves().size() == 1;
    DocValues dv = ireader.leaves().get(0).reader().docValues("dv");
    assertEquals(1, dv.getSource().getInt(0));
    assertEquals(2, dv.getSource().getInt(1));

    ireader.close();
    directory.close();
  }

  public void testBigRange() throws IOException {
    Analyzer analyzer = new MockAnalyzer(random());

    // Store the index in memory:
    Directory directory = newDirectory();
    // To store an index on disk, use this instead:
    // Directory directory = FSDirectory.open(new File("/tmp/testindex"));
    // we don't use RandomIndexWriter because it might add more docvalues than we expect !!!!1
    IndexWriterConfig iwc = newIndexWriterConfig(TEST_VERSION_CURRENT, analyzer);
    iwc.setMergePolicy(newLogMergePolicy());
    IndexWriter iwriter = new IndexWriter(directory, iwc);
    Document doc = new Document();
    doc.add(new PackedLongDocValuesField("dv", Long.MIN_VALUE));
    iwriter.addDocument(doc);
    doc = new Document();
    doc.add(new PackedLongDocValuesField("dv", Long.MAX_VALUE));
    iwriter.addDocument(doc);
    iwriter.forceMerge(1);
    iwriter.close();
    
    // Now search the index:
    IndexReader ireader = DirectoryReader.open(directory); // read-only=true
    assert ireader.leaves().size() == 1;
    DocValues dv = ireader.leaves().get(0).reader().docValues("dv");
    assertEquals(Long.MIN_VALUE, dv.getSource().getInt(0));
    assertEquals(Long.MAX_VALUE, dv.getSource().getInt(1));

    ireader.close();
    directory.close();
  }
  
  public void testDemoBytes() throws IOException {
    Analyzer analyzer = new MockAnalyzer(random());

    // Store the index in memory:
    Directory directory = newDirectory();
    // To store an index on disk, use this instead:
    // Directory directory = FSDirectory.open(new File("/tmp/testindex"));
    // we don't use RandomIndexWriter because it might add more docvalues than we expect !!!!1
    IndexWriter iwriter = new IndexWriter(directory, newIndexWriterConfig(TEST_VERSION_CURRENT, analyzer));
    Document doc = new Document();
    String longTerm = "longtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongterm";
    String text = "This is the text to be indexed. " + longTerm;
    doc.add(newTextField("fieldname", text, Field.Store.YES));
    doc.add(new StraightBytesDocValuesField("dv", new BytesRef("hello world")));
    iwriter.addDocument(doc);
    iwriter.close();
    
    // Now search the index:
    IndexReader ireader = DirectoryReader.open(directory); // read-only=true
    IndexSearcher isearcher = new IndexSearcher(ireader);

    assertEquals(1, isearcher.search(new TermQuery(new Term("fieldname", longTerm)), 1).totalHits);
    Query query = new TermQuery(new Term("fieldname", "text"));
    TopDocs hits = isearcher.search(query, null, 1);
    assertEquals(1, hits.totalHits);
    // Iterate through the results:
    for (int i = 0; i < hits.scoreDocs.length; i++) {
      StoredDocument hitDoc = isearcher.doc(hits.scoreDocs[i].doc);
      assertEquals(text, hitDoc.get("fieldname"));
      assert ireader.leaves().size() == 1;
      DocValues dv = ireader.leaves().get(0).reader().docValues("dv");
      assertEquals(new BytesRef("hello world"), dv.getSource().getBytes(hits.scoreDocs[i].doc, new BytesRef()));
    }

    // Test simple phrase query
    PhraseQuery phraseQuery = new PhraseQuery();
    phraseQuery.add(new Term("fieldname", "to"));
    phraseQuery.add(new Term("fieldname", "be"));
    assertEquals(1, isearcher.search(phraseQuery, null, 1).totalHits);

    ireader.close();
    directory.close();
  }

  public void testDemoSortedBytes() throws IOException {
    Analyzer analyzer = new MockAnalyzer(random());

    // Store the index in memory:
    Directory directory = newDirectory();
    // To store an index on disk, use this instead:
    // Directory directory = FSDirectory.open(new File("/tmp/testindex"));
    // we don't use RandomIndexWriter because it might add more docvalues than we expect !!!!1
    IndexWriter iwriter = new IndexWriter(directory, newIndexWriterConfig(TEST_VERSION_CURRENT, analyzer));
    Document doc = new Document();
    String longTerm = "longtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongtermlongterm";
    String text = "This is the text to be indexed. " + longTerm;
    doc.add(newTextField("fieldname", text, Field.Store.YES));
    doc.add(new SortedBytesDocValuesField("dv", new BytesRef("hello world")));
    iwriter.addDocument(doc);
    iwriter.close();
    
    // Now search the index:
    IndexReader ireader = DirectoryReader.open(directory); // read-only=true
    IndexSearcher isearcher = new IndexSearcher(ireader);

    assertEquals(1, isearcher.search(new TermQuery(new Term("fieldname", longTerm)), 1).totalHits);
    Query query = new TermQuery(new Term("fieldname", "text"));
    TopDocs hits = isearcher.search(query, null, 1);
    assertEquals(1, hits.totalHits);
    // Iterate through the results:
    for (int i = 0; i < hits.scoreDocs.length; i++) {
      StoredDocument hitDoc = isearcher.doc(hits.scoreDocs[i].doc);
      assertEquals(text, hitDoc.get("fieldname"));
      assert ireader.leaves().size() == 1;
      DocValues dv = ireader.leaves().get(0).reader().docValues("dv");
      assertEquals(new BytesRef("hello world"), dv.getSource().getBytes(hits.scoreDocs[i].doc, new BytesRef()));
    }

    // Test simple phrase query
    PhraseQuery phraseQuery = new PhraseQuery();
    phraseQuery.add(new Term("fieldname", "to"));
    phraseQuery.add(new Term("fieldname", "be"));
    assertEquals(1, isearcher.search(phraseQuery, null, 1).totalHits);

    ireader.close();
    directory.close();
  }
}
