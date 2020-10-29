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
package io.stargate.producer.kafka.configuration;

import java.util.Map;

public interface ConfigLoader {
  String CDC_TOPIC_PREFIX_NAME = "topic.prefix-name";
  String CDC_KAFKA_PRODUCER_SETTING_PREFIX = "producer";
  String SCHEMA_REGISTRY_URL_SETTING_NAME = "schema.registry.url";
  String METRICS_ENABLED_SETTING_NAME = "metrics.enabled";
  String METRICS_INCLUDE_TAGS_SETTING_NAME = "metrics.include-tags";
  String METRICS_NAME_SETTING_NAME = "metrics.name";

  CDCKafkaConfig loadConfig(Map<String, Object> options);
}
