package org.radarcns.collect.rest;

import org.apache.avro.generic.GenericRecord;
import org.radarcns.SchemaRetriever;
import org.radarcns.collect.AvroTopic;
import org.radarcns.collect.KafkaSender;
import org.radarcns.collect.LocalSchemaRetriever;
import org.radarcns.test.producer.MockDevice;
import org.radarcns.collect.RecordList;
import org.radarcns.collect.SchemaRegistryRetriever;
import org.radarcns.util.RollingTimeAverage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Properties;
import java.util.Queue;

/**
 * Send Avro Records to a Kafka REST Proxy.
 *
 * This queues messages for a specified amount of time and then sends all messages up to that time.
 */
public class ThreadedKafkaSender<K, V> extends Thread implements KafkaSender<K, V> {
    private final static Logger logger = LoggerFactory.getLogger(ThreadedKafkaSender.class);
    private final static int RETRIES = 3;
    private final static int QUEUE_CAPACITY = 100;
    private final static long HEARTBEAT_TIMEOUT_MILLIS = 60000L;
    private final static long HEARTBEAT_TIMEOUT_MARGIN = 10000L;

    private final KafkaSender<K, V> sender;
    private long lastHeartbeat;
    private final Queue<RecordList<K, V>> recordQueue;
    private long lastConnection;
    private boolean wasDisconnected;
    private boolean isSending;

    /**
     * Create a REST producer that caches some values
     *
     * @param sender Actual KafkaSender
     */
    public ThreadedKafkaSender(KafkaSender<K, V> sender) {
        super("Kafka REST Producer");
        this.sender = sender;
        this.recordQueue = new ArrayDeque<>(QUEUE_CAPACITY);
        this.wasDisconnected = true;
        this.lastHeartbeat = 0L;
        this.lastConnection = 0L;
        this.isSending = false;
    }

    /**
     * Actually make REST requests.
     *
     * The offsets of the sent messages are added to a
     */
    public void run() {
        RollingTimeAverage opsSent = new RollingTimeAverage(20000L);
        RollingTimeAverage opsRequests = new RollingTimeAverage(20000L);
        try {
            while (true) {
                RecordList<K, V> records;

                synchronized (this) {
                    long nextHeartbeatEvent = Math.max(lastConnection, lastHeartbeat) + HEARTBEAT_TIMEOUT_MILLIS;
                    long now = System.currentTimeMillis();
                    while (this.recordQueue.isEmpty() && nextHeartbeatEvent > now) {
                        wait(nextHeartbeatEvent - now);
                        now = System.currentTimeMillis();
                    }
                    records = this.recordQueue.poll();
                    isSending = records != null;
                }

                opsRequests.add(1);
                boolean result;
                if (records != null) {
                    opsSent.add(records.size());
                    result = sendMessages(records);

                } else {
                    result = sendHeartbeat();
                }

                synchronized (this) {
                    if (result) {
                        lastConnection = System.currentTimeMillis();
                        if (records == null) {
                            lastHeartbeat = System.currentTimeMillis();
                        }
                    } else {
                        logger.error("Failed to send message");
                        disconnect();
                    }
                    isSending = false;
                    notifyAll();
                }

                if (opsSent.hasAverage() && opsRequests.hasAverage()) {
                    logger.info("Sending {} messages in {} requests per second",
                            (int) Math.round(opsSent.getAverage()),
                            (int) Math.round(opsRequests.getAverage()));
                }
            }
        } catch (InterruptedException e) {
            // exit loop and reset interrupt status
            Thread.currentThread().interrupt();
        }
    }

    private boolean sendMessages(RecordList<K, V> records) {
        IOException exception = null;
        for (int i = 0; i < RETRIES; i++) {
            try {
                sender.send(records);
                break;
            } catch (IOException ex) {
                exception = ex;
            }
        }
        return exception == null;
    }

    private boolean sendHeartbeat() {
        boolean success = false;
        for (int i = 0; !success && i < RETRIES; i++) {
            success = sender.isConnected();
        }
        return success;
    }

