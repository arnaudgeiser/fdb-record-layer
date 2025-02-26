/*
 * RecordQuerySetPlan.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2020 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.query.plan.plans;

import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.PlanHashable;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.record.query.plan.cascades.AliasMap;
import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.ExpressionRef;
import com.apple.foundationdb.record.query.plan.cascades.Quantifier;
import com.apple.foundationdb.record.query.plan.cascades.typing.Type;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.protobuf.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Interface for query plans that represent set-based operators such as union or intersection.
 */
public interface RecordQuerySetPlan extends RecordQueryPlan {
    @Nonnull
    Set<KeyExpression> getRequiredFields();

    /**
     * Method that returns a list of values that are required to be evaluable by this set-based plan operator. These
     * values are evaluated by the plan operator during execution time and are usually comprised of values that e.g.
     * establish equality between records. This method is declarative in nature and is called by the planner in order
     * to evaluate optimized plan alternatives.
     *
     * @param baseAlias the base alias to use for all external references. This is the alias of the data stream
     *        the values can be evaluated over.
     * @param inputType type as the base of expansion
     * @return a list of values where each value is required to be evaluable by the set base operation
     */
    @Nonnull
    default List<? extends Value> getRequiredValues(@Nonnull final CorrelationIdentifier baseAlias, @Nonnull Type inputType) {
        return Value.fromKeyExpressions(getRequiredFields(), baseAlias, inputType);
    }

    @Nonnull
    default TranslateValueFunction pushValueFunction(final List<TranslateValueFunction> dependentFunctions) {
        Verify.verify(!dependentFunctions.isEmpty());
        return (value, newBaseQuantifiedValue) -> {
            @Nullable Value previousPushedValue = null;
            @Nullable AliasMap equivalencesMap = null;
            for (final TranslateValueFunction dependentFunction : dependentFunctions) {
                final Optional<Value> pushedValueOptional = dependentFunction.translateValue(value, newBaseQuantifiedValue);
                if (pushedValueOptional.isEmpty()) {
                    return Optional.empty();
                }
                if (previousPushedValue == null) {
                    previousPushedValue = pushedValueOptional.get();
                    equivalencesMap = AliasMap.identitiesFor(previousPushedValue.getCorrelatedTo());
                } else {
                    if (!previousPushedValue.semanticEquals(pushedValueOptional.get(), equivalencesMap)) {
                        return Optional.empty();
                    }
                }
            }
            return Optional.ofNullable(previousPushedValue); // cannot be null, but suppress warning
        };
    }

    @Nonnull
    @SuppressWarnings("java:S135")
    default Set<CorrelationIdentifier> tryPushValues(@Nonnull final List<TranslateValueFunction> dependentFunctions,
                                                     @Nonnull final List<? extends Quantifier> quantifiers,
                                                     @Nonnull final Iterable<? extends Value> values) {
        Verify.verify(!dependentFunctions.isEmpty());
        Verify.verify(dependentFunctions.size() == quantifiers.size());

        final Set<CorrelationIdentifier> candidatesAliases =
                quantifiers.stream()
                        .map(Quantifier::getAlias)
                        .collect(Collectors.toSet());

        final CorrelationIdentifier newBaseAlias = CorrelationIdentifier.uniqueID();

        for (final Value value : values) {
            final AliasMap equivalencesMap = AliasMap.identitiesFor(ImmutableSet.of(newBaseAlias));
            @Nullable Value previousPushedValue = null;

            for (int i = 0; i < dependentFunctions.size(); i++) {
                final TranslateValueFunction dependentFunction = dependentFunctions.get(i);
                final Quantifier quantifier = quantifiers.get(i);

                if (!candidatesAliases.contains(quantifier.getAlias())) {
                    continue;
                }

                final Optional<Value> pushedValueOptional = dependentFunction.translateValue(value, newBaseAlias);

                if (pushedValueOptional.isEmpty()) {
                    candidatesAliases.remove(quantifier.getAlias());
                    continue;
                }

                if (previousPushedValue == null) {
                    previousPushedValue = pushedValueOptional.get();
                } else {
                    if (!previousPushedValue.semanticEquals(pushedValueOptional.get(), equivalencesMap)) {
                        // something is really wrong as we cannot establish a proper genuine derivation path
                        return ImmutableSet.of();
                    }
                }
            }
        }

        return ImmutableSet.copyOf(candidatesAliases);
    }

