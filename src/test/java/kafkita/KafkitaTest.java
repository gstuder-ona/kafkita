package kafkita;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import kafka.admin.TopicCommand;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class KafkitaTest {

  @Rule public KafkitaRule kafkitaRule = new KafkitaRule();

  @Test
  public void testBasic() throws Exception {

    Kafkita kafkita = kafkitaRule.kafkita;

    Assert.assertNotNull(kafkita);
    kafkita.waitForStart();

    Assert.assertEquals(true, kafkita.getService().process.isAlive());
    Assert.assertEquals(true, kafkita.getZkService().process.isAlive());
  }

  @Test
  public void testProps() throws Exception {

    Kafkita kafkita = kafkitaRule.kafkita;

    kafkita.getAddlProperties().put("kafkita.test.prop", "${kafkita.kafka.port}");
    kafkita.getAddlJvmProperties().put("kafkita.test.prop", "${kafkita.kafka.port}");
    kafkita.getAddlZkProperties().put("kafkita.test.prop", "${kafkita.zk.clientPort}");
    kafkita.getAddlZkJvmProperties().put("kafkita.test.prop", "${kafkita.zk.clientPort}");

    kafkita.waitForStart();

    Assert.assertEquals(
        "" + kafkita.getPort(), kafkita.getProperties().getProperty("kafkita.test.prop"));
    Assert.assertTrue(
        Arrays.asList(kafkita.getService().jvmArgs)
            .contains("-Dkafkita.test.prop=" + kafkita.getPort()));

    Assert.assertEquals(
        "" + kafkita.getZkPort(), kafkita.getZkProperties().getProperty("kafkita.test.prop"));
    Assert.assertTrue(
        Arrays.asList(kafkita.getZkService().jvmArgs)
            .contains("-Dkafkita.test.prop=" + kafkita.getZkPort()));
  }

  @Test
  public void testIo() throws Exception {

    Kafkita kafkita = kafkitaRule.kafkita;
    kafkita.waitForStart();

    Properties pProps = new Properties();
    pProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:" + kafkita.getPort());
    pProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    pProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    KafkaProducer<String, String> producer = new KafkaProducer<>(pProps);

    for (int i = 0; i < 10; ++i) {
      ProducerRecord<String, String> record =
          new ProducerRecord<>("testIo", "some", "value");
      producer.send(record);
    }
    producer.flush();

    Properties cProps = new Properties();
    cProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:" + kafkita.getPort());
    cProps.put(ConsumerConfig.GROUP_ID_CONFIG, "testConsumer");
    cProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    cProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    cProps.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "0");
    cProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    KafkaConsumer consumer = new KafkaConsumer(cProps);
    consumer.subscribe(Collections.singletonList("testIo"));

    ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
    Assert.assertTrue(!records.isEmpty());

    for (ConsumerRecord<String, String> record : records) {
      Assert.assertEquals("some", record.key());
      Assert.assertEquals("value", record.value());
    }

    producer.close();
    consumer.close();
  }
}
