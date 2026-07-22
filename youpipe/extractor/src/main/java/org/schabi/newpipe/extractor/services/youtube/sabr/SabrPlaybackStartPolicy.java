package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SabrPlaybackStartPolicy {
    @Nonnull
    private final List<ReadaheadPolicy> startMinReadaheadPolicies;
    @Nonnull
    private final List<ReadaheadPolicy> resumeMinReadaheadPolicies;
    @Nonnull
    private final Map<Integer, Long> extraVarints;

    private SabrPlaybackStartPolicy(
            @Nonnull final List<ReadaheadPolicy> startMinReadaheadPolicies,
            @Nonnull final List<ReadaheadPolicy> resumeMinReadaheadPolicies,
            @Nonnull final Map<Integer, Long> extraVarints) {
        this.startMinReadaheadPolicies = Collections.unmodifiableList(
                new ArrayList<>(startMinReadaheadPolicies));
        this.resumeMinReadaheadPolicies = Collections.unmodifiableList(
                new ArrayList<>(resumeMinReadaheadPolicies));
        this.extraVarints = Collections.unmodifiableMap(new LinkedHashMap<>(extraVarints));
    }

    @Nonnull
    static SabrPlaybackStartPolicy decode(@Nonnull final byte[] data)
            throws SabrProtocolException {
        final List<ReadaheadPolicy> startMinReadaheadPolicies = new ArrayList<>();
        final List<ReadaheadPolicy> resumeMinReadaheadPolicies = new ArrayList<>();
        final Map<Integer, Long> extraVarints = new LinkedHashMap<>();

        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                startMinReadaheadPolicies.add(decodeReadaheadPolicy(field.getBytes()));
            } else if (field.getNumber() == 2
                    && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                resumeMinReadaheadPolicies.add(decodeReadaheadPolicy(field.getBytes()));
            } else if (field.getWireType() == SabrProto.WIRE_VARINT) {
                extraVarints.put(field.getNumber(), field.getVarint());
            }
        }

        return new SabrPlaybackStartPolicy(startMinReadaheadPolicies,
                resumeMinReadaheadPolicies, extraVarints);
    }

    @Nonnull
    private static ReadaheadPolicy decodeReadaheadPolicy(@Nonnull final byte[] data)
            throws SabrProtocolException {
        int minBandwidthBytesPerSecond = -1;
        int minReadaheadMs = -1;
        for (final SabrProto.Field field : SabrProto.readFields(data)) {
            if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                minBandwidthBytesPerSecond = (int) field.getVarint();
            } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_VARINT) {
                minReadaheadMs = (int) field.getVarint();
            }
        }
        return new ReadaheadPolicy(minBandwidthBytesPerSecond, minReadaheadMs);
    }

    @Nonnull
    public String summarize() {
        return "start=" + summarizePolicies(startMinReadaheadPolicies)
                + ", resume=" + summarizePolicies(resumeMinReadaheadPolicies)
                + ", extraVarints=" + extraVarints;
    }

    @Nonnull
    private static String summarizePolicies(@Nonnull final List<ReadaheadPolicy> policies) {
        if (policies.isEmpty()) {
            return "[]";
        }
        final StringBuilder builder = new StringBuilder();
        builder.append(policies.size()).append('[');
        final int sampleSize = Math.min(6, policies.size());
        for (int i = 0; i < sampleSize; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(policies.get(i).summarize());
        }
        if (policies.size() > sampleSize) {
            builder.append(",...");
        }
        builder.append(']');
        return builder.toString();
    }

    public static final class ReadaheadPolicy {
        private final int minBandwidthBytesPerSecond;
        private final int minReadaheadMs;

        private ReadaheadPolicy(final int minBandwidthBytesPerSecond,
                                final int minReadaheadMs) {
            this.minBandwidthBytesPerSecond = minBandwidthBytesPerSecond;
            this.minReadaheadMs = minReadaheadMs;
        }

        public int getMinBandwidthBytesPerSecond() {
            return minBandwidthBytesPerSecond;
        }

        public int getMinReadaheadMs() {
            return minReadaheadMs;
        }

        @Nonnull
        private String summarize() {
            return minReadaheadMs + "ms/" + minBandwidthBytesPerSecond + "Bps";
        }
    }
}
