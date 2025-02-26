/*
 * LuceneAutoCompleteResultCursor.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2022 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.lucene;

import com.apple.foundationdb.record.IndexEntry;
import com.apple.foundationdb.record.PipelineOperation;
import com.apple.foundationdb.record.RecordCoreArgumentException;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordCursorContinuation;
import com.apple.foundationdb.record.RecordCursorResult;
import com.apple.foundationdb.record.RecordCursorVisitor;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.cursors.BaseCursor;
import com.apple.foundationdb.record.logging.KeyValueLogMessage;
import com.apple.foundationdb.record.logging.LogMessageKeys;
import com.apple.foundationdb.record.lucene.directory.FDBDirectoryManager;
import com.apple.foundationdb.record.lucene.search.LuceneOptimizedIndexSearcher;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBStoreTimer;
import com.apple.foundationdb.record.provider.foundationdb.IndexMaintainerState;
import com.apple.foundationdb.record.provider.foundationdb.SubspaceProvider;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.foundationdb.tuple.TupleHelpers;
import com.google.common.annotations.VisibleForTesting;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.spans.FieldMaskingSpanQuery;
import org.apache.lucene.search.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.search.spans.SpanNearQuery;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.SpanTermQuery;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * This class is a Record Cursor implementation for Lucene auto complete suggestion lookup.
 * Because use cases of auto complete never need to get a big number of suggestions in one call, no scan with continuation support is needed.
 * Suggestion result is populated as an {@link IndexEntry}, the key is in {@link IndexEntry#getKey()}, the field where it is indexed from and the value are in {@link IndexEntry#getValue()}.
 */
public class LuceneAutoCompleteResultCursor implements BaseCursor<IndexEntry> {
    private static final Logger LOGGER = LoggerFactory.getLogger(LuceneAutoCompleteResultCursor.class);
    @Nonnull
    private final Executor executor;
    @Nonnull
    private final IndexMaintainerState state;
    @Nonnull
    private final String query;
    @Nullable
    private final FDBStoreTimer timer;
    private final int limit;
    private final int skip;
    @Nullable
    private RecordCursor<IndexEntry> lookupResults = null;
    @Nullable
    private final Tuple groupingKey;
    private final boolean highlight;
    private final Analyzer queryAnalyzer;

    public LuceneAutoCompleteResultCursor(@Nonnull String query,
                                          @Nonnull Executor executor, @Nonnull ScanProperties scanProperties,
                                          @Nonnull Analyzer queryAnalyzer, @Nonnull IndexMaintainerState state,
                                          @Nullable Tuple groupingKey, boolean highlight) {
        if (query.isEmpty()) {
            throw new RecordCoreArgumentException("Invalid query for auto-complete search")
                    .addLogInfo(LogMessageKeys.QUERY, query)
                    .addLogInfo(LogMessageKeys.INDEX_NAME, state.index.getName());
        }

        this.query = query;
        this.executor = executor;
        this.limit = Math.min(scanProperties.getExecuteProperties().getReturnedRowLimitOrMax(),
                state.context.getPropertyStorage().getPropertyValue(LuceneRecordContextProperties.LUCENE_AUTO_COMPLETE_SEARCH_LIMITATION));
        this.skip = scanProperties.getExecuteProperties().getSkip();
        this.timer = state.context.getTimer();
        this.highlight = highlight;
        this.state = state;
        this.groupingKey = groupingKey;
        this.queryAnalyzer = queryAnalyzer;
    }

    private synchronized IndexReader getIndexReader() throws IOException {
        return FDBDirectoryManager.getManager(state).getIndexReader(groupingKey);
    }

    @Nonnull
    @Override
    public CompletableFuture<RecordCursorResult<IndexEntry>> onNext() {
        if (lookupResults == null) {
            return CompletableFuture.<CompletableFuture<RecordCursorResult<IndexEntry>>>supplyAsync(() -> {
                try {
                    performLookup();
                } catch (IndexNotFoundException indexNotFoundException) {
                    // Trying to open an empty directory results in an IndexNotFoundException,
                    // but this should be interpreted as there not being any data to read
                    return CompletableFuture.completedFuture(RecordCursorResult.exhausted());
                } catch (IOException ioException) {
                    throw new RecordCoreException("Exception to lookup the auto complete suggestions", ioException)
                            .addLogInfo(LogMessageKeys.QUERY, query);
                }
                return lookupResults.onNext();
            }).thenCompose(Function.identity());
        }
        return lookupResults.onNext();
    }

