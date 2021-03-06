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

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.models.Kv;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.PostOffice;
import org.platformlambda.core.util.Utility;
import org.platformlambda.core.websocket.common.MultipartPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.*;

public class EventConsumer extends Thread {
    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    public static final String INIT_TOKEN = "INIT_"+UUID.randomUUID().toString();
    private static final String CLOUD_CONNECTOR = PostOffice.CLOUD_CONNECTOR;
    private static final String TYPE = "type";
    private static final String CONSUMER_READY = "consumer_ready";
    private static final long FAST_POLL = 10;
    private static final long REGULAR_POLL = 60;
    private String topic;
    private KafkaConsumer<String, byte[]> consumer;
    private boolean normal = true, pubSub = false;
    private long offset = -1;

    public EventConsumer(Properties base, String topic) {
        initialize(base, topic, false);
    }

    public EventConsumer(Properties base, String topic, boolean pubSub, String... parameters) {
        initialize(base, topic, pubSub, parameters);
    }

    private void initialize(Properties base, String topic, boolean pubSub, String... parameters) {
        String origin = Platform.getInstance().getOrigin();
        Properties prop = new Properties();
        prop.putAll(base);
        // create unique values for client ID and group ID
        if (pubSub) {
            if (parameters.length == 2 || parameters.length == 3) {
                prop.put(ConsumerConfig.CLIENT_ID_CONFIG, parameters[0]);
                prop.put(ConsumerConfig.GROUP_ID_CONFIG, parameters[1]);
                /*
                 * If offset is not given, the consumer will read from the latest when it is started for the first time.
                 * Subsequent restart of the consumer will resume read from the current offset.
                 */
                if (parameters.length == 3) {
                    Utility util = Utility.getInstance();
                    long value = util.str2int(parameters[2]);
                    if (value > -1) {
                        offset = value;
                    }
                }
            } else {
                throw new IllegalArgumentException("Unable to start consumer for topic "+topic+" - number of parameters must be 2 or 3");
            }
        } else {
            prop.put(ConsumerConfig.CLIENT_ID_CONFIG, origin.substring(8));
            prop.put(ConsumerConfig.GROUP_ID_CONFIG, origin.substring(6));
            prop.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        }
        prop.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class);
        prop.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
        this.pubSub = pubSub;
        this.topic = topic;
        this.consumer = new KafkaConsumer<>(prop);
    }

    private long getEarliest(TopicPartition tp) {
        Map<TopicPartition, Long> data = consumer.beginningOffsets(Collections.singletonList(tp));
        return data.get(tp);
    }

    private long getLatest(TopicPartition tp) {
        Map<TopicPartition, Long> data = consumer.endOffsets(Collections.singletonList(tp));
        return data.get(tp);
    }

    @Override
    public void run() {
        boolean ready = pubSub;
        int outdatedEvents = 0;
        boolean resetOffset = pubSub;
        long interval = pubSub? FAST_POLL : REGULAR_POLL;
        PostOffice po = PostOffice.getInstance();
        consumer.subscribe(Collections.singletonList(topic), new ConsumerLifeCycle(topic, pubSub));
        log.info("Subscribed topic {}", topic);
        try {
            while (normal) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(interval));
                if (pubSub && offset > -1 && resetOffset) {
                    // wait until a partition is assigned
                    boolean scanOffset = true;
                    if (ConsumerLifeCycle.isReady()) {
                        Set<TopicPartition> p = consumer.assignment();
                        if (!p.isEmpty()) {
                            // must have at least one partition assigned
                            resetOffset = false;
                            interval = REGULAR_POLL;
                            for (TopicPartition tp : p) {
                                long earliest = getEarliest(tp);
                                long latest = getLatest(tp);
                                log.info("Current offset range for topic {} = {} - {}", topic, earliest, latest);
                                if (offset < earliest) {
                                    consumer.seek(tp, earliest);
                                    log.warn("Reset offset for topic {}, partition-{} to {} instead of {}",
                                            topic, tp.partition(), earliest, offset);
                                } else if (offset < latest) {
                                    consumer.seek(tp, offset);
                                    log.info("Reset offset for topic {}, partition-{} to {}", topic, tp.partition(), offset);
                                } else {
                                    scanOffset = false;
                                    log.warn("Offset for {} not changed because {} is out of range {} - {}",
                                            topic, offset, earliest, latest);
                                }
                            }
                        }
                    } else {
                        log.warn("Awaiting partition assignment for topic {}", topic);
                    }
                    if (scanOffset) {
                        continue;
                    }
                }
                for (ConsumerRecord<String, byte[]> record : records) {
                    byte[] data = record.value();
                    if (!ready) {
                        /*
                         * Since we reuse the same topic, we like to drop outdated events.
                         * Anything before the initialization token event are outdated.
                         */
                        if (data.length == 0 && INIT_TOKEN.equals(record.key())) {
                            ready = true;
                            ConsumerLifeCycle.setEventStreamReady();
                            if (outdatedEvents > 0) {
                                NumberFormat number = NumberFormat.getInstance();
                                log.warn("Total {} outdated event{} ignored", number.format(outdatedEvents),
                                         outdatedEvents == 1?  "" : "s");
                            }
                            log.info("Event streams ready");
                            PostOffice.getInstance().send(CLOUD_CONNECTOR, new Kv(TYPE, CONSUMER_READY));
                        } else {
                            outdatedEvents++;
                            /*
                             * print status for every 1,000 events to keep the user informed
                             * (this is roughly 0.2 seconds)
                             */
                            if (outdatedEvents % 1000 == 0) {
                                NumberFormat number = NumberFormat.getInstance();
                                log.info("Skipping outdated {} events", number.format(outdatedEvents));
                            }
                        }
                        continue;
                    }
                    // initialization events
                    if (data.length == 0) {
                        // when event stream is reconnected
                        if (INIT_TOKEN.equals(record.key())) {
                            log.info("Event streams running");
                            PostOffice.getInstance().send(CLOUD_CONNECTOR, new Kv(TYPE, CONSUMER_READY));
                        }
                        continue;
                    }
                    EventEnvelope message = new EventEnvelope();
                    try {
                        message.load(data);
                        message.setEndOfRoute();
                    } catch (Exception e) {
                        log.error("Unable to decode incoming event for {} - {}", topic, e.getMessage());
                        continue;
                    }
                    try {
                        if (message.getTo() != null) {
                            po.send(message);
                        } else {
                            MultipartPayload.getInstance().incoming(message);
                        }
                    } catch (IOException e) {
                        log.error("Unable to process incoming event for {} - {}", topic, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            if (e instanceof WakeupException) {
                log.info("Stopping listener for {}", topic);
            } else {
                /*
                 * We will let the cloud restarts the application instance automatically.
                 * There is nothing we can do.
                 */
                log.error("Unrecoverable event stream error for {} - {} {}", topic, e.getClass(), e.getMessage());
                System.exit(10);
            }
        } finally {
            consumer.close();
            log.info("Unsubscribed topic {}", topic);
        }
    }

    public void shutdown() {
        if (normal) {
            normal = false;
            consumer.wakeup();
        }
    }

}
