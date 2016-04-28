package org.apache.flink.streaming.api.operators.windowing;


import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.streaming.api.windowing.policy.*;
import org.apache.flink.streaming.paper.AggregationFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.*;

public class PairDiscretization {

    private static final Logger LOG = LoggerFactory.getLogger(PairDiscretization.class);
    /**
     * It creates a new MultiDiscretizer that emulates the logic of operator sharing using pairs, for the given policies 
     * 
     * @param policyGroups
     * @param reduceFunction
     * @param identityValue
     * @param capacity
     * @param serializer
     * @param aggregationType
     * @param <IN>
     * @return
     */
    public static <IN, AGG extends Serializable> B2BMultiDiscretizer<IN, AGG> create(List<DeterministicPolicyGroup<IN>> policyGroups, ReduceFunction<AGG> reduceFunction,
																					 AGG identityValue, int capacity, TypeSerializer<AGG> serializer,
																					 AggregationFramework.AGGREGATION_STRATEGY aggregationType) {
        ensureCompatibility(policyGroups);

        List<DeterministicPolicyGroup<IN>> groups = new ArrayList<>(policyGroups.size() + 1);
        for (DeterministicPolicyGroup group : policyGroups) {
            groups.add(new PairPolicyGroup<>(group));
        }
        groups.add(getCommonPairPolicy((List<PairPolicyGroup<IN>>) (List) groups));

        return new B2BMultiDiscretizer<>(groups, reduceFunction, identityValue, capacity, serializer, aggregationType);

    }

    /**
     * Performs a check to see if all policies are compatible (i.e. of the same measure such as count or event time)
     *
     * @param policyGroups
     */
    private static <IN> void ensureCompatibility(List<DeterministicPolicyGroup<IN>> policyGroups) throws IllegalArgumentException {
        Class policyClass = null;
        for (DeterministicPolicyGroup<IN> group : policyGroups) {
            if (policyClass == null) {
                policyClass = group.getTrigger().getClass();
            } else {
                if (!policyClass.equals(group.getTrigger().getClass())) {
                    throw new IllegalArgumentException("Pairing only works with policies of the same measure. \n" +
                            "Mismatch of policies detected :: " + policyClass + " and " + group.getTrigger().getClass());
                }
            }
        }
    }

    /**
     * Computes the two pre-aggregation panes, knows as 'pairs aggregation technique' defined by Krishnamurthy et.al.
     *
     * @param range
     * @param slide
     * @return
     */
    public static long[] computePairs(long range, long slide) {
        long[] pairs = new long[2];
        pairs[1] = range % slide;
        pairs[0] = slide - pairs[1];
        return pairs;
    }

    /**
     * The generated policy contains the extended window with all panes shared by all given policies. The size of the
     * window is equal to the least common multiple of the period (part1+part2) among the given policies. This policy can
     * be used in the same DeterministicMultiDiscretizer with the given policies to emulate the execution of pairs.
     *
     * @param policies
     * @param <DATA>
     * @return
     */
    public static <DATA> DeterministicPolicyGroup<DATA> getCommonPairPolicy(List<PairPolicyGroup<DATA>> policies) {

        // we first compute the uber window size as the lcm of all subsequent period sizes
        BigInteger lcm = null;
        for (PairPolicyGroup wrapper : policies) {
            BigInteger next = BigInteger.valueOf(wrapper.getPart1() + wrapper.getPart2());
            lcm = (lcm == null) ? next : lcm.multiply(next).divide(lcm.gcd(next));
        }

        //then we compute all derivative window sequences
        List<Deque<Long>> subsequences = new ArrayList<>();
        for (PairPolicyGroup wrapper : policies) {
            int multiples = lcm.divide(BigInteger.valueOf(wrapper.getPart1() + wrapper.getPart2())).intValue();
            int subPanes = multiples * 2;
            LOG.error("Allocating "+subPanes+" pairs");
            Deque<Long> periods = new ArrayDeque<>();
            int pair = 1;
            for (int i = 0; i < subPanes; i++) {
                periods.addFirst(wrapper.getPart(pair));
                pair = (pair == 0) ? 1 : 0;
            }
            subsequences.add(periods);
        }

        //and then we make a proper uber-pane sequence out of its derivatives
        List<Long> sequence = new ArrayList<>();

        while (!subsequences.isEmpty()) {
            List<Long> nextPairs = new ArrayList<>(subsequences.size());
            for (Deque<Long> pairStack : subsequences) {
                nextPairs.add(pairStack.peekFirst());
            }
            long min = Collections.min(nextPairs);
            sequence.add(min);
            List<Deque<Long>> toRemove = new ArrayList<>();
            for (Deque<Long> pairStack : subsequences) {
                long next = pairStack.removeFirst();
                if (next > min) {
                    pairStack.addFirst(next - min);
                } else {
                    if (pairStack.isEmpty()) {
                        toRemove.add(pairStack);
                    }
                }
            }

            subsequences.removeAll(toRemove);
        }

        //now that we have all subsequences we create a policyGroup that does the magic
       return getPairSequence(policies.get(0), sequence);
    }
    
    private static <DATA> DeterministicPolicyGroup<DATA> getPairSequence(PairPolicyGroup<DATA> group,
                                                                            List<Long> sequence){
        if (group.getEviction() instanceof DeterministicCountEvictionPolicy) {
            return new TempPolicyGroup<>(new DeterministicCountSequenceTrigger<>(sequence, 
					((DeterministicCountTriggerPolicy)group.getTrigger()).getStartValue()),
					new DeterministicCountSequenceEviction<>(sequence), group.getFieldExtractor());
        } else
            throw new IllegalArgumentException("Only count based policies are currently supported for pairs ");
    }

}
