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

package org.apache.flink.runtime.io.network.api.reader;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import org.apache.flink.core.io.IOReadableWritable;
import org.apache.flink.runtime.event.task.AbstractEvent;
import org.apache.flink.runtime.event.task.StreamingSuperstep;
import org.apache.flink.runtime.io.network.api.serialization.RecordDeserializer;
import org.apache.flink.runtime.io.network.api.serialization.RecordDeserializer.DeserializationResult;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.partition.consumer.BufferOrEvent;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;
import org.apache.flink.runtime.io.network.serialization.SpillingAdaptiveSpanningRecordDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A record-oriented reader.
 * <p>
 * This abstract base class is used by both the mutable and immutable record
 * readers.
 * 
 * @param <T>
 *            The type of the record that can be read with this record reader.
 */
abstract class AbstractRecordReader<T extends IOReadableWritable> extends AbstractReader implements
		ReaderBase {

	private static final Logger LOG = LoggerFactory.getLogger(AbstractRecordReader.class);

	private final RecordDeserializer<T>[] recordDeserializers;

	private RecordDeserializer<T> currentRecordDeserializer;

	private boolean isFinished;

	private final BarrierBuffer barrierBuffer;

	protected AbstractRecordReader(InputGate inputGate) {
		super(inputGate);
		barrierBuffer = new BarrierBuffer(inputGate, this);

		// Initialize one deserializer per input channel
		this.recordDeserializers = new SpillingAdaptiveSpanningRecordDeserializer[inputGate
				.getNumberOfInputChannels()];
		for (int i = 0; i < recordDeserializers.length; i++) {
			recordDeserializers[i] = new SpillingAdaptiveSpanningRecordDeserializer<T>();
		}
	}

	protected boolean getNextRecord(T target) throws IOException, InterruptedException {
		if (isFinished) {
			return false;
		}

		while (true) {
			if (currentRecordDeserializer != null) {
				DeserializationResult result = currentRecordDeserializer.getNextRecord(target);

				if (result.isBufferConsumed()) {
					currentRecordDeserializer.getCurrentBuffer().recycle();
					currentRecordDeserializer = null;
				}

				if (result.isFullRecord()) {
					return true;
				}
			}

			BufferOrEvent bufferOrEvent = null;

			if (barrierBuffer.containsNonprocessed()) {
				bufferOrEvent = barrierBuffer.getNonProcessed();
			} else {
				while (bufferOrEvent == null) {
					BufferOrEvent nextBufferOrEvent = inputGate.getNextBufferOrEvent();
					if (barrierBuffer.isBlocked(nextBufferOrEvent.getChannelIndex())) {
						barrierBuffer.store(nextBufferOrEvent);
					} else {
						bufferOrEvent = nextBufferOrEvent;
					}
				}
			}

			if (bufferOrEvent.isBuffer()) {
				currentRecordDeserializer = recordDeserializers[bufferOrEvent.getChannelIndex()];
				currentRecordDeserializer.setNextBuffer(bufferOrEvent.getBuffer());
			} else {
				// Event received
				final AbstractEvent event = bufferOrEvent.getEvent();

				if (event instanceof StreamingSuperstep) {
					int channelIndex = bufferOrEvent.getChannelIndex();
					if (barrierBuffer.isBlocked(channelIndex)) {
						barrierBuffer.store(bufferOrEvent);
					} else {
						StreamingSuperstep superstep = (StreamingSuperstep) event;
						if (!barrierBuffer.receivedSuperstep()) {
							barrierBuffer.startSuperstep(superstep);
						}
						barrierBuffer.blockChannel(channelIndex);
					}
				} else {
					if (handleEvent(event)) {
						if (inputGate.isFinished()) {
							isFinished = true;
							return false;
						} else if (hasReachedEndOfSuperstep()) {
							return false;
						} // else: More data is coming...
					}
				}
			}
		}
	}

	public void clearBuffers() {
		for (RecordDeserializer<?> deserializer : recordDeserializers) {
			Buffer buffer = deserializer.getCurrentBuffer();
			if (buffer != null && !buffer.isRecycled()) {
				buffer.recycle();
			}
		}
	}
}
