/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.common.ConditionBuilder;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;
import io.strimzi.api.kafka.model.topic.KafkaTopicStatusBuilder;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.model.InvalidResourceException;
import io.strimzi.operator.common.model.StatusDiff;
import io.strimzi.operator.common.model.StatusUtils;
import io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder;
import io.strimzi.operator.topic.model.Either;
import io.strimzi.operator.topic.model.KubeRef;
import io.strimzi.operator.topic.model.Pair;
import io.strimzi.operator.topic.model.PartitionedByError;
import io.strimzi.operator.topic.model.ReconcilableTopic;
import io.strimzi.operator.topic.model.TopicOperatorException;
import io.strimzi.operator.topic.model.TopicState;
import io.strimzi.operator.topic.model.UncheckedInterruptedException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.AlterConfigsResult;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.CreatePartitionsResult;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.PartitionReassignment;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicCollection;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.errors.TopicDeletionDisabledException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.strimzi.api.kafka.model.topic.ReplicasChangeState.ONGOING;
import static io.strimzi.api.kafka.model.topic.ReplicasChangeState.PENDING;
import static io.strimzi.operator.topic.TopicOperatorUtil.partitionedByError;
import static io.strimzi.operator.topic.TopicOperatorUtil.partitions;
import static io.strimzi.operator.topic.TopicOperatorUtil.replicas;
import static io.strimzi.operator.topic.TopicOperatorUtil.startReconciliationTimer;
import static io.strimzi.operator.topic.TopicOperatorUtil.stopReconciliationTimer;
import static io.strimzi.operator.topic.TopicOperatorUtil.topicNames;
import static java.util.function.UnaryOperator.identity;

/**
 * Controller that reconciles batches of {@link KafkaTopic} events.
 */
@SuppressWarnings({"checkstyle:ClassFanOutComplexity", "checkstyle:ClassDataAbstractionCoupling"})
public class BatchingTopicController {
    static final ReconciliationLogger LOGGER = ReconciliationLogger.create(BatchingTopicController.class);

    static final String FINALIZER = "strimzi.io/topic-operator";
    static final String AUTO_CREATE_TOPICS_ENABLE = "auto.create.topics.enable";
    static final String MIN_INSYNC_REPLICAS = "min.insync.replicas";
    
    private static final int BROKER_DEFAULT = -1;
    private final boolean useFinalizer;
    private final boolean enableAdditionalMetrics;

    private final Admin admin;

    private final TopicOperatorConfig config;
    private final Map<String, String> selector;

    private final KubernetesClient kubeClient;

    // Key: topic name, Value: The KafkaTopics known to manage that topic
    /* test */ final Map<String, List<KubeRef>> topics = new HashMap<>();

    private final TopicOperatorMetricsHolder metrics;
    private final String namespace;
    private final ReplicasChangeHandler replicasChangeHandler;

    BatchingTopicController(TopicOperatorConfig config,
                            Map<String, String> selector,
                            Admin admin,
                            KubernetesClient kubeClient,
                            TopicOperatorMetricsHolder metrics, 
                            ReplicasChangeHandler replicasChangeHandler) {
        this.config = config;
        this.selector = Objects.requireNonNull(selector);
        this.useFinalizer = config.useFinalizer();
        this.admin = admin;

        var skipClusterConfigReview = config.skipClusterConfigReview();
        if (!skipClusterConfigReview) {
            // Get the config of some broker and check whether auto topic creation is enabled
            Optional<String> autoCreateValue = getClusterConfig(admin, AUTO_CREATE_TOPICS_ENABLE);
            if (autoCreateValue.filter("true"::equals).isPresent()) {
                LOGGER.warnOp(
                      "It is recommended that " + AUTO_CREATE_TOPICS_ENABLE + " is set to 'false' " +
                            "to avoid races between the operator and Kafka applications auto-creating topics");
            }
        }

        this.kubeClient = kubeClient;
        this.metrics = metrics;
        this.namespace = config.namespace();
        this.enableAdditionalMetrics = config.enableAdditionalMetrics();
        this.replicasChangeHandler = replicasChangeHandler;
    }

    /**
     * Retrieves the specified configuration value for a Kafka cluster.
     *
     * This method queries the Kafka cluster to obtain the configuration value associated with the given name.
     * It iterates through all nodes (brokers) in the cluster, requesting their configurations, and returns the
     * value of the configuration if found. The search stops at the first occurrence of the configuration name
     * across all nodes, assuming uniform configuration across the cluster.
     *
     * @param admin The {@link Admin} client used to interact with the Kafka cluster.
     * @param name The name of the configuration to retrieve.
     * @return An {@link Optional} {@link String} containing the value of the requested configuration if found, or an empty Optional if not.
     * @throws RuntimeException if there is an error during the operation. This exception wraps the underlying exception's message.
     */
    private static Optional<String> getClusterConfig(Admin admin, String name) {
        try {
            DescribeClusterResult describeClusterResult = admin.describeCluster();
            var nodes = describeClusterResult.nodes().get();
            Map<ConfigResource, KafkaFuture<Map<ConfigResource, Config>>> futures = new HashMap<>();
            for (var node : nodes) {
                ConfigResource nodeResource = new ConfigResource(ConfigResource.Type.BROKER, node.idString());
                futures.put(nodeResource, admin.describeConfigs(Set.of(nodeResource)).all());
            }
            for (var entry : futures.entrySet()) {
                var nodeConfig = entry.getValue().get().get(entry.getKey());
                var configEntry = nodeConfig.get(name);
                return Optional.of(configEntry.value());
            }
            return Optional.empty();
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get cluster configuration: " + e.getMessage());
        }
    }
    
    private static boolean isForDeletion(KafkaTopic kt) {
        if (kt.getMetadata().getDeletionTimestamp() != null) {
            var deletionTimestamp = StatusUtils.isoUtcDatetime(kt.getMetadata().getDeletionTimestamp());
            var now = Instant.now();
            return !deletionTimestamp.isAfter(now);
        } else {
            return false;
        }
    }

    static String resourceVersion(KafkaTopic kt) {
        return kt == null || kt.getMetadata() == null ? "null" : kt.getMetadata().getResourceVersion();
    }

    private List<ReconcilableTopic> addOrRemoveFinalizer(boolean useFinalizer, List<ReconcilableTopic> reconcilableTopics) {
        List<ReconcilableTopic> collect = reconcilableTopics.stream()
                .map(reconcilableTopic ->
                        new ReconcilableTopic(reconcilableTopic.reconciliation(), useFinalizer
                            ? addFinalizer(reconcilableTopic) : removeFinalizer(reconcilableTopic), reconcilableTopic.topicName()))
                .collect(Collectors.toList());
        LOGGER.traceOp("{} {} topics", useFinalizer ? "Added finalizers to" : "Removed finalizers from", reconcilableTopics.size());
        return collect;
    }

