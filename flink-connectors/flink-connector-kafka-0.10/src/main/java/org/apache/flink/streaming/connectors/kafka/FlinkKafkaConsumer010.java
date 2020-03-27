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

package org.apache.flink.streaming.connectors.kafka;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.functions.AssignerWithPunctuatedWatermarks;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.streaming.connectors.kafka.config.OffsetCommitMode;
import org.apache.flink.streaming.connectors.kafka.internal.Kafka010Fetcher;
import org.apache.flink.streaming.connectors.kafka.internal.Kafka010PartitionDiscoverer;
import org.apache.flink.streaming.connectors.kafka.internals.AbstractFetcher;
import org.apache.flink.streaming.connectors.kafka.internals.AbstractPartitionDiscoverer;
import org.apache.flink.streaming.connectors.kafka.internals.KafkaTopicPartition;
import org.apache.flink.streaming.connectors.kafka.internals.KafkaTopicsDescriptor;
import org.apache.flink.streaming.util.serialization.KeyedDeserializationSchema;
import org.apache.flink.streaming.util.serialization.KeyedDeserializationSchemaWrapper;
import org.apache.flink.util.SerializedValue;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The Flink Kafka Consumer is a streaming data source that pulls a parallel data stream from
 * Apache Kafka 0.10.x. The consumer can run in multiple parallel instances, each of which will pull
 * data from one or more Kafka partitions.
 *
 * <p>The Flink Kafka Consumer participates in checkpointing and guarantees that no data is lost
 * during a failure, and that the computation processes elements "exactly once".
 * (Note: These guarantees naturally assume that Kafka itself does not loose any data.)</p>
 *
 * <p>Please note that Flink snapshots the offsets internally as part of its distributed checkpoints. The offsets
 * committed to Kafka / ZooKeeper are only to bring the outside view of progress in sync with Flink's view
 * of the progress. That way, monitoring and other jobs can get a view of how far the Flink Kafka consumer
 * has consumed a topic.</p>
 *
 * <p>Please refer to Kafka's documentation for the available configuration properties:
 * http://kafka.apache.org/documentation.html#newconsumerconfigs</p>
 */
@PublicEvolving
public class FlinkKafkaConsumer010<T> extends FlinkKafkaConsumer09<T> {

	private static final long serialVersionUID = 2324564345203409112L;

	// ------------------------------------------------------------------------

	/**
	 * Creates a new Kafka streaming source consumer for Kafka 0.10.x.
	 *
	 * @param topic
	 *           The name of the topic that should be consumed.
	 * @param valueDeserializer
	 *           The de-/serializer used to convert between Kafka's byte messages and Flink's objects.
	 * @param props
	 *           The properties used to configure the Kafka consumer client, and the ZooKeeper client.
	 */
	public FlinkKafkaConsumer010(String topic, DeserializationSchema<T> valueDeserializer, Properties props) {
		this(Collections.singletonList(topic), valueDeserializer, props);
	}

	/**
	 * Creates a new Kafka streaming source consumer for Kafka 0.10.x
	 *
	 * <p>This constructor allows passing a {@see KeyedDeserializationSchema} for reading key/value
	 * pairs, offsets, and topic names from Kafka.
	 *
	 * @param topic
	 *           The name of the topic that should be consumed.
	 * @param deserializer
	 *           The keyed de-/serializer used to convert between Kafka's byte messages and Flink's objects.
	 * @param props
	 *           The properties used to configure the Kafka consumer client, and the ZooKeeper client.
	 */
	public FlinkKafkaConsumer010(String topic, KeyedDeserializationSchema<T> deserializer, Properties props) {
		this(Collections.singletonList(topic), deserializer, props);
	}

	/**
	 * Creates a new Kafka streaming source consumer for Kafka 0.10.x
	 *
	 * <p>This constructor allows passing multiple topics to the consumer.
	 *
	 * @param topics
	 *           The Kafka topics to read from.
	 * @param deserializer
	 *           The de-/serializer used to convert between Kafka's byte messages and Flink's objects.
	 * @param props
	 *           The properties that are used to configure both the fetcher and the offset handler.
	 */
	public FlinkKafkaConsumer010(List<String> topics, DeserializationSchema<T> deserializer, Properties props) {
		this(topics, new KeyedDeserializationSchemaWrapper<>(deserializer), props);
	}

	/**
	 * Creates a new Kafka streaming source consumer for Kafka 0.10.x
	 *
	 * <p>This constructor allows passing multiple topics and a key/value deserialization schema.
	 *
	 * @param topics
	 *           The Kafka topics to read from.
	 * @param deserializer
	 *           The keyed de-/serializer used to convert between Kafka's byte messages and Flink's objects.
	 * @param props
	 *           The properties that are used to configure both the fetcher and the offset handler.
	 */
	public FlinkKafkaConsumer010(List<String> topics, KeyedDeserializationSchema<T> deserializer, Properties props) {
		super(topics, deserializer, props);
	}

	/**
	 * Creates a new Kafka streaming source consumer for Kafka 0.10.x. Use this constructor to
	 * subscribe to multiple topics based on a regular expression pattern.
	 *
	 * <p>If partition discovery is enabled (by setting a non-negative value for
	 * {@link FlinkKafkaConsumer010#KEY_PARTITION_DISCOVERY_INTERVAL_MILLIS} in the properties), topics
	 * with names matching the pattern will also be subscribed to as they are created on the fly.
	 *
	 * @param subscriptionPattern
	 *           The regular expression for a pattern of topic names to subscribe to.
	 * @param valueDeserializer
	 *           The de-/serializer used to convert between Kafka's byte messages and Flink's objects.
	 * @param props
	 *           The properties used to configure the Kafka consumer client, and the ZooKeeper client.
	 */
	@PublicEvolving
	public FlinkKafkaConsumer010(Pattern subscriptionPattern, DeserializationSchema<T> valueDeserializer, Properties props) {
		this(subscriptionPattern, new KeyedDeserializationSchemaWrapper<>(valueDeserializer), props);
	}