    private synchronized void disconnect() {
        this.wasDisconnected = true;
        this.recordQueue.clear();
        this.lastConnection = 0L;
        notifyAll();
    }

    @Override
    public synchronized boolean isConnected() {
        if (this.wasDisconnected) {
            return false;
        }
        if (System.currentTimeMillis() - lastConnection > HEARTBEAT_TIMEOUT_MILLIS + HEARTBEAT_TIMEOUT_MARGIN) {
            this.wasDisconnected = true;
            disconnect();
            return false;
        }

        return true;
    }

    @Override
    public synchronized void clear() {
        sender.clear();
    }

    @Override
    public boolean resetConnection() {
        if (isConnected()) {
            return true;
        } else if (sender.isConnected()) {
            synchronized (this) {
                lastHeartbeat = System.currentTimeMillis();
                lastConnection = System.currentTimeMillis();
                this.wasDisconnected = false;
                return true;
            }
        }
        return false;
    }

    @Override
    public void configure(Properties properties) {
        this.resetConnection();
        start();
        sender.configure(properties);
    }

    /**
     * Send given key and record to a topic.
     * @param topic topic name
     * @param key key
     * @param value value with schema
     * @throws IllegalStateException if the producer is not connected.
     */
    @Override
    public void send(AvroTopic topic, long offset, K key, V value) throws IOException {
        RecordList<K, V> recordList = new RecordList<>(topic);
        recordList.add(offset, key, value);
        send(recordList);
    }

    @Override
    public synchronized void send(RecordList<K, V> records) throws IOException {
        if (records.isEmpty()) {
            return;
        }
        if (!isConnected()) {
            throw new IOException("Producer is not connected");
        }
        recordQueue.add(records);
        logger.debug("Queue size: {}", recordQueue.size());
        notifyAll();
    }

    @Override
    public long getLastSentOffset(AvroTopic topic) {
        return this.sender.getLastSentOffset(topic);
    }

    @Override
    public synchronized void flush() throws IOException {
        try {
            if (!isConnected()) {
                throw new IOException("Not connected.");
            }
            while (!this.isInterrupted() && (isSending || !this.recordQueue.isEmpty())) {
                wait();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        } finally {
            sender.flush();
        }
    }

    @Override
    public void close() throws IOException {
        IOException ex = null;
        try {
            try {
                flush();
            } catch (IOException e) {
                logger.warn("Cannot flush buffer", e);
                ex = e;
            }
            interrupt();
            join();
        } catch (InterruptedException e) {
            ex = new IOException("Sending interrupted.");
            Thread.currentThread().interrupt();
        } finally {
            try {
                sender.close();
            } catch (IOException e) {
                logger.warn("Cannot close sender", e);
                ex = e;
            }
        }
        if (ex != null) {
            throw ex;
        }
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        System.out.println(System.currentTimeMillis());
        int numberOfDevices = 1;
        if (args.length > 0) {
            numberOfDevices = Integer.parseInt(args[0]);
        }

        logger.info("Simulating the load of " + numberOfDevices);
        MockDevice[] threads = new MockDevice[numberOfDevices];
        KafkaSender[] senders = new KafkaSender[1];
        SchemaRetriever schemaRetriever = new SchemaRegistryRetriever("http://radar-test.thehyve.net:8081");
        SchemaRetriever localSchemaRetriever =  new LocalSchemaRetriever();

        KafkaSender<String, GenericRecord> kafkaThread = new ThreadedKafkaSender<>(new RestSender<>(new URL("http://radar-test.thehyve.net:8082"), schemaRetriever, new StringEncoder(), new GenericRecordEncoder()));
        senders[0] = new BatchedKafkaSender<>(kafkaThread, 1000, 250);
        senders[0].configure(null);
        for (int i = 0; i < numberOfDevices; i++) {
            threads[i] = new MockDevice(senders[0], "device" + i, localSchemaRetriever);
            threads[i].start();
        }
        for (MockDevice device : threads) {
            device.waitFor();
        }
        for (KafkaSender sender : senders) {
            sender.close();
        }
    }
}