    private KafkaTopic addFinalizer(ReconcilableTopic reconcilableTopic) {
        if (!reconcilableTopic.kt().getMetadata().getFinalizers().contains(FINALIZER)) {
            LOGGER.debugCr(reconcilableTopic.reconciliation(), "Adding finalizer {}", FINALIZER);
            var timerSample = TopicOperatorUtil.startExternalRequestTimer(metrics, enableAdditionalMetrics);
            KafkaTopic edit = Crds.topicOperation(kubeClient).resource(reconcilableTopic.kt()).edit(old ->
                    new KafkaTopicBuilder(old).editOrNewMetadata().addToFinalizers(FINALIZER).endMetadata().build());
            TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::addFinalizerTimer, enableAdditionalMetrics, namespace);
            LOGGER.traceCr(reconcilableTopic.reconciliation(), "Added finalizer {}, resourceVersion now {}", FINALIZER, resourceVersion(edit));
            return edit;
        }
        return reconcilableTopic.kt();
    }

    private KafkaTopic removeFinalizer(ReconcilableTopic reconcilableTopic) {
        if (reconcilableTopic.kt().getMetadata().getFinalizers().contains(FINALIZER)) {
            LOGGER.debugCr(reconcilableTopic.reconciliation(), "Removing finalizer {}", FINALIZER);
            var timerSample = TopicOperatorUtil.startExternalRequestTimer(metrics, enableAdditionalMetrics);
            var result = Crds.topicOperation(kubeClient).resource(reconcilableTopic.kt()).edit(old ->
                    new KafkaTopicBuilder(old).editOrNewMetadata().removeFromFinalizers(FINALIZER).endMetadata().build());
            TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::removeFinalizerTimer, enableAdditionalMetrics, namespace);
            LOGGER.traceCr(reconcilableTopic.reconciliation(), "Removed finalizer {}, resourceVersion now {}", FINALIZER, resourceVersion(result));
            return result;
        } else {
            return reconcilableTopic.kt();
        }
    }

    private Either<TopicOperatorException, Boolean> validate(ReconcilableTopic reconcilableTopic) {
        var doReconcile = Either.<TopicOperatorException, Boolean>ofRight(true);
        doReconcile = doReconcile.flatMapRight((Boolean x) -> x ? validateUnchangedTopicName(reconcilableTopic) : Either.ofRight(false));
        doReconcile = doReconcile.mapRight((Boolean x) -> x ? rememberTopic(reconcilableTopic) : false);
        return doReconcile;
    }

    private boolean rememberTopic(ReconcilableTopic reconcilableTopic) {
        String tn = reconcilableTopic.topicName();
        var existing = topics.computeIfAbsent(tn, k -> new ArrayList<>(1));
        KubeRef thisRef = new KubeRef(reconcilableTopic.kt());
        if (!existing.contains(thisRef)) {
            existing.add(thisRef);
        }
        return true;
    }

    private Either<TopicOperatorException, Boolean> validateSingleManagingResource(ReconcilableTopic reconcilableTopic) {
        String tn = reconcilableTopic.topicName();
        var existing = topics.get(tn);
        KubeRef thisRef = new KubeRef(reconcilableTopic.kt());
        if (existing.size() != 1) {
            var byCreationTime = existing.stream().sorted(Comparator.comparing(KubeRef::creationTime)).toList();

            var oldest = byCreationTime.get(0);
            var nextOldest = byCreationTime.size() >= 2 ? byCreationTime.get(1) : null;
            TopicOperatorException e = new TopicOperatorException.ResourceConflict("Managed by " + oldest);
            if (nextOldest == null) {
                // This is only resource for that topic => it is the unique oldest
                return Either.ofRight(true);
            } else if (thisRef.equals(oldest) && nextOldest.creationTime() != oldest.creationTime()) {
                // This resource is the unique oldest, so it's OK.
                // The others will eventually get reconciled and put into ResourceConflict
                return Either.ofRight(true);
            } else if (thisRef.equals(oldest)
                    && reconcilableTopic.kt().getStatus() != null
                    && reconcilableTopic.kt().getStatus().getConditions() != null
                    && reconcilableTopic.kt().getStatus().getConditions().stream().anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()))) {
                return Either.ofRight(true);
            } else {
                // Return an error putting this resource into ResourceConflict
                return Either.ofLeft(e);
            }

        }
        return Either.ofRight(true);
    }

    /* test */ static boolean matchesSelector(Map<String, String> selector, Map<String, String> resourceLabels) {
        if (!selector.isEmpty()) {
            for (var selectorEntry : selector.entrySet()) {
                String resourceValue = resourceLabels.get(selectorEntry.getKey());
                if (resourceValue == null
                        || !resourceValue.equals(selectorEntry.getValue())) {
                    return false;
                }
            }
        }
        return resourceLabels.keySet().containsAll(selector.keySet());
    }

    private static Either<TopicOperatorException, Boolean> validateUnchangedTopicName(ReconcilableTopic reconcilableTopic) {
        if (reconcilableTopic.kt().getStatus() != null
                && reconcilableTopic.kt().getStatus().getTopicName() != null
                && !TopicOperatorUtil.topicName(reconcilableTopic.kt()).equals(reconcilableTopic.kt().getStatus().getTopicName())) {
            return Either.ofLeft(new TopicOperatorException.NotSupported("Changing spec.topicName is not supported"
            ));
        }
        return Either.ofRight(true);
    }

    private PartitionedByError<ReconcilableTopic, Void> createTopics(List<ReconcilableTopic> kts) {
        Map<ReconcilableTopic, TopicOperatorException> newTopicsErrors = new HashMap<>();
        var newTopics = kts.stream().map(reconcilableTopic -> {
            try {
                return buildNewTopic(reconcilableTopic.kt(), reconcilableTopic.topicName());
            } catch (InvalidResourceException e) {
                newTopicsErrors.put(reconcilableTopic, new TopicOperatorException.InternalError(e));
                return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toSet());

        LOGGER.debugOp("Admin.createTopics({})", newTopics);
        var timerSample = TopicOperatorUtil.startExternalRequestTimer(metrics, enableAdditionalMetrics);
        CreateTopicsResult ctr = admin.createTopics(newTopics);
        ctr.all().whenComplete((i, e) -> {
            TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::createTopicsTimer, enableAdditionalMetrics, namespace);
            if (e != null) {
                LOGGER.traceOp("Admin.createTopics({}) failed with {}", newTopics, String.valueOf(e));
            } else {
                LOGGER.traceOp("Admin.createTopics({}) completed", newTopics);
            }
        });
        Map<String, KafkaFuture<Void>> values = ctr.values();
        return partitionedByError(kts.stream().map(reconcilableTopic -> {
            if (newTopicsErrors.containsKey(reconcilableTopic)) {
                return new Pair<>(reconcilableTopic, Either.ofLeft(newTopicsErrors.get(reconcilableTopic)));
            }
            try {
                values.get(reconcilableTopic.topicName()).get();
                reconcilableTopic.kt().setStatus(new KafkaTopicStatusBuilder()
                    .withTopicId(ctr.topicId(reconcilableTopic.topicName()).get().toString()).build());
                return new Pair<>(reconcilableTopic, Either.ofRight((null)));
            } catch (ExecutionException e) {
                if (e.getCause() != null && e.getCause() instanceof TopicExistsException) {
                    // we treat this as a success, the next reconciliation checks the configuration
                    return new Pair<>(reconcilableTopic, Either.ofRight((null)));
                } else {
                    return new Pair<>(reconcilableTopic, Either.ofLeft(handleAdminException(e)));
                }
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }
        }));
    }

    private static TopicOperatorException handleAdminException(ExecutionException e) {
        var cause = e.getCause();
        if (cause instanceof ApiException) {
            return new TopicOperatorException.KafkaError((ApiException) cause);
        } else {
            return new TopicOperatorException.InternalError(cause);
        }
    }

    private static NewTopic buildNewTopic(KafkaTopic kt, String topicName) {
        return new NewTopic(topicName, partitions(kt), replicas(kt)).configs(buildConfigsMap(kt));
    }

    private static Map<String, String> buildConfigsMap(KafkaTopic kt) {
        Map<String, String> configs = new HashMap<>();
        if (hasConfig(kt)) {
            for (var entry : kt.getSpec().getConfig().entrySet()) {
                configs.put(entry.getKey(), configValueAsString(entry.getKey(), entry.getValue()));
            }
        }
        return configs;
    }

    private static String configValueAsString(String key, Object value) {
        String valueStr;
        if (value == null) {
            valueStr = null;
        } else if (value instanceof String
                || value instanceof Boolean) {
            valueStr = value.toString();
        } else if (value instanceof Number) {
            valueStr = value.toString();
        } else if (value instanceof List) {
            valueStr = ((List<?>) value).stream()
                    .map(v -> BatchingTopicController.configValueAsString(key, v))
                    .collect(Collectors.joining(","));
        } else {
            throw new InvalidResourceException("Invalid value for topic config '" + key + "': " + value);
        }
        return valueStr;
    }

    /**
     * @param topics The topics to reconcile
     * @throws InterruptedException If the thread was interrupted while blocking
     */
    void onUpdate(List<ReconcilableTopic> topics) throws InterruptedException {
        try {
            updateInternal(topics);
        } catch (UncheckedInterruptedException e) {
            throw e.getCause();
        } catch (KubernetesClientException e) {
            if (e.getCause() instanceof InterruptedIOException) {
                throw new InterruptedException();
            } else {
                throw e;
            }
        }
    }

    private void updateInternal(List<ReconcilableTopic> batch) {
        LOGGER.debugOp("Reconciling batch {}", batch);
        // process deletions
        var partitionedByDeletion = batch.stream().filter(reconcilableTopic -> {
            var kt = reconcilableTopic.kt();
            if (!matchesSelector(selector, kt.getMetadata().getLabels())) {
                forgetTopic(reconcilableTopic);
                LOGGER.debugCr(reconcilableTopic.reconciliation(), "Ignoring KafkaTopic with labels {} not selected by selector {}",
                    kt.getMetadata().getLabels(), selector);
                return false;
            }
            return true;
        }).collect(Collectors.partitioningBy(reconcilableTopic -> {
            boolean forDeletion = isForDeletion(reconcilableTopic.kt());
            if (forDeletion) {
                LOGGER.debugCr(reconcilableTopic.reconciliation(), "metadata.deletionTimestamp is set, so try onDelete()");
            }
            return forDeletion;
        }));

        var toBeDeleted = partitionedByDeletion.get(true);
        if (!toBeDeleted.isEmpty()) {
            deleteInternal(toBeDeleted, false);
        }

        // process remaining
        Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results = new HashMap<>();
        var remainingAfterDeletions = partitionedByDeletion.get(false);
        var timerSamples = remainingAfterDeletions.stream().collect(
            Collectors.toMap(identity(), rt -> startReconciliationTimer(metrics)));

        var partitionedByManaged = remainingAfterDeletions.stream().collect(
            Collectors.partitioningBy(reconcilableTopic -> TopicOperatorUtil.isManaged(reconcilableTopic.kt())));

        // process unmanaged
        var unmanaged = partitionedByManaged.get(false);
        unmanaged.forEach(rt -> putResult(results, rt, Either.ofRight(null)));

        // process paused
        var managed = partitionedByManaged.get(true);
        var partitionedByPaused = validateManagedTopics(managed).stream().filter(hasTopicSpec)
            .collect(Collectors.partitioningBy(reconcilableTopic -> TopicOperatorUtil.isPaused(reconcilableTopic.kt())));
        var paused = partitionedByPaused.get(true);
        paused.forEach(rt -> putResult(results, rt, Either.ofRight(null)));

        // process managed not paused
        var mayNeedUpdate = partitionedByPaused.get(false);
        addOrRemoveFinalizer(useFinalizer, mayNeedUpdate);

        // figure out necessary updates
        var currentStatesOrError = describeTopic(mayNeedUpdate);
        createMissingTopics(results, currentStatesOrError);
        List<Pair<ReconcilableTopic, Collection<AlterConfigOp>>> someAlterConfigs = configChanges(results, currentStatesOrError);
        List<Pair<ReconcilableTopic, NewPartitions>> someCreatePartitions = partitionChanges(results, currentStatesOrError);

        // execute those updates
        var alterConfigsResults = alterConfigs(someAlterConfigs);
        var createPartitionsResults = createPartitions(someCreatePartitions);
        var checkReplicasChangesResults = checkReplicasChanges(batch, currentStatesOrError);

        // update statuses
        accumulateResults(results, alterConfigsResults, createPartitionsResults, checkReplicasChangesResults);
        updateStatuses(results);
        metrics.reconciliationsCounter(namespace).increment(results.size());
        timerSamples.keySet().forEach(rt -> stopReconciliationTimer(metrics, timerSamples.get(rt), namespace));
        LOGGER.traceOp("Reconciled batch of {} KafkaTopics", results.size());
    }

    /**
     * Check topic replicas changes.
     * 
     * <p>If Cruise Control integration is disabled, it simply returns an error for each change.</p>
     * 
     * <p>
     * If Cruise Control integration is enabled, it runs the following operations in order:
     * <ol>
     *     <li>Request new and pending changes</li>
     *     <li>Check the state of ongoing changes</li>
     *     <li>Complete pending but completed changes (*)</li>
     * </ol>
     * (*) A pending change needs to be completed by the reconciliation when it is unknown to Cruise Control
     * due to a restart, but the task actually completed successfully (i.e. the new replication factor
     * was applied), or when the user reverts an invalid replicas change to the previous value.
     * <p>
     *
     * @param reconcilableTopics Reconcilable topics from Kube
     * @param currentStatesOrError Current topic state or error from Kafka
     * @return Reconcilable topics partitioned by error
     */
    /* test */ PartitionedByError<ReconcilableTopic, Void> checkReplicasChanges(List<ReconcilableTopic> reconcilableTopics,
                                                                                PartitionedByError<ReconcilableTopic, TopicState> currentStatesOrError) {
        var differentRfResults = findDifferentRf(currentStatesOrError);
        Stream<Pair<ReconcilableTopic, Either<TopicOperatorException, Void>>> successStream;
            
        if (config.cruiseControlEnabled()) {
            var results = new HashSet<ReconcilableTopic>();
            var differentRfMap = differentRfResults.ok().map(Pair::getKey).collect(Collectors.toList())
                .stream().collect(Collectors.toMap(ReconcilableTopic::topicName, Function.identity()));
            
            var pending = topicsMatching(reconcilableTopics, this::isPendingReplicasChange);
            var brandNew = differentRfMap.values().stream()
                .filter(rt -> !isPendingReplicasChange(rt.kt()) && !isOngoingReplicasChange(rt.kt()))
                .collect(Collectors.toList());
            pending.addAll(brandNew);
            warnTooLargeMinIsr(pending);
            results.addAll(replicasChangeHandler.requestPendingChanges(pending));

            var ongoing = topicsMatching(reconcilableTopics, this::isOngoingReplicasChange);
            results.addAll(replicasChangeHandler.requestOngoingChanges(ongoing));
            
            var completed = pending.stream()
                .filter(rt -> !differentRfMap.containsKey(rt.topicName()) && !isFailedReplicasChange(rt.kt()))
                .collect(Collectors.toList());
            var reverted = pending.stream()
                .filter(rt -> !differentRfMap.containsKey(rt.topicName()) && isFailedReplicasChange(rt.kt()))
                .collect(Collectors.toList());
            completed.addAll(reverted);
            if (!completed.isEmpty()) {
                LOGGER.debugOp("Pending but completed replicas changes, Topics: {}", topicNames(completed));
            }
            completed.forEach(reconcilableTopic -> {
                reconcilableTopic.kt().getStatus().setReplicasChange(null);
            });
            results.addAll(completed);
            
            successStream = results.stream().map(reconcilableTopic -> new Pair<>(reconcilableTopic, Either.ofRight(null)));
            
        } else {
            successStream = differentRfResults.ok().map(pair -> {
                var reconcilableTopic = pair.getKey();
                var specReplicas = replicas(reconcilableTopic.kt());
                var partitions = pair.getValue().partitionsWithDifferentRfThan(specReplicas);
                return new Pair<>(reconcilableTopic, Either.ofLeft(new TopicOperatorException.NotSupported(
                    "Replication factor change not supported, but required for partitions " + partitions)));
            });
        }

        Stream<Pair<ReconcilableTopic, Either<TopicOperatorException, Void>>> errorStream = differentRfResults.errors()
            .map(pair -> new Pair<>(pair.getKey(), Either.ofLeft(pair.getValue())));
        
        return partitionedByError(Stream.concat(successStream, errorStream));
    }

    private PartitionedByError<ReconcilableTopic, TopicState> findDifferentRf(PartitionedByError<ReconcilableTopic, TopicState> currentStatesOrError) {
        var apparentlyDifferentRf = currentStatesOrError.ok().filter(pair -> {
            var reconcilableTopic = pair.getKey();
            var currentState = pair.getValue();
            return reconcilableTopic.kt().getSpec().getReplicas() != null
                && currentState.uniqueReplicationFactor() != reconcilableTopic.kt().getSpec().getReplicas();
        }).toList();

        return partitionedByError(filterByReassignmentTargetReplicas(apparentlyDifferentRf).stream());
    }

    /**
     * Cruise Control allows scale down of the replication factor under the min.insync.replicas value, which can cause 
     * disruption to producers with acks=all. When this happens, the Topic Operator won't block the operation, but will 
     * just log a warning, because the KafkaRoller ignores topics with RF < minISR, and they don't even show up as under 
     * replicated in Kafka metrics.
     *
     * @param reconcilableTopics Reconcilable topic.
     */
    private void warnTooLargeMinIsr(List<ReconcilableTopic> reconcilableTopics) {
        if (config.skipClusterConfigReview()) {
            // This method is for internal configurations. So skipping.
            return;
        }

        Optional<String> clusterMinIsr = getClusterConfig(admin, MIN_INSYNC_REPLICAS);
        for (ReconcilableTopic reconcilableTopic : reconcilableTopics) {
            var topicConfig = reconcilableTopic.kt().getSpec().getConfig();
            if (topicConfig != null) {
                Integer topicMinIsr = (Integer) topicConfig.get(MIN_INSYNC_REPLICAS);
                int minIsr = topicMinIsr != null ? topicMinIsr : clusterMinIsr.isPresent() ? Integer.parseInt(clusterMinIsr.get()) : 1;
                int targetRf = reconcilableTopic.kt().getSpec().getReplicas();
                if (targetRf < minIsr) {
                    LOGGER.warnCr(reconcilableTopic.reconciliation(),
                        "The target replication factor ({}) is below the configured {} ({})", targetRf, MIN_INSYNC_REPLICAS, minIsr);
                }
            }
        }
    }

    private List<ReconcilableTopic> topicsMatching(List<ReconcilableTopic> reconcilableTopics, Predicate<KafkaTopic> status) {
        return reconcilableTopics.stream().filter(rt -> status.test(rt.kt())).collect(Collectors.toList());
    }

    private boolean isPendingReplicasChange(KafkaTopic kafkaTopic) {
        return TopicOperatorUtil.hasReplicasChange(kafkaTopic.getStatus())
            && kafkaTopic.getStatus().getReplicasChange().getState() == PENDING
            && kafkaTopic.getStatus().getReplicasChange().getSessionId() == null;
    }

    private boolean isOngoingReplicasChange(KafkaTopic kafkaTopic) {
        return TopicOperatorUtil.hasReplicasChange(kafkaTopic.getStatus())
            && kafkaTopic.getStatus().getReplicasChange().getState() == ONGOING
            && kafkaTopic.getStatus().getReplicasChange().getSessionId() != null;
    }

    private boolean isFailedReplicasChange(KafkaTopic kafkaTopic) {
        return TopicOperatorUtil.hasReplicasChange(kafkaTopic.getStatus())
            && kafkaTopic.getStatus().getReplicasChange().getState() == PENDING
            && kafkaTopic.getStatus().getReplicasChange().getMessage() != null;
    }

    private Predicate<ReconcilableTopic> hasTopicSpec = reconcilableTopic -> {
        var hasSpec = reconcilableTopic.kt().getSpec() != null;
        if (!hasSpec) {
            LOGGER.warnCr(reconcilableTopic.reconciliation(), "Topic has no spec.");
        }
        return hasSpec;
    };

    private List<ReconcilableTopic> validateManagedTopics(List<ReconcilableTopic> reconcilableTopics) {
        var mayNeedUpdate = reconcilableTopics.stream().filter(reconcilableTopic -> {
            var e = validate(reconcilableTopic);
            if (e.isRightEqual(false)) {
                // Do nothing
                return false;
            } else if (e.isRightEqual(true)) {
                return true;
            } else {
                updateStatusForException(reconcilableTopic, e.left());
                return false;
            }
        }).filter(reconcilableTopic -> {
            var e = validateSingleManagingResource(reconcilableTopic);
            if (e.isRightEqual(false)) {
                // Do nothing
                return false;
            } else if (e.isRightEqual(true)) {
                return true;
            } else {
                updateStatusForException(reconcilableTopic, e.left());
                return false;
            }
        }).toList();
        return mayNeedUpdate;
    }

    private static void putResult(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results, ReconcilableTopic key, Either<TopicOperatorException, Object> result) {
        results.compute(key, (k, v) -> {
            if (v == null) {
                return result;
            } else if (v.isRight()) {
                return result;
            } else {
                return v;
            }
        });
    }

    private void createMissingTopics(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results, PartitionedByError<ReconcilableTopic, TopicState> currentStatesOrError) {
        var partitionedByUnknownTopic = currentStatesOrError.errors().collect(Collectors.partitioningBy(pair -> {
            var ex = pair.getValue();
            return ex instanceof TopicOperatorException.KafkaError
                    && ex.getCause() instanceof UnknownTopicOrPartitionException;
        }));
        partitionedByUnknownTopic.get(false).forEach(pair -> putResult(results, pair.getKey(), Either.ofLeft(pair.getValue())));

        if (!partitionedByUnknownTopic.get(true).isEmpty()) {
            var createResults = createTopics(partitionedByUnknownTopic.get(true).stream().map(Pair::getKey).toList());
            createResults.ok().forEach(pair -> putResult(results, pair.getKey(), Either.ofRight(null)));
            createResults.errors().forEach(pair -> putResult(results, pair.getKey(), Either.ofLeft(pair.getValue())));
        }
    }

    private void updateStatuses(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results) {
        // Update statues with the overall results.
        results.entrySet().stream().forEach(entry -> {
            var reconcilableTopic = entry.getKey();
            var either = entry.getValue();
            if (either.isRight()) {
                updateStatusForSuccess(reconcilableTopic);
            } else {
                updateStatusForException(reconcilableTopic, either.left());
            }
        });
        LOGGER.traceOp("Updated status of {} KafkaTopics", results.size());
    }

    private void accumulateResults(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results,
                                   PartitionedByError<ReconcilableTopic, Void> alterConfigsResults,
                                   PartitionedByError<ReconcilableTopic, Void> createPartitionsResults,
                                   PartitionedByError<ReconcilableTopic, Void> replicasChangeResults) {
        // add the successes to the results
        alterConfigsResults.ok().forEach(pair -> putResult(results, pair.getKey(), Either.ofRight(null)));
        createPartitionsResults.ok().forEach(pair -> putResult(results, pair.getKey(), Either.ofRight(null)));
        replicasChangeResults.ok().forEach(pair -> putResult(results, pair.getKey(), Either.ofRight(null)));

        // add to errors (potentially overwriting some successes, e.g. if configs succeeded but partitions failed)
        alterConfigsResults.errors().forEach(pair -> putResult(results, pair.getKey(), Either.ofLeft(pair.getValue())));
        createPartitionsResults.errors().forEach(pair -> putResult(results, pair.getKey(), Either.ofLeft(pair.getValue())));
        replicasChangeResults.errors().forEach(pair -> putResult(results, pair.getKey(), Either.ofLeft(pair.getValue())));
    }

    private List<Pair<ReconcilableTopic, Collection<AlterConfigOp>>> configChanges(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results, PartitionedByError<ReconcilableTopic, TopicState> currentStatesOrError) {
        // Determine config changes
        Map<Boolean, List<Pair<ReconcilableTopic, Collection<AlterConfigOp>>>> alterConfigs = currentStatesOrError.ok().map(pair -> {
            var reconcilableTopic = pair.getKey();
            var currentState = pair.getValue();
            // determine config changes
            return new Pair<>(reconcilableTopic, buildAlterConfigOps(reconcilableTopic.reconciliation(), reconcilableTopic.kt(), currentState.configs()));
        }).collect(Collectors.partitioningBy(pair -> pair.getValue().isEmpty()));

        // add topics which don't require configs changes to the results (may be overwritten later)
        alterConfigs.get(true).forEach(pair -> putResult(results, pair.getKey(), Either.ofRight(null)));
        var someAlterConfigs = alterConfigs.get(false);
        return someAlterConfigs;
    }

    private static List<Pair<ReconcilableTopic, NewPartitions>> partitionChanges(Map<ReconcilableTopic, Either<TopicOperatorException, Object>> results, PartitionedByError<ReconcilableTopic, TopicState> currentStatesOrError) {
        // Determine partition changes
        PartitionedByError<ReconcilableTopic, NewPartitions> newPartitionsOrError = partitionedByError(currentStatesOrError.ok().map(pair -> {
            var reconcilableTopic = pair.getKey();
            var currentState = pair.getValue();
            // determine config changes
            return new Pair<>(reconcilableTopic, buildNewPartitions(reconcilableTopic.reconciliation(), reconcilableTopic.kt(), currentState.numPartitions()));
        }));
        newPartitionsOrError.errors().forEach(pair -> putResult(results, pair.getKey(), Either.ofLeft(pair.getValue())));

        var createPartitions = newPartitionsOrError.ok().collect(
                Collectors.partitioningBy(pair -> pair.getValue() == null));
        // add topics which don't require partitions changes to the results (may be overwritten later)
        createPartitions.get(true).forEach(pair -> putResult(results, pair.getKey(), Either.ofRight(null)));
        var someCreatePartitions = createPartitions.get(false);
        return someCreatePartitions;
    }

    private List<Pair<ReconcilableTopic, Either<TopicOperatorException, TopicState>>> filterByReassignmentTargetReplicas(
            List<Pair<ReconcilableTopic, TopicState>> apparentlyDifferentRfTopics) {
        if (apparentlyDifferentRfTopics.isEmpty()) {
            return List.of();
        }
        Set<TopicPartition> apparentDifferentRfPartitions = apparentlyDifferentRfTopics.stream()
            .flatMap(pair -> pair.getValue().description().partitions().stream()
                .filter(pi -> {
                    // includes only the partitions of the topic with a RF that mismatches the desired RF
                    var desiredRf = pair.getKey().kt().getSpec().getReplicas();
                    return desiredRf != pi.replicas().size();
                })
                .map(pi -> new TopicPartition(pair.getKey().topicName(), pi.partition()))).collect(Collectors.toSet());

        Map<TopicPartition, PartitionReassignment> reassignments;
        LOGGER.traceOp("Admin.listPartitionReassignments({})", apparentDifferentRfPartitions);
        var timerSample = TopicOperatorUtil.startExternalRequestTimer(metrics, enableAdditionalMetrics);
        try {
            reassignments = admin.listPartitionReassignments(apparentDifferentRfPartitions).reassignments().get();
            TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::listReassignmentsTimer, enableAdditionalMetrics, namespace);
            LOGGER.traceOp("Admin.listPartitionReassignments({}) completed", apparentDifferentRfPartitions);
        } catch (ExecutionException e) {
            TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::listReassignmentsTimer, enableAdditionalMetrics, namespace);
            LOGGER.traceOp("Admin.listPartitionReassignments({}) failed with {}", apparentDifferentRfPartitions, e);
            return apparentlyDifferentRfTopics.stream().map(pair ->
                    new Pair<>(pair.getKey(), Either.<TopicOperatorException, TopicState>ofLeft(handleAdminException(e)))).toList();
        } catch (InterruptedException e) {
            throw new UncheckedInterruptedException(e);
        }

        var partitionToTargetRf = reassignments.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
            var tp = entry.getKey();
            var partitionReassignment = entry.getValue();
            // See https://cwiki.apache.org/confluence/display/KAFKA/KIP-455%3A+Create+an+Administrative+API+for+Replica+Reassignment#KIP455:CreateanAdministrativeAPIforReplicaReassignment-Algorithm
            // for a full description of the algorithm
            // but in essence replicas() will include addingReplicas() from the beginning
            // so the target rf will be the replicas minus the removing
            var target = new HashSet<>(partitionReassignment.replicas());
            target.removeAll(partitionReassignment.removingReplicas());
            return target.size();
        }));

        return apparentlyDifferentRfTopics.stream().filter(pair -> {
            boolean b = pair.getValue().description().partitions().stream().anyMatch(pi -> {
                TopicPartition tp = new TopicPartition(pair.getKey().topicName(), pi.partition());
                Integer targetRf = partitionToTargetRf.get(tp);
                Integer desiredRf = pair.getKey().kt().getSpec().getReplicas();
                return !Objects.equals(targetRf, desiredRf);
            });
            return b;
        }).map(pair -> new Pair<>(pair.getKey(), Either.<TopicOperatorException, TopicState>ofRight(pair.getValue()))).toList();
    }

    private PartitionedByError<ReconcilableTopic, Void> alterConfigs(List<Pair<ReconcilableTopic, Collection<AlterConfigOp>>> someAlterConfigs) {
        if (someAlterConfigs.isEmpty()) {
            return new PartitionedByError<>(List.of(), List.of());
        }
        Map<ConfigResource, Collection<AlterConfigOp>> alteredConfigs = someAlterConfigs.stream().collect(Collectors.toMap(entry -> topicConfigResource(entry.getKey().topicName()), Pair::getValue));
        LOGGER.debugOp("Admin.incrementalAlterConfigs({})", alteredConfigs);
        var timerSample = TopicOperatorUtil.startExternalRequestTimer(metrics, enableAdditionalMetrics);
        AlterConfigsResult acr = admin.incrementalAlterConfigs(alteredConfigs);
        TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::alterConfigsTimer, enableAdditionalMetrics, namespace);
        acr.all().whenComplete((i, e) -> {
            TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::alterConfigsTimer, enableAdditionalMetrics, namespace);
            if (e != null) {
                LOGGER.traceOp("Admin.incrementalAlterConfigs({}) failed with {}", alteredConfigs, String.valueOf(e));
            } else {
                LOGGER.traceOp("Admin.incrementalAlterConfigs({}) completed", alteredConfigs);
            }
        });
        var alterConfigsResult = acr.values();
        Stream<Pair<ReconcilableTopic, Either<TopicOperatorException, Void>>> entryStream = someAlterConfigs.stream().map(entry -> {
            try {
                return new Pair<>(entry.getKey(), Either.ofRight(alterConfigsResult.get(topicConfigResource(entry.getKey().topicName())).get()));
            } catch (ExecutionException e) {
                return new Pair<>(entry.getKey(), Either.ofLeft(handleAdminException(e)));
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }
        });
        return partitionedByError(entryStream);
    }

    private PartitionedByError<ReconcilableTopic, Void> createPartitions(List<Pair<ReconcilableTopic, NewPartitions>> someCreatePartitions) {
        if (someCreatePartitions.isEmpty()) {
            return new PartitionedByError<>(List.of(), List.of());
        }
        Map<String, NewPartitions> newPartitions = someCreatePartitions.stream().collect(Collectors.toMap(pair -> pair.getKey().topicName(), Pair::getValue));
        LOGGER.debugOp("Admin.createPartitions({})", newPartitions);
        var timerSample = TopicOperatorUtil.startExternalRequestTimer(metrics, enableAdditionalMetrics);
        CreatePartitionsResult cpr = admin.createPartitions(newPartitions);
        cpr.all().whenComplete((i, e) -> {
            TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::createPartitionsTimer, enableAdditionalMetrics, namespace);
            if (e != null) {
                LOGGER.traceOp("Admin.createPartitions({}) failed with {}", newPartitions, String.valueOf(e));
            } else {
                LOGGER.traceOp("Admin.createPartitions({}) completed", newPartitions);
            }
        });
        var createPartitionsResult = cpr.values();
        var entryStream = someCreatePartitions.stream().map(entry -> {
            try {
                createPartitionsResult.get(entry.getKey().topicName()).get();
                return new Pair<>(entry.getKey(), Either.<TopicOperatorException, Void>ofRight(null));
            } catch (ExecutionException e) {
                return new Pair<>(entry.getKey(), Either.<TopicOperatorException, Void>ofLeft(handleAdminException(e)));
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }
        });
        return partitionedByError(entryStream);
    }

    private static ConfigResource topicConfigResource(String tn) {
        return new ConfigResource(ConfigResource.Type.TOPIC, tn);
    }

    private PartitionedByError<ReconcilableTopic, TopicState> describeTopic(List<ReconcilableTopic> batch) {
        if (batch.isEmpty()) {
            return new PartitionedByError<>(List.of(), List.of());
        }
        Set<ConfigResource> configResources = batch.stream()
                .map(reconcilableTopic -> topicConfigResource(reconcilableTopic.topicName()))
                .collect(Collectors.toSet());
        Set<String> tns = batch.stream().map(ReconcilableTopic::topicName).collect(Collectors.toSet());

        DescribeTopicsResult describeTopicsResult;
        {
            LOGGER.debugOp("Admin.describeTopics({})", tns);
            var timerSample = TopicOperatorUtil.startExternalRequestTimer(metrics, enableAdditionalMetrics);
            describeTopicsResult = admin.describeTopics(tns);
            describeTopicsResult.allTopicNames().whenComplete((i, e) -> {
                TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::describeTopicsTimer, enableAdditionalMetrics, namespace);
                if (e != null) {
                    LOGGER.traceOp("Admin.describeTopics({}) failed with {}", tns, String.valueOf(e));
                } else {
                    LOGGER.traceOp("Admin.describeTopics({}) completed", tns);
                }
            });
        }
        DescribeConfigsResult describeConfigsResult;
        {
            LOGGER.debugOp("Admin.describeConfigs({})", configResources);
            var timerSample = TopicOperatorUtil.startExternalRequestTimer(metrics, enableAdditionalMetrics);
            describeConfigsResult = admin.describeConfigs(configResources);
            describeConfigsResult.all().whenComplete((i, e) -> {
                TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::describeConfigsTimer, enableAdditionalMetrics, namespace);
                if (e != null) {
                    LOGGER.traceOp("Admin.describeConfigs({}) failed with {}", configResources, String.valueOf(e));
                } else {
                    LOGGER.traceOp("Admin.describeConfigs({}) completed", configResources);
                }
            });
        }

        var cs1 = describeTopicsResult.topicNameValues();
        var cs2 = describeConfigsResult.values();
        return partitionedByError(batch.stream().map(reconcilableTopic -> {
            Config configs = null;
            TopicDescription description = null;
            ExecutionException exception = null;
            try {
                description = cs1.get(reconcilableTopic.topicName()).get();
            } catch (ExecutionException e) {
                exception = e;
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }

            try {
                configs = cs2.get(topicConfigResource(reconcilableTopic.topicName())).get();
            } catch (ExecutionException e) {
                exception = e;
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }
            if (exception != null) {
                return new Pair<>(reconcilableTopic, Either.ofLeft(handleAdminException(exception)));
            } else {
                return new Pair<>(reconcilableTopic, Either.ofRight(new TopicState(description, configs)));
            }
        }));
    }

    void onDelete(List<ReconcilableTopic> batch) throws InterruptedException {
        try {
            deleteInternal(batch, true);
        } catch (UncheckedInterruptedException e) {
            throw e.getCause();
        } catch (KubernetesClientException e) {
            if (e.getCause() instanceof InterruptedIOException) {
                throw new InterruptedException();
            } else {
                throw e;
            }
        }
    }

    private void deleteInternal(List<ReconcilableTopic> batch, boolean onDeletePath) {
        metrics.reconciliationsCounter(namespace).increment(batch.size());
        var managedToDelete = batch.stream().filter(reconcilableTopic -> {
            if (TopicOperatorUtil.isManaged(reconcilableTopic.kt())) {
                var e = validate(reconcilableTopic);
                if (e.isRightEqual(true)) {
                    // adminDelete, removeFinalizer, forgetTopic, updateStatus
                    return true;
                } else if (e.isRightEqual(false)) {
                    // do nothing
                    return false;
                } else {
                    updateStatusForException(reconcilableTopic, e.left());
                    return false;
                }
            } else {
                deleteUnmanagedTopic(reconcilableTopic);
                return false;
            }
        });
        
        deleteManagedTopics(batch, onDeletePath, managedToDelete);
    }

    private void deleteUnmanagedTopic(ReconcilableTopic reconcilableTopic) {
        var timerSample = startReconciliationTimer(metrics);
        removeFinalizer(reconcilableTopic);
        forgetTopic(reconcilableTopic);
        TopicOperatorUtil.stopReconciliationTimer(metrics, timerSample, namespace);
        metrics.successfulReconciliationsCounter(namespace).increment();
    }

    private void deleteManagedTopics(List<ReconcilableTopic> batch, boolean onDeletePath, Stream<ReconcilableTopic> managedToDelete) {
        var timerSamples = batch.stream().collect(
            Collectors.toMap(identity(), rt -> startReconciliationTimer(metrics)));
        
        Set<String> topicNames = managedToDelete.map(ReconcilableTopic::topicName).collect(Collectors.toSet());
        PartitionedByError<ReconcilableTopic, Object> deleteResult = deleteTopics(batch, topicNames);

        // remove the finalizer and forget the topic
        deleteResult.ok().forEach(pair -> {
            try {
                removeFinalizer(pair.getKey());
            } catch (KubernetesClientException e) {
                // If this method be being called because the resource was deleted
                // then we expect the PATCH will error with Not Found
                if (!(onDeletePath && e.getCode() == 404)) { // 404 = Not Found
                    throw e;
                }
            }
            forgetTopic(pair.getKey());
            metrics.successfulReconciliationsCounter(namespace).increment();
        });

        // join that to fail
        deleteResult.errors().forEach(entry -> {
            if (!this.useFinalizer
                    && onDeletePath) {
                // When not using finalizers and a topic is deleted there will be no KafkaTopic to update
                // the status of, so we have to log errors here.
                if (entry.getValue().getCause() instanceof TopicDeletionDisabledException) {
                    LOGGER.warnCr(entry.getKey().reconciliation(),
                            "Unable to delete topic '{}' from Kafka because topic deletion is disabled on the Kafka controller.",
                            entry.getKey().topicName());
                } else {
                    LOGGER.warnCr(entry.getKey().reconciliation(),
                            "Unable to delete topic '{}' from Kafka.",
                            entry.getKey().topicName(),
                            entry.getValue());
                }
                metrics.failedReconciliationsCounter(namespace).increment();
            } else {
                updateStatusForException(entry.getKey(), entry.getValue());
            }
        });
        timerSamples.keySet().forEach(rt -> stopReconciliationTimer(metrics, timerSamples.get(rt), namespace));
    }

    private PartitionedByError<ReconcilableTopic, Object> deleteTopics(List<ReconcilableTopic> batch, Set<String> topicNames) {
        if (topicNames.isEmpty()) {
            return new PartitionedByError<>(List.of(), List.of());
        }
        var someDeleteTopics = TopicCollection.ofTopicNames(topicNames);
        LOGGER.debugOp("Admin.deleteTopics({})", someDeleteTopics.topicNames());

        // Admin delete
        var timerSample = TopicOperatorUtil.startExternalRequestTimer(metrics, enableAdditionalMetrics);
        DeleteTopicsResult dtr = admin.deleteTopics(someDeleteTopics);
        dtr.all().whenComplete((i, e) -> {
            TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::deleteTopicsTimer, enableAdditionalMetrics, namespace);
            if (e != null) {
                LOGGER.traceOp("Admin.deleteTopics({}) failed with {}", someDeleteTopics.topicNames(), String.valueOf(e));
            } else {
                LOGGER.traceOp("Admin.deleteTopics({}) completed", someDeleteTopics.topicNames());
            }
        });
        var futuresMap = dtr.topicNameValues();
        var deleteResult = partitionedByError(batch.stream().map(reconcilableTopic -> {
            try {
                futuresMap.get(reconcilableTopic.topicName()).get();
                return new Pair<>(reconcilableTopic, Either.ofRight(null));
            } catch (ExecutionException e) {
                if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                    return new Pair<>(reconcilableTopic, Either.ofRight(null));
                } else {
                    return new Pair<>(reconcilableTopic, Either.ofLeft(handleAdminException(e)));
                }
            } catch (InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }
        }));
        return deleteResult;
    }

    private void forgetTopic(ReconcilableTopic reconcilableTopic) {
        topics.compute(reconcilableTopic.topicName(), (k, v) -> {
            if (v != null) {
                v.remove(new KubeRef(reconcilableTopic.kt()));
                if (v.isEmpty()) {
                    return null;
                } else {
                    return v;
                }
            } else {
                return null;
            }
        });
    }

    private static Either<TopicOperatorException, NewPartitions> buildNewPartitions(Reconciliation reconciliation, KafkaTopic kt, int currentNumPartitions) {
        int requested = kt.getSpec() == null || kt.getSpec().getPartitions() == null ? BROKER_DEFAULT : kt.getSpec().getPartitions();
        if (requested > currentNumPartitions) {
            LOGGER.debugCr(reconciliation, "Partition increase from {} to {}", currentNumPartitions, requested);
            return Either.ofRight(NewPartitions.increaseTo(requested));
        } else if (requested != BROKER_DEFAULT && requested < currentNumPartitions) {
            LOGGER.debugCr(reconciliation, "Partition decrease from {} to {}", currentNumPartitions, requested);
            return Either.ofLeft(new TopicOperatorException.NotSupported("Decreasing partitions not supported"));
        } else {
            LOGGER.debugCr(reconciliation, "No partition change");
            return Either.ofRight(null);
        }
    }

    private Collection<AlterConfigOp> buildAlterConfigOps(Reconciliation reconciliation, KafkaTopic kt, Config configs) {
        Set<AlterConfigOp> alterConfigOps = new HashSet<>();
        if (hasConfig(kt)) {
            for (var specConfigEntry : kt.getSpec().getConfig().entrySet()) {
                String key = specConfigEntry.getKey();
                var specValueStr = configValueAsString(specConfigEntry.getKey(), specConfigEntry.getValue());
                var kafkaConfigEntry = configs.get(key);
                if (kafkaConfigEntry == null
                        || !Objects.equals(specValueStr, kafkaConfigEntry.value())) {
                    alterConfigOps.add(new AlterConfigOp(
                            new ConfigEntry(key, specValueStr),
                            AlterConfigOp.OpType.SET));
                }
            }
        }
        HashSet<String> keysToRemove = configs.entries().stream()
                .filter(configEntry -> configEntry.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG)
                .map(ConfigEntry::name).collect(Collectors.toCollection(HashSet::new));
        if (hasConfig(kt)) {
            keysToRemove.removeAll(kt.getSpec().getConfig().keySet());
        }
        for (var key : keysToRemove) {
            alterConfigOps.add(new AlterConfigOp(
                    new ConfigEntry(key, null),
                    AlterConfigOp.OpType.DELETE));
        }

        skipNonAlterableConfigs(alterConfigOps);

        if (alterConfigOps.isEmpty()) {
            LOGGER.debugCr(reconciliation, "No config change");
        } else {
            LOGGER.debugCr(reconciliation, "Config changes {}", alterConfigOps);
        }
        return alterConfigOps;
    }

    /**
     * <p>The Topic Operator {@code alterableTopicConfig} can be used to specify a comma separated list of Kafka
     * topic configurations that can be altered by users through {@code .spec.config}. Keep in mind that if changes
     * are applied directly in Kafka, the operator will try to revert them producing a warning.</p>
     *
     * <p>This is useful in standalone mode when you have a Kafka service that restricts alter operations
     * to a subset of all the Kafka topic configurations.</p>
     *
     * <p>The default value is "ALL", which means no restrictions in changing {@code .spec.config}.
     * The opposite is "NONE", which can be set to explicitly disable any change.</p>
     *
     * @param alterConfigOps Requested alter config operations.
     */
    private void skipNonAlterableConfigs(Set<AlterConfigOp> alterConfigOps) {
        var alterableConfigs = config.alterableTopicConfig();
        if (alterableConfigs != null && alterConfigOps != null && !alterableConfigs.isEmpty()) {
            if (alterableConfigs.equalsIgnoreCase("NONE")) {
                alterConfigOps.clear();
            } else if (!alterableConfigs.equalsIgnoreCase("ALL")) {
                var alterablePropertySet = Arrays.stream(alterableConfigs.replaceAll("\\s", "").split(","))
                      .collect(Collectors.toSet());
                alterConfigOps.removeIf(op -> !alterablePropertySet.contains(op.configEntry().name()));
            }
        }
    }

    private static boolean hasConfig(KafkaTopic kt) {
        return kt.getSpec() != null
                && kt.getSpec().getConfig() != null;
    }
    
    private void updateStatusForSuccess(ReconcilableTopic reconcilableTopic) {
        List<Condition> conditions = new ArrayList<>();
        var conditionType = "Ready";
        if (!TopicOperatorUtil.isManaged(reconcilableTopic.kt())) {
            conditionType = "Unmanaged";
        } else if (TopicOperatorUtil.isPaused(reconcilableTopic.kt())) {
            conditionType = "ReconciliationPaused";
        }

        conditions.add(new ConditionBuilder()
              .withType(conditionType)
              .withStatus("True")
              .withLastTransitionTime(StatusUtils.iso8601Now())
              .build());

        addNonAlterableConfigsWarning(reconcilableTopic, conditions);

        reconcilableTopic.kt().setStatus(
            new KafkaTopicStatusBuilder(reconcilableTopic.kt().getStatus())
                .withConditions(conditions)
            .build());
        updateStatus(reconcilableTopic);
        metrics.successfulReconciliationsCounter(namespace).increment();
    }

    private void addNonAlterableConfigsWarning(ReconcilableTopic reconcilableTopic,
                                               List<Condition> conditions) {
        var readOnlyConfigs = new ArrayList<>();
        var alterableConfigs = config.alterableTopicConfig();

        if (reconcilableTopic != null && reconcilableTopic.kt() != null
              && hasConfig(reconcilableTopic.kt()) && alterableConfigs != null) {
            if (alterableConfigs.equalsIgnoreCase("NONE")) {
                reconcilableTopic.kt().getSpec().getConfig().forEach((key, value) -> readOnlyConfigs.add(key));
            } else if (!alterableConfigs.equalsIgnoreCase("ALL") && !alterableConfigs.isBlank()) {
                var alterablePropertySet = Arrays.stream(alterableConfigs.replaceAll("\\s", "").split(","))
                      .collect(Collectors.toSet());
                reconcilableTopic.kt().getSpec().getConfig().forEach((key, value) -> {
                    if (!alterablePropertySet.contains(key)) {
                        readOnlyConfigs.add(key);
                    }
                });
            }
        }

        if (!readOnlyConfigs.isEmpty()) {
            var properties = String.join(", ", readOnlyConfigs.toArray(new String[0]));
            var message = "These .spec.config properties are not configurable: [" + properties + "]";
            LOGGER.warnCr(reconcilableTopic.reconciliation(), message);
            conditions.add(new ConditionBuilder()
                  .withMessage(message)
                  .withReason("NotConfigurable")
                  .withStatus("True")
                  .withType("Warning")
                  .withLastTransitionTime(StatusUtils.iso8601Now())
                  .build());
        }
    }

    private void updateStatusForException(ReconcilableTopic reconcilableTopic, Exception e) {
        String reason;
        if (e instanceof TopicOperatorException) {
            LOGGER.warnCr(reconcilableTopic.reconciliation(), "Reconciliation failed: {}", e.getMessage());
            reason = ((TopicOperatorException) e).reason();
        } else {
            LOGGER.errorCr(reconcilableTopic.reconciliation(), "Reconciliation failed with unexpected exception", e);
            reason = e.getClass().getSimpleName();
        }
        reconcilableTopic.kt().setStatus(
            new KafkaTopicStatusBuilder(reconcilableTopic.kt().getStatus())
                .withConditions(List.of(new ConditionBuilder()
                    .withType("Ready")
                    .withStatus("False")
                    .withReason(reason)
                    .withMessage(e.getMessage())
                    .withLastTransitionTime(StatusUtils.iso8601Now())
                    .build()))
                .build());
        updateStatus(reconcilableTopic);
        metrics.failedReconciliationsCounter(namespace).increment();
    }

    private void updateStatus(ReconcilableTopic reconcilableTopic) {
        var oldStatus = Crds.topicOperation(kubeClient)
            .inNamespace(reconcilableTopic.kt().getMetadata().getNamespace())
            .withName(reconcilableTopic.kt().getMetadata().getName()).get().getStatus();

        // the observedGeneration is a marker that shows that the operator works and that it saw the last update to the resource
        reconcilableTopic.kt().getStatus().setObservedGeneration(reconcilableTopic.kt().getMetadata().getGeneration());

        // set or reset the topicName
        reconcilableTopic.kt().getStatus().setTopicName(
            !TopicOperatorUtil.isManaged(reconcilableTopic.kt())
                ? null
                : oldStatus != null && oldStatus.getTopicName() != null
                ? oldStatus.getTopicName()
                : TopicOperatorUtil.topicName(reconcilableTopic.kt())
        );

        StatusDiff statusDiff = new StatusDiff(oldStatus, reconcilableTopic.kt().getStatus());
        if (!statusDiff.isEmpty()) {
            try {
                var updatedTopic = new KafkaTopicBuilder(reconcilableTopic.kt())
                    .editOrNewMetadata()
                        .withResourceVersion(null)
                    .endMetadata()
                    .withStatus(reconcilableTopic.kt().getStatus())
                    .build();
                LOGGER.debugCr(reconcilableTopic.reconciliation(), "Updating status with {}", updatedTopic.getStatus());
                var timerSample = TopicOperatorUtil.startExternalRequestTimer(metrics, enableAdditionalMetrics);
                var got = Crds.topicOperation(kubeClient).resource(updatedTopic).updateStatus();
                TopicOperatorUtil.stopExternalRequestTimer(timerSample, metrics::updateStatusTimer, enableAdditionalMetrics, namespace);
                LOGGER.traceCr(reconcilableTopic.reconciliation(), "Updated status to observedGeneration {}, resourceVersion {}",
                    got.getStatus().getObservedGeneration(), got.getMetadata().getResourceVersion());
            } catch (Throwable e) {
                LOGGER.errorOp("Status update failed: {}", e.getMessage());
            }
        }
    }
}
