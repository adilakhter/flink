/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.sampling.samplers;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.sampling.helpers.SamplingUtils;
import org.apache.flink.streaming.sampling.helpers.StreamTimestamp;
import org.apache.flink.util.Collector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * Created by marthavk on 2015-04-21.
 */
public class PrioritySampler<T> implements Sampler<Tuple2<T,StreamTimestamp>>,FlatMapFunction<T,T> {

	Chain<Tuple2<T, StreamTimestamp>> chainSample;
	ArrayList<LinkedList<Double>> priorityList;
	Long windowSize;

	public PrioritySampler(int lSize, long lWindowSize) {
		this.chainSample = new Chain<Tuple2<T, StreamTimestamp>>(lSize);
		this.windowSize = lWindowSize;
		this.priorityList = new ArrayList<LinkedList<Double>>();
		initializeLists();
	}

	/**
	 * METHODS IMPLEMENTING FlatMapFunction
	 */

	//TODO implement Collector Policy
	@Override
	public void flatMap(T value, Collector<T> out) throws Exception {
		final StreamTimestamp t = new StreamTimestamp();
		Tuple2<T, StreamTimestamp> wrappedValue = new Tuple2<T, StreamTimestamp>(value,t);
		sample(wrappedValue);

	}

	@Override
	public ArrayList<Tuple2<T, StreamTimestamp>> getElements() {
		return chainSample.extractSample();
	}


	/**
	 * METHODS IMPLEMENTING Sampler INTERFACE *
	 */
	@Override
	public void sample(Tuple2<T, StreamTimestamp> element) {
		//update expired elements
		StreamTimestamp currentTimestamp = new StreamTimestamp(element.f1.getTimestamp());
		update(currentTimestamp);

		//assign k priorities between 0,1
		ArrayList<Double> priorities = assignPriorities();

		//place new sample
		placeInList(element, priorities);

	}


	/**
	 * PRIORITY SAMPLER METHODS *
	 */
	public ArrayList<Double> assignPriorities() {
		ArrayList<Double> priorities = new ArrayList<Double>();
		for (int i = 0; i < chainSample.getMaxSize(); i++) {
			priorities.add(SamplingUtils.randomPriority());
		}
		return priorities;
	}

	/**
	 * initialize priorityList and chainSample with null elements
	 */
	public void initializeLists() {

		//initialize priority list
		for (int i = 0; i < chainSample.getMaxSize(); i++) {
			LinkedList<Double> priorityInitList = new LinkedList<Double>();
			priorityInitList.add(-1.0);
			priorityList.add(priorityInitList);
			chainSample.addSample(null);
		}

	}

	/**
	 * for each position in the sample checks if the assigned priority
	 * of the new item for this position is bigger than any priority in
	 * the list. If so, deletes all elements with lower priority and
	 * chains the current element in the chainSample and its priority
	 * in the priorityList
	 *
	 * @param item
	 * @param priorities
	 */
	public void placeInList(Tuple2<T, StreamTimestamp> item, ArrayList<Double> priorities) {

		//printIndexedString("\t***placeInList",0);
		for (int pos = 0; pos < chainSample.getMaxSize(); pos++) {

			Double p = priorities.get(pos);
			int lastElement = priorityList.get(pos).size() - 1;

			for (int i = 0; i < priorityList.get(pos).size(); i++) {
				double currentP = priorityList.get(pos).get(i);

				if (p > currentP) {

					//delete all priorities in the queue and the sample
					priorityList.get(pos).subList(i, priorityList.get(pos).size()).clear();
					chainSample.get(pos).subList(i, chainSample.get(pos).size()).clear();

					//add this item to the queue and its priority to the sample
					priorityList.get(pos).add(p);
					chainSample.get(pos).add(item);

					break;

				} else if (i == lastElement) {

					priorityList.get(pos).add(p);
					chainSample.get(pos).add(item);

				} else {
					//do nothing
				}
			}
		}

	}

	/**
	 * checks if the timestamp of all sampled elements is between the
	 * declared window. If not, pops them out of the list
	 */
	public void update(StreamTimestamp timestamp) {


		for (int i = 0; i < chainSample.getMaxSize(); i++) {

			LinkedList<Tuple2<T, StreamTimestamp>> listInPos = chainSample.get(i);

			if (!listInPos.contains(null)) {

				int counter = 0;

				Iterator<Tuple2<T, StreamTimestamp>> iter = listInPos.iterator();
				while (iter.hasNext()) {
					Tuple2<T, StreamTimestamp> nextItem = iter.next();
					if (timestamp.getTimestamp() - nextItem.f1.getTimestamp() > windowSize) {
						counter++;
					}
				}

				listInPos.subList(0, counter).clear();
				priorityList.get(i).subList(0, counter).clear();

				if (listInPos.isEmpty()) {
					listInPos.add(null);
				}

				if (priorityList.get(i).isEmpty()) {
					priorityList.get(i).add(-1.0);
				}
			}
		}
	}

	/**
	 * DEBUG MESSAGES
	 *
	 */

	String prioritiesToString() {
		String prStr;
		prStr = "[";

		Iterator<LinkedList<Double>> iter = priorityList.iterator();
		while (iter.hasNext()) {
			LinkedList<Double> list = iter.next();
			prStr += "(";
			if (!list.contains(null)) {
				for (int i = 0; i < list.size(); i++) {
					prStr += list.get(i).floatValue() + "->";
				}
			}
			prStr += ")";
		}
		prStr += "]";
		return prStr;
	}

	String assignedPriorsToString(ArrayList<Double> p) {
		String pStr = "[";
		for (int i = 0; i < p.size(); i++) {
			pStr += " " + p.get(i).floatValue() + " ";
		}
		pStr += "]";
		return pStr;
	}



}