	/**
	 * Creates a new Kafka streaming source consumer for Kafka 0.10.x. Use this constructor to
	 * subscribe to multiple topics based on a regular expression pattern.
	 *
	 * <p>If partition discovery is enabled (by setting a non-negative value for
	 * {@link FlinkKafkaConsumer010#KEY_PARTITION_DISCOVERY_INTERVAL_MILLIS} in the properties), topics
	 * with names matching the pattern will also be subscribed to as they are created on the fly.
	 *
	 * <p>This constructor allows passing a {@see KeyedDeserializationSchema} for reading key/value
	 * pairs, offsets, and topic names from Kafka.
	 *
	 * @param subscriptionPattern
	 *           The regular expression for a pattern of topic names to subscribe to.
	 * @param deserializer
	 *           The keyed de-/serializer used to convert between Kafka's byte messages and Flink's objects.
	 * @param props
	 *           The properties used to configure the Kafka consumer client, and the ZooKeeper client.
	 */
	@PublicEvolving
	public FlinkKafkaConsumer010(Pattern subscriptionPattern, KeyedDeserializationSchema<T> deserializer, Properties props) {
		super(subscriptionPattern, deserializer, props);
	}

	@Override
	protected AbstractFetcher<T, ?> createFetcher(
			SourceContext<T> sourceContext,
			Map<KafkaTopicPartition, Long> assignedPartitionsWithInitialOffsets,
			Map<KafkaTopicPartition, Long> assignedPartitionsToEndOffsets,
			SerializedValue<AssignerWithPeriodicWatermarks<T>> watermarksPeriodic,
			SerializedValue<AssignerWithPunctuatedWatermarks<T>> watermarksPunctuated,
			StreamingRuntimeContext runtimeContext,
			OffsetCommitMode offsetCommitMode,
			MetricGroup consumerMetricGroup,
			boolean useMetrics) throws Exception {

		// make sure that auto commit is disabled when our offset commit mode is ON_CHECKPOINTS;
		// this overwrites whatever setting the user configured in the properties
		if (offsetCommitMode == OffsetCommitMode.ON_CHECKPOINTS || offsetCommitMode == OffsetCommitMode.DISABLED) {
			properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		}

		return new Kafka010Fetcher<>(
				sourceContext,
				assignedPartitionsWithInitialOffsets,
				assignedPartitionsToEndOffsets,
				watermarksPeriodic,
				watermarksPunctuated,
				runtimeContext.getProcessingTimeService(),
				runtimeContext.getExecutionConfig().getAutoWatermarkInterval(),
				runtimeContext.getUserCodeClassLoader(),
				runtimeContext.getTaskNameWithSubtasks(),
				deserializer,
				properties,
				pollTimeout,
				runtimeContext.getMetricGroup(),
				consumerMetricGroup,
				useMetrics);
	}

	@Override
	protected AbstractPartitionDiscoverer createPartitionDiscoverer(
			KafkaTopicsDescriptor topicsDescriptor,
			int indexOfThisSubtask,
			int numParallelSubtasks) {

		return new Kafka010PartitionDiscoverer(topicsDescriptor, indexOfThisSubtask, numParallelSubtasks, properties);
	}

	// ------------------------------------------------------------------------
	//  Timestamp-based startup
	// ------------------------------------------------------------------------

	@Override
	public FlinkKafkaConsumerBase<T> setStartFromTimestamp(long startupOffsetsTimestamp) {
		// the purpose of this override is just to publicly expose the method for Kafka 0.10+;
		// the base class doesn't publicly expose it since not all Kafka versions support the functionality
		return super.setStartFromTimestamp(startupOffsetsTimestamp);
	}

	@Override
	protected Map<KafkaTopicPartition, Long> fetchOffsetsWithTimestamp(
			Collection<KafkaTopicPartition> partitions,
			long timestamp) {

		Map<TopicPartition, Long> partitionOffsetsRequest = new HashMap<>(partitions.size());
		for (KafkaTopicPartition partition : partitions) {
			partitionOffsetsRequest.put(
				new TopicPartition(partition.getTopic(), partition.getPartition()),
				timestamp);
		}

		// use a short-lived consumer to fetch the offsets;
		// this is ok because this is a one-time operation that happens only on startup
		KafkaConsumer<?, ?> consumer = createKafkaConsumer(properties);

		Map<KafkaTopicPartition, Long> result = new HashMap<>(partitions.size());
		for (Map.Entry<TopicPartition, OffsetAndTimestamp> partitionToOffset :
				consumer.offsetsForTimes(partitionOffsetsRequest).entrySet()) {

			result.put(
				new KafkaTopicPartition(partitionToOffset.getKey().topic(), partitionToOffset.getKey().partition()),
				(partitionToOffset.getValue() == null) ? null : partitionToOffset.getValue().offset());
		}

		consumer.close();
		return result;
	}

	private KafkaConsumer createKafkaConsumer(Properties kafkaProperties) {
		try {
			return new KafkaConsumer<>(kafkaProperties);
		} catch (KafkaException e) {
			ClassLoader original = Thread.currentThread().getContextClassLoader();
			try {
				Thread.currentThread().setContextClassLoader(null);
				return new KafkaConsumer<>(kafkaProperties);
			} finally {
				Thread.currentThread().setContextClassLoader(original);
			}
		}
	}
}