    /**
     * Method to create a new set-based plan operator that mirrors the attributes of {@code this} except its children
     * which are replaced with new children. It is the responsibility of the caller to ensure that the newly created plan
     * operator is consistent with the new children. For instance, it is not advised to recreate this plan with a
     * list of children of different size.
     *
     * @param newChildren a list of new children
     * @return a new set-based plan
     */
    @Nonnull
    RecordQuerySetPlan withChildrenReferences(@Nonnull List<? extends ExpressionRef<? extends RecordQueryPlan>> newChildren);

    /**
     * Returns whether the set operation is dynamic if it only has exactly one leg, i.e., the leg of the plan can be
     * executed many times side-by-side as if the set operation were created over many legs. This usually only makes
     * sense if the leg is correlated to some outer that feeds a dynamic argument to the inner leg.
     * @return {@code true} if this set operation is dynamic, {@code false} otherwise
     */
    default boolean isDynamic() {
        return false;
    }

    /**
     * A comparison key function that extracts a comparison key for binary comparison by some set operations which
     * also provides a stable plan hash and hash code.
     */
    interface ComparisonKeyFunction extends PlanHashable {

        @Nonnull
        <M extends Message> Function<QueryResult, List<Object>> apply(@Nonnull FDBRecordStoreBase<M> store, @Nonnull EvaluationContext evaluationContext);

        /**
         * Class to encapsulate the functionality of extracting a comparison key from a {@link QueryResult} while
         * also providing comparability and the ability to compute a stable plan hash.
         */
        class OnKeyExpression implements ComparisonKeyFunction {
            @Nonnull
            private final KeyExpression comparisonKeyExpression;

            protected OnKeyExpression(@Nonnull final KeyExpression comparisonKeyExpression) {
                this.comparisonKeyExpression = comparisonKeyExpression;
            }

            @Nonnull
            @Override
            public final <M extends Message> Function<QueryResult, List<Object>> apply(@Nonnull final FDBRecordStoreBase<M> store, @Nonnull final EvaluationContext evaluationContext) {
                return queryResult -> comparisonKeyExpression.evaluateMessageSingleton(null, queryResult.getMessage()).toTupleAppropriateList();
            }

            @Nonnull
            public KeyExpression getComparisonKey() {
                return comparisonKeyExpression;
            }

            @Override
            public int hashCode() {
                return comparisonKeyExpression.hashCode();
            }

            @Override
            public boolean equals(final Object o) {
                if (o == null) {
                    return false;
                }

                if (o == this) {
                    return true;
                }

                if (o.getClass() != getClass()) {
                    return false;
                }

                final var other = (OnKeyExpression)o;
                return comparisonKeyExpression.equals(other.comparisonKeyExpression);
            }

            @Override
            public String toString() {
                return comparisonKeyExpression.toString();
            }

            @Override
            public int planHash(@Nonnull final PlanHashKind hashKind) {
                return comparisonKeyExpression.planHash(hashKind);
            }
        }

        /**
         * Class to encapsulate the functionality of extracting a comparison key from a {@link QueryResult} while
         * also providing comparability and the ability to compute a stable plan hash.
         */
        class OnValue implements ComparisonKeyFunction {
            @Nonnull
            private final CorrelationIdentifier baseAlias;
            @Nonnull
            private final Value comparisonKeyValue;

            protected OnValue(@Nonnull final CorrelationIdentifier baseAlias,
                              @Nonnull final Value comparisonKeyValue) {
                this.baseAlias = baseAlias;
                this.comparisonKeyValue = comparisonKeyValue;
            }

            @Nonnull
            public Value getComparisonKeyValue() {
                return comparisonKeyValue;
            }

            @Nonnull
            @Override
            public final <M extends Message> Function<QueryResult, List<Object>> apply(@Nonnull final FDBRecordStoreBase<M> store, @Nonnull final EvaluationContext evaluationContext) {
                return queryResult -> {
                    final var nestedContext = evaluationContext.withBinding(baseAlias, queryResult);
                    return Lists.newArrayList(comparisonKeyValue.eval(store, nestedContext));
                };
            }

            @Override
            public int hashCode() {
                return comparisonKeyValue.hashCode();
            }

            @Override
            public boolean equals(final Object o) {
                if (o == null) {
                    return false;
                }

                if (o == this) {
                    return true;
                }

                if (o.getClass() != getClass()) {
                    return false;
                }

                final var other = (OnValue)o;
                return comparisonKeyValue.equals(other.comparisonKeyValue);
            }

            @Override
            public String toString() {
                return comparisonKeyValue.toString();
            }

            @Override
            public int planHash(@Nonnull final PlanHashKind hashKind) {
                return comparisonKeyValue.planHash(hashKind);
            }
        }
    }
}
