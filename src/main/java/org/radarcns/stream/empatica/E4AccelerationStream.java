/*
 * Copyright 2017 King's College London and The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.stream.empatica;

import java.util.Collection;
import javax.annotation.Nonnull;
import org.apache.kafka.streams.kstream.KStream;
import org.radarcns.config.RadarPropertyHandler;
import org.radarcns.kafka.AggregateKey;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.passive.empatica.EmpaticaE4Acceleration;
import org.radarcns.stream.KStreamWorker;
import org.radarcns.stream.StreamDefinition;
import org.radarcns.stream.StreamMaster;
import org.radarcns.stream.aggregator.AggregateList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Definition of Kafka Stream for aggregating data collected by Empatica E4 Accelerometer sensor.
 */
public class E4AccelerationStream extends KStreamWorker<ObservationKey, EmpaticaE4Acceleration> {
    private static final Logger logger = LoggerFactory.getLogger(E4AccelerationStream.class);

    public E4AccelerationStream(Collection<StreamDefinition> definitions, int numThread,
            StreamMaster master, RadarPropertyHandler properties) {
        super(definitions, numThread, master, properties, logger);
    }

    @Override
    protected KStream<AggregateKey, AggregateList> implementStream(
            StreamDefinition definition,
            @Nonnull KStream<ObservationKey, EmpaticaE4Acceleration> kstream) {
        return aggregateFields(definition, kstream, new String[] {"x", "y", "z"},
                EmpaticaE4Acceleration.getClassSchema());
    }
}
