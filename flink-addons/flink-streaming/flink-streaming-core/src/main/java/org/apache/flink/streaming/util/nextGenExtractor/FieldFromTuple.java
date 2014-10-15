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

package org.apache.flink.streaming.util.nextGenExtractor;

import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.streaming.api.invokable.operator.NextGenExtractor;

public class FieldFromTuple<OUT> implements NextGenExtractor<Tuple, OUT> {

	/**
	 * Auto-gernated version id
	 */
	private static final long serialVersionUID = -5161386546695574359L;
	private int fieldId = 0;

	public FieldFromTuple() {
		// noting to do => will use default 0
	}

	public FieldFromTuple(int fieldId) {
		this.fieldId = fieldId;
	}

	@Override
	public OUT extract(Tuple in) {
		return in.getField(fieldId);
	}

}