    @Override
    public void close() {
        // Nothing to close.
    }

    @Nonnull
    @Override
    public Executor getExecutor() {
        return executor;
    }

    @Override
    public boolean accept(@Nonnull RecordCursorVisitor visitor) {
        visitor.visitEnter(this);
        return visitor.visitLeave(this);
    }

    private void performLookup() throws IOException {
        if (lookupResults != null) {
            return;
        }
        long startTime = System.nanoTime();

        lookupResults = lookup().skip(skip).limitRowsTo(limit);
        if (timer != null) {
            timer.recordSinceNanoTime(LuceneEvents.Events.LUCENE_AUTO_COMPLETE_SUGGESTIONS_SCAN, startTime);
        }
    }

    @SuppressWarnings("squid:S3776") // Cognitive complexity is too high. Candidate for later refactoring
    @Nullable
    @VisibleForTesting
    static String searchAllMaybeHighlight(Analyzer queryAnalyzer, String text, Set<String> matchedTokens, @Nullable String prefixToken, boolean highlight) {
        try (TokenStream ts = queryAnalyzer.tokenStream("text", new StringReader(text))) {
            CharTermAttribute termAtt = ts.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = ts.addAttribute(OffsetAttribute.class);
            ts.reset();
            StringBuilder sb = highlight ? new StringBuilder() : null;
            int upto = 0;
            Set<String> matchedInText = new HashSet<>();
            boolean matchedPrefix = false;
            while (ts.incrementToken()) {
                String token = termAtt.toString();
                int startOffset = offsetAtt.startOffset();
                int endOffset = offsetAtt.endOffset();
                if (upto < startOffset) {
                    if (highlight) {
                        addNonMatch(sb, text.substring(upto, startOffset));
                    }
                    upto = startOffset;
                } else if (upto > startOffset) {
                    continue;
                }

                if (matchedTokens.contains(token)) {
                    // Token matches.
                    if (highlight) {
                        addWholeMatch(sb, text.substring(startOffset, endOffset));
                    }
                    upto = endOffset;
                    matchedInText.add(token);
                } else if (prefixToken != null && token.startsWith(prefixToken)) {
                    if (highlight) {
                        addPrefixMatch(sb, text.substring(startOffset, endOffset), prefixToken);
                    }
                    upto = endOffset;
                    matchedPrefix = true;
                }
            }
            ts.end();

            if ((prefixToken != null && !matchedPrefix) || (matchedInText.size() < matchedTokens.size())) {
                // Query text not actually found in document text. Return null
                return null;
            }

            // Text was found. Return text (highlighted or not)
            if (highlight) {
                int endOffset = offsetAtt.endOffset();
                if (upto < endOffset) {
                    addNonMatch(sb, text.substring(upto));
                }
                return sb.toString();
            } else {
                return text;
            }

        } catch (IOException e) {
            return null;
        }
    }

    /** Called while highlighting a single result, to append a
     *  non-matching chunk of text from the suggestion to the
     *  provided fragments list.
     *  @param sb The {@code StringBuilder} to append to
     *  @param text The text chunk to add
     */
    private static void addNonMatch(StringBuilder sb, String text) {
        sb.append(text);
    }

    /** Called while highlighting a single result, to append
     *  the whole matched token to the provided fragments list.
     * @param sb The {@code StringBuilder} to append to
     *  @param surface The surface form (original) text
     */
    private static void addWholeMatch(StringBuilder sb, String surface) {
        sb.append("<b>");
        sb.append(surface);
        sb.append("</b>");
    }

    /** Called while highlighting a single result, to append a
     *  matched prefix token, to the provided fragments list.
     * @param sb The {@code StringBuilder} to append to
     *  @param surface The fragment of the surface form
     *        (indexed during build, corresponding to
     *        this match
     * @param prefixToken The prefix of the token that matched
     */
    private static void addPrefixMatch(StringBuilder sb, String surface, String prefixToken) {
        // TODO: apps can try to invert their analysis logic
        // here, e.g. downcase the two before checking prefix:
        if (prefixToken.length() >= surface.length()) {
            addWholeMatch(sb, surface);
            return;
        }
        sb.append("<b>");
        sb.append(surface.substring(0, prefixToken.length()));
        sb.append("</b>");
        sb.append(surface.substring(prefixToken.length()));
    }

