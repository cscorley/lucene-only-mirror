package org.apache.lucene.facet.simple;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.facet.params.CategoryListParams;
import org.apache.lucene.facet.taxonomy.FacetLabel;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.FilteredQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

/**
 * A {@link Query} for drill-down over {@link FacetLabel categories}. You
 * should call {@link #add(FacetLabel...)} for every group of categories you
 * want to drill-down over. Each category in the group is {@code OR'ed} with
 * the others, and groups are {@code AND'ed}.
 * <p>
 * <b>NOTE:</b> if you choose to create your own {@link Query} by calling
 * {@link #term}, it is recommended to wrap it with {@link ConstantScoreQuery}
 * and set the {@link ConstantScoreQuery#setBoost(float) boost} to {@code 0.0f},
 * so that it does not affect the scores of the documents.
 * 
 * @lucene.experimental
 */
public final class SimpleDrillDownQuery extends Query {

  private static Term term(String field, char delimChar, FacetLabel path) {
    return new Term(field, path.toString(delimChar));
  }

  private final BooleanQuery query;
  private final Map<String,Integer> drillDownDims = new LinkedHashMap<String,Integer>();

  /** Used by clone() */
  SimpleDrillDownQuery(BooleanQuery query, Map<String,Integer> drillDownDims) {
    this.query = query.clone();
    this.drillDownDims.putAll(drillDownDims);
  }

  /** Used by DrillSideways */
  SimpleDrillDownQuery(Filter filter, SimpleDrillDownQuery other) {
    query = new BooleanQuery(true); // disable coord

    BooleanClause[] clauses = other.query.getClauses();
    if (clauses.length == other.drillDownDims.size()) {
      throw new IllegalArgumentException("cannot apply filter unless baseQuery isn't null; pass ConstantScoreQuery instead");
    }
    assert clauses.length == 1+other.drillDownDims.size(): clauses.length + " vs " + (1+other.drillDownDims.size());
    drillDownDims.putAll(other.drillDownDims);
    query.add(new FilteredQuery(clauses[0].getQuery(), filter), Occur.MUST);
    for(int i=1;i<clauses.length;i++) {
      query.add(clauses[i].getQuery(), Occur.MUST);
    }
  }

  /** Used by DrillSideways */
  SimpleDrillDownQuery(Query baseQuery, List<Query> clauses, Map<String,Integer> drillDownDims) {
    this.query = new BooleanQuery(true);
    if (baseQuery != null) {
      query.add(baseQuery, Occur.MUST);      
    }
    for(Query clause : clauses) {
      query.add(clause, Occur.MUST);
    }
    this.drillDownDims.putAll(drillDownDims);
  }

  /**
   * Creates a new {@code SimpleDrillDownQuery} without a base query, 
   * to perform a pure browsing query (equivalent to using
   * {@link MatchAllDocsQuery} as base).
   */
  public SimpleDrillDownQuery() {
    this(null);
  }
  
  /**
   * Creates a new {@code SimpleDrillDownQuery} over the given base query. Can be
   * {@code null}, in which case the result {@link Query} from
   * {@link #rewrite(IndexReader)} will be a pure browsing query, filtering on
   * the added categories only.
   */
  public SimpleDrillDownQuery(Query baseQuery) {
    query = new BooleanQuery(true); // disable coord
    if (baseQuery != null) {
      query.add(baseQuery, Occur.MUST);
    }
  }

  /**
   * Adds one dimension of drill downs; if you pass multiple values they are
   * OR'd, and then the entire dimension is AND'd against the base query.
   */
  // nocommit can we remove FacetLabel here?
  public void add(FacetLabel... paths) {
    add(FacetsConfig.DEFAULT_INDEXED_FIELD_NAME, Constants.DEFAULT_DELIM_CHAR, paths);
  }

  // nocommit can we remove FacetLabel here?
  public void add(String field, FacetLabel... paths) {
    add(field, Constants.DEFAULT_DELIM_CHAR, paths);
  }

  // nocommit can we remove FacetLabel here?
  public void add(String field, char delimChar, FacetLabel... paths) {
    Query q;
    if (paths[0].length == 0) {
      throw new IllegalArgumentException("all CategoryPaths must have length > 0");
    }
    String dim = paths[0].components[0];
    if (drillDownDims.containsKey(dim)) {
      throw new IllegalArgumentException("dimension '" + dim + "' was already added");
    }
    if (paths.length == 1) {
      q = new TermQuery(term(field, delimChar, paths[0]));
    } else {
      BooleanQuery bq = new BooleanQuery(true); // disable coord
      for (FacetLabel cp : paths) {
        if (cp.length == 0) {
          throw new IllegalArgumentException("all CategoryPaths must have length > 0");
        }
        if (!cp.components[0].equals(dim)) {
          throw new IllegalArgumentException("multiple (OR'd) drill-down paths must be under same dimension; got '" 
              + dim + "' and '" + cp.components[0] + "'");
        }
        bq.add(new TermQuery(term(field, delimChar, cp)), Occur.SHOULD);
      }
      q = bq;
    }

    add(dim, q);
  }

  /** Expert: add a custom drill-down subQuery.  Use this
   *  when you have a separate way to drill-down on the
   *  dimension than the indexed facet ordinals. */
  public void add(String dim, Query subQuery) {

    // TODO: we should use FilteredQuery?

    // So scores of the drill-down query don't have an
    // effect:
    final ConstantScoreQuery drillDownQuery = new ConstantScoreQuery(subQuery);
    drillDownQuery.setBoost(0.0f);

    query.add(drillDownQuery, Occur.MUST);

    drillDownDims.put(dim, drillDownDims.size());
  }

  @Override
  public SimpleDrillDownQuery clone() {
    return new SimpleDrillDownQuery(query, drillDownDims);
  }
  
  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    return prime * result + query.hashCode();
  }
  
  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof SimpleDrillDownQuery)) {
      return false;
    }
    
    SimpleDrillDownQuery other = (SimpleDrillDownQuery) obj;
    return query.equals(other.query) && super.equals(other);
  }
  
  @Override
  public Query rewrite(IndexReader r) throws IOException {
    if (query.clauses().size() == 0) {
      // baseQuery given to the ctor was null + no drill-downs were added
      // note that if only baseQuery was given to the ctor, but no drill-down terms
      // is fine, since the rewritten query will be the original base query.
      throw new IllegalStateException("no base query or drill-down categories given");
    }
    return query;
  }

  @Override
  public String toString(String field) {
    return query.toString(field);
  }

  BooleanQuery getBooleanQuery() {
    return query;
  }

  Map<String,Integer> getDims() {
    return drillDownDims;
  }
}
