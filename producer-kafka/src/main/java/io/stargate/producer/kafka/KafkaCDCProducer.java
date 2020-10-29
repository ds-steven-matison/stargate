/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.producer.kafka;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.dropwizard.kafka.metrics.DropwizardMetricsReporter;
import io.stargate.config.store.api.ConfigStore;
import io.stargate.db.cdc.SchemaAwareCDCProducer;
import io.stargate.producer.kafka.configuration.CDCKafkaConfig;
import io.stargate.producer.kafka.configuration.DefaultConfigLoader;
import io.stargate.producer.kafka.mapping.DefaultMappingService;
import io.stargate.producer.kafka.mapping.MappingService;
import io.stargate.producer.kafka.producer.CompletableKafkaProducer;
import io.stargate.producer.kafka.schema.KeyValueConstructor;
import io.stargate.producer.kafka.schema.SchemaProvider;
import io.stargate.producer.kafka.schema.SchemaRegistryProvider;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.apache.avro.generic.GenericRecord;
import org.apache.cassandra.stargate.db.DeleteEvent;
import org.apache.cassandra.stargate.db.MutationEvent;
import org.apache.cassandra.stargate.db.RowUpdateEvent;
import org.apache.cassandra.stargate.schema.TableMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;

public class KafkaCDCProducer extends SchemaAwareCDCProducer {
  public static final String CONFIG_STORE_MODULE_NAME = "cdc.kafka";

  private final DefaultConfigLoader configLoader;

  private final ConfigStore configStore;

  private MappingService mappingService;

  KeyValueConstructor keyValueConstructor;

  SchemaProvider schemaProvider;

  private CompletableFuture<CompletableKafkaProducer<GenericRecord, GenericRecord>> kafkaProducer;

  public KafkaCDCProducer(MetricRegistry registry, ConfigStore configStore) {
    registerMetrics(registry);
    this.configStore = configStore;
    this.configLoader = new DefaultConfigLoader();
  }

  /**
   * It registers the provided MetricRegistry in the Dropwizard shared metrics registry (see {@link
   * SharedMetricRegistries}). The {@link DropwizardMetricsReporter} is getting the metrics registry
   * via {@code SharedMetricRegistries.getOrCreate("default")} in the constructor.
   */
  private void registerMetrics(MetricRegistry registry) {
    SharedMetricRegistries.add("default", registry);
  }

  @Override
  public CompletableFuture<Void> init() {
    CDCKafkaConfig cdcKafkaConfig =
        configLoader.loadConfig(
            configStore.getConfigForModule(CONFIG_STORE_MODULE_NAME).getConfigMap());

    this.mappingService = new DefaultMappingService(cdcKafkaConfig.getTopicPrefixName());
    this.schemaProvider =
        new SchemaRegistryProvider(cdcKafkaConfig.getSchemaRegistryUrl(), mappingService);
    this.keyValueConstructor = new KeyValueConstructor(schemaProvider);

    kafkaProducer =
        CompletableFuture.supplyAsync(
            () -> new CompletableKafkaProducer<>(cdcKafkaConfig.getKafkaProducerSettings()));
    return kafkaProducer.thenAccept(toVoid());
  }

  private Consumer<Object> toVoid() {
    return (producer) -> {};
  }

  @Override
  protected CompletableFuture<Void> createTableSchemaAsync(TableMetadata tableMetadata) {
    return CompletableFuture.runAsync(() -> schemaProvider.createOrUpdateSchema(tableMetadata));
  }

  @Override
  protected CompletableFuture<Void> send(MutationEvent mutationEvent) {
    return kafkaProducer.thenCompose(
        producer -> {
          if (mutationEvent instanceof RowUpdateEvent) {
            return handleRowUpdateEvent((RowUpdateEvent) mutationEvent, producer);
          } else if (mutationEvent instanceof DeleteEvent) {
            return handleDeleteEvent((DeleteEvent) mutationEvent, producer);
          } else {
            return handleNotSupportedEventType(mutationEvent);
          }
        });
  }

  @NonNull
  private CompletionStage<Void> handleDeleteEvent(
      DeleteEvent mutationEvent, CompletableKafkaProducer<GenericRecord, GenericRecord> producer) {
    ProducerRecord<GenericRecord, GenericRecord> producerRecord = toProducerRecord(mutationEvent);
    return producer.sendAsync(producerRecord).thenAccept(toVoid());
  }

  @NonNull
  private CompletionStage<Void> handleRowUpdateEvent(
      RowUpdateEvent mutationEvent,
      CompletableKafkaProducer<GenericRecord, GenericRecord> producer) {
    ProducerRecord<GenericRecord, GenericRecord> producerRecord = toProducerRecord(mutationEvent);
    return producer.sendAsync(producerRecord).thenAccept(toVoid());
  }

  @NonNull
  private CompletionStage<Void> handleNotSupportedEventType(MutationEvent mutationEvent) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    result.completeExceptionally(
        new UnsupportedOperationException(
            "The MutationEvent of type: " + mutationEvent.getClass() + " is not supported."));
    return result;
  }

  private ProducerRecord<GenericRecord, GenericRecord> toProducerRecord(
      RowUpdateEvent mutationEvent) {
    String topicName = mappingService.getTopicNameFromTableMetadata(mutationEvent.getTable());

    GenericRecord key = keyValueConstructor.constructKey(mutationEvent, topicName);
    GenericRecord value = keyValueConstructor.constructValue(mutationEvent, topicName);
    return new ProducerRecord<>(topicName, key, value);
  }

  private ProducerRecord<GenericRecord, GenericRecord> toProducerRecord(DeleteEvent mutationEvent) {
    String topicName = mappingService.getTopicNameFromTableMetadata(mutationEvent.getTable());

    GenericRecord key = keyValueConstructor.constructKey(mutationEvent, topicName);
    GenericRecord value = keyValueConstructor.constructValue(mutationEvent, topicName);
    return new ProducerRecord<>(topicName, key, value);
  }

  @Override
  public CompletableFuture<Void> close() {
    return kafkaProducer.thenAccept(
        producer -> {
          producer.flush();
          producer.close();
        });
  }
}
