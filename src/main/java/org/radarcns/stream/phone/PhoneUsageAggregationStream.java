package org.radarcns.stream.phone;

import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.radarcns.aggregator.PhoneUsageAggregator;
import org.radarcns.config.KafkaProperty;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.kafka.AggregateKey;
import org.radarcns.passive.phone.PhoneUsageEvent;
import org.radarcns.stream.StreamMaster;
import org.radarcns.stream.StreamWorker;
import org.radarcns.util.RadarSingletonFactory;
import org.radarcns.util.RadarUtilities;
import org.radarcns.util.serde.RadarSerdes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;

/**
 * Created by piotrzakrzewski on 26/07/2017.
 */
public class PhoneUsageAggregationStream extends StreamWorker<ObservationKey, PhoneUsageEvent> {

    private static final Logger log = LoggerFactory.getLogger(PhoneUsageAggregationStream.class);
    private static final long DAY_IN_MS = 24 * 60 * 60 * 1000;
    private final RadarUtilities utilities = RadarSingletonFactory.getRadarUtilities();

    public PhoneUsageAggregationStream(@Nonnull String clientId,
                                       int numThreads,
                                       @Nonnull StreamMaster master,
                                       KafkaProperty kafkaProperties) {
        super(PhoneStreams.getInstance().getUsageEventAggregationStream(), clientId,
                numThreads, master, kafkaProperties, log);
    }

    @Override
    protected KStream<AggregateKey, PhoneUsageAggregator> defineStream(
            @Nonnull KStream<ObservationKey, PhoneUsageEvent> kstream) {
        return kstream.groupBy((k, v) -> new TemporaryPackageKey(k, v.getPackageName()))
                .aggregate(
                        PhoneUsageCollector::new,
                        (k, v, valueCollector) -> valueCollector.update(v),
                        TimeWindows.of(DAY_IN_MS),
                        RadarSerdes.getInstance().getPhoneUsageCollector(),
                        getStreamDefinition().getStateStoreName())
                .toStream()
                .map(utilities::collectorToAvro);
    }
}
