package org.radarcns.monitor;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.radarcns.config.ConfigRadar;
import org.radarcns.config.RadarPropertyHandler;
import org.radarcns.kafka.AggregateKey;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.producer.KafkaTopicSender;
import org.radarcns.producer.direct.DirectSender;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.CLIENT_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;
import static org.radarcns.util.PersistentStateStore.measurementKeyToString;

/**
 * Monitor a set of streams and compute some basic statistics.
 */
public class SourceStatisticsMonitor extends AbstractKafkaMonitor<GenericRecord, GenericRecord, SourceStatisticsMonitor.SourceStatistics> {
    private static final Logger logger = LoggerFactory.getLogger(SourceStatisticsMonitor.class);
    private final AvroTopic<ObservationKey, AggregateKey> outputTopic;
    private final RadarPropertyHandler radar;
    private KafkaTopicSender<ObservationKey, AggregateKey> sender;

    /**
     * Set some basic properties.
     *
     * @param inputTopics       topics to monitor
     */
    public SourceStatisticsMonitor(RadarPropertyHandler radar, Collection<String> inputTopics, String outputTopic) {
        super(radar, inputTopics, null, "1", new SourceStatistics());

        this.radar = radar;
        // Group ID based on what persistent state we have.
        // If the persistent state is lost, start from scratch.
        this.outputTopic = new AvroTopic<>(outputTopic,
                ObservationKey.getClassSchema(), AggregateKey.getClassSchema(),
                ObservationKey.class, AggregateKey.class);

        Properties props = new Properties();
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, state.getGroupId());
        configure(props);
    }

    @Override
    public void start() {
        Properties properties = new Properties();
        properties.setProperty(KEY_SERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());
        properties.setProperty(VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());
        properties.setProperty(GROUP_ID_CONFIG, state.getGroupId() + "_producers");
        properties.setProperty(CLIENT_ID_CONFIG, getClass().getName() + "-1");
        properties.setProperty(ENABLE_AUTO_COMMIT_CONFIG, "true");
        properties.setProperty(AUTO_COMMIT_INTERVAL_MS_CONFIG, "1001");
        properties.setProperty(SESSION_TIMEOUT_MS_CONFIG, "15101");
        properties.setProperty(HEARTBEAT_INTERVAL_MS_CONFIG, "7500");

        ConfigRadar config = radar.getRadarProperties();
        properties.setProperty(SCHEMA_REGISTRY_URL_CONFIG, config.getSchemaRegistryPaths());
        properties.setProperty(BOOTSTRAP_SERVERS_CONFIG, config.getBrokerPaths());

        try (DirectSender producer = new DirectSender(properties)) {
            sender = producer.sender(outputTopic);
            super.start();
        } catch (IOException ex) {
            logger.error("Failed to create sender.", ex);
        } finally {
            try {
                sender.close();
            } catch (IOException e) {
                logger.error("Failed to close sender", e);
            }

        }
    }

    @Override
    protected void evaluateRecord(ConsumerRecord<GenericRecord, GenericRecord> records) {
        GenericRecord key = records.key();
        ObservationKey obsKey;
        try {
            obsKey = new ObservationKey(
                    key.get("projectId").toString(),
                    key.get("userId").toString(),
                    key.get("sourceId").toString());
        } catch (NullPointerException ex) {
            logger.error("Could not deserialize key without basic ObservationKey properties: {}",
                    key);
            return;
        }

        GenericRecord value = records.value();

        Schema keySchema = key.getSchema();
        Schema valueSchema = value.getSchema();

        long time = (long)(getNumber(value, valueSchema, "timeReceived").doubleValue() * 1000d);
        if (time == 0L) {
            time = (long)(getNumber(value, valueSchema, "time").doubleValue() * 1000d);
        }
        long start = getNumber(key, keySchema, "start").longValue();
        long end = getNumber(key, keySchema, "end").longValue();

        if (time != 0L) {
            if (start == 0L) {
                start = time;
            }
            if (end == 0L) {
                end = time;
            }
        } else if (start == 0L || end == 0L) {
            logger.error("Record in topic {} did not contain time values: {}, {}",
                    records.topic(), records.key(), records.value());
            return;
        }

        AggregateKey newValue = new AggregateKey(
                obsKey.getProjectId(), obsKey.getUserId(), obsKey.getSourceId(), start, end);

        newValue = state.getSources().merge(measurementKeyToString(obsKey), newValue,
                (value1, value2) -> {
                    value1.setStart(Math.min(value1.getStart(), value2.getStart()));
                    value1.setEnd(Math.max(value1.getEnd(), value2.getEnd()));
                    return value1;
                });

        try {
            sender.send(obsKey, newValue);
        } catch (IOException e) {
            logger.error("Failed to update key/value statistics {}: {}", obsKey, newValue, e);
        }
    }

    private static Number getNumber(GenericRecord record, Schema schema, String fieldName) {
        Schema.Field field = schema.getField(fieldName);
        if (field != null) {
            return (Number) record.get(field.pos());
        } else {
            return 0;
        }
    }

    public static class SourceStatistics {
        private Map<String, AggregateKey> sources = new HashMap<>();
        private String groupId = UUID.randomUUID().toString();

        public Map<String, AggregateKey> getSources() {
            return sources;
        }

        public void setSources(Map<String, AggregateKey> sources) {
            this.sources = sources;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }
    }
}