    @SuppressWarnings("PMD.CloseResource")
    public RecordCursor<IndexEntry> lookup() throws IOException {
        // Determine the tokens from the query key
        final boolean phraseQueryNeeded = query.startsWith("\"") && query.endsWith("\"");
        final String searchKey = phraseQueryNeeded ? query.substring(1, query.length() - 1) : query;
        List<String> tokens = new ArrayList<>();
        final String prefixToken = getQueryTokens(queryAnalyzer, searchKey, tokens);

        IndexReader indexReader = getIndexReader();
        Set<String> fieldNames = getAllIndexedFieldNames(indexReader);

        final Set<String> tokenSet = new HashSet<>(tokens);
        // Note: phrase matching needs to know token order, so we pass the *list* of tokens to the phrase
        // matching query, but if we don't need the phrase query, we pass the *set* because order doesn't
        // matter and we want to remove duplicates
        Query finalQuery = phraseQueryNeeded
                             ? buildQueryForPhraseMatching(fieldNames, tokens, prefixToken)
                             : buildQueryForTermsMatching(fieldNames, tokenSet, prefixToken);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(logMessage("query for auto-complete")
                    .addKeyAndValue(LogMessageKeys.QUERY, query.replace("\"", "\\\""))
                    .addKeyAndValue("lucene_query", finalQuery)
                    .toString());
        }

