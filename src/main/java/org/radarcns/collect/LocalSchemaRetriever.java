package org.radarcns.collect;

import org.radarcns.ParsedSchemaMetadata;
import org.radarcns.SchemaRetriever;
import org.radarcns.util.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class LocalSchemaRetriever extends SchemaRetriever {
    private final static Logger logger = LoggerFactory.getLogger(LocalSchemaRetriever.class);

    @Override
    protected ParsedSchemaMetadata retrieveSchemaMetadata(String topic, boolean ofValue) throws IOException {
        logger.debug("Retrieving schema for topic {} locally", topic);
        String schemaString = IO.readInputStream(AvroTopic.class.getResourceAsStream("/avro/" + topic + ".avsc"));
        return new ParsedSchemaMetadata(null, null, parseSchema(schemaString));
    }
}
