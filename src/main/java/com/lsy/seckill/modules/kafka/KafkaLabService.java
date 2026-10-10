package com.lsy.seckill.modules.kafka;

import com.lsy.seckill.modules.kafka.dto.LabMessage;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * kafka-lab 演示服务 —— ★ r10-kafka-lab
 *
 * <p>覆盖：
 * <ul>
 *   <li><b>顺序消费</b>：按逻辑键检测乱序次数（keyed vs unkeyed 对照）；</li>
 *   <li><b>堆积 / 消费滞后</b>：用 AdminClient 读取消费者组 lag</li>
 *       （lag = 分区 latest offset − 已提交 offset）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class KafkaLabService {

    public static final String TOPIC = "lab-msg";
    public static final String GROUP = "kafka-lab";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaAdmin kafkaAdmin;

    private final AtomicLong produced = new AtomicLong();
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong outOfOrder = new AtomicLong();
    private final AtomicLong slowMs = new AtomicLong(0);
    private final Map<String, Long> lastSeq = new ConcurrentHashMap<>();

    public void setSlowMs(long ms) {
        slowMs.set(ms);
    }

    public long slowMs() {
        return slowMs.get();
    }

    /** 发送 count 条；useKey=true 时用逻辑键做 Kafka 分区键，否则 key=null（打散到各分区） */
    public int produce(String logicalKey, int count, boolean useKey) {
        for (int i = 0; i < count; i++) {
            LabMessage msg = new LabMessage(logicalKey, i);
            kafkaTemplate.send(TOPIC, useKey ? logicalKey : null, msg);
            produced.incrementAndGet();
        }
        return count;
    }

    /** 消费者回传：按逻辑键判定乱序 */
    public void record(LabMessage msg) {
        consumed.incrementAndGet();
        if (msg == null || msg.getKey() == null) {
            return;
        }
        synchronized (lastSeq) {
            Long prev = lastSeq.get(msg.getKey());
            if (prev != null && msg.getSeq() <= prev) {
                outOfOrder.incrementAndGet();
            }
            lastSeq.put(msg.getKey(), msg.getSeq());
        }
    }

    public void reset() {
        produced.set(0);
        consumed.set(0);
        outOfOrder.set(0);
        lastSeq.clear();
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("produced", produced.get());
        m.put("consumed", consumed.get());
        m.put("backlogInApp", produced.get() - consumed.get());
        m.put("outOfOrder", outOfOrder.get());
        m.put("slowMs", slowMs.get());
        m.put("lastSeqByKey", new LinkedHashMap<>(lastSeq));
        return m;
    }

    /** 通过 AdminClient 读取消费者组 lag（真实消费者滞后） */
    public Map<String, Object> lag() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            TopicDescription desc = admin.describeTopics(List.of(TOPIC))
                    .allTopicNames().get().get(TOPIC);
            Map<TopicPartition, OffsetSpec> specs = new HashMap<>();
            for (var p : desc.partitions()) {
                specs.put(new TopicPartition(TOPIC, p.partition()), OffsetSpec.latest());
            }
            Map<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo> end =
                    admin.listOffsets(specs).all().get();

            Map<TopicPartition, OffsetAndMetadata> committed;
            try {
                committed = admin.listConsumerGroupOffsets(GROUP)
                        .partitionsToOffsetAndMetadata().get();
            } catch (Exception e) {
                committed = Map.of();
            }

            long total = 0;
            List<Map<String, Object>> parts = new ArrayList<>();
            for (var e : end.entrySet()) {
                TopicPartition tp = e.getKey();
                long endOffset = e.getValue().offset();
                long committedOffset = committed.containsKey(tp) ? committed.get(tp).offset() : 0L;
                long lag = Math.max(0, endOffset - committedOffset);
                total += lag;
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("partition", tp.partition());
                pm.put("endOffset", endOffset);
                pm.put("committed", committedOffset);
                pm.put("lag", lag);
                parts.add(pm);
            }
            result.put("group", GROUP);
            result.put("topic", TOPIC);
            result.put("totalLag", total);
            result.put("partitions", parts);
        }
        return result;
    }
}
