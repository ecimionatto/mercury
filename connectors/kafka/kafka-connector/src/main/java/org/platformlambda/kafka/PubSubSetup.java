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

import org.platformlambda.core.annotations.CloudService;
import org.platformlambda.core.models.CloudSetup;
import org.platformlambda.core.system.PubSub;
import org.platformlambda.core.util.Utility;
import org.platformlambda.kafka.pubsub.KafkaPubSub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;

/**
 * This cloud service provides native pub/sub service only.
 * This allows the user application to run as a standalone executable and
 * communicate with each other purely using native pub/sub.
 *
 * For real-time and pub/sub use cases, please use the "kafka" connector.
 * It uses Kafka as a service mesh and offers native pub/sub service.
 */
@CloudService(name="kafka.pubsub")
public class PubSubSetup implements CloudSetup {
    private static final Logger log = LoggerFactory.getLogger(PubSubSetup.class);

    @Override
    public void initialize() {
        if (!PubSub.getInstance().featureEnabled()) {
            Properties p = KafkaSetup.getKafkaProperties();
            String brokerUrls = p.getProperty(KafkaSetup.BROKER_URL);
            List<String> brokers = Utility.getInstance().split(brokerUrls, ",");
            log.info("{} = {}", KafkaSetup.BROKER_URL, brokers);
            PubSub.getInstance().enableFeature(new KafkaPubSub());
        }
    }
    
}
