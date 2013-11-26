package org.apache.lucene.demo.facet;

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
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.simple.Facets;
import org.apache.lucene.facet.simple.FacetsConfig;
import org.apache.lucene.facet.simple.FloatAssociationFacetField;
import org.apache.lucene.facet.simple.IntAssociationFacetField;
import org.apache.lucene.facet.simple.SimpleFacetResult;
import org.apache.lucene.facet.simple.SimpleFacetsCollector;
import org.apache.lucene.facet.simple.TaxonomyFacetSumFloatAssociations;
import org.apache.lucene.facet.simple.TaxonomyFacetSumIntAssociations;
import org.apache.lucene.facet.taxonomy.FacetLabel;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.facet.taxonomy.TaxonomyWriter;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;

/** Shows example usage of category associations. */
public class AssociationsFacetsExample {

  private final Directory indexDir = new RAMDirectory();
  private final Directory taxoDir = new RAMDirectory();

  /** Empty constructor */
  public AssociationsFacetsExample() {}
  
  /** Build the example index. */
  private void index() throws IOException {
    IndexWriterConfig iwc = new IndexWriterConfig(FacetExamples.EXAMPLES_VER, 
                                                  new WhitespaceAnalyzer(FacetExamples.EXAMPLES_VER));
    IndexWriter indexWriter = new IndexWriter(indexDir, iwc);

    // Writes facet ords to a separate directory from the main index
    DirectoryTaxonomyWriter taxoWriter = new DirectoryTaxonomyWriter(taxoDir);

    // Reused across documents, to add the necessary facet fields
    FacetsConfig config = getConfig(taxoWriter);

    Document doc = new Document();
    // 3 occurrences for tag 'lucene'
    doc.add(new IntAssociationFacetField(3, "tags", "lucene"));
    // 87% confidence level of genre 'computing'
    doc.add(new FloatAssociationFacetField(0.87f, "genre", "computing"));
    indexWriter.addDocument(config.build(doc));

    doc = new Document();
    // 1 occurrence for tag 'lucene'
    doc.add(new IntAssociationFacetField(1, "tags", "lucene"));
    // 2 occurrence for tag 'solr'
    doc.add(new IntAssociationFacetField(2, "tags", "solr"));
    // 75% confidence level of genre 'computing'
    doc.add(new FloatAssociationFacetField(0.75f, "genre", "computing"));
    // 34% confidence level of genre 'software'
    doc.add(new FloatAssociationFacetField(0.34f, "genre", "software"));
    indexWriter.addDocument(config.build(doc));

    indexWriter.close();
    taxoWriter.close();
  }

  /** It's fine if taxoWriter is null (i.e., at search time) */
  private FacetsConfig getConfig(TaxonomyWriter taxoWriter) {
    FacetsConfig config = new FacetsConfig(taxoWriter);
    config.setMultiValued("tags", true);
    config.setIndexFieldName("tags", "$tags");
    config.setMultiValued("genre", true);
    config.setIndexFieldName("genre", "$genre");
    return config;
  }

  /** User runs a query and aggregates facets by summing their association values. */
  private List<SimpleFacetResult> sumAssociations() throws IOException {
    DirectoryReader indexReader = DirectoryReader.open(indexDir);
    IndexSearcher searcher = new IndexSearcher(indexReader);
    TaxonomyReader taxoReader = new DirectoryTaxonomyReader(taxoDir);
    FacetsConfig config = getConfig(null);
    
    SimpleFacetsCollector sfc = new SimpleFacetsCollector();
    
    // MatchAllDocsQuery is for "browsing" (counts facets
    // for all non-deleted docs in the index); normally
    // you'd use a "normal" query, and use MultiCollector to
    // wrap collecting the "normal" hits and also facets:
    searcher.search(new MatchAllDocsQuery(), sfc);
    
    Facets tags = new TaxonomyFacetSumIntAssociations("$tags", taxoReader, config, sfc);
    Facets genre = new TaxonomyFacetSumFloatAssociations("$genre", taxoReader, config, sfc);

    // Retrieve results
    List<SimpleFacetResult> results = new ArrayList<SimpleFacetResult>();
    results.add(tags.getTopChildren(10, "tags"));
    results.add(genre.getTopChildren(10, "genre"));

    indexReader.close();
    taxoReader.close();
    
    return results;
  }
  
  /** Runs summing association example. */
  public List<SimpleFacetResult> runSumAssociations() throws IOException {
    index();
    return sumAssociations();
  }
  
  /** Runs the sum int/float associations examples and prints the results. */
  public static void main(String[] args) throws Exception {
    System.out.println("Sum associations example:");
    System.out.println("-------------------------");
    List<SimpleFacetResult> results = new AssociationsFacetsExample().runSumAssociations();
    System.out.println("tags: " + results.get(0));
    System.out.println("genre: " + results.get(1));
  }
}
