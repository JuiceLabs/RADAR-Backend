/*
 * Copyright 2017 Kings College London and The Hyve
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

package org.radarcns.monitor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.radarcns.monitor.BatteryLevelMonitor.Status.LOW;

import java.util.Collections;
import javax.mail.MessagingException;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Parser;
import org.apache.avro.generic.GenericData.Record;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.radarcns.config.ConfigRadar;
import org.radarcns.config.RadarPropertyHandler;
import org.radarcns.util.EmailSender;

public class BatteryLevelMonitorTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private long offset;
    private long timeReceived;
    private int timesSent;
    private Schema keySchema;
    private Schema valueSchema;
    private EmailSender sender;

    @Before
    public void setUp() {
        Parser parser = new Parser();
        keySchema = parser.parse("{\"name\": \"key\", \"type\": \"record\", \"fields\": ["
                + "{\"name\": \"userId\", \"type\": \"string\"},"
                + "{\"name\": \"sourceId\", \"type\": \"string\"}"
                + "]}");

        valueSchema = parser.parse("{\"name\": \"value\", \"type\": \"record\", \"fields\": ["
                + "{\"name\": \"timeReceived\", \"type\": \"double\"},"
                + "{\"name\": \"batteryLevel\", \"type\": \"float\"}"
                + "]}");

        offset = 1000L;
        timeReceived = 2000L;
        timesSent = 0;
        sender = mock(EmailSender.class);
    }

    @Test
    public void evaluateRecord() throws Exception {
        ConfigRadar config = KafkaMonitorFactoryTest
                .getBatteryMonitorConfig(25252, folder);
        RadarPropertyHandler properties = KafkaMonitorFactoryTest
                .getRadarPropertyHandler(config, folder);

        BatteryLevelMonitor monitor = new BatteryLevelMonitor(properties,
                Collections.singletonList("mytopic"), sender, LOW);

        sendMessage(monitor, 1.0f, false);
        sendMessage(monitor, 1.0f, false);
        sendMessage(monitor, 0.1f, true);
        sendMessage(monitor, 0.1f, false);
        sendMessage(monitor, 0.3f, true);
        sendMessage(monitor, 0.4f, false);
        sendMessage(monitor, 0.01f, true);
        sendMessage(monitor, 0.01f, false);
        sendMessage(monitor, 0.1f, false);
        sendMessage(monitor, 0.1f, false);
        sendMessage(monitor, 0.01f, true);
        sendMessage(monitor, 1f, true);
    }

    private void sendMessage(BatteryLevelMonitor monitor, float batteryLevel, boolean sentMessage)
            throws MessagingException {
        Record key = new Record(keySchema);
        key.put("sourceId", "1");
        key.put("userId", "me");

        Record value = new Record(valueSchema);
        value.put("timeReceived", timeReceived++);
        value.put("batteryLevel", batteryLevel);
        monitor.evaluateRecord(new ConsumerRecord<>("mytopic", 0, offset++, key, value));

        if (sentMessage) {
            timesSent++;
        }
        verify(sender, times(timesSent)).sendEmail(anyString(), anyString());
    }
}