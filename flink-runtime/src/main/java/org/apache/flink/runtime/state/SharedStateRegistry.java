/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.state;

import org.apache.flink.runtime.concurrent.Executors;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * This registry manages state that is shared across (incremental) checkpoints, and is responsible
 * for deleting shared state that is no longer used in any valid checkpoint.
 *
 * A {@code SharedStateRegistry} will be deployed in the 
 * {@link org.apache.flink.runtime.checkpoint.CheckpointCoordinator} to
 * maintain the reference count of {@link StreamStateHandle}s by a key that (logically) identifies
 * them.
 */
public class SharedStateRegistry {

	private static final Logger LOG = LoggerFactory.getLogger(SharedStateRegistry.class);

	/** All registered state objects by an artificial key */
	private final Map<SharedStateRegistryKey, SharedStateRegistry.SharedStateEntry> registeredStates;

	/** Executor for async state deletion */
	private final Executor asyncDisposalExecutor;

	public SharedStateRegistry() {
		this(Executors.directExecutor());
	}

	public SharedStateRegistry(Executor asyncDisposalExecutor) {
		this.registeredStates = new HashMap<>();
		this.asyncDisposalExecutor = Preconditions.checkNotNull(asyncDisposalExecutor);
	}

	/**
	 * Register a reference to the given shared state in the registry.
	 * This does the following: We check if the state handle is actually new by the
	 * registrationKey. If it is new, we register it with a reference count of 1. If there is
	 * already a state handle registered under the given key, we dispose the given "new" state
	 * handle, uptick the reference count of the previously existing state handle and return it as
	 * a replacement with the result.
	 *
	 * <p>IMPORTANT: caller should check the state handle returned by the result, because the
	 * registry is performing de-duplication and could potentially return a handle that is supposed
	 * to replace the one from the registration request.
	 *
	 * @param state the shared state for which we register a reference.
	 * @return the result of this registration request, consisting of the state handle that is
	 * registered under the key by the end of the oepration and its current reference count.
	 */
	public Result registerReference(SharedStateRegistryKey registrationKey, StreamStateHandle state) {

		Preconditions.checkNotNull(state);

		StreamStateHandle scheduledStateDeletion = null;
		SharedStateRegistry.SharedStateEntry entry;

		synchronized (registeredStates) {
			entry = registeredStates.get(registrationKey);

			if (entry == null) {
				entry = new SharedStateRegistry.SharedStateEntry(state);
				registeredStates.put(registrationKey, entry);
			} else {
				// delete if this is a real duplicate
				if (!Objects.equals(state, entry.state)) {
					scheduledStateDeletion = state;
				}
				entry.increaseReferenceCount();
			}
		}

		scheduleAsyncDelete(scheduledStateDeletion);
		return new Result(entry);
	}

	/**
	 * Releases one reference to the given shared state in the registry. This decreases the
	 * reference count by one. Once the count reaches zero, the shared state is deleted.
	 *
	 * @param registrationKey the shared state for which we release a reference.
	 * @return the result of the request, consisting of the reference count after this operation
	 * and the state handle, or null if the state handle was deleted through this request.
	 */
	public Result unregisterReference(SharedStateRegistryKey registrationKey) {

		Preconditions.checkNotNull(registrationKey);

		final Result result;
		final StreamStateHandle scheduledStateDeletion;

		synchronized (registeredStates) {
			SharedStateRegistry.SharedStateEntry entry = registeredStates.get(registrationKey);

			Preconditions.checkState(entry != null,
				"Cannot unregister a state that is not registered.");

			entry.decreaseReferenceCount();

			// Remove the state from the registry when it's not referenced any more.
			if (entry.getReferenceCount() <= 0) {
				registeredStates.remove(registrationKey);
				scheduledStateDeletion = entry.getState();
				result = new Result(null, 0);
			} else {
				scheduledStateDeletion = null;
				result = new Result(entry);
			}
		}

		scheduleAsyncDelete(scheduledStateDeletion);
		return result;
	}

	/**
	 * Register given shared states in the registry.
	 *
	 * @param stateHandles The shared states to register.
	 */
	public void registerAll(Iterable<? extends CompositeStateHandle> stateHandles) {
		if (stateHandles == null) {
			return;
		}

		synchronized (registeredStates) {
			for (CompositeStateHandle stateHandle : stateHandles) {
				stateHandle.registerSharedStates(this);
			}
		}
	}

	private void scheduleAsyncDelete(StreamStateHandle streamStateHandle) {
		// We do the small optimization to not issue discards for placeholders, which are NOPs.
		if (streamStateHandle != null && !isPlaceholder(streamStateHandle)) {
			asyncDisposalExecutor.execute(
				new SharedStateRegistry.AsyncDisposalRunnable(streamStateHandle));
		}
	}

	private boolean isPlaceholder(StreamStateHandle stateHandle) {
		return stateHandle instanceof PlaceholderStreamStateHandle;
	}

	/**
	 * An entry in the registry, tracking the handle and the corresponding reference count.
	 */
	private static class SharedStateEntry {

		/** The shared state handle */
		private final StreamStateHandle state;

		/** The current reference count of the state handle */
		private int referenceCount;

		SharedStateEntry(StreamStateHandle value) {
			this.state = value;
			this.referenceCount = 1;
		}

		StreamStateHandle getState() {
			return state;
		}

		int getReferenceCount() {
			return referenceCount;
		}

		void increaseReferenceCount() {
			++referenceCount;
		}

		void decreaseReferenceCount() {
			--referenceCount;
		}
	}

	/**
	 * The result of an attempt to (un)/reference state
	 */
	public static class Result {

		/** The (un)registered state handle from the request */
		private final StreamStateHandle reference;

		/** The reference count to the state handle after the request to (un)register */
		private final int referenceCount;

		private Result(SharedStateEntry sharedStateEntry) {
			this.reference = sharedStateEntry.getState();
			this.referenceCount = sharedStateEntry.getReferenceCount();
		}

		public Result(StreamStateHandle reference, int referenceCount) {
			Preconditions.checkArgument(referenceCount >= 0);

			this.reference = reference;
			this.referenceCount = referenceCount;
		}

		public StreamStateHandle getReference() {
			return reference;
		}

		public int getReferenceCount() {
			return referenceCount;
		}
	}

	/**
	 * Encapsulates the operation the delete state handles asynchronously.
	 */
	private static final class AsyncDisposalRunnable implements Runnable {

		private final StateObject toDispose;

		public AsyncDisposalRunnable(StateObject toDispose) {
			this.toDispose = Preconditions.checkNotNull(toDispose);
		}

		@Override
		public void run() {
			try {
				toDispose.discardState();
			} catch (Exception e) {
				LOG.warn("A problem occurred during asynchronous disposal of a shared state object: {}", toDispose, e);
			}
		}
	}

	/**
	 * Clears the registry.
	 */
	public void clear() {
		registeredStates.clear();
	}
}
