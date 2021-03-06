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

package org.apache.flink.runtime.executiongraph;

import org.apache.flink.api.common.accumulators.Accumulator;
import org.apache.flink.api.common.accumulators.AccumulatorHelper;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.core.io.InputSplitAssigner;
import org.apache.flink.core.io.InputSplitSource;
import org.apache.flink.core.io.LocatableInputSplit;
import org.apache.flink.runtime.JobException;
import org.apache.flink.runtime.accumulators.StringifiedAccumulatorResult;
import org.apache.flink.runtime.checkpoint.stats.CheckpointStatsTracker;
import org.apache.flink.runtime.checkpoint.stats.OperatorCheckpointStats;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.api.common.Archiveable;
import org.apache.flink.runtime.instance.SlotProvider;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.IntermediateDataSet;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.JobEdge;
import org.apache.flink.api.common.JobID;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobmanager.scheduler.CoLocationGroup;
import org.apache.flink.runtime.jobmanager.scheduler.NoResourceAvailableException;
import org.apache.flink.runtime.jobmanager.scheduler.SlotSharingGroup;
import org.apache.flink.runtime.util.SerializableObject;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.SerializedValue;
import org.slf4j.Logger;

import scala.Option;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExecutionJobVertex implements AccessExecutionJobVertex, Archiveable<ArchivedExecutionJobVertex> {

	/** Use the same log for all ExecutionGraph classes */
	private static final Logger LOG = ExecutionGraph.LOG;
	
	private final SerializableObject stateMonitor = new SerializableObject();
	
	private final ExecutionGraph graph;
	
	private final JobVertex jobVertex;
	
	private final ExecutionVertex[] taskVertices;

	private IntermediateResult[] producedDataSets;
	
	private final List<IntermediateResult> inputs;
	
	private final int parallelism;

	private final int maxParallelism;
	
	private final boolean[] finishedSubtasks;
			
	private volatile int numSubtasksInFinalState;
	
	private final SlotSharingGroup slotSharingGroup;
	
	private final CoLocationGroup coLocationGroup;
	
	private final InputSplit[] inputSplits;

	/**
	 * Serialized task information which is for all sub tasks the same. Thus, it avoids to
	 * serialize the same information multiple times in order to create the
	 * TaskDeploymentDescriptors.
	 */
	private final SerializedValue<TaskInformation> serializedTaskInformation;

	private InputSplitAssigner splitAssigner;
	
	public ExecutionJobVertex(
		ExecutionGraph graph,
		JobVertex jobVertex,
		int defaultParallelism,
		Time timeout) throws JobException, IOException {

		this(graph, jobVertex, defaultParallelism, timeout, System.currentTimeMillis());
	}
	
	public ExecutionJobVertex(
		ExecutionGraph graph,
		JobVertex jobVertex,
		int defaultParallelism,
		Time timeout,
		long createTimestamp) throws JobException, IOException {

		if (graph == null || jobVertex == null) {
			throw new NullPointerException();
		}
		
		this.graph = graph;
		this.jobVertex = jobVertex;

		int vertexParallelism = jobVertex.getParallelism();
		int numTaskVertices = vertexParallelism > 0 ? vertexParallelism : defaultParallelism;

		this.parallelism = numTaskVertices;

		int maxP = jobVertex.getMaxParallelism();

		Preconditions.checkArgument(maxP >= parallelism, "The maximum parallelism (" +
			maxP + ") must be greater or equal than the parallelism (" + parallelism +
			").");
		this.maxParallelism = maxP;

		this.serializedTaskInformation = new SerializedValue<>(new TaskInformation(
			jobVertex.getID(),
			jobVertex.getName(),
			parallelism,
			maxParallelism,
			jobVertex.getInvokableClassName(),
			jobVertex.getConfiguration()));

		this.taskVertices = new ExecutionVertex[numTaskVertices];
		
		this.inputs = new ArrayList<IntermediateResult>(jobVertex.getInputs().size());
		
		// take the sharing group
		this.slotSharingGroup = jobVertex.getSlotSharingGroup();
		this.coLocationGroup = jobVertex.getCoLocationGroup();
		
		// setup the coLocation group
		if (coLocationGroup != null && slotSharingGroup == null) {
			throw new JobException("Vertex uses a co-location constraint without using slot sharing");
		}
		
		// create the intermediate results
		this.producedDataSets = new IntermediateResult[jobVertex.getNumberOfProducedIntermediateDataSets()];

		for (int i = 0; i < jobVertex.getProducedDataSets().size(); i++) {
			final IntermediateDataSet result = jobVertex.getProducedDataSets().get(i);

			this.producedDataSets[i] = new IntermediateResult(
					result.getId(),
					this,
					numTaskVertices,
					result.getResultType(),
					result.getEagerlyDeployConsumers());
		}

		// create all task vertices
		for (int i = 0; i < numTaskVertices; i++) {
			ExecutionVertex vertex = new ExecutionVertex(this, i, this.producedDataSets, timeout, createTimestamp);
			this.taskVertices[i] = vertex;
		}
		
		// sanity check for the double referencing between intermediate result partitions and execution vertices
		for (IntermediateResult ir : this.producedDataSets) {
			if (ir.getNumberOfAssignedPartitions() != parallelism) {
				throw new RuntimeException("The intermediate result's partitions were not correctly assigned.");
			}
		}
		
		// set up the input splits, if the vertex has any
		try {
			@SuppressWarnings("unchecked")
			InputSplitSource<InputSplit> splitSource = (InputSplitSource<InputSplit>) jobVertex.getInputSplitSource();
			
			if (splitSource != null) {
				Thread currentThread = Thread.currentThread();
				ClassLoader oldContextClassLoader = currentThread.getContextClassLoader();
				currentThread.setContextClassLoader(graph.getUserClassLoader());
				try {
					inputSplits = splitSource.createInputSplits(numTaskVertices);

					if (inputSplits != null) {
						splitAssigner = splitSource.getInputSplitAssigner(inputSplits);
					}
				} finally {
					currentThread.setContextClassLoader(oldContextClassLoader);
				}
			}
			else {
				inputSplits = null;
			}
		}
		catch (Throwable t) {
			throw new JobException("Creating the input splits caused an error: " + t.getMessage(), t);
		}
		
		finishedSubtasks = new boolean[parallelism];
	}

	public ExecutionGraph getGraph() {
		return graph;
	}
	
	public JobVertex getJobVertex() {
		return jobVertex;
	}

	@Override
	public String getName() {
		return getJobVertex().getName();
	}

	@Override
	public int getParallelism() {
		return parallelism;
	}

	@Override
	public int getMaxParallelism() {
		return maxParallelism;
	}

	public JobID getJobId() {
		return graph.getJobID();
	}
	
	@Override
	public JobVertexID getJobVertexId() {
		return jobVertex.getID();
	}
	
	@Override
	public ExecutionVertex[] getTaskVertices() {
		return taskVertices;
	}
	
	public IntermediateResult[] getProducedDataSets() {
		return producedDataSets;
	}
	
	public InputSplitAssigner getSplitAssigner() {
		return splitAssigner;
	}
	
	public SlotSharingGroup getSlotSharingGroup() {
		return slotSharingGroup;
	}
	
	public CoLocationGroup getCoLocationGroup() {
		return coLocationGroup;
	}
	
	public List<IntermediateResult> getInputs() {
		return inputs;
	}

	public SerializedValue<TaskInformation> getSerializedTaskInformation() {
		return serializedTaskInformation;
	}
	
	public boolean isInFinalState() {
		return numSubtasksInFinalState == parallelism;
	}
	
	@Override
	public ExecutionState getAggregateState() {
		int[] num = new int[ExecutionState.values().length];
		for (ExecutionVertex vertex : this.taskVertices) {
			num[vertex.getExecutionState().ordinal()]++;
		}
		
		return getAggregateJobVertexState(num, parallelism);
	}
	
	@Override
	public Option<OperatorCheckpointStats> getCheckpointStats() {
		CheckpointStatsTracker tracker = getGraph().getCheckpointStatsTracker();
		if (tracker == null) {
			return Option.empty();
		} else {
			return tracker.getOperatorStats(getJobVertexId());
		}
	}

	//---------------------------------------------------------------------------------------------
	
	public void connectToPredecessors(Map<IntermediateDataSetID, IntermediateResult> intermediateDataSets) throws JobException {
		
		List<JobEdge> inputs = jobVertex.getInputs();
		
		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("Connecting ExecutionJobVertex %s (%s) to %d predecessors.", jobVertex.getID(), jobVertex.getName(), inputs.size()));
		}
		
		for (int num = 0; num < inputs.size(); num++) {
			JobEdge edge = inputs.get(num);
			
			if (LOG.isDebugEnabled()) {
				if (edge.getSource() == null) {
					LOG.debug(String.format("Connecting input %d of vertex %s (%s) to intermediate result referenced via ID %s.", 
							num, jobVertex.getID(), jobVertex.getName(), edge.getSourceId()));
				} else {
					LOG.debug(String.format("Connecting input %d of vertex %s (%s) to intermediate result referenced via predecessor %s (%s).",
							num, jobVertex.getID(), jobVertex.getName(), edge.getSource().getProducer().getID(), edge.getSource().getProducer().getName()));
				}
			}
			
			// fetch the intermediate result via ID. if it does not exist, then it either has not been created, or the order
			// in which this method is called for the job vertices is not a topological order
			IntermediateResult ires = intermediateDataSets.get(edge.getSourceId());
			if (ires == null) {
				throw new JobException("Cannot connect this job graph to the previous graph. No previous intermediate result found for ID "
						+ edge.getSourceId());
			}
			
			this.inputs.add(ires);
			
			int consumerIndex = ires.registerConsumer();
			
			for (int i = 0; i < parallelism; i++) {
				ExecutionVertex ev = taskVertices[i];
				ev.connectSource(num, ires, edge, consumerIndex);
			}
		}
	}
	
	//---------------------------------------------------------------------------------------------
	//  Actions
	//---------------------------------------------------------------------------------------------
	
	public void scheduleAll(SlotProvider slotProvider, boolean queued) throws NoResourceAvailableException {
		
		ExecutionVertex[] vertices = this.taskVertices;

		// kick off the tasks
		for (ExecutionVertex ev : vertices) {
			ev.scheduleForExecution(slotProvider, queued);
		}
	}

	public void cancel() {
		for (ExecutionVertex ev : getTaskVertices()) {
			ev.cancel();
		}
	}
	
	public void fail(Throwable t) {
		for (ExecutionVertex ev : getTaskVertices()) {
			ev.fail(t);
		}
	}
	
	public void waitForAllVerticesToReachFinishingState() throws InterruptedException {
		synchronized (stateMonitor) {
			while (numSubtasksInFinalState < parallelism) {
				stateMonitor.wait();
			}
		}
	}
	
	public void resetForNewExecution() {
		if (!(numSubtasksInFinalState == 0 || numSubtasksInFinalState == parallelism)) {
			throw new IllegalStateException("Cannot reset vertex that is not in final state");
		}
		
		synchronized (stateMonitor) {
			// check and reset the sharing groups with scheduler hints
			if (slotSharingGroup != null) {
				slotSharingGroup.clearTaskAssignment();
			}
			
			// reset vertices one by one. if one reset fails, the "vertices in final state"
			// fields will be consistent to handle triggered cancel calls
			for (int i = 0; i < parallelism; i++) {
				taskVertices[i].resetForNewExecution();
				if (finishedSubtasks[i]) {
					finishedSubtasks[i] = false;
					numSubtasksInFinalState--;
				}
			}
			
			if (numSubtasksInFinalState != 0) {
				throw new RuntimeException("Bug: resetting the execution job vertex failed.");
			}
			
			// set up the input splits again
			try {
				if (this.inputSplits != null) {
					// lazy assignment
					@SuppressWarnings("unchecked")
					InputSplitSource<InputSplit> splitSource = (InputSplitSource<InputSplit>) jobVertex.getInputSplitSource();
					this.splitAssigner = splitSource.getInputSplitAssigner(this.inputSplits);
				}
			}
			catch (Throwable t) {
				throw new RuntimeException("Re-creating the input split assigner failed: " + t.getMessage(), t);
			}

			// Reset intermediate results
			for (IntermediateResult result : producedDataSets) {
				result.resetForNewExecution();
			}
		}
	}
	
	//---------------------------------------------------------------------------------------------
	//  Notifications
	//---------------------------------------------------------------------------------------------
	
	void vertexFinished(int subtask) {
		subtaskInFinalState(subtask);
	}
	
	void vertexCancelled(int subtask) {
		subtaskInFinalState(subtask);
	}
	
	void vertexFailed(int subtask, Throwable error) {
		subtaskInFinalState(subtask);
	}
	
	private void subtaskInFinalState(int subtask) {
		synchronized (stateMonitor) {
			if (!finishedSubtasks[subtask]) {
				finishedSubtasks[subtask] = true;
				
				if (numSubtasksInFinalState+1 == parallelism) {
					
					// call finalizeOnMaster hook
					try {
						getJobVertex().finalizeOnMaster(getGraph().getUserClassLoader());
					}
					catch (Throwable t) {
						getGraph().fail(t);
					}

					numSubtasksInFinalState++;
					
					// we are in our final state
					stateMonitor.notifyAll();
					
					// tell the graph
					graph.jobVertexInFinalState();
				} else {
					numSubtasksInFinalState++;
				}
			}
		}
	}

	// --------------------------------------------------------------------------------------------
	//  Accumulators / Metrics
	// --------------------------------------------------------------------------------------------

	public StringifiedAccumulatorResult[] getAggregatedUserAccumulatorsStringified() {
		Map<String, Accumulator<?, ?>> userAccumulators = new HashMap<String, Accumulator<?, ?>>();

		for (ExecutionVertex vertex : taskVertices) {
			Map<String, Accumulator<?, ?>> next = vertex.getCurrentExecutionAttempt().getUserAccumulators();
			if (next != null) {
				AccumulatorHelper.mergeInto(userAccumulators, next);
			}
		}

		return StringifiedAccumulatorResult.stringifyAccumulatorResults(userAccumulators);
	}

	// --------------------------------------------------------------------------------------------
	//  Static / pre-assigned input splits
	// --------------------------------------------------------------------------------------------

	private List<LocatableInputSplit>[] computeLocalInputSplitsPerTask(InputSplit[] splits) throws JobException {
		
		final int numSubTasks = getParallelism();
		
		// sanity check
		if (numSubTasks > splits.length) {
			throw new JobException("Strictly local assignment requires at least as many splits as subtasks.");
		}
		
		// group the splits by host while preserving order per host
		Map<String, List<LocatableInputSplit>> splitsByHost = new HashMap<String, List<LocatableInputSplit>>();
		
		for (InputSplit split : splits) {
			// check that split has exactly one local host
			if(!(split instanceof LocatableInputSplit)) {
				throw new JobException("Invalid InputSplit type " + split.getClass().getCanonicalName() + ". " +
						"Strictly local assignment requires LocatableInputSplit");
			}
			LocatableInputSplit lis = (LocatableInputSplit) split;

			if (lis.getHostnames() == null) {
				throw new JobException("LocatableInputSplit has no host information. " +
						"Strictly local assignment requires exactly one hostname for each LocatableInputSplit.");
			}
			else if (lis.getHostnames().length != 1) {
				throw new JobException("Strictly local assignment requires exactly one hostname for each LocatableInputSplit.");
			}
			String hostName = lis.getHostnames()[0];
			
			if (hostName == null) {
				throw new JobException("For strictly local input split assignment, no null host names are allowed.");
			}

			List<LocatableInputSplit> hostSplits = splitsByHost.get(hostName);
			if (hostSplits == null) {
				hostSplits = new ArrayList<LocatableInputSplit>();
				splitsByHost.put(hostName, hostSplits);
			}
			hostSplits.add(lis);
		}
		
		
		int numHosts = splitsByHost.size();
		
		if (numSubTasks < numHosts) {
			throw new JobException("Strictly local split assignment requires at least as " +
					"many parallel subtasks as distinct split hosts. Please increase the parallelism " +
					"of DataSource "+this.getJobVertex().getName()+" to at least "+numHosts+".");
		}

		// get list of hosts in deterministic order
		List<String> hosts = new ArrayList<String>(splitsByHost.keySet());
		Collections.sort(hosts);
		
		@SuppressWarnings("unchecked")
		List<LocatableInputSplit>[] subTaskSplitAssignment = (List<LocatableInputSplit>[]) new List<?>[numSubTasks];
		
		final int subtasksPerHost = numSubTasks / numHosts;
		final int hostsWithOneMore = numSubTasks % numHosts;
		
		int subtaskNum = 0;
		
		// we go over all hosts and distribute the hosts' input splits
		// over the subtasks
		for (int hostNum = 0; hostNum < numHosts; hostNum++) {
			String host = hosts.get(hostNum);
			List<LocatableInputSplit> splitsOnHost = splitsByHost.get(host);
			
			int numSplitsOnHost = splitsOnHost.size();
			
			// the number of subtasks to split this over.
			// NOTE: if the host has few splits, some subtasks will not get anything.
			int subtasks = Math.min(numSplitsOnHost, 
							hostNum < hostsWithOneMore ? subtasksPerHost + 1 : subtasksPerHost);
			
			int splitsPerSubtask = numSplitsOnHost / subtasks;
			int subtasksWithOneMore = numSplitsOnHost % subtasks;
			
			int splitnum = 0;
			
			// go over the subtasks and grab a subrange of the input splits
			for (int i = 0; i < subtasks; i++) {
				int numSplitsForSubtask = (i < subtasksWithOneMore ? splitsPerSubtask + 1 : splitsPerSubtask);
				
				List<LocatableInputSplit> splitList;
				
				if (numSplitsForSubtask == numSplitsOnHost) {
					splitList = splitsOnHost;
				}
				else {
					splitList = new ArrayList<LocatableInputSplit>(numSplitsForSubtask);
					for (int k = 0; k < numSplitsForSubtask; k++) {
						splitList.add(splitsOnHost.get(splitnum++));
					}
				}
				
				subTaskSplitAssignment[subtaskNum++] = splitList;
			}
		}
		
		return subTaskSplitAssignment;
	}

	public static ExecutionState getAggregateJobVertexState(int[] verticesPerState, int parallelism) {
		if (verticesPerState == null || verticesPerState.length != ExecutionState.values().length) {
			throw new IllegalArgumentException("Must provide an array as large as there are execution states.");
		}

		if (verticesPerState[ExecutionState.FAILED.ordinal()] > 0) {
			return ExecutionState.FAILED;
		}
		if (verticesPerState[ExecutionState.CANCELING.ordinal()] > 0) {
			return ExecutionState.CANCELING;
		}
		else if (verticesPerState[ExecutionState.CANCELED.ordinal()] > 0) {
			return ExecutionState.CANCELED;
		}
		else if (verticesPerState[ExecutionState.RUNNING.ordinal()] > 0) {
			return ExecutionState.RUNNING;
		}
		else if (verticesPerState[ExecutionState.FINISHED.ordinal()] > 0) {
			return verticesPerState[ExecutionState.FINISHED.ordinal()] == parallelism ?
					ExecutionState.FINISHED : ExecutionState.RUNNING;
		}
		else {
			// all else collapses under created
			return ExecutionState.CREATED;
		}
	}

	@Override
	public ArchivedExecutionJobVertex archive() {
		return new ArchivedExecutionJobVertex(this);
	}
}