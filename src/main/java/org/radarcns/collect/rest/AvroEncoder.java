package org.radarcns.collect.rest;

import org.apache.avro.Schema;

import java.io.IOException;

/** Encode Avro values with a given encoder */
public interface AvroEncoder<T> {
    /** Create a new writer. This method is thread-safe */
    AvroWriter<T> writer(Schema schema) throws IOException;

    interface AvroWriter<T> {
        /** Encode an object to String. This method is not thread-safe. */
        String encode(T object) throws IOException;
    }
}
