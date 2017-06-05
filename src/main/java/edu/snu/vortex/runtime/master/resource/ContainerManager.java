/*
 * Copyright (C) 2017 Seoul National University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.snu.vortex.runtime.master.resource;

import edu.snu.vortex.runtime.common.RuntimeAttribute;
import edu.snu.vortex.runtime.common.message.MessageEnvironment;
import edu.snu.vortex.runtime.common.message.MessageSender;
import edu.snu.vortex.runtime.exception.ContainerException;
import org.apache.reef.annotations.audience.DriverSide;
import org.apache.reef.driver.context.ActiveContext;
import org.apache.reef.driver.evaluator.AllocatedEvaluator;
import org.apache.reef.driver.evaluator.EvaluatorRequest;
import org.apache.reef.driver.evaluator.EvaluatorRequestor;
import org.apache.reef.tang.Configuration;

import javax.inject.Inject;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Encapsulates REEF's evaluator management for executors.
 * Serves as a single point of container/executor management in Vortex Runtime.
 * We define a unit of resource a container (an evaluator in REEF), and launch a single executor on each container.
 */
// TODO #60: Specify Types in Requesting Containers
// We need an overall cleanup of this class after #60 is resolved.
@DriverSide
public final class ContainerManager {
  private static final Logger LOG = Logger.getLogger(ContainerManager.class.getName());

  private final EvaluatorRequestor evaluatorRequestor;
  private final MessageEnvironment messageEnvironment;

  /**
   * A map containing a list of executor representations for each container type.
   */
  private final Map<RuntimeAttribute, List<ExecutorRepresenter>> executorsByContainerType;

  /**
   * A map of executor ID to the corresponding {@link ExecutorRepresenter}.
   */
  private final Map<String, ExecutorRepresenter> executorRepresenterMap;

  /**
   * Keeps track of evaluator and context requests.
   */
  private final Map<String, ResourceSpecification> pendingContextIdToResourceSpec;
  private final Map<RuntimeAttribute, List<ResourceSpecification>> pendingContainerRequestsByContainerType;

  @Inject
  public ContainerManager(final EvaluatorRequestor evaluatorRequestor,
                          final MessageEnvironment messageEnvironment) {
    this.evaluatorRequestor = evaluatorRequestor;
    this.messageEnvironment = messageEnvironment;
    this.executorsByContainerType = new HashMap<>();
    this.executorRepresenterMap = new HashMap<>();
    this.pendingContextIdToResourceSpec = new HashMap<>();
    this.pendingContainerRequestsByContainerType = new HashMap<>();
  }

  /**
   * Requests containers/evaluators with the given specifications.
   * @param numToRequest number of containers to request
   * @param resourceSpecification containing the specifications of
   */
  public synchronized void requestContainer(final int numToRequest,
                                            final ResourceSpecification resourceSpecification) {
    // Create a list of executor specifications to be used when containers are allocated.
    final List<ResourceSpecification> resourceSpecificationList = new ArrayList<>(numToRequest);
    for (int i = 0; i < numToRequest; i++) {
      resourceSpecificationList.add(resourceSpecification);
    }
    executorsByContainerType.putIfAbsent(resourceSpecification.getContainerType(), new ArrayList<>(numToRequest));

    // Mark the request as pending with the given specifications.
    pendingContainerRequestsByContainerType.putIfAbsent(resourceSpecification.getContainerType(), new ArrayList<>());
    pendingContainerRequestsByContainerType.get(resourceSpecification.getContainerType())
        .addAll(resourceSpecificationList);

    // Request the evaluators
    evaluatorRequestor.submit(EvaluatorRequest.newBuilder()
        .setNumber(numToRequest)
        .setMemory(resourceSpecification.getMemory())
        .setNumberOfCores(resourceSpecification.getCapacity())
        .build());
  }

  /**
   * Take the necessary actions in container manager once a container a is allocated.
   * @param executorId of the executor to launch on this container.
   * @param allocatedContainer the allocated container.
   * @param executorConfiguration executor related configuration.
   */
  public synchronized void onContainerAllocated(final String executorId,
                                                final AllocatedEvaluator allocatedContainer,
                                                final Configuration executorConfiguration) {
    onContainerAllocated(selectResourceSpecForContainer(), executorId,
        allocatedContainer, executorConfiguration);
  }

  // To be exposed as a public synchronized method in place of the above "onContainerAllocated"
  /**
   * Launches executor once a container is allocated.
   * @param resourceSpecification of the executor to be launched.
   * @param executorId of the executor to be launched.
   * @param allocatedContainer the allocated container.
   * @param executorConfiguration executor related configuration.
   */
  private void onContainerAllocated(final ResourceSpecification resourceSpecification,
                                    final String executorId,
                                    final AllocatedEvaluator allocatedContainer,
                                    final Configuration executorConfiguration) {
    LOG.log(Level.INFO, "Container type (" + resourceSpecification.getContainerType()
        + ") allocated, will be used for [" + executorId + "]");
    pendingContextIdToResourceSpec.put(executorId, resourceSpecification);

    allocatedContainer.submitContext(executorConfiguration);
  }

  /**
   * Selects an executor specification for the executor to be launched on a container.
   * Important! This is a "hack" to get around the inability to mark evaluators with Node Labels in REEF.
   * @return the selected executor specification.
   */
  private ResourceSpecification selectResourceSpecForContainer() {
    ResourceSpecification selectedResourceSpec = null;
    for (final Map.Entry<RuntimeAttribute, List<ResourceSpecification>> entry
        : pendingContainerRequestsByContainerType.entrySet()) {
      if (entry.getValue().size() > 0) {
        selectedResourceSpec = entry.getValue().remove(0);
        break;
      }
    }

    if (selectedResourceSpec != null) {
      return selectedResourceSpec;
    }
    throw new ContainerException(new Throwable("We never requested for an extra container"));
  }

  /**
   * Initializes master's connection to the executor once launched.
   * A representation of the executor to reside in master is created.
   * @param activeContext for the launcned executor.
   */
  public synchronized void onExecutorLaunched(final ActiveContext activeContext) {
    // We set contextId = executorId in VortexDriver when we generate executor configuration.
    final String executorId = activeContext.getId();

    LOG.log(Level.INFO, "[" + executorId + "] is up and running");

    final ResourceSpecification resourceSpec = pendingContextIdToResourceSpec.remove(executorId);

    // Connect to the executor and initiate Master side's executor representation.
    final MessageSender messageSender;
    try {
      messageSender =
          messageEnvironment.asyncConnect(executorId, MessageEnvironment.EXECUTOR_MESSAGE_RECEIVER).get();
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }

    // Create the executor representation.
    final ExecutorRepresenter executorRepresenter =
        new ExecutorRepresenter(executorId, resourceSpec, messageSender, activeContext);

    executorsByContainerType.putIfAbsent(resourceSpec.getContainerType(), new ArrayList<>());
    executorsByContainerType.get(resourceSpec.getContainerType()).add(executorRepresenter);
    executorRepresenterMap.put(executorId, executorRepresenter);
  }

  // TODO #163: Handle Fault Tolerance
  public synchronized void onContainerFailed() {
  }

  public synchronized Map<String, ExecutorRepresenter> getExecutorRepresenterMap() {
    return executorRepresenterMap;
  }

  public synchronized void terminate() {
    executorRepresenterMap.entrySet().stream().forEach(e -> e.getValue().shutDown());
  }
}