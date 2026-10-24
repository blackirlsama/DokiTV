package com.tianmu.agent.core;

import java.time.*;
import java.util.*;

/** Videos and live rooms share videoId and danmaku semantics; wall times are Asia/Shanghai. */
public final class DanmakuTypes {
    private DanmakuTypes() {}
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public record Query(long videoId, LocalDateTime start, LocalDateTime end) {
        public Query {
            Objects.requireNonNull(start); Objects.requireNonNull(end);
            if (videoId <= 0 || !start.isBefore(end) || start.getNano() != 0 || end.getNano() != 0 || Duration.between(start, end).compareTo(Duration.ofDays(1)) > 0)
                throw new IllegalArgumentException("Report range must be positive and at most 24 hours");
        }
        public void checkTime(Instant now) {
            if (end.atZone(ZONE).toInstant().isAfter(now) || start.toLocalDate().isBefore(now.atZone(ZONE).toLocalDate().minusDays(30)))
                throw new AgentException("DATE_OUT_OF_RANGE", "Use a completed range within the previous 30 days");
        }
    }
    public record Bucket(int index, long count) {
        public Bucket { if (index < 0 || index >= 12 || count < 0) throw new IllegalArgumentException("Invalid bucket"); }
    }
    public record SampleOccurrence(String id, int bucket, boolean truncated) {
        public SampleOccurrence {
            if (id == null || !id.matches("[1-9][0-9]{0,18}") || bucket < 0 || bucket >= 12)
                throw new IllegalArgumentException("Invalid sample occurrence");
        }
    }
    public record Sample(String id, int bucket, String text, boolean truncated, List<SampleOccurrence> occurrences) {
        public Sample(String id, int bucket, String text, boolean truncated) {
            this(id, bucket, text, truncated, List.of(new SampleOccurrence(id, bucket, truncated)));
        }
        public Sample {
            if (id == null || !id.matches("[1-9][0-9]{0,18}") || bucket < 0 || bucket >= 12 || text == null || text.length() > 320)
                throw new IllegalArgumentException("Invalid sample");
            occurrences = List.copyOf(occurrences);
            if (occurrences.isEmpty() || occurrences.size() > 48 || !occurrences.get(0).id().equals(id)
                    || occurrences.get(0).bucket() != bucket || occurrences.stream().anyMatch(o -> o.truncated() && !truncated))
                throw new IllegalArgumentException("Invalid grouped sample");
        }
        public int occurrenceCount() { return occurrences.size(); }
    }
    public record Dataset(Query query, long totalMessages, long uniqueSenders, List<Bucket> buckets,
                          List<Sample> samples, Instant dataAsOf, String source, boolean demo) {
        public Dataset {
            Objects.requireNonNull(query); Objects.requireNonNull(dataAsOf);
            buckets = List.copyOf(buckets); samples = List.copyOf(samples);
            if (totalMessages < 0 || uniqueSenders < 0 || uniqueSenders > totalMessages || buckets.size() != 12 || samples.size() > 48
                    || source == null || !source.matches("[a-zA-Z0-9:._-]{1,100}")) throw new IllegalArgumentException("Invalid analysis dataset");
            long sum = 0, seconds = Duration.between(query.start(), query.end()).getSeconds(), width = Math.max(1, (seconds + 11) / 12);
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < 12; i++) {
                if (buckets.get(i).index() != i || width * i >= seconds && buckets.get(i).count() != 0) throw new IllegalArgumentException("Invalid bucket range");
                sum = Math.addExact(sum, buckets.get(i).count());
            }
            if (sum != totalMessages) throw new IllegalArgumentException("Inconsistent total");
            int occurrenceCount = 0;
            int[] perBucket = new int[12];
            for (Sample sample : samples) for (SampleOccurrence occurrence : sample.occurrences()) {
                if (!ids.add(occurrence.id())) throw new IllegalArgumentException("Duplicate sample occurrence");
                occurrenceCount++;
                if (++perBucket[occurrence.bucket()] > 4 || perBucket[occurrence.bucket()] > buckets.get(occurrence.bucket()).count())
                    throw new IllegalArgumentException("Inconsistent samples");
            }
            if (occurrenceCount > 48) throw new IllegalArgumentException("Too many sampled occurrences");
        }
        public int sampleOccurrenceCount() { return samples.stream().mapToInt(Sample::occurrenceCount).sum(); }
        public String samplingMethod() { return "Up to 12 fixed-width time bins (rounded up to seconds); first up to 4 messages per bin, ordered by create_time and bullet_id; snippets at most 160 code points; exact text duplicates grouped after redaction with all sampled IDs retained; non-random and not population-representative"; }
    }
    public record ReportRef(String artifactId, String url) {}
}
