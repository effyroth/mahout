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

package org.apache.mahout.utils.nlp.collocations.llr;

import static org.apache.mahout.utils.nlp.collocations.llr.Gram.Type.HEAD;
import static org.apache.mahout.utils.nlp.collocations.llr.Gram.Type.TAIL;
import static org.apache.mahout.utils.nlp.collocations.llr.Gram.Type.UNIGRAM;

import java.io.IOException;
import java.util.Iterator;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.shingle.ShingleFilter;
import org.apache.lucene.analysis.tokenattributes.TermAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.mahout.common.StringTuple;
import org.apache.mahout.math.function.ObjectIntProcedure;
import org.apache.mahout.math.map.OpenObjectIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs pass 1 of the Collocation discovery job on input of SequeceFile<Text,Text>, where the key is a
 * document id and the value is the document contents. . Delegates to NGramCollector to perform tokenization,
 * ngram-creation and output collection.
 * 
 * @see org.apache.mahout.text.SequenceFilesFromDirectory
 * @see org.apache.mahout.utils.nlp.collocations.llr.colloc.NGramCollector
 */
public class CollocMapper extends MapReduceBase implements Mapper<Text,StringTuple,Gram,Gram> {
  
  public static final String MAX_SHINGLE_SIZE = "maxShingleSize";
  public static final int DEFAULT_MAX_SHINGLE_SIZE = 2;
  
  public enum Count {
    NGRAM_TOTAL
  }
  
  private static final Logger log = LoggerFactory.getLogger(CollocMapper.class);
  
  private int maxShingleSize;
  private boolean emitUnigrams;
  
  @Override
  public void configure(JobConf job) {
    super.configure(job);
    
    this.maxShingleSize = job.getInt(MAX_SHINGLE_SIZE, DEFAULT_MAX_SHINGLE_SIZE);
    
    this.emitUnigrams = job.getBoolean(CollocDriver.EMIT_UNIGRAMS, CollocDriver.DEFAULT_EMIT_UNIGRAMS);
    
    if (log.isInfoEnabled()) {
      log.info("Max Ngram size is {}", this.maxShingleSize);
      log.info("Emit Unitgrams is {}", emitUnigrams);
    }
  }
  
  /**
   * Collocation finder: pass 1 map phase.
   * 
   * Receives a token stream which gets passed through the ShingleFilter. The ShingleFilter delivers ngrams of
   * the appropriate size which are then decomposed into head and tail subgrams which are collected in the
   * following manner
   * 
   * k:h_subgram v:ngram k:t_subgram v:ngram
   * 
   * The 'h_' or 't_' prefix is used to specify whether the subgram in question is the head or tail of the
   * ngram. In this implementation the head of the ngram is a (n-1)gram, and the tail is a (1)gram.
   * 
   * For example, given 'click and clack' and an ngram length of 3: k:'h_click and' v:'click and clack'
   * k;'t_clack' v:'click and clack'
   * 
   * Also counts the total number of ngrams encountered and adds it to the counter
   * CollocDriver.Count.NGRAM_TOTAL
   * 
   * @param r
   *          The reader to read input from -- used to create a tokenstream from the analyzer
   * 
   * @param collector
   *          The collector to send output to
   * 
   * @param reporter
   *          Used to deliver the final ngram-count.
   * 
   * @throws IOException
   *           if there's a problem with the ShingleFilter reading data or the collector collecting output.
   */
  @Override
  public void map(Text key, StringTuple value,
                  final OutputCollector<Gram,Gram> collector, Reporter reporter) throws IOException {
    
    ShingleFilter sf = new ShingleFilter(new IteratorTokenStream(value.getEntries().iterator()),
        maxShingleSize);
    int count = 0; // ngram count
    OpenObjectIntHashMap<String> ngrams = new OpenObjectIntHashMap<String>(value.getEntries().size()
                                                                           * (maxShingleSize - 1));
    OpenObjectIntHashMap<String> unigrams = new OpenObjectIntHashMap<String>(value.getEntries().size());
    
    do {
      String term = ((TermAttribute) sf.getAttribute(TermAttribute.class)).term();
      String type = ((TypeAttribute) sf.getAttribute(TypeAttribute.class)).type();
      if ("shingle".equals(type)) {
        count++;
        if (ngrams.containsKey(term) == false) {
          ngrams.put(term, 1);
        } else {
          ngrams.put(term, 1 + ngrams.get(term));
        }
      } else if (emitUnigrams && term.length() > 0) { // unigram
        if (unigrams.containsKey(term) == false) {
          unigrams.put(term, 1);
        } else {
          unigrams.put(term, 1 + unigrams.get(term));
        }
      }
    } while (sf.incrementToken());
    
    ngrams.forEachPair(new ObjectIntProcedure<String>() {
      
      @Override
      public boolean apply(String term, int frequency) {
        Gram ngram = new Gram(term, frequency);
        // obtain components, the leading (n-1)gram and the trailing unigram.
        int i = term.lastIndexOf(' ');
        if (i != -1) { // bigram, trigram etc
          try {
            collector.collect(new Gram(term.substring(0, i), frequency, HEAD), ngram);
            collector.collect(new Gram(term.substring(i + 1), frequency, TAIL), ngram);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
        return true;
      }
    });
    
    unigrams.forEachPair(new ObjectIntProcedure<String>() {
      @Override
      public boolean apply(String term, int frequency) {
        try {
          Gram ngram = new Gram(term, frequency);
          Gram unigram = new Gram(term, frequency, UNIGRAM);
          collector.collect(unigram, ngram);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return true;
      }
    });
    
    reporter.incrCounter(Count.NGRAM_TOTAL, count);
    
    sf.end();
    sf.close();
    
  }
  
  /** Used to emit tokens from an input string array in the style of TokenStream */
  public static class IteratorTokenStream extends TokenStream {
    private final TermAttribute termAtt;
    private final Iterator<String> iterator;
    
    public IteratorTokenStream(Iterator<String> iterator) {
      this.iterator = iterator;
      this.termAtt = (TermAttribute) addAttribute(TermAttribute.class);
    }
    
    @Override
    public boolean incrementToken() throws IOException {
      if (iterator.hasNext()) {
        clearAttributes();
        termAtt.setTermBuffer(iterator.next());
        return true;
      } else {
        return false;
      }
    }
  }
}