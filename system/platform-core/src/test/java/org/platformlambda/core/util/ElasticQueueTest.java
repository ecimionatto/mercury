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

package org.platformlambda.core.util;

import org.junit.Test;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.serializers.PayloadMapper;
import org.platformlambda.core.util.models.PoJo;

import java.io.File;
import java.io.IOException;

import org.junit.Assert;

public class ElasticQueueTest {

    @Test
    public void peeking() throws IOException {

        String firstItem = "hello world 1";
        String secondItem = "hello world 2";
        // temp queue directory
        File dir = new File("fifo");
        ElasticQueue spooler = new ElasticQueue(dir, "peek");
        spooler.write(new EventEnvelope().setBody(firstItem));
        spooler.write(new EventEnvelope().setBody(secondItem));

        EventEnvelope first = spooler.peek();
        Assert.assertEquals(firstItem, first.getBody());

        EventEnvelope firstAgain = spooler.read();
        Assert.assertEquals(firstItem, firstAgain.getBody());

        EventEnvelope second = spooler.read();
        Assert.assertEquals(secondItem, second.getBody());
        Assert.assertNull(spooler.peek());
        Assert.assertNull(spooler.read());
        // elastic queue should be automatically closed when all messages are consumed
        Assert.assertTrue(spooler.isClosed());
        // remove elastic queue folder
        spooler.destroy();
    }

    @Test
    public void normalPayload() throws IOException {
        readWrite("normal", 10);
    }

    @Test
    public void largePayload() throws IOException {
        readWrite("large", 90000);
    }

    private void readWrite(String path, int size) throws IOException {
        String target = "hello.world";
        // create input
        StringBuilder sb = new StringBuilder();
        for (int i=0; i < size; i++) {
            sb.append("0123456789");
        }
        sb.append(": ");
        String baseText = sb.toString();
        // temp queue directory
        File dir = new File("fifo");
        ElasticQueue spooler = new ElasticQueue(dir, path);
        // immediate read after write
        for (int i = 0; i < ElasticQueue.MEMORY_BUFFER * 3; i++) {
            String input = baseText + i;
            EventEnvelope event = new EventEnvelope();
            event.setTo(target);
            event.setBody(input);
            spooler.write(event);
            EventEnvelope data = spooler.read();
            Assert.assertNotNull(data);
            Assert.assertEquals(input, data.getBody());
        }
        /*
         * Test serialization of Java objects
         * by writing a larger number of messages to force buffering to disk
         */
        for (int i = 0; i < ElasticQueue.MEMORY_BUFFER * 3; i++) {
            String input = baseText+i;
            PoJo pojo = new PoJo();
            EventEnvelope event = new EventEnvelope();
            event.setTo(target);
            pojo.setName(input);
            event.setBody(pojo);
            spooler.write(event);
        }
        // then read the messages
        for (int i = 0; i < ElasticQueue.MEMORY_BUFFER * 3; i++) {
            String input = baseText+i;
            EventEnvelope data = spooler.read();
            Assert.assertNotNull(data);
            Assert.assertTrue(data.getBody() instanceof PoJo);
            PoJo o = (PoJo) data.getBody();
            Assert.assertEquals(input, o.getName());
        }
        // it should return null when there are no more messages to be read
        EventEnvelope nothing = spooler.read();
        Assert.assertNull(nothing);
        // elastic queue should be automatically closed when all messages are consumed
        Assert.assertTrue(spooler.isClosed());
        // closing again has no effect
        spooler.close();
        // remove elastic queue folder
        spooler.destroy();
        // remove temp directory
        if (dir.exists()) {
            Utility.getInstance().cleanupDir(dir);
        }
        // finally, verify if the PoJo class name is cached
        ManagedCache cache = ManagedCache.getInstance(PayloadMapper.JAVA_CLASS_CACHE);
        Assert.assertNotNull(cache);
        Assert.assertTrue(cache.exists(PoJo.class.getName()));
    }

}