        IndexSearcher searcher = new LuceneOptimizedIndexSearcher(indexReader, executor);
        TopDocs topDocs = searcher.search(finalQuery, limit + skip);
        if (timer != null) {
            timer.increment(LuceneEvents.Counts.LUCENE_SCAN_MATCHED_AUTO_COMPLETE_SUGGESTIONS, topDocs.scoreDocs.length);
        }
        return createResults(searcher, topDocs, tokenSet, prefixToken);
    }

    private Set<String> getAllIndexedFieldNames(IndexReader indexReader) {
        Set<String> fieldNames = new HashSet<>();
        indexReader.leaves().forEach(leaf -> leaf.reader().getFieldInfos().forEach(fieldInfo -> {
            // Exclude any field where the field's IndexOptions indicate that the field data is not indexed
            if (fieldInfo.getIndexOptions() != IndexOptions.NONE) {
                fieldNames.add(fieldInfo.name);
            }
        }));
        return fieldNames;
    }

    /**
     * Extract the query tokens from a string. All of the tokens (except the last one) will be added to the
     * {@code tokens} list. The last token is special. If the there is no whitespace following that token,
     * this indicates that this is an incomplete prefix of a token that will be completed by the query.
     * If there is whitespace following that token, then it is assumed that token is complete and is added
     * to the {@code tokens} list. The final token will be returned by this method if and only if we are in
     * the former case.
     *
     * @param searchKey the phrase to find completions of
     * @param tokens the list to insert all complete tokens extracted from the query phrase
     * @return the final token if it needs to be added as a "prefix" component to the final query
     * @throws IOException from the analyzer attempting to tokenize the query
     */
    @Nullable
    @VisibleForTesting
    static String getQueryTokens(Analyzer queryAnalyzer, String searchKey, @Nonnull List<String> tokens) throws IOException {
        String prefixToken = null;
        try (TokenStream ts = queryAnalyzer.tokenStream("", new StringReader(searchKey))) {
            ts.reset();
            final CharTermAttribute termAtt = ts.addAttribute(CharTermAttribute.class);
            final OffsetAttribute offsetAtt = ts.addAttribute(OffsetAttribute.class);
            String lastToken = null;
            int maxEndOffset = -1;
            while (ts.incrementToken()) {
                if (lastToken != null) {
                    tokens.add(lastToken);
                }
                lastToken = termAtt.toString();
                if (lastToken != null) {
                    maxEndOffset = Math.max(maxEndOffset, offsetAtt.endOffset());
                }
            }
            ts.end();

            if (lastToken != null) {
                if (maxEndOffset == offsetAtt.endOffset()) {
                    // Use PrefixQuery (or the ngram equivalent) when
                    // there was no trailing discarded chars in the
                    // string (e.g. whitespace), so that if query does
                    // not end with a space we show prefix matches for
                    // that token:
                    prefixToken = lastToken;
                } else {
                    // Use TermQuery for an exact match if there were
                    // trailing discarded chars (e.g. whitespace), so
                    // that if query ends with a space we only show
                    // exact matches for that term:
                    tokens.add(lastToken);
                }
            }
        }
        return prefixToken;
    }

    @Nullable
    private Query buildQueryForPhraseMatching(@Nonnull Collection<String> fieldNames,
                                              @Nonnull List<String> matchedTokens,
                                              @Nullable String prefixToken) {
        // Construct a query that is essentially:
        //  - in any field,
        //  - the phrase must occur (with possibly the last token in the phrase as a prefix)
        BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();

        for (String field : fieldNames) {
            PhraseQuery.Builder phraseQueryBuilder = new PhraseQuery.Builder();
            for (String token : matchedTokens) {
                phraseQueryBuilder.add(new Term(field, token));
            }
            Query fieldQuery;
            if (prefixToken == null) {
                fieldQuery = phraseQueryBuilder.build();
            } else {
                fieldQuery = getPhrasePrefixQuery(field, phraseQueryBuilder.build(), prefixToken);
            }
            queryBuilder.add(fieldQuery, BooleanClause.Occur.SHOULD);
        }

        queryBuilder.setMinimumNumberShouldMatch(1);
        return queryBuilder.build();
    }

    @Nullable
    private Query buildQueryForTermsMatching(@Nonnull Collection<String> fieldNames,
                                             @Nonnull Set<String> tokenSet,
                                             @Nullable String prefixToken) {
        // Construct a query that is essentially:
        //  - in any field,
        //  - all of the tokens must occur (with the last one as a prefix)
        BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();

        for (String field : fieldNames) {
            BooleanQuery.Builder fieldQuery = new BooleanQuery.Builder();
            for (String token : tokenSet) {
                fieldQuery.add(new TermQuery(new Term(field, token)), BooleanClause.Occur.MUST);
            }
            if (prefixToken != null) {
                fieldQuery.add(new PrefixQuery(new Term(field, prefixToken)), BooleanClause.Occur.MUST);
            }
            queryBuilder.add(fieldQuery.build(), BooleanClause.Occur.SHOULD);
        }
        queryBuilder.setMinimumNumberShouldMatch(1);
        return queryBuilder.build();
    }

    private Query getPhrasePrefixQuery(@Nonnull String fieldName, @Nonnull PhraseQuery phraseQuery, @Nonnull String lastToken) {
        Term[] terms = phraseQuery.getTerms();
        SpanNearQuery.Builder spanQuery = new SpanNearQuery.Builder(fieldName, true); // field
        for (Term term : terms) {
            spanQuery.addClause(new SpanTermQuery(term));
        }
        SpanQuery lastTokenQuery = new SpanMultiTermQueryWrapper<>(new PrefixQuery(new Term(fieldName, lastToken)));
        FieldMaskingSpanQuery fieldMask = new FieldMaskingSpanQuery(lastTokenQuery, fieldName);
        spanQuery.addClause(fieldMask);

        return spanQuery.build();
    }

    protected RecordCursor<IndexEntry> createResults(IndexSearcher searcher,
                                                     TopDocs topDocs,
                                                     Set<String> queryTokens,
                                                     @Nullable String prefixToken) {
        return RecordCursor.flatMapPipelined(
                outerContinuation -> scoreDocsFromLookup(searcher, topDocs),
                (scoreDocAndRecord, innerContinuation) -> findIndexEntriesInRecord(scoreDocAndRecord, queryTokens, prefixToken, innerContinuation),
                scoreDocAndRecord -> scoreDocAndRecord.rec.getPrimaryKey().pack(),
                null,
                1 // Use a pipeline size of 1 because the inner cursors don't do I/O and the outer cursor has its own pipelining
        );
    }

    private static final class ScoreDocAndRecord {
        private final ScoreDoc scoreDoc;
        private final FDBRecord<?> rec;

        private ScoreDocAndRecord(ScoreDoc scoreDoc, FDBRecord<?> rec) {
            this.scoreDoc = scoreDoc;
            this.rec = rec;
        }
    }

    private RecordCursor<ScoreDocAndRecord> scoreDocsFromLookup(IndexSearcher searcher, TopDocs topDocs) {
        return RecordCursor.fromIterator(executor, Arrays.stream(topDocs.scoreDocs).iterator())
                .mapPipelined(scoreDoc -> loadRecordFromScoreDocAsync(searcher, scoreDoc), state.store.getPipelineSize(PipelineOperation.KEY_TO_RECORD))
                .filter(Objects::nonNull)
                .mapResult(result -> {
                    if (result.hasNext()) {
                        // TODO: this cursor does not support real continuations (yet)
                        // However, if we want to use the "searchAfter" to resume this scan, this is the
                        // continuation we'd need for it
                        RecordCursorContinuation continuationFromDoc = LuceneCursorContinuation.fromScoreDoc(result.get().scoreDoc);
                        return RecordCursorResult.withNextValue(result.get(), continuationFromDoc);
                    } else {
                        // TODO: This always overrides the NoNextReason to SOURCE_EXHAUSTED
                        // This means that if we wanted to support paginating auto-complete queries with the "limit"
                        // field, we'd need to do a better job here and gather the final returned result, or something
                        // and use it as the continuation if the cursor terminates early
                        return RecordCursorResult.exhausted();
                    }
                });
    }

    private CompletableFuture<ScoreDocAndRecord> loadRecordFromScoreDocAsync(IndexSearcher searcher, ScoreDoc scoreDoc) {
        try {
            IndexableField primaryKey = searcher.doc(scoreDoc.doc).getField(LuceneIndexMaintainer.PRIMARY_KEY_FIELD_NAME);
            BytesRef pk = primaryKey.binaryValue();
            return state.store.loadRecordAsync(Tuple.fromBytes(pk.bytes)).thenApply(rec -> new ScoreDocAndRecord(scoreDoc, rec));
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new RecordCoreException("unable to read document from Lucene", e));
        }
    }

    private RecordCursor<IndexEntry> findIndexEntriesInRecord(ScoreDocAndRecord scoreDocAndRecord, Set<String> queryTokens, @Nullable String prefixToken, @Nullable byte[] continuation) {
        // Extract the indexed fields from the document again
        final List<LuceneDocumentFromRecord.DocumentField> documentFields = LuceneDocumentFromRecord.getRecordFields(state.index.getRootExpression(), scoreDocAndRecord.rec)
                .get(groupingKey == null ? TupleHelpers.EMPTY : groupingKey);
        return RecordCursor.fromList(executor, documentFields, continuation).map(documentField -> {
            // Search each field to find the first match.
            final int maxTextLength = Objects.requireNonNull(state.context.getPropertyStorage()
                    .getPropertyValue(LuceneRecordContextProperties.LUCENE_AUTO_COMPLETE_TEXT_SIZE_UPPER_LIMIT));
            Object fieldValue = documentField.getValue();
            if (!(fieldValue instanceof String)) {
                // Only can search through string fields
                return null;
            }
            String text = (String)fieldValue;
            if (text.length() > maxTextLength) {
                // Apply the text length filter before searching through the text for the
                // matched terms
                return null;
            }
            String match = searchAllMaybeHighlight(queryAnalyzer, text, queryTokens, prefixToken, highlight);
            if (match == null) {
                // Text not found in this field
                return null;
            }

            // Found a match with this field!
            Tuple key = Tuple.from(documentField.getFieldName(), match);
            if (groupingKey != null) {
                key = groupingKey.addAll(key);
            }
            // TODO: Add the primary key to the index entry
            // Not having the primary key is fine for auto-complete queries that just want the
            // text, but queries wanting to do something with both the auto-completed text and the
            // original record need to do something else
            IndexEntry indexEntry = new IndexEntry(state.index, key, Tuple.from(scoreDocAndRecord.scoreDoc.score),
                    scoreDocAndRecord.rec.getPrimaryKey());
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace(logMessage("Suggestion read as an index entry")
                        .addKeyAndValue(LogMessageKeys.INDEX_KEY, key)
                        .addKeyAndValue(LogMessageKeys.INDEX_VALUE, indexEntry.getValue())
                        .toString());
            }
            return indexEntry;
        }).filter(Objects::nonNull); // Note: may not return any results if all matches exceed the maxTextLength
    }

    private KeyValueLogMessage logMessage(String staticMessage) {
        final SubspaceProvider subspaceProvider = state.store.getSubspaceProvider();
        return KeyValueLogMessage.build(staticMessage)
                .addKeyAndValue(LogMessageKeys.INDEX_NAME, state.index.getName())
                .addKeyAndValue(subspaceProvider.logKey(), subspaceProvider.toString(state.context));
    }
}
