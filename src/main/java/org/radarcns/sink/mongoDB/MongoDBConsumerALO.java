package org.radarcns.sink.mongoDB;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;

import org.apache.avro.specific.SpecificData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.bson.Document;
import org.omg.CORBA.Object;
import org.radarcns.Statistic;
import org.radarcns.consumer.ConsumerALO;
import org.radarcns.key.WindowedKey;
import org.radarcns.util.KafkaProperties;
import org.radarcns.util.RadarConfig;
import org.radarcns.util.Serialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;

/**
 * Created by Francesco Nobilia on 29/09/2016.
 */
public class MongoDBConsumerALO extends ConsumerALO<Object,Object> {
    private final static Logger log = LoggerFactory.getLogger(MongoDBConsumerALO.class);

    private final MongoDatabase database;
    private final MongoCollection collection;

    protected MongoDBConsumerALO(String clientID,MongoDatabase database){
        super(clientID, RadarConfig.TopicGroup.mongo_sink, KafkaProperties.getAutoCommitConsumer(true,clientID));
        this.database = database;
        this.collection = this.database.getCollection("monitor");
    }

    /**
     * Implement the business auto of consumer
     */
    public void process(ConsumerRecord<Object,Object> record) {

        log.trace("{}",record.toString());

        WindowedKey key = (WindowedKey) SpecificData.get().deepCopy(WindowedKey.SCHEMA$, record.key());
        Statistic statistic = (Statistic) SpecificData.get().deepCopy(Statistic.SCHEMA$, record.value());

        String mongoId = key.getUserID()+"-"+key.getSourceID()+"-"+key.getStart()+"-"+key.getEnd();

        LinkedList<Document> quartil = new LinkedList<>();
        quartil.addLast(new Document("25", statistic.getQuartile().get(0)));
        quartil.addLast(new Document("50", statistic.getQuartile().get(1)));
        quartil.addLast(new Document("75", statistic.getQuartile().get(2)));

        Document doc = new Document("_id", mongoId)
                                .append("min", statistic.getMin())
                                .append("max", statistic.getMax())
                                .append("sum", statistic.getSum())
                                .append("count", statistic.getCount())
                                .append("avg", statistic.getAvg())
                                .append("quartile", statistic.getAvg())
                                .append("iqr", statistic.getIqr())
                                .append("start", key.getStart())
                                .append("end", key.getEnd());

        try{
            collection.replaceOne(eq("_id", mongoId),doc,(new UpdateOptions()).upsert(true));
            log.trace("[{} - {}] has been written in {} collection",
                    key, statistic, collection.getNamespace().getCollectionName().toString());

        } catch (MongoException e){
            log.error("Failed to insert record in MongoDB", e);
            log.error("Error on writing [{} - {}] in {} collection",
                    key, statistic, collection.getNamespace().getCollectionName().toString());
        }
    }

    @Override
    protected void handleSerializationError(SerializationException e) {
        log.error("Cannot deserialize", e);
    }
}
