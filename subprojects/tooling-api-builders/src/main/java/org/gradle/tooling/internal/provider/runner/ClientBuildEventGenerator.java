/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.tooling.internal.provider.runner;

import com.google.common.collect.ImmutableList;
import org.gradle.internal.Cast;
import org.gradle.internal.build.event.BuildEventSubscriptions;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationListener;
import org.gradle.internal.operations.OperationFinishEvent;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.internal.operations.OperationProgressEvent;
import org.gradle.internal.operations.OperationStartEvent;
import org.gradle.tooling.internal.protocol.events.InternalOperationDescriptor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates progress events to send back to the client,
 */
public class ClientBuildEventGenerator implements BuildOperationListener {
    private final BuildOperationListener fallback;
    private final List<Mapper> mappers;
    private final Map<OperationIdentifier, Operation> running = new ConcurrentHashMap<>();

    public ClientBuildEventGenerator(ProgressEventConsumer progressEventConsumer, BuildEventSubscriptions subscriptions, List<? extends BuildEventMapper<?, ?>> mappers, BuildOperationListener fallback) {
        this.fallback = fallback;
        ImmutableList.Builder<Mapper> builder = ImmutableList.builder();
        for (BuildEventMapper<?, ?> mapper : mappers) {
            if (mapper.isEnabled(subscriptions)) {
                builder.add(new Enabled(mapper, progressEventConsumer));
            } else {
                builder.add(new Disabled(mapper));
            }
        }
        this.mappers = builder.build();
    }

    @Override
    public void started(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent) {
        for (Mapper mapper : mappers) {
            Operation operation = mapper.accept(buildOperation);
            if (operation != null) {
                Operation previous = running.put(buildOperation.getId(), operation);
                if (previous != null) {
                    throw new IllegalStateException("Operation " + buildOperation.getId() + " already started.");
                }
                operation.generateStartEvent(buildOperation, startEvent);
                return;
            }
        }
        // Not recognized, so generate generic events, if appropriate
        fallback.started(buildOperation, startEvent);
    }

    @Override
    public void progress(OperationIdentifier operationIdentifier, OperationProgressEvent progressEvent) {
        if (running.containsKey(operationIdentifier)) {
            return;
        }
        fallback.progress(operationIdentifier, progressEvent);
    }

    @Override
    public void finished(BuildOperationDescriptor buildOperation, OperationFinishEvent finishEvent) {
        Operation operation = running.remove(buildOperation.getId());
        if (operation != null) {
            operation.generateFinishEvent(buildOperation, finishEvent);
            return;
        }
        // Not recognized, so generate generic events, if appropriate
        fallback.finished(buildOperation, finishEvent);
    }

    private static abstract class Operation {
        public abstract void generateStartEvent(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent);

        public abstract void generateFinishEvent(BuildOperationDescriptor buildOperation, OperationFinishEvent finishEvent);
    }

    private static class EnabledOperation extends Operation {
        private final InternalOperationDescriptor descriptor;
        private final BuildEventMapper<Object, InternalOperationDescriptor> mapper;
        private final ProgressEventConsumer progressEventConsumer;

        public EnabledOperation(InternalOperationDescriptor descriptor, BuildEventMapper<Object, InternalOperationDescriptor> mapper, ProgressEventConsumer progressEventConsumer) {
            this.descriptor = descriptor;
            this.mapper = mapper;
            this.progressEventConsumer = progressEventConsumer;
        }

        @Override
        public void generateStartEvent(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent) {
            progressEventConsumer.started(mapper.createStartedEvent(descriptor, buildOperation.getDetails(), startEvent));
        }

        @Override
        public void generateFinishEvent(BuildOperationDescriptor buildOperation, OperationFinishEvent finishEvent) {
            progressEventConsumer.finished(mapper.createFinishedEvent(descriptor, finishEvent));
        }
    }


    private static final Operation DISABLED_OPERATION = new Operation() {
        @Override
        public void generateStartEvent(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent) {
        }

        @Override
        public void generateFinishEvent(BuildOperationDescriptor buildOperation, OperationFinishEvent finishEvent) {
        }
    };

    private static abstract class Mapper {
        @Nullable
        public abstract Operation accept(BuildOperationDescriptor buildOperation);
    }

    private static class Enabled extends Mapper {
        private final BuildEventMapper<Object, InternalOperationDescriptor> mapper;
        private final ProgressEventConsumer progressEventConsumer;

        public Enabled(BuildEventMapper<?, ?> mapper, ProgressEventConsumer progressEventConsumer) {
            this.mapper = Cast.uncheckedCast(mapper);
            this.progressEventConsumer = progressEventConsumer;
        }

        @Nullable
        @Override
        public Operation accept(BuildOperationDescriptor buildOperation) {
            if (mapper.getDetailsType().isInstance(buildOperation.getDetails())) {
                OperationIdentifier parentId = progressEventConsumer.findStartedParentId(buildOperation);
                InternalOperationDescriptor descriptor = mapper.createDescriptor(buildOperation.getDetails(), buildOperation, parentId);
                return new EnabledOperation(descriptor, mapper, progressEventConsumer);
            } else {
                return null;
            }
        }
    }

    private static class Disabled extends Mapper {
        private final Class<?> detailsType;

        public Disabled(BuildEventMapper<?, ?> mapper) {
            this.detailsType = mapper.getDetailsType();
        }

        @Nullable
        @Override
        public Operation accept(BuildOperationDescriptor buildOperation) {
            if (detailsType.isInstance(buildOperation.getDetails())) {
                return DISABLED_OPERATION;
            } else {
                return null;
            }
        }
    }
}
