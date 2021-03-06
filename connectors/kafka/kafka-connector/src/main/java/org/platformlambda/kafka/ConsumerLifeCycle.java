/*

    Copyright 2018-2020 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.kafka;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.platformlambda.core.models.Kv;
import org.platformlambda.core.system.PostOffice;
import org.platformlambda.core.util.AppConfigReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class ConsumerLifeCycle implements ConsumerRebalanceListener {
    private static final Logger log = LoggerFactory.getLogger(ConsumerLifeCycle.class);

    private static final AtomicInteger count = new AtomicInteger(0);
    private static final String TYPE = "type";
    private static final String INIT = "init";
    private static boolean ready = false;
    private boolean pubSub, serviceMonitor;
    private String topic;
    private KafkaConsumer<String, byte[]> consumer;
    private long offset = -1;

    public ConsumerLifeCycle(String topic, boolean pubSub) {
        this.topic = topic;
        this.pubSub = pubSub;
        AppConfigReader reader = AppConfigReader.getInstance();
        this.serviceMonitor = "true".equals(reader.getProperty("service.monitor", "false"));
    }

    public void setOffset(KafkaConsumer<String, byte[]> consumer, long offset) {
        this.offset = offset;
        this.consumer = consumer;
    }

    public static boolean isReady() {
        return ready;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> collection) {
        if (!pubSub) {
            ready = false;
        }
        log.info("Standing by for topic {}", topic);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> collection) {
        // Ignore assignment event when collection is empty
        if (!collection.isEmpty()) {
            log.info("Topic {} with {} partition{} is ready", topic, collection.size(),
                    collection.size() == 1 ? "" : "s");
            if (pubSub) {
                /*
                 * Set subscriber offset once
                 */
                if (offset > -1 && count.incrementAndGet() == 1) {
                    // do a quick poll to tell Kafka that the consumer is ready
                    consumer.poll(Duration.ofSeconds(1));
                    Set<TopicPartition> p = consumer.assignment();
                    for (TopicPartition tp : p) {
                        consumer.seek(tp, offset);
                        log.info("Reset offset for topic {}, partition-{} to {}", topic, tp.partition(), offset);
                    }
                }
            } else {
                ready = true;
                // sending a loop-back message to tell the event consumer that the system is ready
                if (!serviceMonitor && TopicManager.regularTopicFormat(topic)) {
                    try {
                        PostOffice.getInstance().send(PostOffice.CLOUD_CONNECTOR, new Kv(TYPE, INIT));
                    } catch (IOException e) {
                        log.error("Unable to send initialization request to {} - {}",
                                PostOffice.CLOUD_CONNECTOR, e.getMessage());
                    }
                }
            }
        }
    }

}