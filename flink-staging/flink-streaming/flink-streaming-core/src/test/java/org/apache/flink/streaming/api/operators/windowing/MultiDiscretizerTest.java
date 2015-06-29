/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.operators.windowing;

import static org.junit.Assert.assertEquals;

import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.windowing.extractor.Extractor;
import org.apache.flink.streaming.api.windowing.helper.Timestamp;
import org.apache.flink.streaming.api.windowing.helper.TimestampWrapper;
import org.apache.flink.streaming.api.windowing.policy.*;
import org.apache.flink.streaming.util.MockContext;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class MultiDiscretizerTest {

    @Test
    public void testMultiDiscretizerDeterministic() {

        //prepare input data
        List<Integer> inputs = new ArrayList<Integer>();
        inputs.add(1);
        inputs.add(2);
        inputs.add(2);
        inputs.add(10);
        inputs.add(11);
        inputs.add(14);
        inputs.add(16);
        inputs.add(21);

        //prepare expected result
        LinkedList<Tuple2<Integer, Integer>> expected = new LinkedList<Tuple2<Integer, Integer>>();
        expected.add(new Tuple2<Integer, Integer>(0, 5));  //0..4
        expected.add(new Tuple2<Integer, Integer>(0, 5));  //0..9
        expected.add(new Tuple2<Integer, Integer>(0, 35)); //5..14
        expected.add(new Tuple2<Integer, Integer>(0, 51)); //10..19

        //prepare policies
        @SuppressWarnings("unchecked")
        TimestampWrapper<Integer> timestampWrapper = new TimestampWrapper<Integer>(new Timestamp() {
            @Override
            public long getTimestamp(Object value) {
                return ((Integer) value);
            }
        }, 0);
        DeterministicTriggerPolicy<Integer> triggerPolicy = new DeterministicTimeTriggerPolicy<Integer>(5, timestampWrapper);
        DeterministicEvictionPolicy<Integer> evictionPolicy = new DeterministicTimeEvictionPolicy<Integer>(10, timestampWrapper);
        DeterministicPolicyGroup<Integer> policyGroup = new DeterministicPolicyGroup<Integer>(triggerPolicy, evictionPolicy, new IntegerToDuble());

        LinkedList<DeterministicPolicyGroup<Integer>> policyGroups = new LinkedList<DeterministicPolicyGroup<Integer>>();
        policyGroups.add(policyGroup);
        LinkedList<TriggerPolicy<Integer>> triggerPolicies = new LinkedList<TriggerPolicy<Integer>>();
        LinkedList<EvictionPolicy<Integer>> evictionPolicies = new LinkedList<EvictionPolicy<Integer>>();

        //Create operator instance
        MultiDiscretizer<Integer> multiDiscretizer = new MultiDiscretizer<Integer>(policyGroups, triggerPolicies, evictionPolicies, new Sum());

        //Run the test
        List<Tuple2<Integer, Integer>> result = MockContext.createAndExecute(multiDiscretizer, inputs);

        //check correctness
        assertEquals(expected, result);
    }

    @Test
    public void testMultiDiscretizerMultipleDeterministic() {
        //prepare input data
        List<Tuple2<Integer, Integer>> inputs = new ArrayList<Tuple2<Integer, Integer>>();
        inputs.add(new Tuple2<Integer, Integer>(1, 0));
        inputs.add(new Tuple2<Integer, Integer>(2, 1));
        inputs.add(new Tuple2<Integer, Integer>(2, 2));
        inputs.add(new Tuple2<Integer, Integer>(10, 3));
        inputs.add(new Tuple2<Integer, Integer>(11, 4));
        inputs.add(new Tuple2<Integer, Integer>(14, 5));
        inputs.add(new Tuple2<Integer, Integer>(16, 6));
        inputs.add(new Tuple2<Integer, Integer>(21, 7));

        //prepare expected result
        LinkedList<Tuple2<Integer, Tuple2<Integer, Integer>>> expected = new LinkedList<Tuple2<Integer, Tuple2<Integer, Integer>>>();
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(1, new Tuple2<Integer, Integer>(3, 1)));    //Q1 seq 0,1
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(0, new Tuple2<Integer, Integer>(5, 3)));   //Q0 0..4
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(0, new Tuple2<Integer, Integer>(5, 3)));   //Q0 0..9
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(1, new Tuple2<Integer, Integer>(15, 6)));   //Q1 seq 0,1,2,3
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(0, new Tuple2<Integer, Integer>(35, 12))); //Q0 5..14
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(1, new Tuple2<Integer, Integer>(39, 15)));  //Q1 seq 1,2,3,4,5
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(0, new Tuple2<Integer, Integer>(51, 18))); //Q0 10..19

        //prepare policies
        @SuppressWarnings("unchecked")
        TimestampWrapper<Tuple2<Integer, Integer>> timestampWrapper = new TimestampWrapper<Tuple2<Integer, Integer>>(new Timestamp() {
            @Override
            public long getTimestamp(Object value) {
                return ((Tuple2<Integer, Integer>) value).f0;
            }
        }, 0);
        DeterministicTriggerPolicy<Tuple2<Integer, Integer>> triggerPolicy =
                new DeterministicTimeTriggerPolicy<Tuple2<Integer, Integer>>(5, timestampWrapper);
        DeterministicEvictionPolicy<Tuple2<Integer, Integer>> evictionPolicy =
                new DeterministicTimeEvictionPolicy<Tuple2<Integer, Integer>>(10, timestampWrapper);
        DeterministicPolicyGroup<Tuple2<Integer, Integer>> policyGroup =
                new DeterministicPolicyGroup<Tuple2<Integer, Integer>>(triggerPolicy, evictionPolicy, new Tuple2ToDuble(0));

        DeterministicTriggerPolicy<Tuple2<Integer, Integer>> triggerPolicy2 =
                new DeterministicCountTriggerPolicy<Tuple2<Integer, Integer>>(2);
        DeterministicEvictionPolicy<Tuple2<Integer, Integer>> evictionPolicy2 =
                new DeterministicCountEvictionPolicy<Tuple2<Integer, Integer>>(5);
        DeterministicPolicyGroup<Tuple2<Integer, Integer>> policyGroup2 =
                new DeterministicPolicyGroup<Tuple2<Integer, Integer>>(triggerPolicy2, evictionPolicy2, new Tuple2ToDuble(1));

        LinkedList<DeterministicPolicyGroup<Tuple2<Integer, Integer>>> policyGroups =
                new LinkedList<DeterministicPolicyGroup<Tuple2<Integer, Integer>>>();
        policyGroups.add(policyGroup);
        policyGroups.add(policyGroup2);
        LinkedList<TriggerPolicy<Tuple2<Integer, Integer>>> triggerPolicies =
                new LinkedList<TriggerPolicy<Tuple2<Integer, Integer>>>();
        LinkedList<EvictionPolicy<Tuple2<Integer, Integer>>> evictionPolicies =
                new LinkedList<EvictionPolicy<Tuple2<Integer, Integer>>>();

        //Create operator instance
        MultiDiscretizer<Tuple2<Integer, Integer>> multiDiscretizer =
                new MultiDiscretizer<Tuple2<Integer, Integer>>(policyGroups, triggerPolicies, evictionPolicies, new TupleSum());

        //Run the test
        List<Tuple2<Integer, Tuple2<Integer, Integer>>> result = MockContext.createAndExecute(multiDiscretizer, inputs);

        //check correctness
        assertEquals(expected, result);
    }

    @Test
    public void testMultiDiscretizerNotDeterministic(){
        //prepare input data
        List<Integer> inputs = new ArrayList<Integer>();
        inputs.add(1);
        inputs.add(2);
        inputs.add(2);
        inputs.add(10);
        inputs.add(11);
        inputs.add(14);
        inputs.add(16);
        inputs.add(21);

        //prepare expected result
        LinkedList<Tuple2<Integer, Integer>> expected = new LinkedList<Tuple2<Integer, Integer>>();
        expected.add(new Tuple2<Integer, Integer>(0, 5));  //0..4
        expected.add(new Tuple2<Integer, Integer>(0, 5));  //0..9
        expected.add(new Tuple2<Integer, Integer>(0, 35)); //5..14
        expected.add(new Tuple2<Integer, Integer>(0, 51)); //10..19

        //prepare policies
        @SuppressWarnings("unchecked")
        TimestampWrapper<Integer> timestampWrapper = new TimestampWrapper<Integer>(new Timestamp() {
            @Override
            public long getTimestamp(Object value) {
                return ((Integer) value);
            }
        }, 0);
        TriggerPolicy<Integer> triggerPolicy = new TimeTriggerPolicy<Integer>(5, timestampWrapper);
        EvictionPolicy<Integer> evictionPolicy = new TimeEvictionPolicy<Integer>(10, timestampWrapper);

        LinkedList<DeterministicPolicyGroup<Integer>> policyGroups = new LinkedList<DeterministicPolicyGroup<Integer>>();
        LinkedList<TriggerPolicy<Integer>> triggerPolicies = new LinkedList<TriggerPolicy<Integer>>();
        triggerPolicies.add(triggerPolicy);
        LinkedList<EvictionPolicy<Integer>> evictionPolicies = new LinkedList<EvictionPolicy<Integer>>();
        evictionPolicies.add(evictionPolicy);

        //Create operator instance
        MultiDiscretizer<Integer> multiDiscretizer = new MultiDiscretizer<Integer>(policyGroups, triggerPolicies, evictionPolicies, new Sum());

        //Run the test
        List<Tuple2<Integer, Integer>> result = MockContext.createAndExecute(multiDiscretizer, inputs);

        //check correctness
        assertEquals(expected, result);
    }

    @Test
    public void testMultiDiscretizerMultipleNotDeterministic(){
        //prepare input data
        List<Tuple2<Integer, Integer>> inputs = new ArrayList<Tuple2<Integer, Integer>>();
        inputs.add(new Tuple2<Integer, Integer>(1, 0));
        inputs.add(new Tuple2<Integer, Integer>(2, 1));
        inputs.add(new Tuple2<Integer, Integer>(2, 2));
        inputs.add(new Tuple2<Integer, Integer>(10, 3));
        inputs.add(new Tuple2<Integer, Integer>(11, 4));
        inputs.add(new Tuple2<Integer, Integer>(14, 5));
        inputs.add(new Tuple2<Integer, Integer>(16, 6));
        inputs.add(new Tuple2<Integer, Integer>(21, 7));

        //prepare expected result
        LinkedList<Tuple2<Integer, Tuple2<Integer, Integer>>> expected = new LinkedList<Tuple2<Integer, Tuple2<Integer, Integer>>>();
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(1, new Tuple2<Integer, Integer>(3, 1)));    //Q1 seq 0,1
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(0, new Tuple2<Integer, Integer>(5, 3)));   //Q0 0..4
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(0, new Tuple2<Integer, Integer>(5, 3)));   //Q0 0..9
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(1, new Tuple2<Integer, Integer>(15, 6)));   //Q1 seq 0,1,2,3
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(0, new Tuple2<Integer, Integer>(35, 12))); //Q0 5..14
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(1, new Tuple2<Integer, Integer>(39, 15)));  //Q1 seq 1,2,3,4,5
        expected.add(new Tuple2<Integer, Tuple2<Integer, Integer>>(0, new Tuple2<Integer, Integer>(51, 18))); //Q0 10..19

        //prepare policies
        @SuppressWarnings("unchecked")
        TimestampWrapper<Tuple2<Integer, Integer>> timestampWrapper = new TimestampWrapper<Tuple2<Integer, Integer>>(new Timestamp() {
            @Override
            public long getTimestamp(Object value) {
                return ((Tuple2<Integer, Integer>) value).f0;
            }
        }, 0);
        TriggerPolicy<Tuple2<Integer, Integer>> triggerPolicy =
                new TimeTriggerPolicy<Tuple2<Integer, Integer>>(5, timestampWrapper);
        EvictionPolicy<Tuple2<Integer, Integer>> evictionPolicy =
                new TimeEvictionPolicy<Tuple2<Integer, Integer>>(10, timestampWrapper);

        TriggerPolicy<Tuple2<Integer, Integer>> triggerPolicy2 =
                new CountTriggerPolicy<Tuple2<Integer, Integer>>(2);
        EvictionPolicy<Tuple2<Integer, Integer>> evictionPolicy2 =
                new CountEvictionPolicy<Tuple2<Integer, Integer>>(5);

        LinkedList<DeterministicPolicyGroup<Tuple2<Integer, Integer>>> policyGroups =
                new LinkedList<DeterministicPolicyGroup<Tuple2<Integer, Integer>>>();
        LinkedList<TriggerPolicy<Tuple2<Integer, Integer>>> triggerPolicies =
                new LinkedList<TriggerPolicy<Tuple2<Integer, Integer>>>();
        triggerPolicies.add(triggerPolicy);
        triggerPolicies.add(triggerPolicy2);
        LinkedList<EvictionPolicy<Tuple2<Integer, Integer>>> evictionPolicies =
                new LinkedList<EvictionPolicy<Tuple2<Integer, Integer>>>();
        evictionPolicies.add(evictionPolicy);
        evictionPolicies.add(evictionPolicy2);

        //Create operator instance
        MultiDiscretizer<Tuple2<Integer, Integer>> multiDiscretizer =
                new MultiDiscretizer<Tuple2<Integer, Integer>>(policyGroups, triggerPolicies, evictionPolicies, new TupleSum());

        //Run the test
        List<Tuple2<Integer, Tuple2<Integer, Integer>>> result = MockContext.createAndExecute(multiDiscretizer, inputs);

        //check correctness
        assertEquals(expected, result);
    }

    /*********************************************
     * Utilities                                 *
     *********************************************/

    private class Sum implements ReduceFunction<Integer> {

        @Override
        public Integer reduce(Integer value1, Integer value2) throws Exception {
            return value1 + value2;
        }

    }

    private class TupleSum implements ReduceFunction<Tuple2<Integer, Integer>> {

        @Override
        public Tuple2<Integer, Integer> reduce(Tuple2<Integer, Integer> value1, Tuple2<Integer, Integer> value2) throws Exception {
            return new Tuple2<Integer, Integer>(value1.f0 + value2.f0, value1.f1 + value2.f1);
        }
    }

    private class IntegerToDuble implements Extractor<Integer, Double> {

        @Override
        public Double extract(Integer in) {
            return in.doubleValue();
        }
    }

    private class Tuple2ToDuble implements Extractor<Tuple2<Integer, Integer>, Double> {

        int field;

        public Tuple2ToDuble(int field) {
            this.field = field;
        }

        @Override
        public Double extract(Tuple2<Integer, Integer> in) {
            return ((Integer) in.getField(this.field)).doubleValue();
        }
    }
}
