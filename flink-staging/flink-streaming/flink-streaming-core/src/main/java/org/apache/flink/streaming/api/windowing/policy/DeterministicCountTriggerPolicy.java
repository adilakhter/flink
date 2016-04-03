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

package org.apache.flink.streaming.api.windowing.policy;

public class DeterministicCountTriggerPolicy<IN> extends CountTriggerPolicy<IN>
		implements DeterministicTriggerPolicy<IN> {

	private int max;
	private int startValue;

	public DeterministicCountTriggerPolicy(int max, int startValue) {
		super(max, startValue);
		this.max = max;
		this.startValue = startValue;
	}

	public DeterministicCountTriggerPolicy(int max) {
		super(max);
		this.max = max;
		this.startValue = 0;
	}

	public int getStartValue() {
		return startValue;
	}

	@Override
	public double getNextTriggerPosition(double previousTriggerPosition) {
		if (previousTriggerPosition < 0) {
			return startValue + max;
		} else {
			return previousTriggerPosition + max;
		}
	}
}
